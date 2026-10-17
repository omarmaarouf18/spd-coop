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

package com.shatteredpixel.shatteredpixeldungeon.coop.ghost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;

import org.junit.jupiter.api.Test;

//pings: ~5 s lifetime, same floor only, local rate limit 1/s
class MarksTest {

	@Test
	void markExpiresAfterFiveSeconds() {
		Marks marks = new Marks();
		long t = 10_000;
		assertNotNull( marks.placeLocal( 2, 0, 77, Marks.Kind.DANGER, t ) );
		assertEquals( 1, marks.active( 2, 0, t + 4_999 ).size() );
		assertEquals( 0, marks.active( 2, 0, t + 5_000 ).size() );
	}

	@Test
	void receivedMarkShowsOnlyOnTheSameFloor() {
		Marks marks = new Marks();
		long t = 10_000;
		assertNull( marks.receive( 3, 0, 77, Marks.Kind.GO, 2, 0, t ), "other depth: ignored" );
		assertNull( marks.receive( 2, 1, 77, Marks.Kind.GO, 2, 0, t ), "other branch: ignored" );
		assertNull( marks.receive( 2, 0, 77, null, 2, 0, t ), "unknown kind: ignored" );
		Marks.Mark m = marks.receive( 2, 0, 77, Marks.Kind.GO, 2, 0, t );
		assertNotNull( m );
		assertTrue( m.fromAlly );
		assertEquals( 1, marks.active( 2, 0, t + 1 ).size() );
		assertEquals( 0, marks.active( 3, 0, t + 1 ).size(), "and is not drawn on another floor" );
		assertEquals( 0, marks.active( 2, 0, t + Marks.LIFETIME_MS ).size() );
	}

	@Test
	void localRateLimitIsOnePerSecond() {
		Marks marks = new Marks();
		long t = 10_000;
		assertNotNull( marks.placeLocal( 1, 0, 5, Marks.Kind.HERE, t ) );
		assertNull( marks.placeLocal( 1, 0, 6, Marks.Kind.HERE, t + 999 ) );
		assertNotNull( marks.placeLocal( 1, 0, 6, Marks.Kind.HERE, t + 1_000 ) );
		//the ally's pings are not rate-limited by ours
		assertNotNull( marks.receive( 1, 0, 7, Marks.Kind.ITEM, 1, 0, t + 1_001 ) );
	}

	@Test
	void newerPingOnTheSameCellReplacesTheOlder() {
		Marks marks = new Marks();
		marks.placeLocal( 1, 0, 5, Marks.Kind.HERE, 0 );
		marks.placeLocal( 1, 0, 5, Marks.Kind.DANGER, 2_000 );
		assertEquals( 1, marks.active( 1, 0, 2_001 ).size() );
		assertEquals( Marks.Kind.DANGER, marks.active( 1, 0, 2_001 ).get( 0 ).kind );
	}

	@Test
	void kindsMatchTheProtocol() {
		assertEquals( Protocol.MARK_KINDS.size(), Marks.Kind.values().length );
		for (Marks.Kind k : Marks.Kind.values()) {
			assertTrue( Protocol.MARK_KINDS.contains( k.wire() ), k.wire() );
			assertEquals( k, Marks.Kind.fromWire( k.wire() ) );
		}
		assertNull( Marks.Kind.fromWire( "LOOT" ) );
	}
}
