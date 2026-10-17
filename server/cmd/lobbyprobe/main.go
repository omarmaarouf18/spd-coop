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

// Command lobbyprobe simulates two clients against a running server and
// checks the whole first-playable flow of PROTOCOL.md. It exits 0 only if
// every step matched.
//
//	go run ./cmd/lobbyprobe                          # full flow on ws://localhost:8080/ws
//	go run ./cmd/lobbyprobe -state /tmp/probe.json   # ... and save both players' resume data
//	go run ./cmd/lobbyprobe -resume /tmp/probe.json  # after a server restart: resume both into the run
//
// The state file holds resume tokens (secrets for those two test players);
// it is written with mode 0600 and tokens are never printed.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/coder/websocket"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

type player struct {
	name  string
	ws    *websocket.Conn
	seq   int64
	ack   int64
	id    string
	token string
	epoch string
}

type savedPlayer struct {
	ID    string `json:"playerId"`
	Token string `json:"resumeToken"`
	Epoch string `json:"epoch"`
	Ack   int64  `json:"ack"`
}

type state struct {
	URL   string      `json:"url"`
	RunID string      `json:"runId"`
	Seed  int64       `json:"seed"`
	A     savedPlayer `json:"a"`
	B     savedPlayer `json:"b"`
}

var (
	url     = flag.String("url", "ws://localhost:8080/ws", "server websocket URL")
	timeout = flag.Duration("timeout", 10*time.Second, "timeout per step")
	stateF  = flag.String("state", "", "write both players' resume data here after the run starts (mode 0600)")
	resumeF = flag.String("resume", "", "resume both players from this state file instead of the full flow")
)

func main() {
	flag.Parse()
	var err error
	if *resumeF != "" {
		err = resumeFlow(*resumeF)
	} else {
		err = fullFlow()
	}
	if err != nil {
		fmt.Printf("FAIL: %v\n", err)
		os.Exit(1)
	}
	fmt.Println("PASS: every step matched")
}

func step(format string, args ...any) { fmt.Printf("ok   "+format+"\n", args...) }

func fullFlow() error {
	a, b := &player{name: "A"}, &player{name: "B"}
	for _, p := range []*player{a, b} {
		if err := p.connect(); err != nil {
			return err
		}
		defer p.ws.CloseNow()
	}
	for _, p := range []*player{a, b} {
		w, err := p.hello(protocol.Envelope{}, &protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "desktop"})
		if err != nil {
			return err
		}
		step("%s HELLO -> WELCOME playerId=%s epoch=%s resumed=%v", p.name, w.PlayerID, w.Epoch, w.Resumed)
	}

	if err := a.send(protocol.Envelope{}, &protocol.RoomCreate{}); err != nil {
		return err
	}
	m, err := a.expect(protocol.TRoomState)
	if err != nil {
		return err
	}
	code := m.Payload.(*protocol.RoomState).Code
	step("A ROOM_CREATE -> ROOM_STATE code=%s", code)

	if err := b.send(protocol.Envelope{}, &protocol.RoomJoin{Code: code}); err != nil {
		return err
	}
	if m, err = b.expect(protocol.TRoomState); err != nil {
		return err
	}
	step("B ROOM_JOIN %s -> ROOM_STATE players=%d", code, len(m.Payload.(*protocol.RoomState).Players))

	if err := a.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "WARRIOR", Ready: true}); err != nil {
		return err
	}
	if err := b.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "MAGE", Ready: true}); err != nil {
		return err
	}
	ma, err := a.expect(protocol.TRunStart)
	if err != nil {
		return err
	}
	mb, err := b.expect(protocol.TRunStart)
	if err != nil {
		return err
	}
	ra, rb := ma.Payload.(*protocol.RunStart), mb.Payload.(*protocol.RunStart)
	step("A RUN_START runId=%s seed=%d roleSeed=%d", ra.RunID, ra.Seed, ra.RoleSeed)
	step("B RUN_START runId=%s seed=%d roleSeed=%d", rb.RunID, rb.Seed, rb.RoleSeed)
	if ra.RunID != rb.RunID || ra.Seed != rb.Seed || ra.RoleSeed != rb.RoleSeed {
		return errors.New("A and B got different runs")
	}
	if ra.Seed < 0 || ra.Seed >= protocol.MaxSeedExclusive {
		return fmt.Errorf("seed %d outside [0, 26^9)", ra.Seed)
	}
	step("both seeds equal and 0 <= seed < 26^9")

	if err := ghostBothWays(a, b); err != nil {
		return err
	}

	if *stateF != "" {
		// Saved before B is killed, so -resume brings both back.
		if err := save(*stateF, state{URL: *url, RunID: ra.RunID, Seed: ra.Seed, A: a.saved(), B: b.saved()}); err != nil {
			return err
		}
		step("resume data saved to %s (mode 0600)", *stateF)
	}

	_ = b.ws.CloseNow()
	step("B killed (connection dropped without a close frame)")
	if m, err = a.expect(protocol.TAllyStatus); err != nil {
		return err
	}
	if as := m.Payload.(*protocol.AllyStatus); as.PlayerID != b.id || as.Connected {
		return fmt.Errorf("A got ALLY_STATUS %+v, want B connected=false", as)
	}
	step("A ALLY_STATUS playerId=%s connected=false", b.id)

	b2 := &player{name: "B'", id: b.id, token: b.token, epoch: b.epoch, ack: b.ack}
	if err := b2.connect(); err != nil {
		return err
	}
	defer b2.ws.CloseNow()
	w, err := b2.hello(protocol.Envelope{Ack: protocol.Int64(b.ack)},
		&protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "desktop", ResumeToken: b.token, Epoch: b.epoch})
	if err != nil {
		return err
	}
	if !w.Resumed || w.Run == nil || w.Run.RunID != ra.RunID {
		return fmt.Errorf("B resume WELCOME = %+v, want resumed into run %s", w, ra.RunID)
	}
	step("B resumed: WELCOME resumed=true run=%s replayed=%d complete=%v", w.Run.RunID, w.Replayed, w.Complete)
	if m, err = a.expect(protocol.TAllyStatus); err != nil {
		return err
	}
	if as := m.Payload.(*protocol.AllyStatus); as.PlayerID != b.id || !as.Connected {
		return fmt.Errorf("A got ALLY_STATUS %+v, want B connected=true", as)
	}
	step("A ALLY_STATUS playerId=%s connected=true", b.id)
	return nil
}

// resumeFlow brings both saved players back (e.g. after a server restart).
func resumeFlow(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var st state
	if err := json.Unmarshal(data, &st); err != nil {
		return err
	}
	a := &player{name: "A", id: st.A.ID, token: st.A.Token, epoch: st.A.Epoch, ack: st.A.Ack}
	b := &player{name: "B", id: st.B.ID, token: st.B.Token, epoch: st.B.Epoch, ack: st.B.Ack}
	for _, p := range []*player{a, b} {
		if err := p.connect(); err != nil {
			return err
		}
		defer p.ws.CloseNow()
		savedEpoch := p.epoch
		w, err := p.hello(protocol.Envelope{Ack: protocol.Int64(p.ack)},
			&protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "desktop", ResumeToken: p.token, Epoch: p.epoch})
		if err != nil {
			return err
		}
		if !w.Resumed || w.Run == nil || w.Run.RunID != st.RunID || w.Run.Seed != st.Seed || w.Room == nil || w.Room.Phase != protocol.PhaseRunning {
			return fmt.Errorf("%s WELCOME = %+v, want resumed into running run %s", p.name, w, st.RunID)
		}
		step("%s resumed: WELCOME resumed=true run=%s seed=%d epoch=%s (saved epoch %s) complete=%v",
			p.name, w.Run.RunID, w.Run.Seed, w.Epoch, savedEpoch, w.Complete)
	}
	m, err := a.expectAlly(b.id, true)
	if err != nil {
		return err
	}
	step("A ALLY_STATUS playerId=%s connected=%v", b.id, m.Connected)
	return ghostBothWays(a, b)
}

func ghostBothWays(a, b *player) error {
	floor := protocol.Envelope{Depth: protocol.Int(1), Branch: protocol.Int(0)}
	for _, pair := range [][2]*player{{a, b}, {b, a}} {
		from, to := pair[0], pair[1]
		g := &protocol.Ghost{Pos: 345, Anim: "run", HPPct: 100, HeroClass: "WARRIOR", Tier: 0}
		if err := from.send(floor, g); err != nil {
			return err
		}
		m, err := to.expect(protocol.TGhost)
		if err != nil {
			return err
		}
		if m.From != from.id || m.Payload.(*protocol.Ghost).Pos != 345 {
			return fmt.Errorf("%s got GHOST from=%s, want from=%s", to.name, m.From, from.id)
		}
		step("%s GHOST -> %s (from=%s depth=%d)", from.name, to.name, m.From, *m.Depth)
	}
	return nil
}

func (p *player) connect() error {
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, *url, nil)
	if err != nil {
		return fmt.Errorf("%s dial %s: %w", p.name, *url, err)
	}
	p.ws = ws
	return nil
}

func (p *player) send(env protocol.Envelope, pl protocol.Payload) error {
	p.seq++
	env.Seq = p.seq
	frame, err := protocol.Encode(protocol.C2S, env, pl)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	return p.ws.Write(ctx, websocket.MessageText, frame)
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
	if m.T == protocol.TError || m.T == protocol.TReject {
		return m, fmt.Errorf("%s got %s %+v", p.name, m.T, m.Payload)
	}
	return m, nil
}

// expect skips other messages until one of type t arrives.
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

func (p *player) expectAlly(id string, connected bool) (*protocol.AllyStatus, error) {
	for i := 0; i < 50; i++ {
		m, err := p.expect(protocol.TAllyStatus)
		if err != nil {
			return nil, err
		}
		if as := m.Payload.(*protocol.AllyStatus); as.PlayerID == id && as.Connected == connected {
			return as, nil
		}
	}
	return nil, fmt.Errorf("%s: no ALLY_STATUS %s connected=%v", p.name, id, connected)
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
	return w, nil
}

func (p *player) saved() savedPlayer {
	return savedPlayer{ID: p.id, Token: p.token, Epoch: p.epoch, Ack: p.ack}
}

func save(path string, st state) error {
	data, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}
