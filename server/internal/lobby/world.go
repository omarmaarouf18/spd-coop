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
	"math"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

// Super Sync (§5.9), shared boss HP, twin windows and door mirroring (§5.10).

// ---- Super Sync ---------------------------------------------------------------

func (r *room) syncDrink(q request, p *protocol.SyncDrink) {
	g := r.g
	switch {
	case g.Sync != nil:
		r.deny(q, protocol.DenyReasonSyncActive)
		return
	case g.Downed[q.id()] != nil:
		r.deny(q, protocol.DenyReasonDowned)
		return
	case r.isOut(q.id()):
		r.deny(q, protocol.DenyReasonInvalidState)
		return
	case !r.allyOnline(q.i):
		r.deny(q, protocol.DenyReasonAllyOffline)
		return
	}
	g.Sync = &syncSession{ID: newHexID(), Drinker: q.i, Host: 1 - q.i, Turns: p.Turns}
	r.hub.log.Info("super sync started", "code", r.code, "sync", g.Sync.ID, "drinker", q.id())
	r.broadcast(&protocol.SyncStart{SyncID: g.Sync.ID, Snapshot: p.Snapshot, Turns: p.Turns})
	r.persist()
}

// syncFrame applies the per-sender frame cap (k: 0 = SYNC_VIEW, 1 =
// SYNC_CMD) and the floor rule, and returns the session if the frame from
// slot i for syncID with floor q may be relayed. Dropped frames are silent.
func (r *room) syncFrame(q request, syncID string, from, k int) *syncSession {
	ss := r.g.Sync
	if ss == nil || ss.ID != syncID || q.i != from {
		return nil // unknown / ended session, or the wrong side
	}
	f0, f1 := r.g.floors[0], r.g.floors[1]
	on := func(f floor) bool { return f.known && f.depth == q.depth && f.branch == q.branch }
	if !on(f0) && !on(f1) {
		r.hub.log.Debug("sync frame for a floor neither player is on dropped", "code", r.code, "depth", q.depth)
		return nil
	}
	now := r.hub.nowMs()
	w := &r.g.frames[q.i][k]
	if now-w.start >= 1000 {
		w.start, w.n = now, 0
	}
	if w.n >= r.rules().SyncFrameCap {
		return nil
	}
	w.n++
	return ss
}

func (r *room) syncView(q request, env protocol.Envelope, p *protocol.SyncView) {
	ss := r.g.Sync
	if ss == nil || r.syncFrame(q, p.SyncID, ss.Host, 0) == nil {
		return
	}
	r.relayTo(r.slots[ss.Drinker], q, env, p)
}

func (r *room) syncCmd(q request, env protocol.Envelope, p *protocol.SyncCmd) {
	ss := r.g.Sync
	if ss == nil || r.syncFrame(q, p.SyncID, ss.Drinker, 1) == nil {
		return
	}
	r.relayTo(r.slots[ss.Host], q, env, p)
}

func (r *room) syncReport(q request, p *protocol.SyncReport) {
	ss := r.g.Sync
	if ss == nil || ss.ID != p.SyncID || q.i != ss.Host {
		return // silently: teardown already reported it
	}
	r.endSync(p.HPLost, p.Reason)
}

func (r *room) endSync(hpLost int64, reason string) {
	ss := r.g.Sync
	end := &protocol.SyncEnd{SyncID: ss.ID, HPLost: hpLost, Reason: reason}
	r.g.Sync = nil
	r.g.LastSyncEnd = end
	r.hub.log.Info("super sync ended", "code", r.code, "sync", ss.ID, "reason", reason)
	r.broadcast(end)
	r.persist()
}

// relayTo forwards a relayed type best-effort with `from` and the sender's
// envelope floor (dropped if the receiver is offline).
func (r *room) relayTo(to *slot, q request, env protocol.Envelope, p protocol.Payload) {
	if to == nil || !to.connected {
		return
	}
	to.sess.send(protocol.Envelope{From: q.id(), Depth: env.Depth, Branch: env.Branch}, p, true)
}

// ---- shared boss HP -------------------------------------------------------------

func (r *room) findBoss(q request, id int64) *boss {
	for _, b := range r.g.Bosses {
		if b.BossID == id && b.Depth == q.depth && b.Branch == q.branch {
			return b
		}
	}
	return nil
}

// bothOnFloor reports whether the ally is online on the sender's floor (the
// sender's floor is the request's).
func (r *room) bothOnFloor(q request) bool {
	return r.allyOnline(q.i) && r.allyOnFloor(q.i, q.depth, q.branch)
}

func (r *room) bossEngage(q request, p *protocol.BossEngage) {
	if b := r.findBoss(q, p.BossID); b != nil {
		if !b.Dead { // a re-engage (e.g. after a reload) gets the pool again
			r.sendReliable(q.s, &protocol.BossHP{BossID: b.BossID, HP: b.HP, HT: b.HT})
		}
		return
	}
	if !r.bothOnFloor(q) {
		return // no pool: the fight stays per-world
	}
	pool := int64(math.Round(float64(p.HT) * r.rules().BossHPScale))
	pool = min(max(pool, 1), protocol.MaxSafeInt)
	b := &boss{Depth: q.depth, Branch: q.branch, BossID: p.BossID, HP: pool, HT: pool}
	r.g.Bosses = append(r.g.Bosses, b)
	r.hub.log.Info("shared boss pool", "code", r.code, "boss", b.BossID, "depth", b.Depth, "pool", pool)
	r.broadcast(&protocol.BossHP{BossID: b.BossID, HP: b.HP, HT: b.HT})
	r.persist()
}

func (r *room) bossHit(q request, p *protocol.BossHit) {
	b := r.findBoss(q, p.BossID)
	if b != nil && b.Dead {
		return // silently ignored after BOSS_DEAD
	}
	if b == nil || !r.bothOnFloor(q) {
		r.deny(q, protocol.DenyReasonNoPool)
		return
	}
	b.HP = max(0, b.HP-p.Dmg)
	r.broadcast(&protocol.BossHP{BossID: b.BossID, HP: b.HP, HT: b.HT})
	if b.HP == 0 {
		b.Dead = true
		r.hub.log.Info("shared boss dead", "code", r.code, "boss", b.BossID)
		r.broadcast(&protocol.BossDead{BossID: b.BossID})
	}
	r.persist()
}

// ---- twin windows ---------------------------------------------------------------

func (r *room) twinAct(q request, p *protocol.TwinAct) {
	g := r.g
	group := p.ObjectID
	if p.GroupID != nil {
		group = *p.GroupID
	}
	key := twinKey{Depth: q.depth, Branch: q.branch, Kind: p.Kind, Group: group}
	for _, k := range g.TwinDone {
		if k == key {
			return // completed: later presses are no-ops
		}
	}
	pr := g.twins[key]
	if !p.Active { // released: the sender's open press is withdrawn
		if pr != nil && pr.slot == q.i {
			delete(g.twins, key)
		}
		return
	}
	now := r.hub.nowMs()
	if pr != nil && pr.slot != q.i && now-pr.at <= r.rules().TwinWindow.Milliseconds() {
		same := pr.objectID == p.ObjectID
		if (p.Kind == "door" && same) || (p.Kind == "switch" && !same) {
			delete(g.twins, key)
			g.TwinDone = append(g.TwinDone, key)
			r.hub.log.Info("twin done", "code", r.code, "kind", p.Kind, "group", group, "depth", q.depth)
			r.broadcast(&protocol.TwinDone{GroupID: group})
			r.persist()
			return
		}
	}
	g.twins[key] = &twinPress{slot: q.i, objectID: p.ObjectID, at: now}
}

// ---- door mirroring -------------------------------------------------------------

func (r *room) terrain(q request, env protocol.Envelope, p *protocol.Terrain) {
	if !protocol.IsDoorTile(p.Tile) {
		r.hub.log.Debug("non-door TERRAIN dropped", "code", r.code, "player", q.id(), "tile", p.Tile)
		return
	}
	if r.allyOnFloor(q.i, q.depth, q.branch) {
		r.relayTo(r.ally(q.i), q, env, p)
	}
}
