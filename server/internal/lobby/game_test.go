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
	"context"
	"encoding/json"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

// startCell pairs two players on depth, A steps on the trap (A is the
// prisoner) and both get MISSION_START.
func startCell(t *testing.T, ts *testServer, depth int) (a, b *client, st *protocol.MissionStart) {
	t.Helper()
	a, b, _, _ = pair(t, ts)
	moveTo(a, b, depth)
	moveTo(b, a, depth)
	a.send(fl(depth), &protocol.MissionTrigger{Kind: "CELL", TrapCell: 1207, RoomID: 7})
	st = a.next(protocol.TMissionStart).Payload.(*protocol.MissionStart)
	if sb := b.next(protocol.TMissionStart).Payload.(*protocol.MissionStart); *sb != *st {
		t.Fatalf("MISSION_START differs: A %+v, B %+v", st, sb)
	}
	return a, b, st
}

func deny(t *testing.T, c *client, reason string) {
	t.Helper()
	m := c.next(protocol.TDeny)
	if got := m.Payload.(*protocol.Deny).Reason; got != reason {
		t.Fatalf("DENY %s, want %s", got, reason)
	}
	if m.Ack == nil || *m.Ack != c.seq {
		t.Fatalf("DENY ack = %v, want the request's seq %d", m.Ack, c.seq)
	}
}

func TestCellKeySuccess(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	now := fc.ms()
	a, b, st := startCell(t, ts, 12) // region index 2

	if st.Prisoner != a.id || st.Rescuer != b.id || st.Depth != 12 || st.Branch != 0 ||
		st.TrapCell != 1207 || st.RoomID != 7 || st.Kind != "CELL" {
		t.Fatalf("MISSION_START = %+v", st)
	}
	if st.StartsAt != now || st.Deadline != now+(4*60+2*15)*1000 || st.WaveIntervalMs != 30000 {
		t.Fatalf("clock fields = startsAt %d deadline %d wave %d (now %d)", st.StartsAt, st.Deadline, st.WaveIntervalMs, now)
	}
	if st.KeySeed < 0 || st.KeySeed > protocol.MaxSafeInt || len(st.MissionID) != 16 {
		t.Fatalf("ids = %+v", st)
	}

	fc.Advance(70 * time.Second)
	key := b.id + ":1"
	b.send(fl(12), &protocol.Give{ItemNetID: key, Kind: "cell_key"})
	for _, c := range []*client{a, b} {
		tr := c.next(protocol.TTransfer).Payload.(*protocol.Transfer)
		if tr.ItemNetID != key || tr.From != b.id || tr.To != a.id || tr.Kind != "cell_key" || tr.Item != nil {
			t.Fatalf("TRANSFER = %+v", tr)
		}
		u := c.next(protocol.TMissionUpdate).Payload.(*protocol.MissionUpdate)
		if u.MissionID != st.MissionID || u.Paused || u.Tasks == nil || u.Tasks.Key == nil || !*u.Tasks.Key ||
			u.RemainingMs != st.Deadline-fc.ms() {
			t.Fatalf("MISSION_UPDATE = %+v", u)
		}
		e := c.next(protocol.TMissionEnd).Payload.(*protocol.MissionEnd)
		if e.MissionID != st.MissionID || e.Result != "success" || e.Reward == nil || e.Penalty != nil {
			t.Fatalf("MISSION_END = %+v", e)
		}
	}
	// The key was transferred exactly once; the mission is over.
	b.send(fl(12), &protocol.Give{ItemNetID: key, Kind: "cell_key"})
	deny(t, b, protocol.DenyReasonAlreadyTransferred)
	b.send(fl(12), &protocol.Give{ItemNetID: b.id + ":2", Kind: "cell_key"})
	deny(t, b, protocol.DenyReasonInvalidState)
	// Nothing fires later (no backstop for an ended mission).
	fc.Advance(10 * time.Minute)
	a.quiet()
	b.quiet()
}

func TestMissionTriggerDenials(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 3)
	moveTo(b, a, 4)
	a.send(fl(3), &protocol.MissionTrigger{Kind: "CELL", TrapCell: 1, RoomID: 1})
	deny(t, a, protocol.DenyReasonAllyOtherFloor)
	b.send(fl(4), &protocol.Give{ItemNetID: b.id + ":1", Kind: "cell_key"})
	deny(t, b, protocol.DenyReasonInvalidState) // no mission

	moveTo(b, a, 3)
	a.send(fl(3), &protocol.MissionTrigger{Kind: "CELL", TrapCell: 1, RoomID: 1})
	a.next(protocol.TMissionStart)
	b.next(protocol.TMissionStart)
	b.send(fl(3), &protocol.MissionTrigger{Kind: "CELL", TrapCell: 2, RoomID: 2})
	deny(t, b, protocol.DenyReasonMissionActive)
	a.send(fl(3), &protocol.Give{ItemNetID: a.id + ":1", Kind: "cell_key"})
	deny(t, a, protocol.DenyReasonInvalidState) // the prisoner holds no key
	b.send(fl(4), &protocol.Give{ItemNetID: b.id + ":1", Kind: "cell_key"})
	deny(t, b, protocol.DenyReasonInvalidState) // not on the mission floor

	// A second pair: the ally offline.
	c, d, _, _ := pair(t, ts)
	moveTo(c, d, 2)
	moveTo(d, c, 2)
	_ = d.ws.CloseNow()
	c.expect(protocol.TAllyStatus)
	c.send(fl(2), &protocol.MissionTrigger{Kind: "CELL", TrapCell: 1, RoomID: 1})
	deny(t, c, protocol.DenyReasonAllyOffline)
}

func TestPrisonerDownedRecallRevives(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, st := startCell(t, ts, 3)

	b.send(fl(3), &protocol.UseRecall{Target: a.id, Via: "recall"})
	deny(t, b, protocol.DenyReasonNotDowned)

	fc.Advance(10 * time.Second)
	a.send(fl(3), &protocol.Downed{Cause: "wave 1"})
	for _, c := range []*client{a, b} {
		d := c.next(protocol.TAllyDowned).Payload.(*protocol.AllyDowned)
		if d.Player != a.id || d.Deadline != fc.ms()+30000 {
			t.Fatalf("ALLY_DOWNED = %+v (now %d)", d, fc.ms())
		}
	}
	a.send(fl(3), &protocol.Downed{}) // repeat: ignored, the window is not reset
	a.quiet()
	a.send(fl(3), &protocol.UseRecall{Target: b.id, Via: "recall"})
	deny(t, a, protocol.DenyReasonDowned)
	b.send(fl(3), &protocol.UseRecall{Target: b.id, Via: "recall"})
	deny(t, b, protocol.DenyReasonInvalidState) // never on yourself

	// The rescuer reads Recall from another floor: in place inside the Cell.
	fc.Advance(20 * time.Second)
	moveTo(b, a, 2)
	b.send(fl(2), &protocol.UseRecall{Target: a.id, Via: "recall"})
	for _, c := range []*client{a, b} {
		rv := c.next(protocol.TRevive).Payload.(*protocol.Revive)
		if rv.Target != a.id || rv.Mode != "inPlace" || rv.HPPct != 25 {
			t.Fatalf("REVIVE = %+v", rv)
		}
		e := c.next(protocol.TMissionEnd).Payload.(*protocol.MissionEnd)
		if e.MissionID != st.MissionID || e.Result != "revived" || e.Penalty != nil || e.Reward != nil {
			t.Fatalf("MISSION_END = %+v", e)
		}
	}
	fc.Advance(time.Minute) // the old window is gone: no expiry
	a.quiet()
	b.quiet()
}

func TestCellDownedExpiryFailsWithoutDeath(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, st := startCell(t, ts, 8)
	a.send(fl(8), &protocol.Downed{})
	a.next(protocol.TAllyDowned)
	b.next(protocol.TAllyDowned)

	fc.Advance(29999 * time.Millisecond)
	a.quiet()
	fc.Advance(time.Millisecond)
	for _, c := range []*client{a, b} {
		ms := c.collect()
		if !slices.Equal(types(ms), []string{protocol.TMissionEnd}) {
			t.Fatalf("after the prisoner's expiry: %v (want only MISSION_END, no DOWNED_EXPIRED)", types(ms))
		}
		e := ms[0].Payload.(*protocol.MissionEnd)
		if e.MissionID != st.MissionID || e.Result != "failed" || e.Penalty == nil ||
			e.Penalty.RegionStartDepth != 6 || e.Penalty.GoldPct != 25 || e.Reward != nil {
			t.Fatalf("MISSION_END = %+v penalty %+v", e, e.Penalty)
		}
	}
	// The prisoner is not out: a later DOWNED opens a new window.
	a.send(fl(6), &protocol.Downed{})
	a.next(protocol.TAllyDowned)
}

func TestDownedOutsideMission(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{DownedWindow: 10 * time.Second})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 4)
	moveTo(b, a, 5)

	// Healing needs the same floor; Recall works from anywhere.
	a.send(fl(4), &protocol.Downed{})
	a.next(protocol.TAllyDowned)
	b.next(protocol.TAllyDowned)
	b.send(fl(5), &protocol.UseRecall{Target: a.id, Via: "healing"})
	deny(t, b, protocol.DenyReasonAllyOtherFloor)
	moveTo(b, a, 4)
	b.send(fl(4), &protocol.UseRecall{Target: a.id, Via: "healing"})
	if rv := b.next(protocol.TRevive).Payload.(*protocol.Revive); rv.Mode != "inPlace" || rv.HPPct != 25 {
		t.Fatalf("healing REVIVE = %+v", rv)
	}
	a.next(protocol.TRevive)

	a.send(fl(4), &protocol.Downed{})
	a.next(protocol.TAllyDowned)
	b.next(protocol.TAllyDowned)
	b.send(fl(9), &protocol.UseRecall{Target: a.id, Via: "recall"})
	if rv := b.next(protocol.TRevive).Payload.(*protocol.Revive); rv.Mode != "regionStart" {
		t.Fatalf("recall REVIVE = %+v", rv)
	}
	a.next(protocol.TRevive)

	// Expiry: the real death path, the player is out for the run.
	a.send(fl(4), &protocol.Downed{})
	a.next(protocol.TAllyDowned)
	b.next(protocol.TAllyDowned)
	fc.Advance(10 * time.Second)
	for _, c := range []*client{a, b} {
		if e := c.next(protocol.TDownedExpired).Payload.(*protocol.DownedExpired); e.Player != a.id {
			t.Fatalf("DOWNED_EXPIRED = %+v", e)
		}
	}
	a.send(fl(4), &protocol.Downed{}) // out: ignored
	a.quiet()
	b.send(fl(4), &protocol.MissionTrigger{Kind: "CELL", TrapCell: 1, RoomID: 1})
	deny(t, b, protocol.DenyReasonAllyOffline)
}

func TestDisconnectPausesMissionAndResumeResendsDowned(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, st := startCell(t, ts, 2)
	fc.Advance(60 * time.Second)

	_ = b.ws.CloseNow()
	if s := a.next(protocol.TAllyStatus).Payload.(*protocol.AllyStatus); s.Connected {
		t.Fatalf("ALLY_STATUS = %+v", s)
	}
	u := a.next(protocol.TMissionUpdate).Payload.(*protocol.MissionUpdate)
	if !u.Paused || u.RemainingMs != 180000 || u.MissionID != st.MissionID {
		t.Fatalf("pause MISSION_UPDATE = %+v", u)
	}
	// Downed while paused: the window starts frozen.
	a.send(fl(2), &protocol.Downed{})
	if d := a.next(protocol.TAllyDowned).Payload.(*protocol.AllyDowned); d.Deadline != fc.ms()+30000 {
		t.Fatalf("ALLY_DOWNED while paused = %+v", d)
	}
	fc.Advance(80 * time.Second) // under the 90 s grace: nothing expires
	a.quiet()

	b2, w := b.resume(ts)
	if !w.Complete {
		t.Fatalf("WELCOME = %+v", w)
	}
	// B's replay holds the pause and the Downed (seq below the WELCOME's);
	// then come the resume events.
	welcomeSeq := b2.ack
	var resumed []protocol.Message
	for _, m := range b2.collect() {
		if m.Seq > welcomeSeq {
			resumed = append(resumed, m)
		}
	}
	now := fc.ms()
	for _, c := range []*client{a} {
		if s := c.next(protocol.TAllyStatus).Payload.(*protocol.AllyStatus); !s.Connected {
			t.Fatalf("ALLY_STATUS = %+v", s)
		}
	}
	for name, ms := range map[string][]protocol.Message{"A": a.collect(), "B": resumed} {
		var gotUpdate, gotDowned bool
		for _, m := range ms {
			switch p := m.Payload.(type) {
			case *protocol.MissionUpdate:
				if !p.Paused && p.RemainingMs == 180000 {
					gotUpdate = true
				}
			case *protocol.AllyDowned:
				if p.Player == a.id && p.Deadline == now+30000 {
					gotDowned = true
				}
			}
		}
		if !gotUpdate || !gotDowned {
			t.Fatalf("%s after resume: %v (update %v, downed %v)", name, types(ms), gotUpdate, gotDowned)
		}
	}
	// The window continues from 30 s: expiry → the Cell fails.
	fc.Advance(30 * time.Second)
	if e := a.expect(protocol.TMissionEnd).Payload.(*protocol.MissionEnd); e.Result != "failed" {
		t.Fatalf("MISSION_END = %+v", e)
	}
	b2.expect(protocol.TMissionEnd)
}

func TestPauseGraceAbortsMission(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, st := startCell(t, ts, 2)
	_ = a.ws.CloseNow()
	b.expect(protocol.TMissionUpdate)
	fc.Advance(89 * time.Second)
	b.quiet()
	fc.Advance(time.Second)
	e := b.next(protocol.TMissionEnd).Payload.(*protocol.MissionEnd)
	if e.MissionID != st.MissionID || e.Result != "aborted" || e.Penalty != nil || e.Reward != nil {
		t.Fatalf("MISSION_END = %+v", e)
	}
}

// An abort revives a Downed prisoner in place: no penalty, no real death.
func TestAbortRevivesDownedPrisoner(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, st := startCell(t, ts, 7)
	a.send(fl(7), &protocol.Downed{})
	a.next(protocol.TAllyDowned)
	b.next(protocol.TAllyDowned)
	_ = a.ws.CloseNow()
	b.expect(protocol.TMissionUpdate)
	fc.Advance(90 * time.Second)
	ms := b.collect()
	if !slices.Equal(types(ms), []string{protocol.TRevive, protocol.TMissionEnd}) {
		t.Fatalf("after the grace: %v, want REVIVE then MISSION_END", types(ms))
	}
	if rv := ms[0].Payload.(*protocol.Revive); rv.Target != a.id || rv.Mode != "inPlace" || rv.HPPct != 25 {
		t.Fatalf("REVIVE = %+v", rv)
	}
	if e := ms[1].Payload.(*protocol.MissionEnd); e.MissionID != st.MissionID || e.Result != "aborted" || e.Penalty != nil {
		t.Fatalf("MISSION_END = %+v", e)
	}
	// The prisoner gets both on resume (replay), and no window is left.
	a2, w := a.resume(ts)
	if w.Replayed < 2 {
		t.Fatalf("WELCOME = %+v", w)
	}
	got := types(a2.collect())
	if !slices.Contains(got, protocol.TRevive) || !slices.Contains(got, protocol.TMissionEnd) {
		t.Fatalf("A after resume: %v", got)
	}
	fc.Advance(time.Minute)
	for _, c := range []*client{a2, b} {
		c.quiet(protocol.TAllyStatus) // no DOWNED_EXPIRED, ever
	}
}

func TestMissionOvertimeBackstop(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _ := startCell(t, ts, 1)
	fc.Advance(4*time.Minute + 29*time.Second) // waves are lethal now; no DOWNED yet
	a.quiet()
	fc.Advance(time.Second)
	for _, c := range []*client{a, b} {
		if e := c.next(protocol.TMissionEnd).Payload.(*protocol.MissionEnd); e.Result != "failed" || e.Penalty.RegionStartDepth != 1 {
			t.Fatalf("MISSION_END = %+v", e)
		}
	}
}

// Replies to one connection arrive in the order its requests were received
// (ITM-01 relies on it): CLAIM → DENY, CLAIMS_REQ → CLAIMS, CLAIM → CLAIMED,
// sent back to back without waiting.
func TestRepliesKeepRequestOrder(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 2)
	moveTo(b, a, 2)
	a.send(fl(2), &protocol.Claim{ItemNetID: "2:0:7"})
	a.next(protocol.TClaimed)
	b.next(protocol.TClaimed)

	for round := 0; round < 8; round++ { // 5 frames a round, under the burst of 60
		fresh := "2:0:" + itoa(int64(100+round))
		b.send(fl(2), &protocol.Claim{ItemNetID: "2:0:7"}) // taken
		denySeq := b.seq
		b.send(protocol.Envelope{}, &protocol.ClaimsReq{Depth: 2, Branch: 0})
		claimsSeq := b.seq
		b.send(fl(2), &protocol.Claim{ItemNetID: fresh})
		b.send(fl(2), &protocol.Claim{ItemNetID: fresh}) // own re-claim: silent
		b.send(protocol.Envelope{}, &protocol.ClaimsReq{Depth: 2, Branch: 0})
		lastSeq := b.seq

		m := b.next(protocol.TDeny)
		if m.Payload.(*protocol.Deny).Reason != protocol.DenyReasonTaken || *m.Ack != denySeq {
			t.Fatalf("round %d: first reply %+v ack %v", round, m.Payload, *m.Ack)
		}
		m = b.next(protocol.TClaims)
		if *m.Ack != claimsSeq || slices.Contains(m.Payload.(*protocol.Claims).ItemNetIDs, fresh) {
			t.Fatalf("round %d: CLAIMS before the claim = %+v", round, m.Payload)
		}
		if c := b.next(protocol.TClaimed).Payload.(*protocol.Claimed); c.ItemNetID != fresh || c.By != b.id {
			t.Fatalf("round %d: CLAIMED = %+v", round, c)
		}
		m = b.next(protocol.TClaims)
		if *m.Ack != lastSeq || !slices.Contains(m.Payload.(*protocol.Claims).ItemNetIDs, fresh) {
			t.Fatalf("round %d: CLAIMS after the claim = %+v", round, m.Payload)
		}
		a.next(protocol.TClaimed)
	}
}

func TestClaimRaceFirstWins(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 2)
	moveTo(b, a, 2)

	a.send(fl(3), &protocol.Claim{ItemNetID: "2:0:1"})
	deny(t, a, protocol.DenyReasonInvalidState) // not this floor's loot
	a.send(fl(2), &protocol.Claim{ItemNetID: b.id + ":4"})
	deny(t, a, protocol.DenyReasonInvalidState) // an unseen runtime id

	for i := int64(0); i < 10; i++ {
		id := "2:0:" + itoa(i)
		var wg sync.WaitGroup
		for _, c := range []*client{a, b} {
			wg.Add(1)
			go func() { defer wg.Done(); c.send(fl(2), &protocol.Claim{ItemNetID: id}) }()
		}
		wg.Wait()
		var winner string
		denies := 0
		for _, c := range []*client{a, b} {
			for _, m := range c.collect() {
				switch p := m.Payload.(type) {
				case *protocol.Claimed:
					if winner != "" && winner != p.By {
						t.Fatalf("two winners for %s", id)
					}
					winner = p.By
				case *protocol.Deny:
					if p.Reason != protocol.DenyReasonTaken {
						t.Fatalf("DENY %s", p.Reason)
					}
					denies++
				}
			}
		}
		if winner == "" || denies != 1 {
			t.Fatalf("%s: winner %q, %d denies", id, winner, denies)
		}
	}
}

func TestGiveExactlyOnceAndDropShare(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 2)
	moveTo(b, a, 2)

	blob := `{"__className":"PotionOfHealing"}`
	id := a.id + ":1"
	a.send(fl(2), &protocol.Give{ItemNetID: id, Kind: "item", Item: &blob, ToCell: protocol.Int(40)})
	for _, c := range []*client{a, b} {
		tr := c.next(protocol.TTransfer).Payload.(*protocol.Transfer)
		if tr.From != a.id || tr.To != b.id || tr.Item == nil || *tr.Item != blob {
			t.Fatalf("TRANSFER = %+v", tr)
		}
	}
	// A retry of the same GIVE (new seq) is a duplicate: not applied twice.
	a.send(fl(2), &protocol.Give{ItemNetID: id, Kind: "item", Item: &blob})
	deny(t, a, protocol.DenyReasonAlreadyTransferred)
	// A byte-identical duplicated frame is refused by the seq rule.
	frame, err := protocol.Encode(protocol.C2S, protocol.Envelope{Seq: a.seq, Depth: protocol.Int(2), Branch: protocol.Int(0)},
		&protocol.Give{ItemNetID: id, Kind: "item", Item: &blob})
	if err != nil {
		t.Fatal(err)
	}
	a.raw(frame)
	a.expectError(protocol.CodeBadMessage)
	b.quiet() // B saw exactly one TRANSFER

	// The current owner may give it on: A → B → A → B, across floors too.
	for i, hop := range [][2]*client{{b, a}, {a, b}} {
		from, to := hop[0], hop[1]
		from.send(fl(7), &protocol.Give{ItemNetID: id, Kind: "item", Item: &blob})
		for _, c := range []*client{a, b} {
			tr := c.next(protocol.TTransfer).Payload.(*protocol.Transfer)
			if tr.From != from.id || tr.To != to.id {
				t.Fatalf("hop %d TRANSFER = %+v", i, tr)
			}
		}
		from.send(fl(7), &protocol.Give{ItemNetID: id, Kind: "item", Item: &blob})
		deny(t, from, protocol.DenyReasonAlreadyTransferred) // its own GIVE again
	}

	a.send(fl(2), &protocol.Claim{ItemNetID: "2:0:3"})
	a.next(protocol.TClaimed)
	b.next(protocol.TClaimed)
	b.send(fl(2), &protocol.Give{ItemNetID: "2:0:3", Kind: "item", Item: &blob})
	deny(t, b, protocol.DenyReasonNotOwner) // A holds it

	// A drops its claimed item: relayed to B (same floor) and claimable once.
	a.send(fl(2), &protocol.DropShare{Cell: 99, ItemNetID: "2:0:3", Item: blob})
	m := b.next(protocol.TDropShare)
	if ds := m.Payload.(*protocol.DropShare); m.From != a.id || *m.Depth != 2 || ds.Cell != 99 || ds.ItemNetID != "2:0:3" {
		t.Fatalf("DROP_SHARE = %+v %+v", m, ds)
	}
	a.quiet() // not echoed to the dropper
	b.send(protocol.Envelope{}, &protocol.ClaimsReq{Depth: 2, Branch: 0})
	if ids := b.next(protocol.TClaims).Payload.(*protocol.Claims).ItemNetIDs; slices.Contains(ids, "2:0:3") {
		t.Fatalf("a dropped item is listed as gone: %v", ids)
	}
	b.send(fl(2), &protocol.Claim{ItemNetID: "2:0:3"})
	if c := b.next(protocol.TClaimed).Payload.(*protocol.Claimed); c.By != b.id {
		t.Fatalf("CLAIMED = %+v", c)
	}
	a.next(protocol.TClaimed)
	a.send(fl(2), &protocol.Claim{ItemNetID: "2:0:3"})
	deny(t, a, protocol.DenyReasonTaken)
	a.send(fl(2), &protocol.DropShare{Cell: 1, ItemNetID: "2:0:3", Item: blob})
	deny(t, a, protocol.DenyReasonNotOwner)

	// A drop with the ally elsewhere is registered but not relayed.
	moveTo(b, a, 3)
	a.send(fl(2), &protocol.DropShare{Cell: 5, ItemNetID: a.id + ":9", Item: blob})
	b.quiet(protocol.TGhost)
}

func TestBossPool(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 5)
	moveTo(b, a, 4)

	a.send(fl(5), &protocol.BossEngage{BossID: 3, HT: 100})
	a.quiet() // ally elsewhere: no pool
	a.send(fl(5), &protocol.BossHit{BossID: 3, Dmg: 5})
	deny(t, a, protocol.DenyReasonNoPool)

	moveTo(b, a, 5)
	a.send(fl(5), &protocol.BossEngage{BossID: 3, HT: 100})
	for _, c := range []*client{a, b} {
		if hp := c.next(protocol.TBossHP).Payload.(*protocol.BossHP); hp.HP != 180 || hp.HT != 180 || hp.BossID != 3 {
			t.Fatalf("BOSS_HP = %+v", hp)
		}
	}
	b.send(fl(5), &protocol.BossEngage{BossID: 3, HT: 999}) // the first engage defined it
	if hp := b.next(protocol.TBossHP).Payload.(*protocol.BossHP); hp.HT != 180 {
		t.Fatalf("re-engage BOSS_HP = %+v", hp)
	}
	b.send(fl(5), &protocol.BossHit{BossID: 3, Dmg: 100})
	for _, c := range []*client{a, b} {
		if hp := c.next(protocol.TBossHP).Payload.(*protocol.BossHP); hp.HP != 80 {
			t.Fatalf("BOSS_HP = %+v", hp)
		}
	}
	a.send(fl(5), &protocol.BossHit{BossID: 3, Dmg: 500})
	for _, c := range []*client{a, b} {
		if hp := c.next(protocol.TBossHP).Payload.(*protocol.BossHP); hp.HP != 0 {
			t.Fatalf("BOSS_HP = %+v", hp)
		}
		c.next(protocol.TBossDead)
	}
	a.send(fl(5), &protocol.BossHit{BossID: 3, Dmg: 1})
	a.send(fl(5), &protocol.BossEngage{BossID: 3, HT: 100})
	a.quiet()
	b.quiet()
}

func TestTwinWindow(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 6)
	moveTo(b, a, 6)
	door := func(c *client, obj int64) {
		c.send(fl(6), &protocol.TwinAct{ObjectID: obj, Kind: "door", Cell: 30, Active: true})
	}

	door(a, 7)
	a.collect() // handled before the clock moves
	fc.Advance(2500 * time.Millisecond)
	door(b, 7) // too late: B's press opens a new window
	b.collect()
	a.quiet()
	fc.Advance(1500 * time.Millisecond)
	door(a, 7)
	for _, c := range []*client{a, b} {
		if d := c.next(protocol.TTwinDone).Payload.(*protocol.TwinDone); d.GroupID != 7 {
			t.Fatalf("TWIN_DONE = %+v", d)
		}
	}
	door(b, 7)
	door(a, 7) // done: no-ops
	a.quiet()

	sw := func(c *client, obj int64, active bool) {
		c.send(fl(6), &protocol.TwinAct{ObjectID: obj, Kind: "switch", Cell: 31, Active: active, GroupID: protocol.Int64(9)})
	}
	sw(a, 1, true)
	sw(b, 1, true) // the same switch is not a pair (B's press is now the open one)
	a.collect()
	b.quiet()
	sw(b, 1, false) // released: withdrawn
	sw(a, 2, true)
	sw(a, 2, false) // released before B pressed
	b.collect()
	sw(b, 1, true)
	a.collect()
	b.quiet()
	sw(a, 2, true)
	for _, c := range []*client{a, b} {
		if d := c.next(protocol.TTwinDone).Payload.(*protocol.TwinDone); d.GroupID != 9 {
			t.Fatalf("TWIN_DONE = %+v", d)
		}
	}
}

func TestTerrainDoorsOnlySameFloor(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 2)
	moveTo(b, a, 2)
	a.send(fl(2), &protocol.Terrain{Cell: 50, Tile: protocol.TileOpenDoor})
	m := b.next(protocol.TTerrain)
	if tr := m.Payload.(*protocol.Terrain); m.From != a.id || *m.Depth != 2 || tr.Cell != 50 || tr.Tile != 6 {
		t.Fatalf("TERRAIN = %+v", m)
	}
	a.send(fl(2), &protocol.Terrain{Cell: 50, Tile: 4}) // a wall: dropped
	b.quiet()
	moveTo(a, b, 3)
	a.send(fl(3), &protocol.Terrain{Cell: 50, Tile: protocol.TileDoor}) // other floor: dropped
	b.quiet()
	a.quiet()
}

func TestSuperSync(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 2)
	moveTo(b, a, 3)

	a.send(fl(2), &protocol.SyncDrink{Snapshot: "snap", Turns: 15})
	st := a.next(protocol.TSyncStart).Payload.(*protocol.SyncStart)
	if sb := b.next(protocol.TSyncStart).Payload.(*protocol.SyncStart); *sb != *st || st.Turns != 15 || st.Snapshot != "snap" {
		t.Fatalf("SYNC_START = %+v / %+v", st, sb)
	}
	b.send(fl(3), &protocol.SyncDrink{Snapshot: "x", Turns: 1})
	deny(t, b, protocol.DenyReasonSyncActive)

	view := &protocol.SyncView{SyncID: st.SyncID, Turn: 1, TerrainDiff: []int64{5, 6}, Entities: []protocol.SyncEntity{{ID: 1, Kind: "projection", Cell: 9, HPPct: 100}}}
	for i := 0; i < 15; i++ {
		b.send(fl(3), view) // the host streams its floor
	}
	b.collect() // B's frames are handled before A looks
	got := a.quiet(protocol.TSyncView)
	if len(got) != 10 || got[0].From != b.id || *got[0].Depth != 3 {
		t.Fatalf("SYNC_VIEW relayed %d frames (want the 10/s cap): %+v", len(got), got)
	}
	fc.Advance(time.Second)
	b.send(fl(3), view)
	a.next(protocol.TSyncView)
	b.send(fl(9), view) // neither player is on floor 9
	a.send(fl(3), view) // the drinker does not stream
	b.collect()
	a.quiet()

	for i := 0; i < 12; i++ {
		a.send(fl(3), &protocol.SyncCmd{SyncID: st.SyncID, Turn: 1, Action: "move", Cell: protocol.Int(10)})
	}
	a.collect()
	if got := b.quiet(protocol.TSyncCmd); len(got) != 10 || got[0].From != a.id {
		t.Fatalf("SYNC_CMD relayed %d frames", len(got))
	}
	a.send(fl(3), &protocol.SyncReport{SyncID: st.SyncID, HPLost: 3, Reason: "expired"}) // not the host
	b.send(fl(3), &protocol.SyncReport{SyncID: "0000000000000000", HPLost: 3, Reason: "expired"})
	b.collect()
	a.quiet(protocol.TSyncCmd)
	b.send(fl(3), &protocol.SyncReport{SyncID: st.SyncID, HPLost: 7, Reason: "expired"})
	for _, c := range []*client{a, b} {
		if e := c.expect(protocol.TSyncEnd).Payload.(*protocol.SyncEnd); e.SyncID != st.SyncID || e.HPLost != 7 || e.Reason != "expired" {
			t.Fatalf("SYNC_END = %+v", e)
		}
	}

	// Never while Downed; a disconnect interrupts it.
	a.send(fl(2), &protocol.Downed{})
	a.next(protocol.TAllyDowned)
	b.next(protocol.TAllyDowned)
	a.send(fl(2), &protocol.SyncDrink{Snapshot: "s", Turns: 5})
	deny(t, a, protocol.DenyReasonDowned)
	b.send(fl(3), &protocol.SyncDrink{Snapshot: "s", Turns: 5})
	st2 := b.next(protocol.TSyncStart).Payload.(*protocol.SyncStart)
	a.next(protocol.TSyncStart)
	_ = b.ws.CloseNow()
	if e := a.expect(protocol.TSyncEnd).Payload.(*protocol.SyncEnd); e.SyncID != st2.SyncID || e.HPLost != 0 || e.Reason != "interrupted" {
		t.Fatalf("SYNC_END on disconnect = %+v", e)
	}
}

func TestEngineStateSurvivesRestart(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, st := startCell(t, ts, 4)
	a.send(fl(4), &protocol.Claim{ItemNetID: "4:0:11"})
	a.next(protocol.TClaimed)
	b.next(protocol.TClaimed)
	fc.Advance(40 * time.Second)
	b.send(fl(4), &protocol.Downed{}) // the rescuer falls
	a.next(protocol.TAllyDowned)
	b.next(protocol.TAllyDowned)
	fc.Advance(10 * time.Second)
	b.send(fl(4), &protocol.BossEngage{BossID: 2, HT: 10})
	a.next(protocol.TBossHP)
	b.next(protocol.TBossHP)
	downDeadline := fc.ms() - 10000 + 30000
	fc.Advance(3 * time.Second)
	ts.close()
	_, _ = a.ws.CloseNow(), b.ws.CloseNow()
	waitOffline(t, ts, w0(a, b))

	// Simulate a crash instead of a clean stop: the last snapshot was taken
	// 2 s before the server died, with both clocks still running.
	savedAt := fc.ms() - 2000
	st0 := ts.hub.opts.Store
	blob, _, err := st0.RunState(context.Background(), ts2RunID(t, ts, a))
	if err != nil {
		t.Fatal(err)
	}
	g := newGame()
	if err := json.Unmarshal(blob, g); err != nil {
		t.Fatal(err)
	}
	g.Mission.Clock = gclock{Deadline: st.Deadline}
	g.Downed[b.id] = &gclock{Deadline: downDeadline}
	if blob, err = json.Marshal(g); err != nil {
		t.Fatal(err)
	}
	if err := st0.SaveRunState(context.Background(), ts2RunID(t, ts, a), blob, savedAt); err != nil {
		t.Fatal(err)
	}
	remaining := st.Deadline - savedAt
	downedLeft := downDeadline - savedAt

	// The new process has its own clock; the server was down for a while.
	fc2 := newFakeClock()
	fc2.now = fc.Now().Add(10 * time.Minute)
	fc = fc2
	ts2 := newGameServer(t, ts.dbPath, fc, Rules{})
	a2, w := a.resume(ts2)
	if w.Complete || w.Run == nil {
		t.Fatalf("WELCOME after restart = %+v", w)
	}
	var start *protocol.MissionStart
	var upd *protocol.MissionUpdate
	var dn *protocol.AllyDowned
	var hp *protocol.BossHP
	for _, m := range a2.collect() {
		switch p := m.Payload.(type) {
		case *protocol.MissionStart:
			start = p
		case *protocol.MissionUpdate:
			upd = p
		case *protocol.AllyDowned:
			dn = p
		case *protocol.BossHP:
			hp = p
		}
	}
	if start == nil || *start != *st {
		t.Fatalf("resynced MISSION_START = %+v, want %+v", start, st)
	}
	if upd == nil || !upd.Paused || upd.RemainingMs != remaining {
		t.Fatalf("resynced MISSION_UPDATE = %+v, want paused with %d ms", upd, remaining)
	}
	if dn == nil || dn.Player != b.id || dn.Deadline != fc.ms()+downedLeft {
		t.Fatalf("resynced ALLY_DOWNED = %+v", dn)
	}
	if hp == nil || hp.HP != 18 {
		t.Fatalf("resynced BOSS_HP = %+v", hp)
	}
	fc.Advance(5 * time.Minute) // still paused while B is away (the grace aborts at 90 s)
	if e := a2.expect(protocol.TMissionEnd).Payload.(*protocol.MissionEnd); e.Result != "aborted" {
		t.Fatalf("MISSION_END = %+v", e)
	}

	b2, _ := b.resume(ts2)
	b2.collect()
	a2.collect()
	// Item ownership survived: B cannot take A's claim.
	moveTo(b2, a2, 4)
	b2.send(fl(4), &protocol.Claim{ItemNetID: "4:0:11"})
	deny(t, b2, protocol.DenyReasonTaken)
	// B's Downed window was frozen for the whole downtime and runs again.
	fc.Advance(time.Duration(downedLeft-1000) * time.Millisecond)
	b2.quiet(protocol.TGhost)
	fc.Advance(time.Second)
	if e := a2.expect(protocol.TDownedExpired).Payload.(*protocol.DownedExpired); e.Player != b.id {
		t.Fatalf("DOWNED_EXPIRED = %+v", e)
	}
}

func TestResumeWithinGraceKeepsMission(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{PauseGrace: 30 * time.Second})
	a, b, st := startCell(t, ts, 2)
	_ = a.ws.CloseNow()
	b.expect(protocol.TMissionUpdate)
	fc.Advance(29 * time.Second)
	a2, _ := a.resume(ts)
	a2.collect()
	b.collect()
	fc.Advance(time.Minute) // the grace no longer applies
	b.quiet()
	a2.send(fl(2), &protocol.Downed{})
	a2.next(protocol.TAllyDowned)
	b.send(fl(2), &protocol.UseRecall{Target: a.id, Via: "healing"})
	b.expect(protocol.TRevive)
	if e := b.next(protocol.TMissionEnd).Payload.(*protocol.MissionEnd); e.MissionID != st.MissionID || e.Result != "revived" {
		t.Fatalf("MISSION_END = %+v", e)
	}
}

func TestGameNeedsRunningRoom(t *testing.T) {
	ts := newTestServer(t, "", nil)
	c := dial(t, ts)
	c.hello("desktop")
	c.send(fl(1), &protocol.Downed{})
	c.expectError(protocol.CodeNotInRoom)
	c.send(protocol.Envelope{}, &protocol.RoomCreate{})
	c.next(protocol.TRoomState)
	c.send(protocol.Envelope{}, &protocol.ClaimsReq{Depth: 1, Branch: 0})
	c.expectError(protocol.CodeInvalidState)
}

// w0 returns the clients' player ids.
func w0(cs ...*client) []string {
	var ids []string
	for _, c := range cs {
		ids = append(ids, c.id)
	}
	return ids
}

// waitOffline waits until the old server's room has handled every player's
// disconnect (its actor keeps running in this process after ts.close).
func waitOffline(t *testing.T, ts *testServer, ids []string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		sess := ts.hub.session(ids[0], 0)
		r, _ := sess.roomState()
		offline := false
		r.do(func() {
			offline = true
			for _, s := range r.slots {
				offline = offline && !s.connected
			}
		})
		if offline {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("the old room never saw the disconnects")
}

func ts2RunID(t *testing.T, ts *testServer, c *client) string {
	t.Helper()
	r, _ := ts.hub.session(c.id, 0).roomState()
	var id string
	r.do(func() { id = r.run.RunID })
	return id
}
