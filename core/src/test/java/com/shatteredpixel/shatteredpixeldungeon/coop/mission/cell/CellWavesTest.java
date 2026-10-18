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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;

import org.junit.jupiter.api.Test;

//MEC-01, game-design §4.4: waves on the mission clock (a fake server clock), pauses excluded,
// +1 tier every 2 waves, lethal after the hard cap
class CellWavesTest {

	private static final long START = 1_700_000_000_000L;
	private static final long CAP = 240_000;
	private static final long WAVE = 30_000;

	private static MissionState.Mission mission() {
		MissionState.Mission m = new MissionState.Mission();
		m.startsAt = START;
		m.deadline = START + CAP;
		m.waveIntervalMs = WAVE;
		m.depth = 7;
		return m;
	}

	@Test
	void oneWaveAtOnceThenOnePerInterval() {
		MissionState.Mission m = mission();
		assertEquals( 1, CellWaves.due( m, CAP, START ) );
		assertEquals( 1, CellWaves.due( m, CAP, START + WAVE - 1 ) );
		assertEquals( 2, CellWaves.due( m, CAP, START + WAVE ) );
		assertEquals( 5, CellWaves.due( m, CAP, START + 4 * WAVE + 10 ) );
		assertEquals( 9, CellWaves.due( m, CAP, START + CAP ) );
		assertFalse( CellWaves.lethal( m, CAP, START + CAP ) );
	}

	@Test
	void aPauseFreezesTheWaves() {
		MissionState.Mission m = mission();
		//the server paused with 3.5 waves' worth gone: nothing more however long it lasts
		m.paused = true;
		m.pausedRemainingMs = CAP - (3 * WAVE + WAVE / 2);
		assertEquals( 4, CellWaves.due( m, CAP, START + 3 * WAVE ) );
		assertEquals( 4, CellWaves.due( m, CAP, START + 100 * WAVE ) );
		assertFalse( CellWaves.lethal( m, CAP, START + 100 * WAVE ) );

		//resumed: the server moved the deadline by the pause, the count goes on from there
		long resumedAt = START + 10 * WAVE;
		m.paused = false;
		m.deadline = resumedAt + m.pausedRemainingMs;
		assertEquals( 4, CellWaves.due( m, CAP, resumedAt ) );
		assertEquals( 5, CellWaves.due( m, CAP, resumedAt + WAVE / 2 ) );
	}

	@Test
	void pastTheCapWavesTurnLethalAndComeTwiceAsOften() {
		MissionState.Mission m = mission();
		long end = START + CAP;
		assertTrue( CellWaves.lethal( m, CAP, end + 1 ) );
		assertEquals( 9, CellWaves.due( m, CAP, end + 1 ) );
		assertEquals( 10, CellWaves.due( m, CAP, end + WAVE / 2 ) );
		assertEquals( 11, CellWaves.due( m, CAP, end + WAVE ) );
	}

	@Test
	void tiersCountsAndSourceFloors() {
		assertEquals( 1, CellWaves.count( 0, false ) );
		assertEquals( 1, CellWaves.count( 1, false ) );
		assertEquals( 2, CellWaves.count( 2, false ) );
		assertEquals( 3, CellWaves.count( 5, false ) );
		assertEquals( CellWaves.MAX_COUNT, CellWaves.count( 40, false ) );
		assertEquals( 3, CellWaves.count( 0, true ) );

		//one floor deeper per tier, within the region (7 → 8, 9, then capped at 9)
		assertEquals( 7, CellWaves.sourceDepth( 7, 0, false ) );
		assertEquals( 7, CellWaves.sourceDepth( 7, 1, false ) );
		assertEquals( 8, CellWaves.sourceDepth( 7, 2, false ) );
		assertEquals( 9, CellWaves.sourceDepth( 7, 4, false ) );
		assertEquals( 9, CellWaves.sourceDepth( 7, 20, false ) );
		assertEquals( 4, CellWaves.sourceDepth( 2, 9, false ) );
		//lethal: the next region's last regular floor
		assertEquals( 14, CellWaves.sourceDepth( 7, 0, true ) );
		assertEquals( 24, CellWaves.sourceDepth( 22, 0, true ) );
	}
}
