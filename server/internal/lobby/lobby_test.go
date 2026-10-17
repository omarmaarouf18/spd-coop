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

package lobby

import (
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

func TestHappyPathRunStart(t *testing.T) {
	ts := newTestServer(t, "", nil)
	a, b, runA, runB := pair(t, ts)

	if runA.RunID != runB.RunID || runA.Seed != runB.Seed || runA.RoleSeed != runB.RoleSeed {
		t.Fatalf("players got different runs: %+v vs %+v", runA, runB)
	}
	if runA.Seed < 0 || runA.Seed >= protocol.MaxSeedExclusive {
		t.Fatalf("seed %d out of [0, 26^9)", runA.Seed)
	}
	p := runA.Players
	if len(p) != 2 || p[0].PlayerID != a.id || p[0].Slot != 0 || p[0].HeroClass != "WARRIOR" ||
		p[1].PlayerID != b.id || p[1].Slot != 1 || p[1].HeroClass != "MAGE" || p[1].Platform != "android" {
		t.Fatalf("RUN_START players = %+v", p)
	}
}

func TestWelcomeAndPing(t *testing.T) {
	ts := newTestServer(t, "", nil)
	c := dial(t, ts)
	w := c.hello("desktop")
	if w.Resumed || !w.Complete || w.Epoch != ts.hub.Epoch() || len(w.ResumeToken) != 43 || len(w.PlayerID) != 16 {
		t.Fatalf("WELCOME = %+v", w)
	}
	c.send(protocol.Envelope{}, &protocol.Ping{T: 123})
	m := c.next(protocol.TPong)
	if m.Payload.(*protocol.Pong).T != 123 || m.Ack == nil || *m.Ack != 2 {
		t.Fatalf("PONG = %+v ack=%v", m.Payload, m.Ack)
	}
}

func TestVersionReject(t *testing.T) {
	ts := newTestServer(t, "", func(o *Options) { o.AllowedVersions = []int{923, 924} })

	c := dial(t, ts)
	c.send(protocol.Envelope{}, &protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "ios"})
	r := c.next(protocol.TReject).Payload.(*protocol.Reject)
	if r.Code != protocol.CodeVersionMismatch || r.MinGameVersionCode == nil || *r.MinGameVersionCode != 923 {
		t.Fatalf("REJECT = %+v", r)
	}
	if got := c.expectClose(); got != protocol.CloseRejected {
		t.Fatalf("close status = %v, want 4001", got)
	}

	c = dial(t, ts)
	c.send(protocol.Envelope{}, &protocol.Hello{Protocol: 2, GameVersionCode: 923, Platform: "ios"})
	if r := c.next(protocol.TReject).Payload.(*protocol.Reject); r.Code != protocol.CodeVersionMismatch {
		t.Fatalf("REJECT for protocol 2 = %+v", r)
	}

	c = dial(t, ts)
	c.raw([]byte(`{"v":2,"t":"HELLO","seq":1,"p":{}}`))
	if r := c.next(protocol.TReject).Payload.(*protocol.Reject); r.Code != protocol.CodeVersionMismatch {
		t.Fatalf("REJECT for v 2 = %+v", r)
	}

	c = dial(t, ts)
	c.send(protocol.Envelope{}, &protocol.Hello{Protocol: 1, GameVersionCode: 924, Platform: "ios"})
	c.next(protocol.TWelcome)
}

func TestBadHelloAndResumeFailed(t *testing.T) {
	ts := newTestServer(t, "", nil)
	c := dial(t, ts)
	c.raw([]byte(`{"v":1,"t":"HELLO","seq":1,"p":{"protocol":1}}`))
	if r := c.next(protocol.TReject).Payload.(*protocol.Reject); r.Code != protocol.CodeBadMessage {
		t.Fatalf("REJECT = %+v", r)
	}

	c = dial(t, ts)
	c.send(protocol.Envelope{}, &protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "desktop",
		ResumeToken: strings.Repeat("x", 43)})
	if r := c.next(protocol.TReject).Payload.(*protocol.Reject); r.Code != protocol.CodeResumeFailed {
		t.Fatalf("REJECT = %+v", r)
	}
	if got := c.expectClose(); got != protocol.CloseRejected {
		t.Fatalf("close status = %v, want 4001", got)
	}
}

func TestRoomNotFoundAndFull(t *testing.T) {
	ts := newTestServer(t, "", nil)
	a, b, c := dial(t, ts), dial(t, ts), dial(t, ts)
	a.hello("desktop")
	b.hello("desktop")
	c.hello("desktop")

	c.send(protocol.Envelope{}, &protocol.RoomJoin{Code: "ZZZZZZ"})
	if e := c.expectError(protocol.CodeRoomNotFound); e.RefSeq == nil || *e.RefSeq != 2 {
		t.Fatalf("ref_seq = %v, want 2", e.RefSeq)
	}

	a.send(protocol.Envelope{}, &protocol.RoomCreate{})
	st := a.next(protocol.TRoomState).Payload.(*protocol.RoomState)
	if len(st.Code) != 6 || st.Phase != "lobby" || len(st.Players) != 1 || st.Players[0].Slot != 0 {
		t.Fatalf("ROOM_STATE = %+v", st)
	}
	for _, ch := range st.Code {
		if !strings.ContainsRune(protocol.RoomCodeAlphabet, ch) {
			t.Fatalf("room code %q uses %q, outside the alphabet", st.Code, ch)
		}
	}
	b.send(protocol.Envelope{}, &protocol.RoomJoin{Code: st.Code})
	if st := b.next(protocol.TRoomState).Payload.(*protocol.RoomState); len(st.Players) != 2 || st.Players[1].PlayerID != b.id {
		t.Fatalf("ROOM_STATE after join = %+v", st)
	}
	c.send(protocol.Envelope{}, &protocol.RoomJoin{Code: st.Code})
	c.expectError(protocol.CodeRoomFull)
}

func TestInvalidStates(t *testing.T) {
	ts := newTestServer(t, "", nil)
	c := dial(t, ts)

	// Before HELLO: the connection stays open.
	c.send(protocol.Envelope{}, &protocol.Ping{T: 1})
	c.expectError(protocol.CodeInvalidState)
	c.hello("desktop")

	c.send(protocol.Envelope{}, &protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "desktop"})
	c.expectError(protocol.CodeInvalidState)
	c.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "MAGE", Ready: true})
	c.expectError(protocol.CodeNotInRoom)
	c.send(protocol.Envelope{}, &protocol.RoomLeave{})
	c.expectError(protocol.CodeNotInRoom)
	c.send(protocol.Envelope{Depth: protocol.Int(1), Branch: protocol.Int(0)}, &protocol.Mark{Cell: 1, Kind: "GO"})
	c.expectError(protocol.CodeNotInRoom)

	c.send(protocol.Envelope{}, &protocol.RoomCreate{})
	c.next(protocol.TRoomState)
	c.send(protocol.Envelope{}, &protocol.RoomCreate{})
	c.expectError(protocol.CodeAlreadyInRoom)
	c.send(protocol.Envelope{}, &protocol.Quick{PhraseID: 1})
	c.expectError(protocol.CodeInvalidState)

	// Decode errors and seq order.
	c.raw([]byte(`{"v":1,"t":"TASK_DONE","seq":99,"p":{}}`))
	c.expectError(protocol.CodeUnknownType)
	c.raw([]byte(`{"v":1,"t":"PING","seq":1,"p":{"t":1}}`))
	c.expectError(protocol.CodeBadMessage) // seq 1 is not above the last one
	c.raw([]byte(`{"v":1,"t":"PING","seq":100,"p":{"t":1},"x":1}`))
	c.expectError(protocol.CodeBadMessage)
	if err := c.ws.Write(c.ctx, websocket.MessageBinary, []byte{1, 2}); err != nil {
		t.Fatal(err)
	}
	c.expectError(protocol.CodeBadMessage)

	// In a running room: no leave, no ready, presence is fine.
	_, b, _, _ := pair(t, ts)
	b.send(protocol.Envelope{}, &protocol.RoomLeave{})
	b.expectError(protocol.CodeInvalidState)
	b.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "MAGE", Ready: false})
	b.expectError(protocol.CodeInvalidState)
	b.send(protocol.Envelope{}, &protocol.RoomJoin{Code: "ZZZZZZ"})
	b.expectError(protocol.CodeAlreadyInRoom)
}

func TestLobbyLeaveFreesSlot(t *testing.T) {
	ts := newTestServer(t, "", nil)
	a, b := dial(t, ts), dial(t, ts)
	a.hello("desktop")
	b.hello("desktop")
	a.send(protocol.Envelope{}, &protocol.RoomCreate{})
	code := a.next(protocol.TRoomState).Payload.(*protocol.RoomState).Code
	b.send(protocol.Envelope{}, &protocol.RoomJoin{Code: code})
	b.next(protocol.TRoomState)
	a.next(protocol.TRoomState)

	b.send(protocol.Envelope{}, &protocol.RoomLeave{})
	if st := a.next(protocol.TRoomState).Payload.(*protocol.RoomState); len(st.Players) != 1 {
		t.Fatalf("after leave: %+v", st)
	}
	a.send(protocol.Envelope{}, &protocol.RoomLeave{})
	a.send(protocol.Envelope{}, &protocol.Ping{T: 1}) // processed after the leave
	a.next(protocol.TPong)
	// The empty room is gone.
	b.send(protocol.Envelope{}, &protocol.RoomJoin{Code: code})
	b.expectError(protocol.CodeRoomNotFound)
}

func TestPresenceRelayOverwritesFrom(t *testing.T) {
	ts := newTestServer(t, "", nil)
	a, b, _, _ := pair(t, ts)

	frame := []byte(`{"v":1,"t":"GHOST","seq":` + itoa(a.seq+1) + `,"from":"ffffffffffffffff","depth":3,"branch":0,` +
		`"p":{"pos":345,"anim":"run","hpPct":80,"heroClass":"WARRIOR","tier":2}}`)
	a.seq++
	a.raw(frame)
	m := b.next(protocol.TGhost)
	g := m.Payload.(*protocol.Ghost)
	if m.From != a.id || *m.Depth != 3 || *m.Branch != 0 || g.Pos != 345 || g.HPPct != 80 || g.Tier != 2 {
		t.Fatalf("relayed GHOST = %+v %+v (want from=%s)", m.Envelope, g, a.id)
	}

	b.send(protocol.Envelope{Depth: protocol.Int(3), Branch: protocol.Int(0)}, &protocol.Mark{Cell: 99, Kind: "DANGER"})
	if m := a.next(protocol.TMark); m.From != b.id || m.Payload.(*protocol.Mark).Kind != "DANGER" {
		t.Fatalf("relayed MARK = %+v", m)
	}
	b.send(protocol.Envelope{}, &protocol.Quick{PhraseID: 5})
	if m := a.next(protocol.TQuick); m.From != b.id || m.Depth != nil {
		t.Fatalf("relayed QUICK = %+v", m)
	}
}

func TestRateLimit(t *testing.T) {
	ts := newTestServer(t, "", func(o *Options) {
		o.RateLimit, o.RateBurst, o.AbuseWindow = 5, 5, 300*time.Millisecond
	})
	c := dial(t, ts)
	c.hello("desktop")
	for i := 0; i < 20; i++ {
		c.send(protocol.Envelope{}, &protocol.Ping{T: int64(i)})
	}
	sawLimited := false
	for i := 0; i < 20 && !sawLimited; i++ {
		m := c.recv()
		if m.T == protocol.TError && m.Payload.(*protocol.Error).Code == protocol.CodeRateLimited {
			sawLimited = true
		}
	}
	if !sawLimited {
		t.Fatal("no RATE_LIMITED error after a burst")
	}

	// Staying over the limit for the abuse window closes the connection.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c.seq++
		if err := c.ws.Write(c.ctx, websocket.MessageText,
			[]byte(`{"v":1,"t":"PING","seq":`+itoa(c.seq)+`,"p":{"t":1}}`)); err != nil {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if got := c.expectClose(); got != websocket.StatusPolicyViolation {
		t.Fatalf("close status = %v, want 1008", got)
	}
}

func TestHeartbeatAndHelloTimeouts(t *testing.T) {
	ts := newTestServer(t, "", func(o *Options) {
		o.HelloTimeout, o.HeartbeatTimeout = 200*time.Millisecond, 300*time.Millisecond
	})
	silent := dial(t, ts)
	if got := silent.expectClose(); got != protocol.CloseTimeout {
		t.Fatalf("no HELLO: close status = %v, want 4002", got)
	}

	c := dial(t, ts)
	c.hello("desktop")
	// Keep alive past the timeout with pings, then go quiet.
	for i := 0; i < 4; i++ {
		time.Sleep(150 * time.Millisecond)
		c.send(protocol.Envelope{}, &protocol.Ping{T: 1})
		c.next(protocol.TPong)
	}
	if got := c.expectClose(); got != protocol.CloseTimeout {
		t.Fatalf("silent client: close status = %v, want 4002", got)
	}
}

func TestDisconnectAndResumeWithReplay(t *testing.T) {
	ts := newTestServer(t, "", nil)
	a, b := dial(t, ts), dial(t, ts)
	a.hello("desktop")
	b.hello("desktop")
	a.send(protocol.Envelope{}, &protocol.RoomCreate{})
	code := a.next(protocol.TRoomState).Payload.(*protocol.RoomState).Code
	b.send(protocol.Envelope{}, &protocol.RoomJoin{Code: code})
	b.next(protocol.TRoomState)
	a.next(protocol.TRoomState)
	b.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "ROGUE", Ready: true})
	b.next(protocol.TRoomState)
	a.next(protocol.TRoomState)

	// B drops: A sees B disconnected (and B's ready reset in the lobby).
	_ = b.ws.CloseNow()
	st := a.next(protocol.TRoomState).Payload.(*protocol.RoomState)
	if st.Players[1].Connected || st.Players[1].Ready {
		t.Fatalf("after B dropped: %+v", st.Players[1])
	}
	if as := a.next(protocol.TAllyStatus).Payload.(*protocol.AllyStatus); as.PlayerID != b.id || as.Connected {
		t.Fatalf("ALLY_STATUS = %+v", as)
	}

	// While B is away A changes its hero: reliable events for B are logged.
	a.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "CLERIC", Ready: true})
	a.next(protocol.TRoomState)

	b2, w := b.resume(ts)
	// B missed two reliable events: the ROOM_STATE of its own disconnect and
	// the one of A's READY.
	if !w.Resumed || !w.Complete || w.Replayed != 2 || w.Room == nil || w.Room.Code != code || w.Run != nil {
		t.Fatalf("WELCOME on resume = %+v", w)
	}
	welcomeSeq := b2.ack
	// The replayed events keep their original seq: consecutive after the old
	// ack, and lower than the WELCOME's.
	r1, r2 := b2.next(protocol.TRoomState), b2.next(protocol.TRoomState)
	if r1.Seq != b.ack+1 || r2.Seq != b.ack+2 || r2.Seq >= welcomeSeq {
		t.Fatalf("replayed seqs %d, %d (old ack %d, WELCOME %d)", r1.Seq, r2.Seq, b.ack, welcomeSeq)
	}
	if r2.Payload.(*protocol.RoomState).Players[0].HeroClass != "CLERIC" {
		t.Fatalf("last replayed event = %+v", r2.Payload)
	}
	// A sees B back.
	a.next(protocol.TRoomState)
	if as := a.next(protocol.TAllyStatus).Payload.(*protocol.AllyStatus); !as.Connected {
		t.Fatalf("ALLY_STATUS after resume = %+v", as)
	}
	// And the room can still start.
	b2.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "ROGUE", Ready: true})
	if run := b2.expect(protocol.TRunStart).Payload.(*protocol.RunStart); run.Players[1].HeroClass != "ROGUE" {
		t.Fatalf("RUN_START after resume = %+v", run)
	}
}

func TestSessionReplaced(t *testing.T) {
	ts := newTestServer(t, "", nil)
	a := dial(t, ts)
	a.hello("desktop")
	a2, w := a.resume(ts)
	if !w.Resumed {
		t.Fatalf("WELCOME = %+v", w)
	}
	a.expectError(protocol.CodeSessionReplaced)
	if got := a.expectClose(); got != protocol.CloseSessionReplaced {
		t.Fatalf("old connection close status = %v, want 4003", got)
	}
	a2.send(protocol.Envelope{}, &protocol.Ping{T: 1})
	a2.next(protocol.TPong)
}

func TestRunSurvivesRestart(t *testing.T) {
	ts := newTestServer(t, "", nil)
	a, b, run, _ := pair(t, ts)
	ts.close()

	// A new server process on the same database: a new epoch.
	ts2 := newTestServer(t, ts.dbPath, nil)
	if ts2.hub.Epoch() == ts.hub.Epoch() {
		t.Fatal("restart kept the epoch")
	}
	a2, w := a.resume(ts2)
	if !w.Resumed || w.Complete || w.Replayed != 0 || w.Run == nil || w.Room == nil {
		t.Fatalf("WELCOME after restart = %+v", w)
	}
	if w.Run.RunID != run.RunID || w.Run.Seed != run.Seed || w.Run.RoleSeed != run.RoleSeed || w.Room.Phase != "running" {
		t.Fatalf("run after restart = %+v room %+v, want %+v", w.Run, w.Room, run)
	}
	if w.Room.Players[1].Connected {
		t.Fatalf("B shows connected before resuming: %+v", w.Room.Players)
	}
	if as := a2.next(protocol.TAllyStatus).Payload.(*protocol.AllyStatus); as.PlayerID != b.id || as.Connected {
		t.Fatalf("A's ALLY_STATUS for offline B = %+v", as)
	}

	b2, wb := b.resume(ts2)
	if wb.Run == nil || wb.Run.RunID != run.RunID {
		t.Fatalf("B's WELCOME after restart = %+v", wb)
	}
	if as := a2.next(protocol.TAllyStatus).Payload.(*protocol.AllyStatus); !as.Connected {
		t.Fatalf("A's ALLY_STATUS after B resumed = %+v", as)
	}
	// Presence flows again.
	b2.send(protocol.Envelope{Depth: protocol.Int(1), Branch: protocol.Int(0)},
		&protocol.Ghost{Pos: 1, Anim: "idle", HPPct: 100, HeroClass: "MAGE", Tier: 0})
	if m := a2.next(protocol.TGhost); m.From != b.id {
		t.Fatalf("GHOST after restart = %+v", m)
	}
}

func TestEmptyLobbyDeleted(t *testing.T) {
	ts := newTestServer(t, "", func(o *Options) { o.EmptyRoomTTL = 100 * time.Millisecond })
	a, c := dial(t, ts), dial(t, ts)
	a.hello("desktop")
	c.hello("desktop")
	a.send(protocol.Envelope{}, &protocol.RoomCreate{})
	code := a.next(protocol.TRoomState).Payload.(*protocol.RoomState).Code
	_ = a.ws.CloseNow()

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if ts.hub.roomByCode(code) == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	c.send(protocol.Envelope{}, &protocol.RoomJoin{Code: code})
	c.expectError(protocol.CodeRoomNotFound)
}

func TestEventLogEviction(t *testing.T) {
	ts := newTestServer(t, "", func(o *Options) { o.EventLogSize = 2 })
	a, b := dial(t, ts), dial(t, ts)
	a.hello("desktop")
	b.hello("desktop")
	a.send(protocol.Envelope{}, &protocol.RoomCreate{})
	code := a.next(protocol.TRoomState).Payload.(*protocol.RoomState).Code
	b.send(protocol.Envelope{}, &protocol.RoomJoin{Code: code})
	b.next(protocol.TRoomState)
	a.next(protocol.TRoomState)
	_ = b.ws.CloseNow()
	a.next(protocol.TRoomState)
	a.next(protocol.TAllyStatus)
	for _, h := range []string{"MAGE", "ROGUE", "CLERIC"} {
		a.send(protocol.Envelope{}, &protocol.Ready{HeroClass: h, Ready: false})
		a.next(protocol.TRoomState)
	}
	_, w := b.resume(ts)
	if w.Complete || w.Replayed != 0 || w.Room == nil {
		t.Fatalf("WELCOME after eviction = %+v (want complete=false, snapshot only)", w)
	}
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }
