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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell;

import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;

//wave pacing inside The Cell (MEC-01, game-design §4.4). Pure logic on the mission clock, which
// is server time minus pauses: elapsed = cap − remaining, and the server freezes remaining while
// paused (and moves its deadline by the pause), so a pause never brings a wave closer. The cap is
// MISSION_START's deadline − startsAt, taken once at the start (the deadline moves later).
//Wave 0 comes at once, wave k at k × waveIntervalMs. Every 2 waves the tier goes up by one:
// one more mob, drawn from one floor deeper in the region. Past the hard cap (the mission's
// deadline) the waves turn lethal (§4.4): the next region's mobs, more of them, twice as often.
final class CellWaves {

	static final int MAX_COUNT = 6;
	static final int LETHAL_EXTRA = 2;

	private CellWaves() {}

	//mission-clock ms since the start (pauses excluded); past the cap, the overtime is added
	static long cap( MissionState.Mission m ) {
		return Math.max( 0, m.deadline - m.startsAt );
	}

	static long elapsed( MissionState.Mission m, long cap, long serverNow ) {
		long remaining = m.remainingMs( serverNow );
		long elapsed = cap - remaining;
		if (!m.paused && remaining == 0) elapsed = cap + Math.max( 0, serverNow - m.deadline );
		return Math.max( 0, elapsed );
	}

	//how many waves are due by this point of the mission clock
	static int due( long elapsed, long cap, long interval ) {
		if (interval <= 0) return 0;
		if (elapsed <= cap) return (int) Math.min( Integer.MAX_VALUE, elapsed / interval + 1 );
		//lethal: twice as often after the cap
		long before = cap / interval + 1;
		long after = (elapsed - cap) / Math.max( 1, interval / 2 );
		return (int) Math.min( Integer.MAX_VALUE, before + after );
	}

	static int due( MissionState.Mission m, long cap, long serverNow ) {
		return due( elapsed( m, cap, serverNow ), cap, m.waveIntervalMs );
	}

	static boolean lethal( MissionState.Mission m, long cap, long serverNow ) {
		return elapsed( m, cap, serverNow ) > cap;
	}

	static int tier( int wave ) {
		return wave / 2;
	}

	static int count( int wave, boolean lethal ) {
		return Math.min( MAX_COUNT, 1 + tier( wave ) + (lethal ? LETHAL_EXTRA : 0) );
	}

	//the floor whose mob rotation the wave draws from: one floor deeper per tier, capped at the
	// region's last regular floor (4, 9, 14, 19, 24); lethal waves use the next region's
	static int sourceDepth( int depth, int wave, boolean lethal ) {
		int regionLast = ((depth - 1) / 5) * 5 + 4;
		if (lethal) return Math.min( 24, regionLast + 5 );
		return Math.min( regionLast, depth + tier( wave ) );
	}
}
