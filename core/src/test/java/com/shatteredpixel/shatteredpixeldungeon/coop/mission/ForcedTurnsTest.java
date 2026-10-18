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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

//forced turns (D-005) against an injectable clock: 5 s of waiting while a clock runs → one wait turn
class ForcedTurnsTest {

	private long now;
	private ForcedTurns f;

	@BeforeEach
	void setUp() {
		now = 1_000_000;
		f = new ForcedTurns( () -> now );
	}

	//frames every 100 ms until `until` (inclusive); returns how many forced turns fired
	private int run( long until, boolean waiting, boolean running ) {
		int fired = 0;
		while (now < until) {
			now = Math.min( until, now + 100 );
			if (f.tick( waiting, running )) fired++;
		}
		return fired;
	}

	@Test
	void fiveSecondsOfWaitingForceExactlyOneWait() {
		assertEquals( 5_000, ForcedTurns.WAIT_MS, "the D-005 constant" );
		f.tick( true, true );
		f.onHeroReady();
		long readyAt = now;
		assertEquals( 0, run( readyAt + ForcedTurns.WAIT_MS - 1, true, true ), "not at 4.999 s" );
		now = readyAt + ForcedTurns.WAIT_MS;
		assertTrue( f.tick( true, true ), "at 5 s" );
		//the forced rest made the hero busy; it gets no second one until it waits again
		assertEquals( 0, run( now + 3_000, false, true ) );
		f.onHeroReady();
		assertEquals( 1, run( now + ForcedTurns.WAIT_MS, true, true ), "the next wait counts from the new Hero.ready()" );
	}

	@Test
	void neverWithoutARunningClock() {
		f.onHeroReady();
		assertEquals( 0, run( now + 60_000, true, false ), "no mission / Downed clock, or paused" );
	}

	@Test
	void pausedClocksHoldTheCountAndResumeRestartsIt() {
		f.tick( true, true );
		f.onHeroReady();
		assertEquals( 0, run( now + 4_000, true, true ) );
		//paused (ally offline) for a minute: nothing fires
		assertEquals( 0, run( now + 60_000, true, false ) );
		//resumed (the first frame that sees the clock running): a fresh 5 s, never an instant turn
		long resumed = now;
		assertFalse( f.tick( true, true ) );
		assertEquals( 0, run( resumed + 4_900, true, true ) );
		assertEquals( 1, run( resumed + 5_000, true, true ) );
	}

	@Test
	void aClockThatStartsWhileTheHeroWaitsCountsFromItsStart() {
		f.onHeroReady();
		assertEquals( 0, run( now + 20_000, true, false ), "idle before the mission" );
		long started = now;
		assertFalse( f.tick( true, true ), "the first frame of the mission" );
		assertEquals( 0, run( started + 4_900, true, true ) );
		assertEquals( 1, run( started + 5_000, true, true ) );
	}

	@Test
	void actingInTimeAvoidsIt() {
		f.tick( true, true );
		for (int i = 0; i < 10; i++) {
			f.onHeroReady();
			assertEquals( 0, run( now + 4_000, true, true ) );
			//the player acts: busy for a moment, then waits again
			assertEquals( 0, run( now + 300, false, true ) );
		}
	}

	@Test
	void theHudCanShowTheTimeLeft() {
		f.tick( true, true );
		f.onHeroReady();
		now += 1_500;
		assertEquals( 3_500, f.msUntilForced( true, true ) );
		assertEquals( -1, f.msUntilForced( true, false ) );
		assertEquals( -1, f.msUntilForced( false, true ) );
		assertFalse( f.tick( true, true ) );
	}
}
