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
	"slices"
	"time"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
	"github.com/omarmaarouf18/spd-coop/server/internal/store"
)

// Connected worlds (SRV-03, PROTOCOL.md §5.11, D-023): the run's shared mob
// kill registry and the permanent terrain changes per floor. Both live in
// the room actor like the items, and every change is written to the store
// at once (run_kills, run_terrain), so they survive a server restart.

// floorKey names one floor of the run.
type floorKey struct{ depth, branch int }

// maxStoredTerrain is how many cells one floor's terrain record holds
// (TERRAINS.changes is capped at the same number, §5.11.2). A variable only
// so a test can lower it (the rate limit keeps a test from sending 513).
var maxStoredTerrain = protocol.MaxFloorList

// loadConnected reads the kill registry and the terrain record of a run
// rebuilt after a restart.
func (r *room) loadConnected(ctx context.Context) {
	st := r.hub.opts.Store
	kills, err := st.Kills(ctx, r.run.RunID)
	if err != nil {
		r.hub.log.Error("loading the run's kills failed", "run", r.run.RunID, "err", err)
	}
	for i := range kills {
		r.g.kills[kills[i].MobNetID] = &kills[i]
	}
	changes, err := st.Terrain(ctx, r.run.RunID)
	if err != nil {
		r.hub.log.Error("loading the run's terrain failed", "run", r.run.RunID, "err", err)
	}
	for _, c := range changes {
		r.floorTerrain(floorKey{c.Depth, c.Branch})[c.Cell] = c.Tile
	}
}

func (r *room) floorTerrain(k floorKey) map[int]int {
	m := r.g.terrain[k]
	if m == nil {
		m = map[int]int{}
		r.g.terrain[k] = m
	}
	return m
}

// ---- shared mob kills (§5.11.1) -------------------------------------------------

func (r *room) mobKilled(q request, p *protocol.MobKilled) {
	d, b, ok := genFloor(p.MobNetID)
	if !ok || d != q.depth || b != q.branch {
		r.deny(q, protocol.DenyReasonInvalidState)
		return
	}
	if r.g.kills[p.MobNetID] != nil {
		return // first kill wins; a later report (either world) is silently ignored
	}
	k := &store.Kill{MobNetID: p.MobNetID, Killer: q.id(), Depth: d, Branch: b}
	r.g.kills[p.MobNetID] = k
	if r.run != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := r.hub.opts.Store.PutKill(ctx, r.run.RunID, *k); err != nil {
			r.hub.log.Error("persisting a kill failed", "run", r.run.RunID, "mob", p.MobNetID, "err", err)
		}
	}
	r.broadcast(&protocol.MobDead{MobNetID: p.MobNetID, By: k.Killer})
}

// mobKillsReq answers with every killed mobNetId of a floor, sorted.
func (r *room) mobKillsReq(q request, p *protocol.MobKillsReq) {
	ids := []string{}
	for id, k := range r.g.kills {
		if k.Depth == p.Depth && k.Branch == p.Branch {
			ids = append(ids, id)
		}
	}
	slices.Sort(ids)
	if len(ids) > protocol.MaxFloorList {
		r.hub.log.Warn("MOB_KILLS truncated", "code", r.code, "depth", p.Depth, "branch", p.Branch, "total", len(ids))
		ids = ids[:protocol.MaxFloorList]
	}
	q.s.sess.send(protocol.Envelope{Ack: protocol.Int64(q.seq)},
		&protocol.MobKills{Depth: p.Depth, Branch: p.Branch, MobNetIDs: ids}, true)
}

// ---- terrain mirroring and the terrain record (§5.10, §5.11.2) -------------------

func (r *room) terrain(q request, env protocol.Envelope, p *protocol.Terrain) {
	if !protocol.IsTerrainTile(p.Tile) {
		r.hub.log.Debug("non-whitelisted TERRAIN dropped", "code", r.code, "player", q.id(), "tile", p.Tile)
		return
	}
	if !p.Persistent() {
		// Door open/close: relay-only, best-effort, to the ally on the floor.
		if r.allyOnFloor(q.i, q.depth, q.branch) {
			r.relayTo(r.ally(q.i), q, env, p)
		}
		return
	}
	if protocol.IsPersistTile(p.Tile) {
		r.storeTerrain(q, p)
	}
	// Reliable: an ally on the floor who is offline right now gets it on resume.
	if a := r.ally(q.i); a != nil && r.allyOnFloor(q.i, q.depth, q.branch) {
		t := *p
		r.sendReliableEnv(a, protocol.Envelope{From: q.id(), Depth: protocol.Int(q.depth), Branch: protocol.Int(q.branch)}, &t)
	}
}

func (r *room) storeTerrain(q request, p *protocol.Terrain) {
	m := r.floorTerrain(floorKey{q.depth, q.branch})
	if _, known := m[p.Cell]; !known && len(m) >= maxStoredTerrain {
		r.hub.log.Warn("terrain record full, change relayed but not stored", "code", r.code,
			"depth", q.depth, "branch", q.branch, "cell", p.Cell)
		return
	}
	m[p.Cell] = p.Tile // the last persisted change of a cell wins
	if r.run == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	tc := store.TerrainChange{Depth: q.depth, Branch: q.branch, Cell: p.Cell, Tile: p.Tile}
	if err := r.hub.opts.Store.PutTerrain(ctx, r.run.RunID, tc); err != nil {
		r.hub.log.Error("persisting a terrain change failed", "run", r.run.RunID, "cell", p.Cell, "err", err)
	}
}

// terrainReq answers with a floor's stored changes, sorted by cell.
func (r *room) terrainReq(q request, p *protocol.TerrainReq) {
	m := r.g.terrain[floorKey{p.Depth, p.Branch}]
	changes := make([]protocol.TerrainChange, 0, len(m))
	for cell, tile := range m {
		changes = append(changes, protocol.TerrainChange{Cell: cell, Tile: tile})
	}
	slices.SortFunc(changes, func(a, b protocol.TerrainChange) int { return a.Cell - b.Cell })
	q.s.sess.send(protocol.Envelope{Ack: protocol.Int64(q.seq)},
		&protocol.Terrains{Depth: p.Depth, Branch: p.Branch, Changes: changes}, true)
}
