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
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionWire.M2;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionWire.ME;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionWire.SYNC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.watabou.utils.Bundle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

//the state mirror: every transition, replays after a resume, and save/load inside the CoopRun
class MissionStateTest {

	@BeforeAll
	static void env() {
		HeadlessEnv.start();
	}

	@Test
	void missionStartMirrorsEveryField() {
		MissionState s = new MissionState();
		assertTrue( s.apply( MissionWire.start( M1, ME, ALLY, 1_000, 241_000 ), 1_000 ) );
		MissionState.Mission m = s.mission();
		assertEquals( M1, m.id );
		assertEquals( MissionState.CELL, m.kind );
		assertEquals( ME, m.prisoner );
		assertEquals( ALLY, m.rescuer );
		assertEquals( 7, m.depth );
		assertEquals( 0, m.branch );
		assertEquals( 345, m.trapCell );
		assertEquals( 12, m.roomId );
		assertEquals( 99, m.keySeed );
		assertEquals( 1_000, m.startsAt );
		assertEquals( 241_000, m.deadline );
		assertEquals( 30_000, m.waveIntervalMs );
		assertFalse( m.paused );
		assertTrue( m.tasks.isEmpty() );
	}

	@Test
	void updatesMergeTaskFlagsAndOtherMissionsAreIgnored() {
		MissionState s = new MissionState();
		s.apply( MissionWire.start( M1, ME, ALLY, 0, 240_000 ), 0 );
		assertTrue( s.apply( MissionWire.update( M1, false, 200_000, "{\"key\":false}" ), 40_000 ) );
		assertEquals( Boolean.FALSE, s.mission().tasks.get( "key" ) );
		assertTrue( s.apply( MissionWire.update( M1, false, 150_000, "{\"key\":true}" ), 90_000 ) );
		assertEquals( Boolean.TRUE, s.mission().tasks.get( "key" ) );
		//an update without tasks keeps the known flags
		s.apply( MissionWire.update( M1, false, 140_000, null ), 100_000 );
		assertEquals( Boolean.TRUE, s.mission().tasks.get( "key" ) );

		assertFalse( s.apply( MissionWire.update( M2, true, 1, null ), 0 ), "another mission's update" );
		assertFalse( s.mission().paused );
	}

	@Test
	void anEndIsQueuedOnceAndReplaysAreIgnored() {
		MissionState s = new MissionState();
		s.apply( MissionWire.start( M1, ME, ALLY, 0, 240_000 ), 0 );
		assertTrue( s.apply( MissionWire.failed( M1, 6, 25 ), 0 ) );
		assertNull( s.mission(), "the mission is over at once" );
		MissionState.End e = s.nextEnd();
		assertNotNull( e );
		assertTrue( e.failed() );
		assertEquals( 6, e.regionStartDepth );
		assertEquals( 25, e.goldPct );
		assertEquals( M1, e.mission.id );

		//resume replay: START + END of the same mission again must not restart it or charge twice
		assertFalse( s.apply( MissionWire.start( M1, ME, ALLY, 0, 240_000 ), 0 ) );
		assertFalse( s.apply( MissionWire.failed( M1, 6, 25 ), 0 ) );
		assertNull( s.mission() );
		assertEquals( 1, s.pendingEnds().size() );

		s.endApplied( e );
		assertNull( s.nextEnd() );
		assertFalse( s.apply( MissionWire.failed( M1, 6, 25 ), 0 ), "still ignored once applied" );

		//a repeated MISSION_START of the active mission is idempotent (keeps paused/tasks)
		s.apply( MissionWire.start( M2, ALLY, ME, 0, 240_000 ), 0 );
		s.apply( MissionWire.update( M2, true, 99_000, "{\"key\":true}" ), 0 );
		assertTrue( s.apply( MissionWire.start( M2, ALLY, ME, 0, 240_000 ), 0 ) );
		assertTrue( s.mission().paused );
		assertEquals( Boolean.TRUE, s.mission().tasks.get( "key" ) );
	}

	@Test
	void successRevivedAndAbortedEnds() {
		MissionState s = new MissionState();
		s.apply( MissionWire.start( M1, ME, ALLY, 0, 240_000 ), 0 );
		s.apply( MissionWire.success( M1, 4242 ), 0 );
		MissionState.End e = s.nextEnd();
		assertTrue( e.success() );
		assertEquals( 4242, e.rewardSeed );
		s.endApplied( e );

		s.apply( MissionWire.start( M2, ME, ALLY, 0, 240_000 ), 0 );
		s.apply( MissionWire.ended( M2, MissionState.ABORTED ), 0 );
		e = s.nextEnd();
		assertFalse( e.failed() );
		assertFalse( e.success() );
		assertNull( s.mission() );
	}

	@Test
	void downedWindowsAndSuperSync() {
		MissionState s = new MissionState();
		assertTrue( s.apply( MissionWire.allyDowned( ALLY, 50_000 ), 0 ) );
		assertTrue( s.isDowned( ALLY ) );
		assertTrue( s.apply( MissionWire.revive( ALLY ), 0 ) );
		assertFalse( s.isDowned( ALLY ) );
		assertFalse( s.apply( MissionWire.revive( ALLY ), 0 ), "revive replay" );

		s.apply( MissionWire.allyDowned( ME, 80_000 ), 0 );
		assertTrue( s.apply( MissionWire.downedExpired( ME ), 0 ) );
		assertFalse( s.isDowned( ME ) );
		assertTrue( s.isOut( ME ) );
		assertFalse( s.apply( MissionWire.allyDowned( ME, 99_000 ), 0 ), "a player who is out can't be Downed again" );

		assertTrue( s.apply( MissionWire.syncStart( SYNC ), 0 ) );
		assertEquals( 15, s.sync().turns );
		assertEquals( "opaque-stats", s.sync().snapshot );
		assertFalse( s.apply( MissionWire.syncEnd( "99999999aaaaaaaa" ), 0 ), "another session" );
		assertTrue( s.apply( MissionWire.syncEnd( SYNC ), 0 ) );
		assertNull( s.sync() );
	}

	@Test
	void survivesSaveAndLoadInsideTheCoopRun() throws Exception {
		CoopRun run = new CoopRun();
		run.runId = "c0ffee0123456789";
		MissionState s = run.missions;
		s.apply( MissionWire.start( M2, ME, ALLY, 1_000, 241_000 ), 1_000 );
		s.apply( MissionWire.update( M2, true, 123_456, "{\"key\":true}" ), 50_000 );
		//a second flag (v1 only defines "key"; the decoder drops unknown ones, PROTOCOL.md §5.5)
		s.mission().tasks.put( "lever", false );
		s.apply( MissionWire.failed( M1, 11, 25 ), 0 );
		s.apply( MissionWire.allyDowned( ALLY, 77_000 ), 0 );
		s.apply( MissionWire.allyDowned( ME, 66_000 ), 0 );
		s.apply( MissionWire.downedExpired( ME ), 0 );
		s.apply( MissionWire.syncStart( SYNC ), 0 );

		Bundle saved = new Bundle();
		saved.put( "coop_run", run );
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		assertTrue( Bundle.write( saved, bytes ) );
		Bundle loaded = Bundle.read( new ByteArrayInputStream( bytes.toByteArray() ) );
		MissionState r = ((CoopRun) loaded.get( "coop_run" )).missions;

		MissionState.Mission m = r.mission();
		assertEquals( M2, m.id );
		assertEquals( 345, m.trapCell );
		assertEquals( 241_000, m.deadline );
		assertTrue( m.paused );
		assertEquals( 123_456, m.remainingMs( 999_999 ) );
		assertEquals( Boolean.TRUE, m.tasks.get( "key" ) );
		assertEquals( Boolean.FALSE, m.tasks.get( "lever" ) );

		MissionState.End e = r.nextEnd();
		assertEquals( M1, e.missionId );
		assertEquals( 11, e.regionStartDepth );
		assertEquals( 25, e.goldPct );
		assertTrue( r.hasEnded( M1 ) );
		assertFalse( r.apply( MissionWire.failed( M1, 11, 25 ), 0 ), "a replay after loading is still ignored" );

		assertTrue( r.isDowned( ALLY ) );
		assertEquals( 7_000, r.downedRemainingMs( ALLY, 70_000 ) );
		assertTrue( r.isOut( ME ) );
		assertEquals( SYNC, r.sync().id );
	}

	@Test
	void anOldSaveWithoutMissionsLoadsEmpty() {
		Bundle old = new Bundle();
		old.put( "coop_run_id", "c0ffee0123456789" );
		CoopRun run = new CoopRun();
		run.restoreFromBundle( old );
		assertNotNull( run.missions );
		assertFalse( run.missions.clockActive() );
	}
}
