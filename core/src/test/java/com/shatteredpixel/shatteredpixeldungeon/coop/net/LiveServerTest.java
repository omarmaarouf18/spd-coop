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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession.State;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

//two real clients (CoopClient + CoopSession) against a running server: the lobbyprobe flow,
// but through the game's own code. Skipped unless LLPD_SERVER_URL is set, e.g.
//   make server-dev   (another terminal)
//   LLPD_SERVER_URL=ws://localhost:8080/ws ./gradlew :core:test --tests '*LiveServerTest'
@EnabledIfEnvironmentVariable(named = "LLPD_SERVER_URL", matches = "wss?://.+")
class LiveServerTest {

	private static final long WAIT_S = 10;

	@Test
	void twoClientsCreateJoinStartAndResume() throws Exception {
		URI uri = URI.create( System.getenv( "LLPD_SERVER_URL" ) );
		Player a = new Player( "A", uri );
		Player b = new Player( "B", uri );
		try {
			a.start();
			a.await( "state:READY" );
			b.start();
			b.await( "state:READY" );

			assertTrue( a.session.createRoom() );
			String roomA = a.await( "room:" );
			String code = roomA.substring( "room:".length(), "room:".length() + 6 );
			System.out.println( "room code " + code );

			assertTrue( b.session.joinRoom( code.toLowerCase() ) );
			b.await( "room:" + code + ":lobby:2" );

			assertTrue( a.session.ready( "WARRIOR", true ) );
			assertTrue( b.session.ready( "MAGE", true ) );
			String runA = a.await( "run:" );
			String runB = b.await( "run:" );
			assertEquals( runA, runB, "both players get the same run and seed" );
			System.out.println( "RUN_START on both: " + runA );

			assertTrue( a.session.ghost( 1, 0, 42, "run", 100, "WARRIOR", 0 ) );
			String ghost = b.await( "presence:GHOST:" );
			assertEquals( "presence:GHOST:" + a.session.playerId(), ghost, "server sets from to the sender" );

			//B drops; A sees it; B comes back with its saved credentials
			b.client.stop();
			a.await( "ally:" + b.session.playerId() + ":false" );
			Player b2 = new Player( "B2", uri );
			b2.session.restoreCredentials( b.playerId, b.token, b.epoch, b.session.ack() );
			b2.start();
			String resumedRun = b2.await( "run:" );
			assertTrue( resumedRun.endsWith( ":snapshot" ), resumedRun );
			assertEquals( runA.replace( ":live", "" ), resumedRun.replace( ":snapshot", "" ) );
			a.await( "ally:" + b.session.playerId() + ":true" );
			assertEquals( b.playerId, b2.session.playerId() );
			System.out.println( "B resumed into the run as " + b2.session.playerId() );
			b2.stop();
		} finally {
			a.stop();
			b.stop();
		}
	}

	private static final class Player implements CoopSession.Listener {
		final String name;
		final BlockingQueue<String> events = new LinkedBlockingQueue<>();
		final ExecutorService main;
		final CoopSession session;
		final CoopClient client;
		String playerId, token, epoch;

		Player( String name, URI uri ) {
			this.name = name;
			this.main = Executors.newSingleThreadExecutor( r -> new Thread( r, "main-" + name ) );
			this.session = new CoopSession( 923, "desktop", this, CoopSession.SYSTEM_CLOCK );
			this.client = new CoopClient( uri, JavaWebSocketTransport::new, main::execute,
					new Backoff( 100, 400, new Random( 1 ) ), session, false );
			session.attach( client );
		}

		void start() {
			client.start();
		}

		void stop() {
			client.stop();
			main.shutdownNow();
		}

		//the next event starting with prefix (earlier, unrelated events are skipped)
		String await( String prefix ) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( WAIT_S );
			while (System.nanoTime() < deadline) {
				String e = events.poll( 100, TimeUnit.MILLISECONDS );
				if (e != null && e.startsWith( prefix )) return e;
			}
			assertNotNull( null, name + ": no '" + prefix + "' event within " + WAIT_S + "s" );
			return null;
		}

		@Override
		public void onStateChanged( State state ) {
			events.add( "state:" + state );
		}

		@Override
		public void onCredentials( String playerId, String resumeToken, String epoch ) {
			this.playerId = playerId;
			this.token = resumeToken;
			this.epoch = epoch;
		}

		@Override
		@SuppressWarnings("unchecked")
		public void onRoomState( Map<String, Object> room ) {
			events.add( "room:" + room.get( "code" ) + ":" + room.get( "phase" ) + ":"
					+ ((java.util.List<Object>) room.get( "players" )).size() );
		}

		@Override
		public void onRunStart( Map<String, Object> run, boolean fromSnapshot ) {
			events.add( "run:" + run.get( "runId" ) + ":" + run.get( "seed" ) + (fromSnapshot ? ":snapshot" : ":live") );
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
		public void onServerError( String code, String msg ) {
			events.add( "error:" + code );
		}

		@Override
		public void onStopped( String code, String msg, Long minGameVersionCode ) {
			events.add( "stopped:" + code );
		}
	}
}
