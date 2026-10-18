/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * Logic Link PD — Copyright (C) 2026 Logic Link PD contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.coop.mission.downed;

//one Downed turn's real-time wait (MEC-02): WAIT_MS of running clock, counted from when the
// clock (re)starts, like ForcedTurns (a resume never lets a turn through at once). Pure logic:
// the clock and the "still waiting" checks are given; sleep() is the only side effect.
final class DownedPace {

	interface Clock {
		long now();
		//the mission/Downed clock runs (link up, not paused)
		boolean running();
		//false: stop waiting (revived, expired, the scene is going away)
		boolean stillDowned();
		//false when interrupted
		boolean sleep( long ms );
	}

	static final long STEP_MS = 100;

	private DownedPace() {}

	//true: the turn may go on; false: the thread was interrupted
	static boolean waitTurn( Clock c, long waitMs ) {
		long since = c.running() ? c.now() : -1;
		while (c.stillDowned()) {
			if (c.running()) {
				if (since < 0) since = c.now();
				if (c.now() - since >= waitMs) return true;
			} else {
				since = -1;
			}
			if (!c.sleep( STEP_MS )) return false;
		}
		return true;
	}
}
