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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

//GHOST throttle: at most one per hero turn, idle resend every 5 s, retries, forced sends
class PresenceSenderTest {

	private final List<HeroSnapshot> sent = new ArrayList<>();
	private boolean accept = true;
	private PresenceSender sender;
	private long now;

	@BeforeEach
	void setUp() {
		sent.clear();
		accept = true;
		now = 1_000_000;
		sender = new PresenceSender( s -> {
			if (!accept) return false;
			sent.add( s );
			return true;
		} );
	}

	private static HeroSnapshot at( int pos, String anim, float turn ) {
		return new HeroSnapshot( 3, 0, pos, anim, 100, "MAGE", 0, turn );
	}

	@Test
	void sendsEachResolvedTurnOnce() {
		sender.offer( at( 10, "idle", 0 ), now );
		sender.offer( at( 11, "run", 1 ), now += 100 );
		sender.offer( at( 12, "run", 2 ), now += 100 );
		assertEquals( 3, sent.size() );
		assertEquals( 12, sent.get( 2 ).pos );
	}

	@Test
	void atMostOnePerHeroTurn() {
		sender.offer( at( 10, "attack", 5 ), now );
		//the hero acts again on the same turn (input after ready()): it waits for the next turn
		sender.offer( at( 10, "idle", 5 ), now += 10 );
		assertEquals( 1, sent.size() );
		assertNotNull( sender.pending() );
		//a new turn: the newest state goes out, the deferred one is dropped
		sender.offer( at( 11, "run", 6 ), now += 10 );
		assertEquals( 2, sent.size() );
		assertEquals( 11, sent.get( 1 ).pos );
		assertNull( sender.pending() );
	}

	@Test
	void unchangedStateIsNotResent() {
		sender.offer( at( 10, "idle", 1 ), now );
		sender.offer( at( 10, "idle", 2 ), now += 10 );
		sender.offer( at( 10, "idle", 3 ), now += 10 );
		assertEquals( 1, sent.size() );
	}

	@Test
	void idleResendEveryFiveSecondsAsIdle() {
		sender.offer( at( 10, "attack", 1 ), now );
		sender.tick( now += 4_999 );
		assertEquals( 1, sent.size(), "nothing before 5 s" );
		sender.tick( now += 1 );
		assertEquals( 2, sent.size(), "resent at 5 s" );
		assertEquals( 10, sent.get( 1 ).pos );
		assertEquals( "idle", sent.get( 1 ).anim, "a resend never replays the attack" );
		sender.tick( now += 2_000 );
		assertEquals( 2, sent.size() );
		sender.tick( now += 3_000 );
		assertEquals( 3, sent.size(), "and again 5 s later" );
	}

	@Test
	void deferredSameTurnChangeGoesOutWithTheIdleResend() {
		sender.offer( at( 10, "run", 1 ), now );
		sender.offer( at( 10, "idle", 1 ).withAnim( "operate" ), now += 10 );
		assertEquals( 1, sent.size() );
		sender.tick( now += 5_000 );
		assertEquals( 2, sent.size() );
		assertEquals( "operate", sent.get( 1 ).anim );
	}

	@Test
	void refusedSendIsRetriedOncePerSecond() {
		accept = false;
		sender.offer( at( 10, "idle", 1 ), now );
		assertEquals( 0, sent.size() );
		accept = true;
		sender.tick( now += 500 );
		assertEquals( 0, sent.size(), "not before the retry interval" );
		sender.tick( now += 500 );
		assertEquals( 1, sent.size() );
		assertEquals( 10, sent.get( 0 ).pos );
	}

	@Test
	void forceSendsImmediately() {
		sender.offer( at( 10, "idle", 1 ), now );
		sender.force();
		sender.tick( now += 1_000 );
		assertEquals( 2, sent.size(), "reconnect / ally back: resend without waiting 5 s" );
		sender.force();
		sender.offer( at( 10, "idle", 1 ), now += 1_000 );
		assertEquals( 3, sent.size(), "a level shown sends even an unchanged state" );
	}

	@Test
	void deathGoesOutOnTheSameTurn() {
		sender.offer( at( 10, "run", 7 ), now );
		sender.offer( new HeroSnapshot( 3, 0, 10, "die", 0, "MAGE", 0, 7 ), now += 10 );
		assertEquals( 2, sent.size() );
		assertEquals( "die", sent.get( 1 ).anim );
		sender.tick( now += 5_000 );
		assertEquals( "die", sent.get( 2 ).anim, "a dead hero is resent as dead, not idle" );
	}

	@Test
	void hpPctIsClampedAndNeverZeroWhileAlive() {
		assertEquals( 1, HeroSnapshot.hpPct( 1, 500 ) );
		assertEquals( 0, HeroSnapshot.hpPct( 0, 20 ) );
		assertEquals( 100, HeroSnapshot.hpPct( 25, 20 ) );
		assertEquals( 50, HeroSnapshot.hpPct( 10, 20 ) );
	}
}
