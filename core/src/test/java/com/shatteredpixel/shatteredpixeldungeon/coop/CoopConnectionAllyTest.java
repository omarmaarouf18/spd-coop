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

package com.shatteredpixel.shatteredpixeldungeon.coop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.coop.mission.CoopMissions;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

//CoopConnection's session listener: allyConnected() right after a run start (no ALLY_STATUS is
// sent then: GHO-01 follow-up), and the mission events / server time handed to CoopMissions
class CoopConnectionAllyTest {

	private static final String PID = "a1b2c3d4e5f60718";
	private static final String ALLY = "b2c3d4e5f6071829";
	private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	private static final String RUN_ID = "c0ffee0123456789";

	private CoopSession.Listener listener;

	@BeforeEach
	void setUp() throws Exception {
		HeadlessEnv.start();
		Class<?> c = Class.forName( CoopConnection.class.getName() + "$SessionListener" );
		Constructor<?> ctor = c.getDeclaredConstructor();
		ctor.setAccessible( true );
		listener = (CoopSession.Listener) ctor.newInstance();
		setStatic( CoopConnection.class, "allyConnected", false );
		setStatic( CoopConnection.class, "playerId", null );
		setStatic( Coop.class, "run", null );
	}

	@AfterEach
	void tearDown() throws Exception {
		setStatic( Coop.class, "run", null );
		setStatic( CoopConnection.class, "room", null );
		setStatic( CoopConnection.class, "allyConnected", false );
	}

	private static void setStatic( Class<?> c, String name, Object value ) throws Exception {
		Field f = c.getDeclaredField( name );
		f.setAccessible( true );
		f.set( null, value );
	}

	private static Message s2c( String t, String payload ) throws Exception {
		return Protocol.decode( Direction.S2C, "{\"v\":1,\"t\":\"" + t + "\",\"seq\":5,\"p\":" + payload + "}" );
	}

	private static String room( boolean allyConnected ) {
		return "{\"code\":\"K7MZQ4\",\"phase\":\"running\",\"players\":["
				+ "{\"playerId\":\"" + PID + "\",\"slot\":0,\"heroClass\":\"WARRIOR\",\"ready\":true,\"connected\":true,\"platform\":\"desktop\"},"
				+ "{\"playerId\":\"" + ALLY + "\",\"slot\":1,\"heroClass\":\"MAGE\",\"ready\":true,\"connected\":" + allyConnected + ",\"platform\":\"desktop\"}]}";
	}

	private static final String RUN = "{\"runId\":\"" + RUN_ID + "\",\"seed\":123456789,\"roleSeed\":987654321,\"players\":["
			+ "{\"playerId\":\"" + PID + "\",\"slot\":0,\"heroClass\":\"WARRIOR\",\"platform\":\"desktop\"},"
			+ "{\"playerId\":\"" + ALLY + "\",\"slot\":1,\"heroClass\":\"MAGE\",\"platform\":\"desktop\"}]}";

	//the run this device is already playing, so onRunStart doesn't switch scenes
	private void playing() throws Exception {
		CoopRun run = new CoopRun();
		run.runId = RUN_ID;
		run.playerId = PID;
		setStatic( Coop.class, "run", run );
	}

	@Test
	void aLiveRunStartMeansTheAllyIsConnected() throws Exception {
		playing();
		listener.onCredentials( PID, TOKEN, "e1e2e3e4e5e6e7e8" );
		assertFalse( CoopConnection.allyConnected() );
		listener.onRunStart( s2c( Protocol.RUN_START, RUN ).p, false );
		assertTrue( CoopConnection.allyConnected(), "RUN_START only goes out with both players connected" );
	}

	@Test
	void theRoomSnapshotSaysWhetherTheAllyIsConnected() throws Exception {
		playing();
		listener.onCredentials( PID, TOKEN, "e1e2e3e4e5e6e7e8" );
		//resume: WELCOME carries the room, then the run snapshot (which proves nothing about the ally)
		listener.onRoomState( s2c( Protocol.ROOM_STATE, room( true ) ).p );
		listener.onRunStart( s2c( Protocol.RUN_START, RUN ).p, true );
		assertTrue( CoopConnection.allyConnected() );

		listener.onRoomState( s2c( Protocol.ROOM_STATE, room( false ) ).p );
		assertFalse( CoopConnection.allyConnected() );
		listener.onRunStart( s2c( Protocol.RUN_START, RUN ).p, true );
		assertFalse( CoopConnection.allyConnected(), "a snapshot run start keeps what the room said" );

		//ALLY_STATUS still has the last word
		listener.onAllyStatus( s2c( Protocol.ALLY_STATUS, "{\"playerId\":\"" + ALLY + "\",\"connected\":true,\"paused\":false,\"lastSeen\":1}" ) );
		assertTrue( CoopConnection.allyConnected() );
	}

	@Test
	void roomHelper() throws Exception {
		Map<String, Object> r = s2c( Protocol.ROOM_STATE, room( false ) ).p;
		assertEquals( Boolean.FALSE, CoopConnection.allyConnectedIn( r, PID ) );
		assertEquals( Boolean.TRUE, CoopConnection.allyConnectedIn( r, ALLY ), "from the other side: we are connected" );
		assertNull( CoopConnection.allyConnectedIn( r, null ) );
		assertNull( CoopConnection.allyConnectedIn( null, PID ) );
	}

	@Test
	void missionEventsReachTheRunsMirror() throws Exception {
		playing();
		listener.onServerTime( 1_000, 1_000 );
		listener.onServerEvent( s2c( Protocol.MISSION_START, "{\"missionId\":\"0123456789abcdef\",\"kind\":\"CELL\",\"prisoner\":\""
				+ PID + "\",\"rescuer\":\"" + ALLY + "\",\"depth\":7,\"branch\":0,\"trapCell\":345,\"roomId\":12,"
				+ "\"keySeed\":99,\"startsAt\":1000,\"deadline\":241000,\"waveIntervalMs\":30000}" ) );
		assertTrue( CoopMissions.missionActive() );
		assertTrue( CoopMissions.isPrisoner() );
		assertFalse( CoopMissions.isRescuer() );
		assertEquals( "0123456789abcdef", Coop.run().missions.mission().id );

		//not a mission/downed/sync lifecycle message: left alone
		listener.onServerEvent( s2c( Protocol.CLAIMED, "{\"itemNetId\":\"3:0:17\",\"by\":\"" + ALLY + "\"}" ) );
		assertTrue( CoopMissions.missionActive() );
	}
}
