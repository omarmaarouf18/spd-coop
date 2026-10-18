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
	"reflect"
	"sync"
	"testing"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

func persist() *bool { v := true; return &v }

func mobKills(t *testing.T, c *client, depth int) []string {
	t.Helper()
	c.send(protocol.Envelope{}, &protocol.MobKillsReq{Depth: depth, Branch: 0})
	m := c.next(protocol.TMobKills)
	k := m.Payload.(*protocol.MobKills)
	if m.Ack == nil || *m.Ack != c.seq || k.Depth != depth || k.Branch != 0 {
		t.Fatalf("MOB_KILLS = %+v (ack %v, want %d)", k, m.Ack, c.seq)
	}
	return k.MobNetIDs
}

func terrains(t *testing.T, c *client, depth int) []protocol.TerrainChange {
	t.Helper()
	c.send(protocol.Envelope{}, &protocol.TerrainReq{Depth: depth, Branch: 0})
	m := c.next(protocol.TTerrains)
	tr := m.Payload.(*protocol.Terrains)
	if m.Ack == nil || *m.Ack != c.seq || tr.Depth != depth || tr.Branch != 0 {
		t.Fatalf("TERRAINS = %+v (ack %v, want %d)", tr, m.Ack, c.seq)
	}
	return tr.Changes
}

// Both worlds report the same mob at once: one MOB_DEAD per player, one
// killer, and the losing report is silently ignored.
func TestMobKillRaceFirstWins(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 2)
	moveTo(b, a, 2)

	a.send(fl(3), &protocol.MobKilled{MobNetID: "2:0:1"})
	deny(t, a, protocol.DenyReasonInvalidState) // not the sender's floor
	b.quiet()

	for i := int64(0); i < 10; i++ {
		id := "2:0:" + itoa(i)
		var wg sync.WaitGroup
		for _, c := range []*client{a, b} {
			wg.Add(1)
			go func() { defer wg.Done(); c.send(fl(2), &protocol.MobKilled{MobNetID: id}) }()
		}
		wg.Wait()
		var killer string
		for _, c := range []*client{a, b} {
			ms := c.quiet(protocol.TMobDead) // no DENY, nothing else
			if len(ms) != 1 {
				t.Fatalf("%s: %d MOB_DEAD, want exactly 1", id, len(ms))
			}
			d := ms[0].Payload.(*protocol.MobDead)
			if d.MobNetID != id || (killer != "" && d.By != killer) {
				t.Fatalf("MOB_DEAD = %+v (killer %q)", d, killer)
			}
			killer = d.By
		}
	}
	// A later report (either player, any time) changes nothing.
	b.send(fl(2), &protocol.MobKilled{MobNetID: "2:0:3"})
	b.quiet()
	a.quiet()
	if got := mobKills(t, a, 2); len(got) != 10 || got[0] != "2:0:0" || got[9] != "2:0:9" {
		t.Fatalf("MOB_KILLS = %v", got)
	}
}

// The ally is offline (or elsewhere) when the mob dies: it gets the reliable
// MOB_DEAD on resume, and the floor's kills with MOB_KILLS_REQ on arrival.
func TestMobKillLateArrival(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 4)
	a.send(fl(4), &protocol.MobKilled{MobNetID: "4:0:12"})
	a.next(protocol.TMobDead)
	if d := b.next(protocol.TMobDead).Payload.(*protocol.MobDead); d.By != a.id || d.MobNetID != "4:0:12" {
		t.Fatalf("MOB_DEAD at B (other floor) = %+v", d)
	}
	_ = b.ws.CloseNow()
	a.expect(protocol.TAllyStatus)
	a.send(fl(4), &protocol.MobKilled{MobNetID: "4:0:0"})
	a.next(protocol.TMobDead)
	b2, w := b.resume(ts)
	if !w.Complete {
		t.Fatalf("WELCOME = %+v", w)
	}
	var replayed []string
	for _, m := range b2.collect() {
		if d, ok := m.Payload.(*protocol.MobDead); ok {
			replayed = append(replayed, d.MobNetID)
		}
	}
	if !reflect.DeepEqual(replayed, []string{"4:0:0"}) {
		t.Fatalf("replayed MOB_DEAD = %v", replayed)
	}
	if got := mobKills(t, b2, 4); !reflect.DeepEqual(got, []string{"4:0:0", "4:0:12"}) {
		t.Fatalf("MOB_KILLS = %v", got)
	}
	if got := mobKills(t, b2, 5); got == nil || len(got) != 0 {
		t.Fatalf("MOB_KILLS of an untouched floor = %#v, want []", got)
	}
}

// Permanent changes are stored per floor (last write wins) and handed to a
// player arriving later; door open/close stays relay-only.
func TestTerrainPersistAndLateArrival(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 2)
	moveTo(b, a, 3) // B elsewhere: nothing is relayed to it

	a.send(fl(2), &protocol.Terrain{Cell: 208, Tile: protocol.TileDoor, Persist: persist()})         // iron key
	a.send(fl(2), &protocol.Terrain{Cell: 431, Tile: protocol.TileInactiveTrap, Persist: persist()}) // trap consumed
	a.send(fl(2), &protocol.Terrain{Cell: 977, Tile: protocol.TileTrap, Persist: persist()})         // trap revealed
	a.send(fl(2), &protocol.Terrain{Cell: 300, Tile: protocol.TileOpenDoor, Persist: persist()})     // relayed, never stored
	a.send(fl(2), &protocol.Terrain{Cell: 208, Tile: protocol.TileOpenDoor})                         // walked in: relay-only
	a.send(fl(2), &protocol.Terrain{Cell: 500, Tile: 4, Persist: persist()})                         // a wall: dropped
	a.send(fl(2), &protocol.Terrain{Cell: 600, Tile: protocol.TileUnlockedExit, Persist: persist()}) // worn key
	a.send(fl(2), &protocol.Terrain{Cell: 610, Tile: protocol.TileEmpty, Persist: persist()})        // crystal door
	a.quiet()
	b.quiet(protocol.TGhost)

	moveTo(b, a, 2)
	want := []protocol.TerrainChange{{Cell: 208, Tile: 5}, {Cell: 431, Tile: 19}, {Cell: 600, Tile: 22}, {Cell: 610, Tile: 1}, {Cell: 977, Tile: 18}}
	if got := terrains(t, b, 2); !reflect.DeepEqual(got, want) {
		t.Fatalf("TERRAINS = %+v, want %+v", got, want)
	}
	if got := terrains(t, b, 3); got == nil || len(got) != 0 {
		t.Fatalf("TERRAINS of an untouched floor = %#v, want []", got)
	}

	// Now on the same floor: a persisted change is relayed (reliably, with
	// persist kept) and overwrites the cell; a plain close is relay-only.
	a.send(fl(2), &protocol.Terrain{Cell: 208, Tile: protocol.TileHeroLkdDr, Persist: persist()})
	m := b.next(protocol.TTerrain)
	if tr := m.Payload.(*protocol.Terrain); m.From != a.id || *m.Depth != 2 || tr.Cell != 208 || tr.Tile != 38 || !tr.Persistent() {
		t.Fatalf("relayed TERRAIN = %+v %+v", m.Envelope, tr)
	}
	a.send(fl(2), &protocol.Terrain{Cell: 431, Tile: protocol.TileDoor})
	if tr := b.next(protocol.TTerrain).Payload.(*protocol.Terrain); tr.Persistent() || tr.Tile != 5 {
		t.Fatalf("relayed close = %+v", tr)
	}
	want[0].Tile = 38
	if got := terrains(t, b, 2); !reflect.DeepEqual(got, want) {
		t.Fatalf("TERRAINS after the lock = %+v, want %+v", got, want)
	}

	// The ally on the floor drops: a persisted change reaches it on resume.
	_ = b.ws.CloseNow()
	a.expect(protocol.TAllyStatus)
	a.send(fl(2), &protocol.Terrain{Cell: 208, Tile: protocol.TileDoor, Persist: persist()}) // unlocked again
	a.send(fl(2), &protocol.Terrain{Cell: 209, Tile: protocol.TileOpenDoor})                 // best-effort: lost
	b2, _ := b.resume(ts)
	var got []protocol.Terrain
	for _, m := range b2.collect() {
		if tr, ok := m.Payload.(*protocol.Terrain); ok {
			got = append(got, *tr)
		}
	}
	if len(got) != 1 || got[0].Cell != 208 || got[0].Tile != 5 || !got[0].Persistent() {
		t.Fatalf("replayed TERRAIN = %+v", got)
	}
}

func TestTerrainRecordCap(t *testing.T) {
	saved := maxStoredTerrain
	maxStoredTerrain = 3
	t.Cleanup(func() { maxStoredTerrain = saved })
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 6)
	moveTo(b, a, 6)
	for cell := 0; cell <= 3; cell++ { // one more than fits
		a.send(fl(6), &protocol.Terrain{Cell: cell, Tile: protocol.TileInactiveTrap, Persist: persist()})
	}
	a.send(fl(6), &protocol.Terrain{Cell: 0, Tile: protocol.TileTrap, Persist: persist()}) // a known cell still updates
	a.quiet()                                                                              // A's frames are handled before B looks
	if ms := b.quiet(protocol.TTerrain); len(ms) != 5 {
		t.Fatalf("B got %d relays, want 5 (a full record still relays)", len(ms))
	}
	want := []protocol.TerrainChange{{Cell: 0, Tile: 18}, {Cell: 1, Tile: 19}, {Cell: 2, Tile: 19}}
	if got := terrains(t, a, 6); !reflect.DeepEqual(got, want) {
		t.Fatalf("TERRAINS = %+v, want %+v", got, want)
	}
}

// The kill registry and the terrain record survive a server restart.
func TestConnectedWorldsSurviveRestart(t *testing.T) {
	fc := newFakeClock()
	ts := newGameServer(t, "", fc, Rules{})
	a, b, _, _ := pair(t, ts)
	moveTo(a, b, 5)
	moveTo(b, a, 5)
	a.send(fl(5), &protocol.MobKilled{MobNetID: "5:0:3"})
	a.next(protocol.TMobDead)
	b.next(protocol.TMobDead)
	b.send(fl(5), &protocol.Terrain{Cell: 1022, Tile: protocol.TileUnlockedExit, Persist: persist()})
	a.next(protocol.TTerrain)
	ts.close()
	_, _ = a.ws.CloseNow(), b.ws.CloseNow()
	waitOffline(t, ts, w0(a, b))

	ts2 := newGameServer(t, ts.dbPath, fc, Rules{})
	a2, w := a.resume(ts2)
	if w.Complete || w.Run == nil {
		t.Fatalf("WELCOME after restart = %+v", w)
	}
	b2, _ := b.resume(ts2)
	a2.collect()
	b2.collect()
	if got := mobKills(t, b2, 5); !reflect.DeepEqual(got, []string{"5:0:3"}) {
		t.Fatalf("MOB_KILLS after restart = %v", got)
	}
	if got := terrains(t, a2, 5); !reflect.DeepEqual(got, []protocol.TerrainChange{{Cell: 1022, Tile: 22}}) {
		t.Fatalf("TERRAINS after restart = %+v", got)
	}
	// The registry still decides: B's late report of the same mob is ignored.
	moveTo(b2, a2, 5)
	b2.send(fl(5), &protocol.MobKilled{MobNetID: "5:0:3"})
	b2.quiet()
	a2.quiet()
}
