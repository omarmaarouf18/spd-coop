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
	"slices"
	"time"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

// Missions (The Cell, §5.6), Downed windows (§5.8) and the D-005 pause.
//
// Clocks: the mission runs to its hard cap (deadline). The waves are client
// pacing and become lethal after the cap, which downs the prisoner; as a
// server backstop, a mission still open DownedWindow after its deadline
// with the prisoner standing fails (PROTOCOL.md §5.6: "the server judges
// the cap and failures"). While a player is away every clock is frozen; a
// mission paused for PauseGrace is aborted.

func (r *room) missionTrigger(q request, p *protocol.MissionTrigger) {
	g := r.g
	a := r.ally(q.i)
	switch {
	case r.isOut(q.id()):
		r.deny(q, protocol.DenyReasonInvalidState)
		return
	case g.Mission != nil:
		r.deny(q, protocol.DenyReasonMissionActive)
		return
	case !r.allyOnline(q.i):
		r.deny(q, protocol.DenyReasonAllyOffline)
		return
	case !r.allyOnFloor(q.i, q.depth, q.branch):
		r.deny(q, protocol.DenyReasonAllyOtherFloor)
		return
	case g.Downed[q.id()] != nil:
		r.deny(q, protocol.DenyReasonDowned)
		return
	case g.Downed[a.sess.id] != nil:
		r.deny(q, protocol.DenyReasonInvalidState)
		return
	}
	now := r.hub.nowMs()
	st := protocol.MissionStart{
		MissionID: newHexID(), Kind: p.Kind, Prisoner: q.id(), Rescuer: a.sess.id,
		Depth: q.depth, Branch: q.branch, TrapCell: p.TrapCell, RoomID: p.RoomID,
		KeySeed: newRoleSeed(), StartsAt: now, Deadline: now + r.rules().cellCap(q.depth).Milliseconds(),
		WaveIntervalMs: r.rules().CellWaveInterval.Milliseconds(),
	}
	g.Mission = &mission{Start: st, Clock: gclock{Deadline: st.Deadline}}
	r.hub.log.Info("mission started", "code", r.code, "mission", st.MissionID, "kind", st.Kind,
		"prisoner", st.Prisoner, "depth", st.Depth, "branch", st.Branch, "deadline", st.Deadline)
	start := st
	r.broadcast(&start)
	r.persist()
}

// missionUpdate broadcasts the mission's clock (and the key flag once set).
func (r *room) missionUpdate() {
	m := r.g.Mission
	if m == nil {
		return
	}
	r.broadcast(r.missionUpdatePayload())
}

func (r *room) missionUpdatePayload() *protocol.MissionUpdate {
	m := r.g.Mission
	u := &protocol.MissionUpdate{MissionID: m.Start.MissionID, Paused: m.Clock.Paused,
		RemainingMs: max(0, m.Clock.left(r.hub.nowMs()))}
	if m.Key {
		u.Tasks = &protocol.MissionTasks{Key: boolPtr(true)}
	}
	return u
}

// endMission ends the active mission with result (success → reward,
// failed → penalty) for both players.
func (r *room) endMission(result string) {
	m := r.g.Mission
	if m == nil {
		return
	}
	end := &protocol.MissionEnd{MissionID: m.Start.MissionID, Result: result}
	if result == "aborted" {
		// An abort carries no penalty: a Downed prisoner is revived in place
		// (owner review of SRV-02), never left to a real death.
		if p := m.Start.Prisoner; r.g.Downed[p] != nil {
			delete(r.g.Downed, p)
			r.hub.log.Info("aborted mission revives the prisoner", "code", r.code, "player", p)
			r.broadcast(&protocol.Revive{Target: p, Mode: "inPlace", HPPct: reviveHPPct})
		}
	}
	switch result {
	case "success":
		end.Reward = &protocol.MissionReward{RewardSeed: newRoleSeed()}
	case "failed":
		end.Penalty = &protocol.MissionPenalty{RegionStartDepth: regionStart(m.Start.Depth), GoldPct: cellGoldPct}
	}
	r.g.Mission = nil
	r.g.LastMissionEnd = end
	r.hub.log.Info("mission ended", "code", r.code, "mission", end.MissionID, "result", result)
	r.broadcast(end)
	r.persist()
}

func (r *room) downed(q request) {
	g := r.g
	id := q.id()
	if r.isOut(id) || g.Downed[id] != nil {
		return // a repeat DOWNED never resets the window
	}
	now := r.hub.nowMs()
	c := &gclock{Deadline: now + r.rules().DownedWindow.Milliseconds()}
	if r.paused() {
		c.pause(now)
	}
	g.Downed[id] = c
	r.hub.log.Info("player downed", "code", r.code, "player", id, "deadline", c.deadlineAt(now), "paused", c.Paused)
	r.broadcast(&protocol.AllyDowned{Player: id, Deadline: c.deadlineAt(now)})
	r.persist()
}

func (r *room) useRecall(q request, p *protocol.UseRecall) {
	g := r.g
	a := r.ally(q.i)
	healing := p.Via == "healing"
	switch {
	case g.Downed[q.id()] != nil:
		r.deny(q, protocol.DenyReasonDowned)
		return
	case a == nil || p.Target != a.sess.id: // never on yourself
		r.deny(q, protocol.DenyReasonInvalidState)
		return
	case g.Downed[p.Target] == nil:
		r.deny(q, protocol.DenyReasonNotDowned)
		return
	case healing && !a.connected:
		r.deny(q, protocol.DenyReasonAllyOffline)
		return
	case healing && !r.allyOnFloor(q.i, q.depth, q.branch):
		r.deny(q, protocol.DenyReasonAllyOtherFloor)
		return
	}
	// Inside a mission a revive is always in place (game-design §5.2).
	mode := "regionStart"
	if healing || g.Mission != nil {
		mode = "inPlace"
	}
	delete(g.Downed, p.Target)
	r.hub.log.Info("player revived", "code", r.code, "player", p.Target, "via", p.Via, "mode", mode)
	r.broadcast(&protocol.Revive{Target: p.Target, Mode: mode, HPPct: reviveHPPct})
	if m := g.Mission; m != nil && m.Start.Prisoner == p.Target {
		r.endMission("revived")
	}
	r.persist()
}

// expire ends a Downed window. The prisoner of a Cell mission is not killed
// (D-022): the mission fails for both instead, with no DOWNED_EXPIRED.
func (r *room) expire(id string) {
	g := r.g
	delete(g.Downed, id)
	if m := g.Mission; m != nil && m.Start.Prisoner == id {
		r.hub.log.Info("prisoner's Downed window expired", "code", r.code, "player", id)
		r.endMission("failed")
		return
	}
	// COOP_DEATH_MODE=player_out (the only v1 mode): out for the run.
	g.Out = append(g.Out, id)
	r.hub.log.Info("Downed window expired: player out", "code", r.code, "player", id)
	r.broadcast(&protocol.DownedExpired{Player: id})
	r.persist()
}

// pauseClocks freezes every clock when a player of a running room goes away
// (D-005) and tears a Super Sync down (§5.9).
func (r *room) pauseClocks() {
	g := r.g
	now := r.hub.nowMs()
	if g.pausedAt == 0 {
		g.pausedAt = now
	}
	changed := false
	if m := g.Mission; m != nil && !m.Clock.Paused {
		m.Clock.pause(now)
		r.missionUpdate()
		changed = true
	}
	for _, c := range g.Downed {
		if !c.Paused {
			c.pause(now)
			changed = true
		}
	}
	if g.Sync != nil {
		r.endSync(0, "interrupted")
	}
	if changed {
		r.persist()
	}
	r.rearm()
}

// resumeClocks restarts the frozen clocks once both players are back: the
// mission continues with its remaining time, and every Downed window gets
// its new deadline re-sent (ALLY_DOWNED).
func (r *room) resumeClocks() {
	if r.paused() {
		return
	}
	g := r.g
	g.pausedAt = 0
	now := r.hub.nowMs()
	changed := false
	if m := g.Mission; m != nil && m.Clock.Paused {
		m.Clock.resume(now)
		r.missionUpdate()
		changed = true
	}
	for _, id := range sortedKeys(g.Downed) {
		if c := g.Downed[id]; c.Paused {
			c.resume(now)
			r.broadcast(&protocol.AllyDowned{Player: id, Deadline: c.Deadline})
			changed = true
		}
	}
	if changed {
		r.persist()
	}
	r.rearm()
}

// resyncTo re-sends the engine state to a player whose resume had no replay
// (another epoch or an evicted log): the snapshot in WELCOME carries no
// mission state, so it follows as reliable events. Clients drop the ones
// they already applied (MEC-00).
func (r *room) resyncTo(s *slot) {
	g := r.g
	now := r.hub.nowMs()
	if e := g.LastMissionEnd; e != nil {
		end := *e
		r.sendReliable(s, &end)
	}
	if m := g.Mission; m != nil {
		start := m.Start
		r.sendReliable(s, &start)
		r.sendReliable(s, r.missionUpdatePayload())
	}
	for _, id := range sortedKeys(g.Downed) {
		r.sendReliable(s, &protocol.AllyDowned{Player: id, Deadline: g.Downed[id].deadlineAt(now)})
	}
	for _, id := range g.Out {
		r.sendReliable(s, &protocol.DownedExpired{Player: id})
	}
	if e := g.LastSyncEnd; e != nil {
		end := *e
		r.sendReliable(s, &end)
	}
	for _, b := range g.Bosses {
		if b.Dead {
			r.sendReliable(s, &protocol.BossDead{BossID: b.BossID})
		} else {
			r.sendReliable(s, &protocol.BossHP{BossID: b.BossID, HP: b.HP, HT: b.HT})
		}
	}
}

// clocksRunning reports whether any clock is ticking (not paused).
func (r *room) clocksRunning() bool {
	if m := r.g.Mission; m != nil && !m.Clock.Paused {
		return true
	}
	for _, c := range r.g.Downed {
		if !c.Paused {
			return true
		}
	}
	return false
}

// tick runs everything that is due, then re-arms the wake-up timer.
func (r *room) tick() {
	if r.stopped {
		return
	}
	g := r.g
	now := r.hub.nowMs()
	rules := r.rules()
	if m := g.Mission; m != nil {
		switch {
		case m.Clock.Paused:
			if g.pausedAt > 0 && now-g.pausedAt >= rules.PauseGrace.Milliseconds() {
				r.hub.log.Info("mission paused too long", "code", r.code, "mission", m.Start.MissionID)
				r.endMission("aborted")
			}
		case m.Clock.left(now) <= -rules.DownedWindow.Milliseconds() && g.Downed[m.Start.Prisoner] == nil:
			r.hub.log.Info("mission overtime ran out", "code", r.code, "mission", m.Start.MissionID)
			r.endMission("failed")
		}
	}
	for _, id := range sortedKeys(g.Downed) {
		if c := g.Downed[id]; c != nil && !c.Paused && c.left(now) <= 0 {
			r.expire(id)
		}
	}
	if r.clocksRunning() && now-g.lastSave >= rules.SnapshotEvery.Milliseconds() {
		r.persist()
	}
	r.rearm()
}

// rearm sets the single wake-up timer to the next due moment. A stale fire
// is harmless: tick only acts on what is due.
func (r *room) rearm() {
	g := r.g
	if g.wake != nil {
		g.wake.Stop()
		g.wake = nil
	}
	if r.stopped {
		return
	}
	rules := r.rules()
	next := int64(-1)
	at := func(t int64) {
		if next < 0 || t < next {
			next = t
		}
	}
	if m := g.Mission; m != nil {
		if m.Clock.Paused {
			if g.pausedAt > 0 {
				at(g.pausedAt + rules.PauseGrace.Milliseconds())
			}
		} else {
			at(m.Clock.Deadline + rules.DownedWindow.Milliseconds())
		}
	}
	for _, c := range g.Downed {
		if !c.Paused {
			at(c.Deadline)
		}
	}
	if r.clocksRunning() {
		at(g.lastSave + rules.SnapshotEvery.Milliseconds())
	}
	if next < 0 {
		return
	}
	d := time.Duration(max(0, next-r.hub.nowMs())) * time.Millisecond
	g.wake = r.hub.opts.Clock.AfterFunc(d, func() { r.do(r.tick) })
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	return keys
}

func boolPtr(v bool) *bool { return &v }
