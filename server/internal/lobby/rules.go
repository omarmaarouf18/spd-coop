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

import "time"

// DeathModePlayerOut is the only v1 COOP_DEATH_MODE: a Downed player whose
// window expires outside a mission is out for the rest of the run, and the
// ally continues (PROTOCOL.md §5.8).
const DeathModePlayerOut = "player_out"

// Rules are the co-op gameplay settings (server/README.md, COOP_* env vars).
// Zero fields take the defaults below, except CellCapPerRegion, where 0 is a
// valid setting: it defaults only when the whole Rules is zero.
type Rules struct {
	CellWaveInterval time.Duration // COOP_CELL_WAVE_INTERVAL, default 30 s
	CellCap          time.Duration // COOP_CELL_CAP, default 4 min
	CellCapPerRegion time.Duration // COOP_CELL_CAP_PER_REGION, default 15 s
	DownedWindow     time.Duration // COOP_DOWNED_WINDOW, default 30 s
	PauseGrace       time.Duration // COOP_PAUSE_GRACE, default 90 s
	TwinWindow       time.Duration // COOP_TWIN_WINDOW, default 2 s
	BossHPScale      float64       // COOP_BOSS_HP_SCALE, default 1.8
	DeathMode        string        // COOP_DEATH_MODE, default player_out

	// SnapshotEvery is how often running clocks are persisted, so a crash
	// loses at most this much mission time (default 5 s; not configurable).
	SnapshotEvery time.Duration
	// SyncFrameCap is the per-sender cap of SYNC_VIEW and SYNC_CMD frames
	// per second (PROTOCOL.md §9; default 10).
	SyncFrameCap int
}

func (r *Rules) defaults() {
	if *r == (Rules{}) {
		r.CellCapPerRegion = 15 * time.Second
	}
	set := func(d *time.Duration, v time.Duration) {
		if *d == 0 {
			*d = v
		}
	}
	set(&r.CellWaveInterval, 30*time.Second)
	set(&r.CellCap, 4*time.Minute)
	set(&r.DownedWindow, 30*time.Second)
	set(&r.PauseGrace, 90*time.Second)
	set(&r.TwinWindow, 2*time.Second)
	set(&r.SnapshotEvery, 5*time.Second)
	if r.BossHPScale == 0 {
		r.BossHPScale = 1.8
	}
	if r.DeathMode == "" {
		r.DeathMode = DeathModePlayerOut
	}
	if r.SyncFrameCap == 0 {
		r.SyncFrameCap = 10
	}
}

// cellCap is the hard cap of a Cell mission on depth (PROTOCOL.md §5.6):
// CellCap + CellCapPerRegion × regionIndex, regionIndex = (depth-1)/5.
func (r *Rules) cellCap(depth int) time.Duration {
	return r.CellCap + time.Duration((depth-1)/5)*r.CellCapPerRegion
}

// regionStart is the first floor of depth's region: 1, 6, 11, 16, 21, 26.
func regionStart(depth int) int { return ((depth-1)/5)*5 + 1 }
