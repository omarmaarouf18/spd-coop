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

package com.shatteredpixel.shatteredpixeldungeon.coop.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Random;

class BackoffTest {

	@Test
	void delaysDoubleWithinJitterBoundsAndStopAtCap() {
		Backoff backoff = new Backoff( 1000, 30_000, new Random( 7 ) );
		long[] ceilings = { 1000, 2000, 4000, 8000, 16_000, 30_000, 30_000, 30_000 };
		for (long ceiling : ceilings) {
			long delay = backoff.nextDelayMs();
			assertTrue( delay >= ceiling / 2 && delay <= ceiling,
					"delay " + delay + " outside [" + ceiling / 2 + ", " + ceiling + "]" );
		}
		assertEquals( ceilings.length, backoff.attempts() );
	}

	@Test
	void manyAttemptsNeverOverflowPastCap() {
		Backoff backoff = new Backoff( 1000, 30_000, new Random( 1 ) );
		for (int i = 0; i < 200; i++) {
			long delay = backoff.nextDelayMs();
			assertTrue( delay >= 15_000 || i < 5, "attempt " + i + " delay " + delay );
			assertTrue( delay <= 30_000, "attempt " + i + " delay " + delay );
		}
	}

	@Test
	void resetStartsOverFromBase() {
		Backoff backoff = new Backoff( 1000, 30_000, new Random( 3 ) );
		for (int i = 0; i < 6; i++) {
			backoff.nextDelayMs();
		}
		backoff.reset();
		assertEquals( 0, backoff.attempts() );
		assertTrue( backoff.nextDelayMs() <= 1000 );
	}

	@Test
	void sameJitterSeedGivesSameSchedule() {
		Backoff a = new Backoff( 1000, 30_000, new Random( 42 ) );
		Backoff b = new Backoff( 1000, 30_000, new Random( 42 ) );
		for (int i = 0; i < 10; i++) {
			assertEquals( a.nextDelayMs(), b.nextDelayMs() );
		}
	}

	@Test
	void rejectsInvalidBounds() {
		assertThrows( IllegalArgumentException.class, () -> new Backoff( 0, 10, new Random() ) );
		assertThrows( IllegalArgumentException.class, () -> new Backoff( 10, 5, new Random() ) );
	}
}
