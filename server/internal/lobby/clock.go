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

// Clock is the time source of the game engine (mission clocks, Downed
// windows, pause grace, twin windows, snapshots). Tests inject a fake one;
// connection-level timers (hello/heartbeat, rate limits) stay on real time.
type Clock interface {
	Now() time.Time
	// AfterFunc calls f in its own goroutine (or, for a fake clock, from
	// the goroutine that advances it) once d has elapsed.
	AfterFunc(d time.Duration, f func()) Timer
}

// Timer is a pending AfterFunc call.
type Timer interface {
	Stop() bool
}

type realClock struct{ now func() time.Time }

func (c realClock) Now() time.Time { return c.now() }

func (realClock) AfterFunc(d time.Duration, f func()) Timer { return time.AfterFunc(d, f) }
