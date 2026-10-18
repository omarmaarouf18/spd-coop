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
	"errors"
	"slices"
	"time"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
	"github.com/omarmaarouf18/spd-coop/server/internal/store"
)

// The co-op engine (SRV-02, PROTOCOL.md §5.6–§5.10) lives in the room actor:
// game is only touched inside room.do closures, so it has no locks. Times
// are server Unix ms from Options.Clock.

// reviveHPPct is the HP every revive gives (game-design §5.2–§5.3).
const reviveHPPct = 25

// cellGoldPct is the gold a failed Cell takes from both players (§4.5).
const cellGoldPct = 25

// game is the room's engine state. The exported fields are the persisted
// form (store run_state, one JSON document); the rest is memory-only.
type game struct {
	Mission        *mission             `json:"mission,omitempty"`
	Downed         map[string]*gclock   `json:"downed,omitempty"` // playerId → Downed window
	Out            []string             `json:"out,omitempty"`    // players out of the run (player_out)
	Sync           *syncSession         `json:"sync,omitempty"`
	Bosses         []*boss              `json:"bosses,omitempty"`
	TwinDone       []twinKey            `json:"twinDone,omitempty"`
	LastMissionEnd *protocol.MissionEnd `json:"lastMissionEnd,omitempty"`
	LastSyncEnd    *protocol.SyncEnd    `json:"lastSyncEnd,omitempty"`

	floors   [2]floor                 // last floor each slot reported
	items    map[string]*store.Item   // itemNetId → record (also in run_items)
	kills    map[string]*store.Kill   // mobNetId → first kill (also in run_kills)
	terrain  map[floorKey]map[int]int // floor → cell → permanent tile (also in run_terrain)
	twins    map[twinKey]*twinPress   // open twin windows
	pausedAt int64                    // when the current pause began, 0 = not paused
	lastSave int64
	wake     Timer
	frames   [2][2]frameWindow // slot × (SYNC_VIEW, SYNC_CMD) per-second caps
}

func newGame() *game {
	return &game{Downed: map[string]*gclock{}, items: map[string]*store.Item{}, twins: map[twinKey]*twinPress{},
		kills: map[string]*store.Kill{}, terrain: map[floorKey]map[int]int{}}
}

// gclock is a pausable countdown: Deadline while running, Remaining (may be
// negative in overtime) while paused.
type gclock struct {
	Deadline  int64 `json:"deadline"`
	Remaining int64 `json:"remaining"`
	Paused    bool  `json:"paused"`
}

func (c *gclock) left(now int64) int64 {
	if c.Paused {
		return c.Remaining
	}
	return c.Deadline - now
}

func (c *gclock) pause(now int64) {
	if !c.Paused {
		c.Remaining, c.Paused = c.Deadline-now, true
	}
}

func (c *gclock) resume(now int64) {
	if c.Paused {
		c.Deadline, c.Paused = now+c.Remaining, false
	}
}

// deadlineAt is the deadline the clients are told: the real one while
// running, now + the frozen remainder while paused.
func (c *gclock) deadlineAt(now int64) int64 {
	if c.Paused {
		return now + max(0, c.Remaining)
	}
	return c.Deadline
}

type mission struct {
	Start protocol.MissionStart `json:"start"`
	Clock gclock                `json:"clock"`
	Key   bool                  `json:"key"`
}

type floor struct {
	depth, branch int
	known         bool
}

type syncSession struct {
	ID      string `json:"id"`
	Drinker int    `json:"drinker"` // slot
	Host    int    `json:"host"`    // slot of the ally world that hosts the Projection
	Turns   int64  `json:"turns"`
}

type boss struct {
	Depth  int   `json:"depth"`
	Branch int   `json:"branch"`
	BossID int64 `json:"bossId"`
	HP     int64 `json:"hp"`
	HT     int64 `json:"ht"` // the scaled pool size
	Dead   bool  `json:"dead"`
}

type twinKey struct {
	Depth  int    `json:"depth"`
	Branch int    `json:"branch"`
	Kind   string `json:"kind"`
	Group  int64  `json:"group"`
}

type twinPress struct {
	slot     int
	objectID int64
	at       int64
}

type frameWindow struct {
	start int64
	n     int
}

// request is one game message being handled.
type request struct {
	i             int // sender's slot
	s             *slot
	seq           int64
	depth, branch int // envelope floor (0/0 when absent)
}

func (q request) id() string { return q.s.sess.id }

// ---- helpers (actor only) ---------------------------------------------------

func (r *room) rules() *Rules { return &r.hub.opts.Rules }

func (r *room) isOut(playerID string) bool { return slices.Contains(r.g.Out, playerID) }

// paused reports whether the run's clocks are frozen: a player is away
// (D-005).
func (r *room) paused() bool {
	for _, s := range r.slots {
		if s == nil || !s.connected {
			return true
		}
	}
	return false
}

// allyOnFloor reports whether the ally of slot i last reported depth/branch.
func (r *room) allyOnFloor(i, depth, branch int) bool {
	f := r.g.floors[1-i]
	return f.known && f.depth == depth && f.branch == branch
}

func (r *room) allyOnline(i int) bool {
	a := r.ally(i)
	return a != nil && a.connected && !r.isOut(a.sess.id)
}

// noteFloor records the sender's floor from a floor-scoped envelope (§5.6).
func (r *room) noteFloor(i int, env protocol.Envelope) {
	if env.Depth != nil && env.Branch != nil {
		r.g.floors[i] = floor{depth: *env.Depth, branch: *env.Branch, known: true}
	}
}

// deny answers the request with a best-effort DENY (§5.7).
func (r *room) deny(q request, reason string) {
	q.s.sess.send(protocol.Envelope{Ack: protocol.Int64(q.seq)}, &protocol.Deny{Reason: reason}, true)
}

// broadcast sends a reliable server-authored event to both players.
func (r *room) broadcast(p protocol.Payload) {
	for _, s := range r.slots {
		if s != nil {
			r.sendReliable(s, p)
		}
	}
}

// persist writes the engine state document (items are written one by one).
func (r *room) persist() {
	if r.run == nil {
		return
	}
	now := r.hub.nowMs()
	r.g.lastSave = now
	blob, err := json.Marshal(r.g)
	if err != nil {
		r.hub.log.Error("encoding the run state failed", "run", r.run.RunID, "err", err)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := r.hub.opts.Store.SaveRunState(ctx, r.run.RunID, blob, now); err != nil {
		r.hub.log.Error("persisting the run state failed", "run", r.run.RunID, "err", err)
	}
}

// restoreGame loads the persisted engine state of a run rebuilt after a
// restart. Every clock comes back paused with the time it had at the last
// save; an interrupted Super Sync is reported as ended on resume.
func (r *room) restoreGame() {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st := r.hub.opts.Store
	items, err := st.Items(ctx, r.run.RunID)
	if err != nil {
		r.hub.log.Error("loading the run's items failed", "run", r.run.RunID, "err", err)
	}
	for i := range items {
		r.g.items[items[i].ItemNetID] = &items[i]
	}
	r.loadConnected(ctx)
	blob, savedAt, err := st.RunState(ctx, r.run.RunID)
	if errors.Is(err, store.ErrNotFound) {
		return
	}
	if err != nil {
		r.hub.log.Error("loading the run state failed", "run", r.run.RunID, "err", err)
		return
	}
	g := newGame()
	if err := json.Unmarshal(blob, g); err != nil {
		r.hub.log.Error("decoding the run state failed", "run", r.run.RunID, "err", err)
		return
	}
	if g.Downed == nil {
		g.Downed = map[string]*gclock{}
	}
	g.items, g.kills, g.terrain = r.g.items, r.g.kills, r.g.terrain
	if g.Mission != nil {
		g.Mission.Clock.pause(savedAt)
	}
	for _, c := range g.Downed {
		c.pause(savedAt)
	}
	if s := g.Sync; s != nil {
		g.LastSyncEnd = &protocol.SyncEnd{SyncID: s.ID, HPLost: 0, Reason: "interrupted"}
		g.Sync = nil
	}
	now := r.hub.nowMs()
	g.pausedAt, g.lastSave = now, now
	r.g = g
	r.hub.log.Info("run state restored (paused)", "run", r.run.RunID,
		"mission", g.Mission != nil, "downed", len(g.Downed), "items", len(g.items),
		"kills", len(g.kills), "terrainFloors", len(g.terrain))
	r.rearm()
}

// ---- dispatch -----------------------------------------------------------------

// play handles one co-op gameplay request (§5.6–§5.10) on the actor. Every
// reply is queued inside this closure, so replies keep the connection's
// request order. The result is an ERROR code or "".
func (r *room) play(sess *session, env protocol.Envelope, p protocol.Payload) string {
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
		q := request{i: i, s: s, seq: env.Seq}
		if env.Depth != nil && env.Branch != nil {
			q.depth, q.branch = *env.Depth, *env.Branch
		}
		switch p.(type) {
		case *protocol.SyncView, *protocol.SyncCmd, *protocol.SyncReport:
			// Their floor is the host world's, not the sender's.
		default:
			r.noteFloor(i, env)
		}
		switch p := p.(type) {
		case *protocol.MissionTrigger:
			r.missionTrigger(q, p)
		case *protocol.Downed:
			r.downed(q)
		case *protocol.UseRecall:
			r.useRecall(q, p)
		case *protocol.Claim:
			r.claim(q, p)
		case *protocol.ClaimsReq:
			r.claimsReq(q, p)
		case *protocol.Give:
			r.give(q, p)
		case *protocol.DropShare:
			r.dropShare(q, p)
		case *protocol.SyncDrink:
			r.syncDrink(q, p)
		case *protocol.SyncView:
			r.syncView(q, env, p)
		case *protocol.SyncCmd:
			r.syncCmd(q, env, p)
		case *protocol.SyncReport:
			r.syncReport(q, p)
		case *protocol.BossEngage:
			r.bossEngage(q, p)
		case *protocol.BossHit:
			r.bossHit(q, p)
		case *protocol.TwinAct:
			r.twinAct(q, p)
		case *protocol.Terrain:
			r.terrain(q, env, p)
		case *protocol.MobKilled:
			r.mobKilled(q, p)
		case *protocol.MobKillsReq:
			r.mobKillsReq(q, p)
		case *protocol.TerrainReq:
			r.terrainReq(q, p)
		}
		r.rearm()
	})
	return code
}

// isGame reports whether p is a co-op gameplay request handled by play.
func isGame(p protocol.Payload) bool {
	switch p.(type) {
	case *protocol.MissionTrigger, *protocol.Downed, *protocol.UseRecall,
		*protocol.Claim, *protocol.ClaimsReq, *protocol.Give, *protocol.DropShare,
		*protocol.SyncDrink, *protocol.SyncView, *protocol.SyncCmd, *protocol.SyncReport,
		*protocol.BossEngage, *protocol.BossHit, *protocol.TwinAct, *protocol.Terrain,
		*protocol.MobKilled, *protocol.MobKillsReq, *protocol.TerrainReq:
		return true
	}
	return false
}
