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
	"strconv"
	"strings"
	"time"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
	"github.com/omarmaarouf18/spd-coop/server/internal/store"
)

// Shared items (§5.7, D-021): every itemNetId has exactly one owner per run,
// kept in r.g.items and persisted row by row (store run_items).
//   - claimed: picked up by Owner (floor loot, or a shared drop);
//   - given:   handed over once by Giver to Owner (GIVE/TRANSFER);
//   - dropped: lying on Depth/Branch, claimable once by either player.

// genFloor returns the floor of a level-gen itemNetId "<depth>:<branch>:<n>".
func genFloor(id string) (depth, branch int, ok bool) {
	parts := strings.Split(id, ":")
	if len(parts) != 3 {
		return 0, 0, false
	}
	d, err1 := strconv.Atoi(parts[0])
	b, err2 := strconv.Atoi(parts[1])
	return d, b, err1 == nil && err2 == nil
}

func (r *room) putItem(it *store.Item) {
	r.g.items[it.ItemNetID] = it
	if r.run == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := r.hub.opts.Store.PutItem(ctx, r.run.RunID, *it); err != nil {
		r.hub.log.Error("persisting an item failed", "run", r.run.RunID, "item", it.ItemNetID, "err", err)
	}
}

func (r *room) claim(q request, p *protocol.Claim) {
	me := q.id()
	it := r.g.items[p.ItemNetID]
	switch {
	case it == nil:
		// Only level-gen loot of the claimer's floor is claimable unseen.
		if d, b, ok := genFloor(p.ItemNetID); !ok || d != q.depth || b != q.branch {
			r.deny(q, protocol.DenyReasonInvalidState)
			return
		}
		it = &store.Item{ItemNetID: p.ItemNetID, Owner: me, State: store.ItemClaimed, Depth: q.depth, Branch: q.branch}
	case it.State == store.ItemDropped:
		if it.Depth != q.depth || it.Branch != q.branch {
			r.deny(q, protocol.DenyReasonInvalidState)
			return
		}
		it.Owner, it.State = me, store.ItemClaimed
	case it.Owner == me:
		return // re-claiming your own: silently ignored
	default:
		r.deny(q, protocol.DenyReasonTaken)
		return
	}
	r.putItem(it)
	r.broadcast(&protocol.Claimed{ItemNetID: p.ItemNetID, By: me})
}

// claimsReq answers with the itemNetIds gone from a floor: level-gen loot
// of that floor that anyone holds, and runtime items held after being
// registered there. Unclaimed drops are still on the floor and not listed.
func (r *room) claimsReq(q request, p *protocol.ClaimsReq) {
	ids := []string{}
	for id, it := range r.g.items {
		if it.State == store.ItemDropped {
			continue
		}
		d, b, gen := genFloor(id)
		if !gen {
			d, b = it.Depth, it.Branch
		}
		if d == p.Depth && b == p.Branch {
			ids = append(ids, id)
		}
	}
	slices.Sort(ids)
	if len(ids) > 256 {
		r.hub.log.Warn("CLAIMS truncated to 256 ids", "code", r.code, "depth", p.Depth, "branch", p.Branch, "total", len(ids))
		ids = ids[:256]
	}
	q.s.sess.send(protocol.Envelope{Ack: protocol.Int64(q.seq)},
		&protocol.Claims{Depth: p.Depth, Branch: p.Branch, ItemNetIDs: ids}, true)
}

func (r *room) give(q request, p *protocol.Give) {
	g := r.g
	me := q.id()
	a := r.ally(q.i)
	it := g.items[p.ItemNetID]
	switch {
	case g.Downed[me] != nil:
		r.deny(q, protocol.DenyReasonDowned)
		return
	case it != nil && it.State == store.ItemGiven:
		r.deny(q, protocol.DenyReasonAlreadyTransferred) // exactly once
		return
	case it != nil && (it.State == store.ItemDropped || it.Owner != me):
		r.deny(q, protocol.DenyReasonNotOwner)
		return
	case a == nil || r.isOut(a.sess.id):
		r.deny(q, protocol.DenyReasonAllyOffline)
		return
	}
	key := p.Kind == "cell_key"
	m := g.Mission
	if key {
		switch {
		case m == nil || m.Start.Rescuer != me || m.Start.Depth != q.depth || m.Start.Branch != q.branch:
			r.deny(q, protocol.DenyReasonInvalidState)
			return
		case m.Clock.Paused:
			r.deny(q, protocol.DenyReasonAllyOffline)
			return
		case g.Downed[m.Start.Prisoner] != nil: // fallen: only a revive helps now
			r.deny(q, protocol.DenyReasonInvalidState)
			return
		}
	}
	if it == nil {
		it = &store.Item{ItemNetID: p.ItemNetID, Depth: q.depth, Branch: q.branch}
	}
	it.Owner, it.State, it.Giver = a.sess.id, store.ItemGiven, me
	r.putItem(it)
	r.hub.log.Info("item transferred", "code", r.code, "item", p.ItemNetID, "kind", p.Kind, "from", me, "to", a.sess.id)
	r.broadcast(&protocol.Transfer{ItemNetID: p.ItemNetID, From: me, To: a.sess.id, Kind: p.Kind, Item: p.Item})
	if key {
		m.Key = true
		r.missionUpdate()
		r.endMission("success")
	}
}

func (r *room) dropShare(q request, p *protocol.DropShare) {
	me := q.id()
	it := r.g.items[p.ItemNetID]
	if it != nil && (it.State == store.ItemDropped || it.Owner != me) {
		r.deny(q, protocol.DenyReasonNotOwner)
		return
	}
	if it == nil {
		it = &store.Item{ItemNetID: p.ItemNetID}
	}
	it.Owner, it.State, it.Giver, it.Depth, it.Branch = "", store.ItemDropped, me, q.depth, q.branch
	r.putItem(it)
	if a := r.ally(q.i); a != nil && r.allyOnFloor(q.i, q.depth, q.branch) {
		ds := *p
		r.sendReliableEnv(a, protocol.Envelope{From: me, Depth: protocol.Int(q.depth), Branch: protocol.Int(q.branch)}, &ds)
	}
}
