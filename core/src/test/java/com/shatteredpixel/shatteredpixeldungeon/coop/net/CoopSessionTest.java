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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession.State;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//CoopSession against a fake link: every frame it sends must be a valid c2s message
class CoopSessionTest {

	private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	private static final String TOKEN2 = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
	private static final String PID = "a1b2c3d4e5f60718";
	private static final String ALLY = "b2c3d4e5f6071829";
	private static final String EPOCH = "e1e2e3e4e5e6e7e8";
	private static final String EPOCH2 = "f1f2f3f4f5f6f7f8";

	private FakeLink link;
	private Recorder rec;
	private long now;
	private CoopSession session;

	@BeforeEach
	void setUp() {
		link = new FakeLink();
		rec = new Recorder();
		now = 1_000_000;
		session = new CoopSession( 923, "desktop", rec, () -> now );
		session.attach( link );
	}

	@Test
	void helloIsTheFirstFrameWithSeqOne() throws Exception {
		session.onConnected();
		Message hello = link.last();
		assertEquals( Protocol.HELLO, hello.t );
		assertEquals( 1, hello.seq );
		assertEquals( "1", hello.p.get( "protocol" ).toString() );
		assertEquals( "923", hello.p.get( "gameVersionCode" ).toString() );
		assertEquals( "desktop", hello.p.get( "platform" ) );
		assertFalse( hello.p.containsKey( "resumeToken" ) );
		assertEquals( State.HANDSHAKE, session.state() );
	}

	@Test
	void requestsWaitForWelcome() throws Exception {
		session.onConnected();
		assertFalse( session.createRoom(), "no requests before WELCOME" );
		session.onMessage( welcome( 1, EPOCH, TOKEN, "" ) );
		assertEquals( State.READY, session.state() );
		assertEquals( PID + "|" + TOKEN + "|" + EPOCH, rec.credentials );
		assertTrue( session.createRoom() );
		Message create = link.last();
		assertEquals( Protocol.ROOM_CREATE, create.t );
		assertEquals( 2, create.seq );
	}

	@Test
	void joinUppercasesTheCode() throws Exception {
		welcomed();
		assertTrue( session.joinRoom( " k7mzq4 " ) );
		assertEquals( "K7MZQ4", link.last().p.get( "code" ) );
	}

	@Test
	void resumeSendsTokenEpochAndAckAndSeqRestartsPerConnection() throws Exception {
		welcomed();
		session.onMessage( roomState( 7, "lobby" ) );
		assertEquals( 7, session.ack() );
		session.createRoom();

		session.onDisconnected( 1006, "", true );
		assertEquals( State.CONNECTING, session.state() );
		session.onConnected();
		Message hello = link.last();
		assertEquals( 1, hello.seq, "c2s seq restarts on every connection" );
		assertEquals( TOKEN, hello.p.get( "resumeToken" ) );
		assertEquals( EPOCH, hello.p.get( "epoch" ) );
		assertEquals( Long.valueOf( 7 ), hello.ack );
	}

	@Test
	void messagesBeforeWelcomeDontMoveTheAck() throws Exception {
		welcomed();
		session.onMessage( roomState( 9, "lobby" ) );
		session.onDisconnected( 1006, "", true );
		session.onConnected();
		//pre-WELCOME frames are numbered per connection (seq 1, 2, ...)
		session.onMessage( "{\"v\":1,\"t\":\"ERROR\",\"seq\":1,\"p\":{\"code\":\"RATE_LIMITED\",\"msg\":\"slow\"}}" );
		assertEquals( 9, session.ack() );
	}

	@Test
	void sameEpochKeepsTheHighestAckNewEpochResetsIt() throws Exception {
		welcomed();
		session.onMessage( roomState( 40, "lobby" ) );
		session.onDisconnected( 1006, "", true );
		session.onConnected();
		//same epoch: WELCOME (seq 44) then replayed events with their original, lower seq
		session.onMessage( welcome( 44, EPOCH, TOKEN, "" ) );
		session.onMessage( roomState( 41, "lobby" ) );
		assertEquals( 44, session.ack() );

		session.onDisconnected( 1006, "", true );
		session.onConnected();
		//server restarted: a new epoch numbers from 1 again
		session.onMessage( welcome( 1, EPOCH2, TOKEN, "" ) );
		assertEquals( 1, session.ack() );
		assertEquals( PID + "|" + TOKEN + "|" + EPOCH2, rec.credentials );
	}

	@Test
	void welcomeSnapshotWithARunReportsIt() throws Exception {
		session.onConnected();
		session.onMessage( welcome( 3, EPOCH, TOKEN, ",\"room\":" + ROOM_RUNNING + ",\"run\":" + RUN ) );
		assertEquals( State.RUNNING, session.state() );
		assertEquals( "run:c0ffee0123456789:snapshot", rec.events.get( rec.events.size() - 1 ) );
	}

	//regression (found by running the lobby UI, NET-01c): WndCoop redraws on onStateChanged and
	// crashed with "room is null" because the state changed before the room was delivered
	@Test
	void theRoomIsDeliveredBeforeTheStateChanges() throws Exception {
		welcomed();
		rec.events.clear();
		session.onMessage( roomState( 2, "lobby" ) );
		assertEquals( "room:lobby", rec.events.get( 0 ) );
		assertEquals( "state:IN_LOBBY", rec.events.get( 1 ) );

		rec.events.clear();
		session.onMessage( "{\"v\":1,\"t\":\"RUN_START\",\"seq\":3,\"p\":" + RUN + "}" );
		assertEquals( "run:c0ffee0123456789:live", rec.events.get( 0 ) );
		assertEquals( "state:RUNNING", rec.events.get( 1 ) );
	}

	@Test
	void runStartMovesToRunning() throws Exception {
		welcomed();
		session.onMessage( roomState( 2, "lobby" ) );
		assertEquals( State.IN_LOBBY, session.state() );
		session.onMessage( "{\"v\":1,\"t\":\"RUN_START\",\"seq\":3,\"p\":" + RUN + "}" );
		assertEquals( State.RUNNING, session.state() );
		assertTrue( rec.events.contains( "run:c0ffee0123456789:live" ) );
	}

	@Test
	void versionMismatchStopsForGood() throws Exception {
		session.onConnected();
		session.onMessage( "{\"v\":1,\"t\":\"REJECT\",\"seq\":1,\"p\":{\"code\":\"VERSION_MISMATCH\",\"msg\":\"update\",\"minGameVersionCode\":930}}" );
		assertEquals( State.STOPPED, session.state() );
		assertTrue( link.stopped );
		assertEquals( "stopped:VERSION_MISMATCH:930", rec.events.get( rec.events.size() - 1 ) );
		session.onConnected();
		assertFalse( session.createRoom() );
	}

	@Test
	void resumeFailedClearsCredentialsAndStartsFresh() throws Exception {
		session.restoreCredentials( PID, TOKEN, EPOCH, 12 );
		session.onConnected();
		assertEquals( TOKEN, link.last().p.get( "resumeToken" ) );
		session.onMessage( "{\"v\":1,\"t\":\"REJECT\",\"seq\":1,\"p\":{\"code\":\"RESUME_FAILED\",\"msg\":\"unknown token\"}}" );
		assertEquals( "null|null|null", rec.credentials );
		assertFalse( link.stopped, "RESUME_FAILED is not fatal" );
		session.onDisconnected( 4001, "", true );
		session.onConnected();
		Message hello = link.last();
		assertFalse( hello.p.containsKey( "resumeToken" ) );
		assertFalse( hello.p.containsKey( "epoch" ) );
		assertNull( hello.ack );
	}

	@Test
	void sessionReplacedStops() throws Exception {
		welcomed();
		session.onMessage( "{\"v\":1,\"t\":\"ERROR\",\"seq\":5,\"p\":{\"code\":\"SESSION_REPLACED\",\"msg\":\"newer connection\"}}" );
		assertEquals( State.STOPPED, session.state() );
		assertTrue( link.stopped );
	}

	@Test
	void ordinaryErrorsKeepTheConnection() throws Exception {
		welcomed();
		session.onMessage( "{\"v\":1,\"t\":\"ERROR\",\"seq\":5,\"ack\":2,\"p\":{\"code\":\"ROOM_NOT_FOUND\",\"msg\":\"no room\",\"ref_seq\":2}}" );
		assertEquals( State.READY, session.state() );
		assertTrue( rec.events.contains( "error:ROOM_NOT_FOUND" ) );
	}

	@Test
	void pingsEveryTenSecondsAndReconnectsWhenTheServerGoesQuiet() throws Exception {
		welcomed();
		int before = link.frames.size();
		now += CoopSession.PING_INTERVAL_MS - 1;
		session.tick( now );
		assertEquals( before, link.frames.size(), "no ping before 10 s" );
		now += 1;
		session.tick( now );
		Message ping = link.last();
		assertEquals( Protocol.PING, ping.t );
		assertEquals( Long.toString( now ), ping.p.get( "t" ).toString() );

		session.onMessage( "{\"v\":1,\"t\":\"PONG\",\"seq\":2,\"p\":{\"t\":" + now + ",\"serverTime\":5}}" );
		now += CoopSession.DEAD_AFTER_MS;
		session.tick( now );
		assertEquals( 0, link.reconnects, "exactly 30 s is still alive" );
		now += 1;
		session.tick( now );
		assertEquals( 1, link.reconnects );
	}

	@Test
	void presenceIsRelayedAndSentWithItsFloor() throws Exception {
		welcomed();
		session.onMessage( "{\"v\":1,\"t\":\"GHOST\",\"seq\":2,\"from\":\"" + ALLY + "\",\"depth\":3,\"branch\":0,"
				+ "\"p\":{\"pos\":120,\"anim\":\"run\",\"hpPct\":80,\"heroClass\":\"MAGE\",\"tier\":1}}" );
		assertTrue( rec.events.contains( "presence:GHOST:" + ALLY ) );
		assertTrue( session.ghost( 3, 0, 121, "idle", 100, "WARRIOR", 0 ) );
		Message g = link.last();
		assertEquals( Integer.valueOf( 3 ), g.depth );
		assertEquals( Integer.valueOf( 0 ), g.branch );
		assertTrue( session.quick( 5, null, null ) );
		assertNull( link.last().depth );
	}

	@Test
	void unknownTypesAndFieldsFromTheServerAreIgnored() throws Exception {
		welcomed();
		//TASK_DONE stays reserved (§5.4)
		session.onMessage( "{\"v\":1,\"t\":\"TASK_DONE\",\"seq\":2,\"p\":{}}" );
		session.onMessage( "{\"v\":1,\"t\":\"ALLY_STATUS\",\"seq\":3,\"future\":1,\"p\":{\"playerId\":\"" + ALLY
				+ "\",\"connected\":false,\"paused\":false,\"lastSeen\":5,\"extra\":true}}" );
		assertTrue( rec.events.contains( "ally:" + ALLY + ":false" ) );
		assertEquals( 3, session.ack() );
	}

	@Test
	void gameplayBroadcastsReachOnServerEvent() throws Exception {
		welcomed();
		session.onMessage( "{\"v\":1,\"t\":\"MISSION_START\",\"seq\":2,\"p\":{\"missionId\":\"0fedcba987654321\",\"kind\":\"CELL\","
				+ "\"prisoner\":\"" + PID + "\",\"rescuer\":\"" + ALLY + "\",\"depth\":3,\"branch\":0,\"trapCell\":1207,\"roomId\":7,"
				+ "\"keySeed\":1,\"startsAt\":2,\"deadline\":3,\"waveIntervalMs\":30000}}" );
		session.onMessage( "{\"v\":1,\"t\":\"CLAIMED\",\"seq\":3,\"p\":{\"itemNetId\":\"3:5\",\"by\":\"" + ALLY + "\"}}" );
		session.onMessage( "{\"v\":1,\"t\":\"SYNC_VIEW\",\"seq\":4,\"from\":\"" + ALLY + "\",\"depth\":3,\"branch\":0,"
				+ "\"p\":{\"syncId\":\"0aaaaaaaaaaaa001\",\"turn\":12,\"terrainDiff\":[],\"entities\":[]}}" );
		assertTrue( rec.events.contains( "event:MISSION_START" ) );
		assertTrue( rec.events.contains( "event:CLAIMED" ) );
		assertTrue( rec.events.contains( "event:SYNC_VIEW" ) );
	}

	@Test
	void gameplayRequestsAreValidC2SFrames() throws Exception {
		welcomed();
		assertTrue( session.missionTrigger( 3, 0, "CELL", 1207, 7 ) );
		{
			Message m = link.last();
			assertEquals( Protocol.MISSION_TRIGGER, m.t );
			assertEquals( Integer.valueOf( 3 ), m.depth );
			assertEquals( "7", m.p.get( "roomId" ).toString() );
		}

		assertTrue( session.claimsReq( 3, 1 ) );
		assertNull( link.last().depth, "CLAIMS_REQ carries the floor in its payload, not the envelope" );
		assertEquals( "1", link.last().p.get( "branch" ).toString() );

		assertTrue( session.give( 2, 0, "0123456789abcdef:1", "item", "blobish", null ) );
		assertEquals( "item", link.last().p.get( "kind" ) );
		assertTrue( session.give( 2, 0, "0123456789abcdef:2", "cell_key", null, 345 ) );
		Message key = link.last();
		assertFalse( key.p.containsKey( "item" ), "no blob with kind cell_key" );
		assertEquals( "345", key.p.get( "toCell" ).toString() );

		assertTrue( session.dropShare( 2, 0, 321, "0123456789abcdef:3", "blob" ) );
		assertTrue( session.downed( 4, 0, "SPIDER" ) );
		assertTrue( session.useRecall( 2, 0, ALLY, "healing" ) );
		assertTrue( session.syncDrink( 4, 0, "snapshot-data", 200 ) );
		assertTrue( session.syncView( 4, 0, "0aaaaaaaaaaaa001", 12, java.util.Arrays.asList( 340, 5 ), new ArrayList<>() ) );
		assertTrue( session.syncCmd( 4, 0, "0aaaaaaaaaaaa001", 12, "move", 403 ) );
		assertTrue( session.syncCmd( 4, 0, "0aaaaaaaaaaaa001", 13, "wait", null ) );
		assertFalse( link.last().p.containsKey( "cell" ), "wait carries no cell" );
		assertTrue( session.bossHit( 20, 0, 1, 12 ) );
		assertTrue( session.twinAct( 9, 0, 11, "switch", 778, true, 4L ) );
		assertTrue( session.twinAct( 9, 0, 3, "door", 610, true, null ) );
		assertFalse( link.last().p.containsKey( "groupId" ) );
		assertTrue( session.terrain( 1, 0, 208, Protocol.TILE_DOOR ) );
		assertEquals( "5", link.last().p.get( "tile" ).toString() );
	}

	@Test
	void anUnsentFrameDoesNotSkipASeq() throws Exception {
		welcomed();
		link.accept = false;
		assertFalse( session.createRoom() );
		link.accept = true;
		assertTrue( session.createRoom() );
		assertEquals( 2, link.last().seq );
	}

	// ---- helpers ----

	private static final String ROOM_RUNNING = "{\"code\":\"K7MZQ4\",\"phase\":\"running\",\"players\":["
			+ "{\"playerId\":\"" + PID + "\",\"slot\":0,\"heroClass\":\"WARRIOR\",\"ready\":true,\"connected\":true,\"platform\":\"desktop\"},"
			+ "{\"playerId\":\"" + ALLY + "\",\"slot\":1,\"heroClass\":\"MAGE\",\"ready\":true,\"connected\":false,\"platform\":\"android\"}]}";

	private static final String RUN = "{\"runId\":\"c0ffee0123456789\",\"seed\":123456789,\"roleSeed\":987654321,\"players\":["
			+ "{\"playerId\":\"" + PID + "\",\"slot\":0,\"heroClass\":\"WARRIOR\",\"platform\":\"desktop\"},"
			+ "{\"playerId\":\"" + ALLY + "\",\"slot\":1,\"heroClass\":\"MAGE\",\"platform\":\"android\"}]}";

	private void welcomed() {
		session.onConnected();
		session.onMessage( welcome( 1, EPOCH, TOKEN, "" ) );
	}

	private static String welcome( long seq, String epoch, String token, String extra ) {
		return "{\"v\":1,\"t\":\"WELCOME\",\"seq\":" + seq + ",\"ack\":1,\"p\":{\"playerId\":\"" + PID
				+ "\",\"resumeToken\":\"" + token + "\",\"epoch\":\"" + epoch
				+ "\",\"serverTime\":1,\"resumed\":false,\"replayed\":0,\"complete\":true" + extra + "}}";
	}

	private static String roomState( long seq, String phase ) {
		return "{\"v\":1,\"t\":\"ROOM_STATE\",\"seq\":" + seq + ",\"p\":{\"code\":\"K7MZQ4\",\"phase\":\"" + phase
				+ "\",\"players\":[{\"playerId\":\"" + PID + "\",\"slot\":0,\"ready\":false,\"connected\":true,\"platform\":\"desktop\"}]}}";
	}

	private static class FakeLink implements FrameLink {
		final List<String> frames = new ArrayList<>();
		boolean accept = true;
		boolean stopped = false;
		int reconnects = 0;

		@Override
		public boolean send( String frame ) {
			if (!accept) return false;
			frames.add( frame );
			return true;
		}

		@Override
		public void reconnect() {
			reconnects++;
		}

		@Override
		public void stop() {
			stopped = true;
		}

		//every frame the session sends must pass the server's c2s decoder
		Message last() throws Protocol.DecodeError {
			return Protocol.decode( Direction.C2S, frames.get( frames.size() - 1 ) );
		}
	}

	private static class Recorder implements CoopSession.Listener {
		final List<String> events = new ArrayList<>();
		String credentials;

		@Override
		public void onStateChanged( State state ) {
			events.add( "state:" + state );
		}

		@Override
		public void onCredentials( String playerId, String resumeToken, String epoch ) {
			credentials = playerId + "|" + resumeToken + "|" + epoch;
		}

		@Override
		public void onRoomState( Map<String, Object> room ) {
			events.add( "room:" + room.get( "phase" ) );
		}

		@Override
		public void onRunStart( Map<String, Object> run, boolean fromSnapshot ) {
			events.add( "run:" + run.get( "runId" ) + (fromSnapshot ? ":snapshot" : ":live") );
		}

		@Override
		public void onAllyStatus( Message status ) {
			events.add( "ally:" + status.str( "playerId" ) + ":" + status.bool( "connected" ) );
		}

		@Override
		public void onPresence( Message presence ) {
			events.add( "presence:" + presence.t + ":" + presence.from );
		}

		@Override
		public void onServerEvent( Message m ) {
			events.add( "event:" + m.t );
		}

		@Override
		public void onServerError( String code, String msg ) {
			events.add( "error:" + code );
		}

		@Override
		public void onStopped( String code, String msg, Long minGameVersionCode ) {
			events.add( "stopped:" + code + ":" + minGameVersionCode );
		}
	}
}
