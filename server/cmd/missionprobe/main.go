// Pixel Dungeon
// Copyright (C) 2012-2015 Oleg Dolya
//
// Shattered Pixel Dungeon
// Copyright (C) 2014-2026 Evan Debenham
//
// Logic Link PD — Copyright (C) 2026 Logic Link PD contributors
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>

// Command missionprobe checks the SRV-02 co-op engine end to end against a
// running server, with two simulated clients per scenario (a fresh run
// each). It exits 0 only if every scenario matched.
//
//	go run ./cmd/missionprobe                         # ws://localhost:8080/ws
//	go run ./cmd/missionprobe -url ws://127.0.0.1:18081/ws
//
// Scenarios: Cell trigger → start → key give → success; prisoner Downed →
// Recall → revived; Downed expiry inside a Cell → failed + penalty (no
// DOWNED_EXPIRED); Downed expiry outside → DOWNED_EXPIRED; disconnect
// mid-mission → paused → resume (+ ALLY_DOWNED re-sent); claim race; twin
// window; shared boss pool. The two expiry scenarios wait out the server's
// Downed window (COOP_DOWNED_WINDOW, 30 s by default).
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"math"
	"os"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

var (
	url        = flag.String("url", "ws://localhost:8080/ws", "server websocket URL")
	timeout    = flag.Duration("timeout", 10*time.Second, "timeout per read")
	twinWindow = flag.Duration("twin-window", 2*time.Second, "the server's COOP_TWIN_WINDOW")
	bossScale  = flag.Float64("boss-scale", 1.8, "the server's COOP_BOSS_HP_SCALE")
)

func main() {
	flag.Parse()
	scenarios := []struct {
		name string
		run  func() error
	}{
		{"cell success", cellSuccess},
		{"cell recall", cellRecall},
		{"cell downed expiry", cellExpiry},
		{"downed expiry outside a mission", downedOutside},
		{"disconnect mid-mission", disconnectResume},
		{"claim race", claimRace},
		{"twin window", twinDoor},
		{"boss pool", bossPool},
	}
	failed := 0
	for _, s := range scenarios {
		fmt.Printf("== %s\n", s.name)
		if err := s.run(); err != nil {
			fmt.Printf("FAIL %s: %v\n", s.name, err)
			failed++
		}
	}
	if failed > 0 {
		fmt.Printf("FAIL: %d of %d scenarios failed\n", failed, len(scenarios))
		os.Exit(1)
	}
	fmt.Printf("PASS: all %d scenarios matched\n", len(scenarios))
}

func step(format string, args ...any) { fmt.Printf("ok   "+format+"\n", args...) }

// ---- scenarios -----------------------------------------------------------------

func cellSuccess() error {
	a, b, st, err := startCell(12)
	if err != nil {
		return err
	}
	defer closeAll(a, b)
	if st.Prisoner != a.id || st.Rescuer != b.id || st.WaveIntervalMs < 1 {
		return fmt.Errorf("MISSION_START = %+v", st)
	}
	if st.Deadline <= st.StartsAt {
		return fmt.Errorf("deadline %d not after startsAt %d", st.Deadline, st.StartsAt)
	}
	step("MISSION_START prisoner=A rescuer=B depth=12 cap=%ds waveIntervalMs=%d", (st.Deadline-st.StartsAt)/1000, st.WaveIntervalMs)
	key := b.id + ":1"
	if err := b.send(fl(12), &protocol.Give{ItemNetID: key, Kind: "cell_key"}); err != nil {
		return err
	}
	for _, p := range []*player{a, b} {
		m, err := p.expect(protocol.TTransfer)
		if err != nil {
			return err
		}
		if tr := m.Payload.(*protocol.Transfer); tr.From != b.id || tr.To != a.id || tr.Kind != "cell_key" {
			return fmt.Errorf("%s TRANSFER = %+v", p.name, tr)
		}
		if m, err = p.expect(protocol.TMissionUpdate); err != nil {
			return err
		}
		if u := m.Payload.(*protocol.MissionUpdate); u.Tasks == nil || u.Tasks.Key == nil || !*u.Tasks.Key {
			return fmt.Errorf("%s MISSION_UPDATE = %+v", p.name, u)
		}
		e, err := p.missionEnd()
		if err != nil {
			return err
		}
		if e.Result != "success" || e.Reward == nil {
			return fmt.Errorf("%s MISSION_END = %+v", p.name, e)
		}
		step("%s TRANSFER cell_key, MISSION_UPDATE tasks.key=true, MISSION_END success rewardSeed=%d", p.name, e.Reward.RewardSeed)
	}
	return nil
}

func cellRecall() error {
	a, b, _, err := startCell(3)
	if err != nil {
		return err
	}
	defer closeAll(a, b)
	if _, err := downed(a, b, 3); err != nil {
		return err
	}
	if err := b.send(fl(3), &protocol.UseRecall{Target: a.id, Via: "recall"}); err != nil {
		return err
	}
	for _, p := range []*player{a, b} {
		m, err := p.expect(protocol.TRevive)
		if err != nil {
			return err
		}
		rv := m.Payload.(*protocol.Revive)
		if rv.Target != a.id || rv.Mode != "inPlace" || rv.HPPct != 25 {
			return fmt.Errorf("%s REVIVE = %+v", p.name, rv)
		}
		e, err := p.missionEnd()
		if err != nil {
			return err
		}
		if e.Result != "revived" {
			return fmt.Errorf("%s MISSION_END = %+v", p.name, e)
		}
		step("%s REVIVE inPlace hpPct=25, MISSION_END revived", p.name)
	}
	return nil
}

func cellExpiry() error {
	a, b, _, err := startCell(8)
	if err != nil {
		return err
	}
	defer closeAll(a, b)
	d, err := downed(a, b, 8)
	if err != nil {
		return err
	}
	if err := waitServer(d.Deadline, a, b); err != nil {
		return err
	}
	for _, p := range []*player{a, b} {
		e, err := p.missionEnd()
		if err != nil {
			return err
		}
		if e.Result != "failed" || e.Penalty == nil || e.Penalty.RegionStartDepth != 6 || e.Penalty.GoldPct != 25 {
			return fmt.Errorf("%s MISSION_END = %+v", p.name, e)
		}
		ms, err := p.collect()
		if err != nil {
			return err
		}
		for _, m := range ms {
			if m.T == protocol.TDownedExpired {
				return fmt.Errorf("%s got DOWNED_EXPIRED for a Cell prisoner (D-022)", p.name)
			}
		}
		step("%s MISSION_END failed penalty{regionStartDepth:6, goldPct:25}, no DOWNED_EXPIRED", p.name)
	}
	return nil
}

func downedOutside() error {
	a, b, err := pair()
	if err != nil {
		return err
	}
	defer closeAll(a, b)
	if err := moveBoth(a, b, 4); err != nil {
		return err
	}
	d, err := downed(a, b, 4)
	if err != nil {
		return err
	}
	if err := waitServer(d.Deadline, a, b); err != nil {
		return err
	}
	for _, p := range []*player{a, b} {
		m, err := p.expect(protocol.TDownedExpired)
		if err != nil {
			return err
		}
		if e := m.Payload.(*protocol.DownedExpired); e.Player != a.id {
			return fmt.Errorf("%s DOWNED_EXPIRED = %+v", p.name, e)
		}
		step("%s DOWNED_EXPIRED player=A", p.name)
	}
	return nil
}

func disconnectResume() error {
	a, b, st, err := startCell(2)
	if err != nil {
		return err
	}
	defer closeAll(a)
	_ = b.ws.CloseNow()
	if err := a.expectAlly(b.id, false); err != nil {
		return err
	}
	m, err := a.expect(protocol.TMissionUpdate)
	if err != nil {
		return err
	}
	u := m.Payload.(*protocol.MissionUpdate)
	if !u.Paused || u.MissionID != st.MissionID {
		return fmt.Errorf("MISSION_UPDATE = %+v", u)
	}
	step("B dropped: A ALLY_STATUS connected=false, MISSION_UPDATE paused=true remainingMs=%d", u.RemainingMs)
	if err := a.send(fl(2), &protocol.Downed{}); err != nil {
		return err
	}
	if _, err := a.expect(protocol.TAllyDowned); err != nil {
		return err
	}
	step("A DOWNED while paused -> ALLY_DOWNED (window frozen)")
	time.Sleep(1500 * time.Millisecond)

	b2 := &player{name: "B"}
	if err := b2.connect(); err != nil {
		return err
	}
	defer closeAll(b2)
	w, err := b2.hello(protocol.Envelope{Ack: protocol.Int64(b.ack)},
		&protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "desktop", ResumeToken: b.token, Epoch: b.epoch})
	if err != nil {
		return err
	}
	step("B resumed: WELCOME resumed=%v replayed=%d complete=%v", w.Resumed, w.Replayed, w.Complete)
	if err := a.expectAlly(b.id, true); err != nil {
		return err
	}
	if m, err = a.expect(protocol.TMissionUpdate); err != nil {
		return err
	}
	if u2 := m.Payload.(*protocol.MissionUpdate); u2.Paused || u2.RemainingMs != u.RemainingMs {
		return fmt.Errorf("resume MISSION_UPDATE = %+v (paused had %d ms)", u2, u.RemainingMs)
	}
	if m, err = a.expect(protocol.TAllyDowned); err != nil {
		return err
	}
	d := m.Payload.(*protocol.AllyDowned)
	if d.Player != a.id || d.Deadline <= a.serverNow() {
		return fmt.Errorf("re-sent ALLY_DOWNED = %+v", d)
	}
	step("A MISSION_UPDATE paused=false remainingMs=%d (unchanged), ALLY_DOWNED re-sent deadline in %ds", u.RemainingMs, (d.Deadline-a.serverNow())/1000)
	// Clean up: the rescuer revives the prisoner.
	if err := b2.send(fl(2), &protocol.UseRecall{Target: a.id, Via: "recall"}); err != nil {
		return err
	}
	if _, err := a.missionEnd(); err != nil {
		return err
	}
	return nil
}

func claimRace() error {
	a, b, err := pair()
	if err != nil {
		return err
	}
	defer closeAll(a, b)
	if err := moveBoth(a, b, 2); err != nil {
		return err
	}
	var wg sync.WaitGroup
	errs := make([]error, 2)
	for i, p := range []*player{a, b} {
		wg.Add(1)
		go func() { defer wg.Done(); errs[i] = p.send(fl(2), &protocol.Claim{ItemNetID: "2:0:7"}) }()
	}
	wg.Wait()
	if err := errors.Join(errs...); err != nil {
		return err
	}
	winners, denies := map[string]bool{}, 0
	for _, p := range []*player{a, b} {
		ms, err := p.collect()
		if err != nil {
			return err
		}
		for _, m := range ms {
			switch q := m.Payload.(type) {
			case *protocol.Claimed:
				winners[q.By] = true
			case *protocol.Deny:
				if q.Reason == protocol.DenyReasonTaken {
					denies++
				}
			}
		}
	}
	if len(winners) != 1 || denies != 1 {
		return fmt.Errorf("claim race: winners %v, %d DENY taken (want 1 and 1)", winners, denies)
	}
	step("both claimed 2:0:7 at once: one CLAIMED (seen by both), one DENY taken")
	return nil
}

func twinDoor() error {
	a, b, err := pair()
	if err != nil {
		return err
	}
	defer closeAll(a, b)
	if err := moveBoth(a, b, 6); err != nil {
		return err
	}
	press := func(p *player) error {
		return p.send(fl(6), &protocol.TwinAct{ObjectID: 7, Kind: "door", Cell: 30, Active: true})
	}
	if err := press(a); err != nil {
		return err
	}
	time.Sleep(*twinWindow + 500*time.Millisecond)
	if err := press(b); err != nil {
		return err
	}
	ms, err := a.collect()
	if err != nil {
		return err
	}
	for _, m := range ms {
		if m.T == protocol.TTwinDone {
			return errors.New("TWIN_DONE for presses outside the window")
		}
	}
	step("presses %s apart: no TWIN_DONE", *twinWindow+500*time.Millisecond)
	if err := press(a); err != nil {
		return err
	}
	for _, p := range []*player{a, b} {
		m, err := p.expect(protocol.TTwinDone)
		if err != nil {
			return err
		}
		if d := m.Payload.(*protocol.TwinDone); d.GroupID != 7 {
			return fmt.Errorf("%s TWIN_DONE = %+v", p.name, d)
		}
	}
	step("presses inside the window: TWIN_DONE{groupId:7} to both")
	return nil
}

func bossPool() error {
	a, b, err := pair()
	if err != nil {
		return err
	}
	defer closeAll(a, b)
	if err := moveBoth(a, b, 5); err != nil {
		return err
	}
	if err := a.send(fl(5), &protocol.BossEngage{BossID: 3, HT: 100}); err != nil {
		return err
	}
	pool := int64(math.Round(100 * *bossScale))
	for _, p := range []*player{a, b} {
		m, err := p.expect(protocol.TBossHP)
		if err != nil {
			return err
		}
		if hp := m.Payload.(*protocol.BossHP); hp.HP != pool || hp.HT != pool {
			return fmt.Errorf("%s BOSS_HP = %+v, want pool %d", p.name, hp, pool)
		}
	}
	step("BOSS_ENGAGE ht=100 -> BOSS_HP hp=ht=%d to both", pool)
	if err := b.send(fl(5), &protocol.BossHit{BossID: 3, Dmg: pool}); err != nil {
		return err
	}
	for _, p := range []*player{a, b} {
		if _, err := p.expect(protocol.TBossDead); err != nil {
			return err
		}
	}
	step("BOSS_HIT dmg=%d -> BOSS_HP 0, BOSS_DEAD to both", pool)
	if err := move(b, a, 9); err != nil {
		return err
	}
	if err := a.send(fl(5), &protocol.BossHit{BossID: 4, Dmg: 1}); err != nil {
		return err
	}
	m, err := a.expect(protocol.TDeny)
	if err != nil {
		return err
	}
	if r := m.Payload.(*protocol.Deny).Reason; r != protocol.DenyReasonNoPool {
		return fmt.Errorf("DENY %s, want no_pool", r)
	}
	step("ally on another floor: BOSS_HIT -> DENY no_pool")
	return nil
}

// ---- flows ---------------------------------------------------------------------

func fl(depth int) protocol.Envelope {
	return protocol.Envelope{Depth: protocol.Int(depth), Branch: protocol.Int(0)}
}

func closeAll(ps ...*player) {
	for _, p := range ps {
		_ = p.ws.CloseNow()
	}
}

// pair starts a fresh run for two new players.
func pair() (a, b *player, err error) {
	a, b = &player{name: "A"}, &player{name: "B"}
	for _, p := range []*player{a, b} {
		if err := p.connect(); err != nil {
			return nil, nil, err
		}
		if _, err := p.hello(protocol.Envelope{}, &protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "desktop"}); err != nil {
			return nil, nil, err
		}
	}
	if err := a.send(protocol.Envelope{}, &protocol.RoomCreate{}); err != nil {
		return nil, nil, err
	}
	m, err := a.expect(protocol.TRoomState)
	if err != nil {
		return nil, nil, err
	}
	if err := b.send(protocol.Envelope{}, &protocol.RoomJoin{Code: m.Payload.(*protocol.RoomState).Code}); err != nil {
		return nil, nil, err
	}
	if _, err := b.expect(protocol.TRoomState); err != nil {
		return nil, nil, err
	}
	for _, p := range []*player{a, b} {
		if err := p.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "WARRIOR", Ready: true}); err != nil {
			return nil, nil, err
		}
	}
	for _, p := range []*player{a, b} {
		if _, err := p.expect(protocol.TRunStart); err != nil {
			return nil, nil, err
		}
	}
	return a, b, nil
}

// move reports p on depth with a GHOST and waits for the relay at ally.
func move(p, ally *player, depth int) error {
	if err := p.send(fl(depth), &protocol.Ghost{Pos: 10, Anim: "idle", HPPct: 100, HeroClass: "WARRIOR", Tier: 0}); err != nil {
		return err
	}
	_, err := ally.expect(protocol.TGhost)
	return err
}

func moveBoth(a, b *player, depth int) error {
	if err := move(a, b, depth); err != nil {
		return err
	}
	return move(b, a, depth)
}

// startCell pairs two players on depth and A steps on the Binding Trap.
func startCell(depth int) (a, b *player, st *protocol.MissionStart, err error) {
	if a, b, err = pair(); err != nil {
		return nil, nil, nil, err
	}
	if err = moveBoth(a, b, depth); err != nil {
		return nil, nil, nil, err
	}
	if err = a.send(fl(depth), &protocol.MissionTrigger{Kind: "CELL", TrapCell: 1207, RoomID: 7}); err != nil {
		return nil, nil, nil, err
	}
	for _, p := range []*player{a, b} {
		m, err := p.expect(protocol.TMissionStart)
		if err != nil {
			return nil, nil, nil, err
		}
		s := m.Payload.(*protocol.MissionStart)
		if st != nil && *s != *st {
			return nil, nil, nil, fmt.Errorf("MISSION_START differs: %+v vs %+v", st, s)
		}
		st = s
	}
	step("MISSION_TRIGGER depth=%d -> MISSION_START to both (missionId=%s)", depth, st.MissionID)
	return a, b, st, nil
}

// downed sends DOWNED from p and returns the ALLY_DOWNED both received.
func downed(p, ally *player, depth int) (*protocol.AllyDowned, error) {
	if err := p.send(fl(depth), &protocol.Downed{Cause: "probe"}); err != nil {
		return nil, err
	}
	var d *protocol.AllyDowned
	for _, q := range []*player{p, ally} {
		m, err := q.expect(protocol.TAllyDowned)
		if err != nil {
			return nil, err
		}
		d = m.Payload.(*protocol.AllyDowned)
		if d.Player != p.id {
			return nil, fmt.Errorf("%s ALLY_DOWNED = %+v", q.name, d)
		}
	}
	step("%s DOWNED -> ALLY_DOWNED to both, window %ds", p.name, (d.Deadline-p.serverNow()+500)/1000)
	return d, nil
}

// ---- client --------------------------------------------------------------------

type player struct {
	name   string
	ws     *websocket.Conn
	seq    int64
	ack    int64
	id     string
	token  string
	epoch  string
	offset int64 // server time - local time, from WELCOME
	pings  int64 // PING.t counter (collect marks)
}

func (p *player) connect() error {
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, *url, nil)
	if err != nil {
		return fmt.Errorf("%s dial %s: %w", p.name, *url, err)
	}
	ws.SetReadLimit(64 << 10)
	p.ws = ws
	return nil
}

func (p *player) send(env protocol.Envelope, pl protocol.Payload) error {
	p.seq++
	env.Seq = p.seq
	frame, err := protocol.Encode(protocol.C2S, env, pl)
	if err != nil {
		return fmt.Errorf("%s encode %s: %w", p.name, pl.Type(), err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	if err := p.ws.Write(ctx, websocket.MessageText, frame); err != nil {
		return fmt.Errorf("%s write: %w", p.name, err)
	}
	return nil
}

func (p *player) recv() (protocol.Message, error) {
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	_, data, err := p.ws.Read(ctx)
	if err != nil {
		return protocol.Message{}, fmt.Errorf("%s read: %w", p.name, err)
	}
	m, err := protocol.Decode(protocol.S2C, data)
	if err != nil {
		return protocol.Message{}, fmt.Errorf("%s got an invalid message: %w", p.name, err)
	}
	if m.Seq > p.ack {
		p.ack = m.Seq
	}
	switch m.Payload.(type) {
	case *protocol.Error, *protocol.Reject:
		return m, fmt.Errorf("%s got %s %+v", p.name, m.T, m.Payload)
	}
	return m, nil
}

func (p *player) expect(t string) (protocol.Message, error) {
	for i := 0; i < 50; i++ {
		m, err := p.recv()
		if err != nil {
			return m, err
		}
		if m.T == t {
			return m, nil
		}
	}
	return protocol.Message{}, fmt.Errorf("%s: no %s within 50 messages", p.name, t)
}

func (p *player) missionEnd() (*protocol.MissionEnd, error) {
	m, err := p.expect(protocol.TMissionEnd)
	if err != nil {
		return nil, err
	}
	return m.Payload.(*protocol.MissionEnd), nil
}

// collect sends a PING and returns what arrived before its PONG (other
// PONGs, from heartbeats, are skipped).
func (p *player) collect() ([]protocol.Message, error) {
	p.pings++
	mark := p.pings
	if err := p.send(protocol.Envelope{}, &protocol.Ping{T: mark}); err != nil {
		return nil, err
	}
	var out []protocol.Message
	for i := 0; i < 100; i++ {
		m, err := p.recv()
		if err != nil {
			return nil, err
		}
		if pong, ok := m.Payload.(*protocol.Pong); ok {
			if pong.T == mark {
				return out, nil
			}
			continue
		}
		out = append(out, m)
	}
	return nil, fmt.Errorf("%s: no PONG within 100 messages", p.name)
}

func (p *player) expectAlly(id string, connected bool) error {
	for i := 0; i < 50; i++ {
		m, err := p.expect(protocol.TAllyStatus)
		if err != nil {
			return err
		}
		if as := m.Payload.(*protocol.AllyStatus); as.PlayerID == id && as.Connected == connected {
			return nil
		}
	}
	return fmt.Errorf("%s: no ALLY_STATUS %s connected=%v", p.name, id, connected)
}

func (p *player) hello(env protocol.Envelope, h *protocol.Hello) (*protocol.Welcome, error) {
	if err := p.send(env, h); err != nil {
		return nil, err
	}
	m, err := p.recv()
	if err != nil {
		return nil, err
	}
	w, ok := m.Payload.(*protocol.Welcome)
	if !ok {
		return nil, fmt.Errorf("%s: expected WELCOME, got %s", p.name, m.T)
	}
	p.id, p.token, p.epoch = w.PlayerID, w.ResumeToken, w.Epoch
	p.offset = w.ServerTime - time.Now().UnixMilli()
	return w, nil
}

func (p *player) serverNow() int64 { return time.Now().UnixMilli() + p.offset }

// waitServer sleeps until the server clock (as ps[0] sees it) passes t,
// plus a margin, sending a heartbeat PING from every player each 5 s like a
// real client (PROTOCOL.md §9: 30 s of silence closes the connection).
func waitServer(t int64, ps ...*player) error {
	d := time.Duration(t-ps[0].serverNow()+500) * time.Millisecond
	if d <= 0 {
		return nil
	}
	fmt.Printf("...  waiting %s for the server clock\n", d.Round(time.Second))
	end := time.Now().Add(d)
	for time.Now().Before(end) {
		for _, p := range ps {
			p.pings++
			if err := p.send(protocol.Envelope{}, &protocol.Ping{T: p.pings}); err != nil {
				return err
			}
		}
		time.Sleep(min(5*time.Second, time.Until(end)))
	}
	return nil
}
