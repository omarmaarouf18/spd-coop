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
	"time"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
	"github.com/omarmaarouf18/spd-coop/server/internal/store"
)

// room is an actor: every field below `cmds` is only touched by the
// goroutine running loop(), inside closures sent through do().
type room struct {
	hub  *Hub
	code string
	cmds chan func()
	done chan struct{} // closed when the actor has stopped

	phase   string
	slots   [2]*slot
	run     *protocol.RunInfo
	log     []logEntry       // reliable s2c events, oldest first, bounded
	evicted map[string]int64 // playerId → highest seq dropped from log
	stopped bool
	empty   *time.Timer // lobby with nobody connected: deletion timer
	g       *game       // co-op engine state of a running run (SRV-02)
}

type slot struct {
	sess      *session
	platform  string
	heroClass string
	ready     bool
	connected bool
	lastSeen  int64
}

type logEntry struct {
	player string
	seq    int64
	frame  []byte
}

// newRoom builds an unstarted room; the caller starts it with start().
func newRoom(h *Hub, code string) *room {
	return &room{
		hub:     h,
		code:    code,
		cmds:    make(chan func()),
		done:    make(chan struct{}),
		phase:   protocol.PhaseLobby,
		evicted: map[string]int64{},
		g:       newGame(),
	}
}

func (r *room) start() { go r.loop() }

func (r *room) loop() {
	for f := range r.cmds {
		f()
		if r.stopped {
			close(r.done)
			return
		}
	}
}

// do runs f on the actor and waits for it. It returns false (without
// running f) if the room has stopped.
func (r *room) do(f func()) bool {
	ran := make(chan struct{})
	select {
	case r.cmds <- func() { f(); close(ran) }:
	case <-r.done:
		return false
	}
	select {
	case <-ran:
		return true
	case <-r.done:
		// f ran and stopped the room.
		return true
	}
}

// restoreRunning loads a persisted run before start() (after a restart).
func (r *room) restoreRunning(slots []*slot, run *protocol.RunInfo) {
	r.phase = protocol.PhaseRunning
	r.run = run
	for i, s := range slots {
		r.slots[i] = s
		s.sess.setRoom(r, protocol.PhaseRunning)
	}
	r.restoreGame()
	r.start()
}

// ---- actor-side helpers (call only inside do) -------------------------------

func (r *room) snapshot() *protocol.RoomState {
	st := &protocol.RoomState{Code: r.code, Phase: r.phase}
	for i, s := range r.slots {
		if s == nil {
			continue
		}
		st.Players = append(st.Players, protocol.RoomPlayer{
			PlayerID: s.sess.id, Slot: i, HeroClass: s.heroClass,
			Ready: s.ready, Connected: s.connected, Platform: s.platform,
		})
	}
	return st
}

func (r *room) slotOf(sess *session) (int, *slot) {
	for i, s := range r.slots {
		if s != nil && s.sess == sess {
			return i, s
		}
	}
	return -1, nil
}

func (r *room) ally(i int) *slot { return r.slots[1-i] }

// sendReliable sends a reliable event to one slot (online or not) and logs
// it for replay.
func (r *room) sendReliable(s *slot, p protocol.Payload) {
	r.sendReliableEnv(s, protocol.Envelope{}, p)
}

// sendReliableEnv is sendReliable with envelope fields (a relayed reliable
// type such as DROP_SHARE carries `from` and the floor).
func (r *room) sendReliableEnv(s *slot, env protocol.Envelope, p protocol.Payload) {
	seq, frame := s.sess.send(env, p, false)
	if frame == nil {
		return
	}
	r.log = append(r.log, logEntry{player: s.sess.id, seq: seq, frame: frame})
	for len(r.log) > r.hub.opts.EventLogSize {
		old := r.log[0]
		if old.seq > r.evicted[old.player] {
			r.evicted[old.player] = old.seq
		}
		r.log = r.log[1:]
	}
}

func (r *room) broadcastState() {
	st := r.snapshot()
	for _, s := range r.slots {
		if s != nil {
			copySt := *st
			r.sendReliable(s, &copySt)
		}
	}
}

func (r *room) allyStatus(to, about *slot) {
	if to == nil {
		return
	}
	r.sendReliable(to, &protocol.AllyStatus{
		PlayerID: about.sess.id, Connected: about.connected, Paused: false, LastSeen: about.lastSeen,
	})
}

func (r *room) stop() {
	r.stopped = true
	if r.empty != nil {
		r.empty.Stop()
	}
	if r.g.wake != nil {
		r.g.wake.Stop()
	}
	for _, s := range r.slots {
		if s != nil {
			s.sess.setRoom(nil, "")
		}
	}
	r.hub.removeRoom(r)
}

// armEmptyTimer deletes a lobby nobody is connected to after EmptyRoomTTL.
func (r *room) armEmptyTimer() {
	if r.phase != protocol.PhaseLobby {
		return
	}
	for _, s := range r.slots {
		if s != nil && s.connected {
			return
		}
	}
	if r.empty != nil {
		r.empty.Stop()
	}
	r.empty = time.AfterFunc(r.hub.opts.EmptyRoomTTL, func() {
		r.do(func() {
			for _, s := range r.slots {
				if s != nil && s.connected {
					return
				}
			}
			if r.phase == protocol.PhaseLobby {
				r.hub.log.Info("empty lobby deleted", "code", r.code)
				r.stop()
			}
		})
	})
}

func (r *room) disarmEmptyTimer() {
	if r.empty != nil {
		r.empty.Stop()
		r.empty = nil
	}
}

// ---- commands (called by the connection's reader goroutine) ------------------

// join adds sess to the first free slot. The result is an error code or "".
func (r *room) join(sess *session, platform string) string {
	code := protocol.CodeRoomNotFound
	r.do(func() {
		if r.phase != protocol.PhaseLobby || (r.slots[0] != nil && r.slots[1] != nil) {
			code = protocol.CodeRoomFull
			return
		}
		i := 0
		if r.slots[0] != nil {
			i = 1
		}
		r.slots[i] = &slot{sess: sess, platform: platform, connected: true, lastSeen: r.hub.nowMs()}
		sess.setRoom(r, r.phase)
		r.disarmEmptyTimer()
		code = ""
		r.hub.log.Info("room joined", "code", r.code, "player", sess.id, "slot", i)
		r.broadcastState()
	})
	return code
}

// leave frees the player's lobby slot; the room stops when it is empty.
func (r *room) leave(sess *session) string {
	code := protocol.CodeNotInRoom
	r.do(func() {
		i, s := r.slotOf(sess)
		if s == nil {
			return
		}
		if r.phase != protocol.PhaseLobby {
			code = protocol.CodeInvalidState
			return
		}
		r.slots[i] = nil
		sess.setRoom(nil, "")
		code = ""
		r.hub.log.Info("room left", "code", r.code, "player", sess.id)
		if r.slots[1-i] == nil {
			r.stop()
			return
		}
		r.broadcastState()
		r.armEmptyTimer()
	})
	return code
}

// ready sets heroClass/ready and starts the run when both are ready.
func (r *room) ready(sess *session, heroClass string, ready bool) string {
	code := protocol.CodeNotInRoom
	r.do(func() {
		_, s := r.slotOf(sess)
		if s == nil {
			return
		}
		if r.phase != protocol.PhaseLobby {
			code = protocol.CodeInvalidState
			return
		}
		s.heroClass, s.ready = heroClass, ready
		code = ""
		r.broadcastState()
		r.maybeStart()
	})
	return code
}

// maybeStart sends RUN_START exactly once, when both slots are filled,
// connected and ready with a hero class (PROTOCOL.md §6).
func (r *room) maybeStart() {
	if r.run != nil {
		return
	}
	for _, s := range r.slots {
		if s == nil || !s.connected || !s.ready || s.heroClass == "" {
			return
		}
	}
	run := &protocol.RunInfo{RunID: newHexID(), Seed: newSeed(), RoleSeed: newRoleSeed()}
	rec := store.Run{RunID: run.RunID, Code: r.code, Seed: run.Seed, RoleSeed: run.RoleSeed,
		CreatedAt: r.hub.nowMs(), Phase: store.RunRunning}
	for i, s := range r.slots {
		run.Players = append(run.Players, protocol.RunPlayer{PlayerID: s.sess.id, Slot: i, HeroClass: s.heroClass, Platform: s.platform})
		rec.Players = append(rec.Players, store.RunPlayer{PlayerID: s.sess.id, Slot: i, HeroClass: s.heroClass, Platform: s.platform})
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := r.hub.opts.Store.CreateRun(ctx, rec); err != nil {
		r.hub.log.Error("persisting the run failed; staying in the lobby", "code", r.code, "err", err)
		for _, s := range r.slots {
			s.sess.send(protocol.Envelope{}, &protocol.Error{Code: protocol.CodeInternal, Msg: "could not start the run, try READY again"}, false)
			s.ready = false
		}
		r.broadcastState()
		return
	}
	r.run = run
	r.phase = protocol.PhaseRunning
	r.hub.registerRun(run.RunID, r)
	r.hub.log.Info("run started", "code", r.code, "run", run.RunID, "seed", run.Seed)
	for _, s := range r.slots {
		s.sess.setRoom(r, r.phase)
		rs := protocol.RunStart(*run)
		r.sendReliable(s, &rs)
	}
	r.broadcastState()
}

// relay forwards a presence message to the ally, best-effort, with `from`
// set by the server (PROTOCOL.md §5.3).
func (r *room) relay(sess *session, env protocol.Envelope, p protocol.Payload) string {
	code := protocol.CodeNotInRoom
	r.do(func() {
		i, s := r.slotOf(sess)
		if s == nil {
			return
		}
		if r.phase != protocol.PhaseRunning {
			code = protocol.CodeInvalidState
			return
		}
		code = ""
		r.noteFloor(i, env)
		a := r.ally(i)
		if a == nil || !a.connected {
			return // ally offline: dropped silently
		}
		a.sess.send(protocol.Envelope{From: sess.id, Depth: env.Depth, Branch: env.Branch}, p, true)
	})
	return code
}

// disconnected marks the player offline unless c is no longer its
// connection (it was replaced by a resume).
func (r *room) disconnected(sess *session) {
	r.do(func() {
		i, s := r.slotOf(sess)
		if s == nil || !s.connected || sess.conn() != nil {
			return
		}
		s.connected = false
		s.lastSeen = r.hub.nowMs()
		r.hub.log.Info("player disconnected from room", "code", r.code, "player", sess.id, "phase", r.phase)
		if r.phase == protocol.PhaseLobby {
			s.ready = false
			r.broadcastState()
			r.armEmptyTimer()
		}
		r.allyStatus(r.ally(i), s)
		if r.phase == protocol.PhaseRunning {
			r.pauseClocks()
		}
	})
}

// resume attaches a resumed player (already attached to c) to the room:
// WELCOME with the snapshot, the replay, then ALLY_STATUS to the ally.
func (r *room) resume(sess *session, c *conn, welcome protocol.Welcome, helloSeq int64, ack int64, sameEpoch bool) bool {
	return r.do(func() {
		i, s := r.slotOf(sess)
		if s == nil {
			c.sendWelcome(sess, welcome, helloSeq, nil)
			return
		}
		var replay [][]byte
		complete := sameEpoch && r.evicted[sess.id] <= ack
		if complete {
			for _, e := range r.log {
				if e.player == sess.id && e.seq > ack {
					replay = append(replay, e.frame)
				}
			}
		}
		s.connected = true
		s.lastSeen = r.hub.nowMs()
		r.disarmEmptyTimer()
		welcome.Room = r.snapshot()
		if r.run != nil {
			run := *r.run
			welcome.Run = &run
		}
		welcome.Replayed = len(replay)
		welcome.Complete = complete
		c.sendWelcome(sess, welcome, helloSeq, replay)
		r.hub.log.Info("player resumed into room", "code", r.code, "player", sess.id, "phase", r.phase,
			"replayed", len(replay), "complete", complete)
		if r.phase == protocol.PhaseLobby {
			r.broadcastState()
		}
		r.allyStatus(r.ally(i), s)
		if a := r.ally(i); a != nil {
			// Tell the resumed player where the ally stands, too.
			r.allyStatus(s, a)
		}
		if r.phase == protocol.PhaseRunning {
			if !complete {
				r.resyncTo(s)
			}
			r.resumeClocks()
		}
	})
}
