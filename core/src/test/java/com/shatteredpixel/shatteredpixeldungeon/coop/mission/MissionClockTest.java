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

import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionWire.ALLY;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionWire.M1;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionWire.ME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.coop.ui.MissionHud;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;

import org.junit.jupiter.api.Test;

//server time → local time, and the mission countdown through pauses (D-005)
class MissionClockTest {

	//the server's clock runs 1 h ahead of ours
	private static final long AHEAD = 3_600_000;

	@Test
	void offsetFromWelcomeConvertsServerTimesToLocal() {
		ServerClock c = new ServerClock();
		assertFalse( c.known() );
		assertEquals( 0, c.offset() );
		c.onServerTime( 1_000_000 + AHEAD, 1_000_000, true );
		assertTrue( c.known() );
		assertEquals( AHEAD, c.offset() );
		assertEquals( 2_000_000 + AHEAD, c.serverNow( 2_000_000 ) );
		assertEquals( 5_000_000, c.toLocal( 5_000_000 + AHEAD ) );
	}

	@Test
	void theLeastDelayedRecentSampleWinsAndAWelcomeStartsOver() {
		ServerClock c = new ServerClock();
		//each sample = true offset − that message's one-way delay
		c.onServerTime( 1_000_000 + AHEAD - 80, 1_000_000, true );
		c.onServerTime( 1_010_000 + AHEAD - 15, 1_010_000, false );
		c.onServerTime( 1_020_000 + AHEAD - 200, 1_020_000, false );
		assertEquals( AHEAD - 15, c.offset(), "max sample = smallest delay" );

		//old samples age out after SAMPLES newer ones
		for (int i = 0; i < ServerClock.SAMPLES; i++) {
			c.onServerTime( 1_100_000 + i + AHEAD - 40, 1_100_000 + i, false );
		}
		assertEquals( AHEAD - 40, c.offset() );

		//another server process (WELCOME) may have another clock: nothing old survives
		c.onServerTime( 2_000_000 + 7, 2_000_000, true );
		assertEquals( 7, c.offset() );
	}

	@Test
	void countdownFollowsTheServerDeadlineAndFreezesWhilePaused() {
		ServerClock c = new ServerClock();
		c.onServerTime( 10_000 + AHEAD, 10_000, true );
		MissionState s = new MissionState();
		long serverStart = 10_000 + AHEAD;
		assertTrue( s.apply( MissionWire.start( M1, ME, ALLY, serverStart, serverStart + 240_000 ), c.serverNow( 10_000 ) ) );
		MissionState.Mission m = s.mission();
		assertEquals( 240_000, m.remainingMs( c.serverNow( 10_000 ) ) );
		assertEquals( 180_000, m.remainingMs( c.serverNow( 70_000 ) ), "real time, not turns" );

		//ally disconnects at local 70 s: the server pauses with 180 s left
		s.apply( MissionWire.update( M1, true, 180_000, null ), c.serverNow( 70_000 ) );
		assertTrue( m.paused );
		assertEquals( 180_000, m.remainingMs( c.serverNow( 70_000 ) ) );
		assertEquals( 180_000, m.remainingMs( c.serverNow( 500_000 ) ), "frozen while paused" );
		assertFalse( s.clockRunning( true ), "no forced turns while the mission is paused" );

		//resumed at local 500 s with the same 180 s: the deadline moves
		s.apply( MissionWire.update( M1, false, 180_000, null ), c.serverNow( 500_000 ) );
		assertFalse( m.paused );
		assertEquals( 180_000, m.remainingMs( c.serverNow( 500_000 ) ) );
		assertEquals( 170_000, m.remainingMs( c.serverNow( 510_000 ) ) );
		assertEquals( 0, m.remainingMs( c.serverNow( 900_000 ) ), "never negative" );
	}

	@Test
	void clocksRunOnlyWithTheLinkUpAndSomethingActive() {
		MissionState s = new MissionState();
		assertFalse( s.clockActive() );
		assertFalse( s.clockRunning( true ), "no clock, no forced turns" );

		s.apply( MissionWire.start( M1, ME, ALLY, 0, 240_000 ), 0 );
		assertTrue( s.clockRunning( true ) );
		assertFalse( s.clockRunning( false ), "we or the ally offline: the server holds the clock" );

		s.apply( MissionWire.ended( M1, MissionState.ABORTED ), 0 );
		assertFalse( s.clockActive() );

		//a Downed window is a clock too (MEC-02)
		s.apply( MissionWire.allyDowned( ALLY, 30_000 ), 0 );
		assertTrue( s.clockRunning( true ) );
		assertEquals( 12_000, s.downedRemainingMs( ALLY, 18_000 ) );
		//the server froze it while the link was down for 5 s
		s.shiftDowned( 5_000 );
		assertEquals( 17_000, s.downedRemainingMs( ALLY, 18_000 ) );
	}

	@Test
	void hudTextCountsDownInMinutesAndSecondsAndNamesThePause() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		assertEquals( "0:00", MissionHud.countdown( 0 ) );
		assertEquals( "0:00", MissionHud.countdown( -5 ) );
		assertEquals( "0:01", MissionHud.countdown( 1 ), "rounded up: 0:00 only when it's really over" );
		assertEquals( "1:00", MissionHud.countdown( 60_000 ) );
		assertEquals( "4:15", MissionHud.countdown( 255_000 ) );

		MissionState s = new MissionState();
		s.apply( MissionWire.start( M1, ME, ALLY, 0, 255_000 ), 0 );
		assertEquals( "The Cell  4:15", MissionHud.titleLine( s.mission(), 0, true, true ) );
		assertEquals( "The Cell  paused (ally offline)", MissionHud.titleLine( s.mission(), 0, true, false ) );
		assertEquals( "The Cell  paused (reconnecting...)", MissionHud.titleLine( s.mission(), 0, false, true ) );
		s.apply( MissionWire.update( M1, true, 100_000, "{\"key\":true}" ), 0 );
		assertEquals( "The Cell  paused", MissionHud.titleLine( s.mission(), 0, true, true ), "paused by the server" );

		assertEquals( "Key", MissionHud.taskName( "key" ) );
		assertEquals( "lever", MissionHud.taskName( "lever" ), "no label: its wire name" );
		assertEquals( "You are downed: 0:30", MissionHud.downedText( ME, ME, 30_000, false ) );
		assertEquals( "Ally is downed: paused", MissionHud.downedText( ALLY, ME, 30_000, true ) );
	}
}
