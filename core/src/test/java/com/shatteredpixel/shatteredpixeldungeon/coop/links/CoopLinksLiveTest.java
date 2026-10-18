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

package com.shatteredpixel.shatteredpixeldungeon.coop.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopTestRuns;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Backoff;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopClient;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession.State;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.JavaWebSocketTransport;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.Door;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

//LINK-01 end to end through a running server: A opens a door -> open in B's world; A walks out ->
// closed in both; nothing comes back to A. Both worlds are the same seeded floor, so one JVM holds
// "B's world" by resetting the door to what B had before applying B's TERRAIN. Skipped unless
// LLPD_SERVER_URL is set, e.g.
//   make server-dev   (another terminal)
//   LLPD_SERVER_URL=ws://localhost:8080/ws ./gradlew :core:test --tests '*CoopLinksLiveTest'
@EnabledIfEnvironmentVariable(named = "LLPD_SERVER_URL", matches = "wss?://.+")
class CoopLinksLiveTest {

	private static final long WAIT_S = 10;
	private static final long SEED = 123456789L;

	@AfterEach
	void tearDown() {
		CoopLinks.link( null );
	}

	@Test
	void aDoorOpenedAndClosedByAIsMirroredInBsWorld() throws Exception {
		URI uri = URI.create( System.getenv( "LLPD_SERVER_URL" ) );
		Player a = new Player( "A", uri );
		Player b = new Player( "B", uri );
		try {
			a.start();
			a.await( "state:READY" );
			b.start();
			b.await( "state:READY" );
			assertTrue( a.call( () -> a.session.createRoom() ) );
			String room = a.await( "room:" );
			String code = room.substring( "room:".length(), "room:".length() + 6 );
			assertTrue( b.call( () -> b.session.joinRoom( code ) ) );
			b.await( "room:" + code + ":lobby:2" );
			assertTrue( a.call( () -> a.session.ready( "WARRIOR", true ) ) );
			assertTrue( b.call( () -> b.session.ready( "MAGE", true ) ) );
			a.await( "run:" );
			b.await( "run:" );
			//both on depth 1 (the server learns floors from floor-scoped messages)
			assertTrue( a.call( () -> a.session.ghost( 1, 0, 42, "idle", 100, "WARRIOR", 0 ) ) );
			assertTrue( b.call( () -> b.session.ghost( 1, 0, 43, "idle", 100, "MAGE", 0 ) ) );
			a.await( "presence:GHOST" );
			b.await( "presence:GHOST" );

			Level level = coopFloor1();
			int door = -1;
			for (int i = 0; i < level.length() && door < 0; i++) {
				if (level.map[i] == Terrain.DOOR) door = i;
			}
			System.out.println( "room " + code + ", door cell " + door );

			//A's world: A steps into the door
			CoopLinks.link( a.link() );
			Door.enter( door );
			assertEquals( Terrain.OPEN_DOOR, level.map[door] );
			//B's world: the same door, still closed, gets A's TERRAIN
			Level.set( door, Terrain.DOOR, level );
			CoopLinks.link( b.link() );
			Message open = b.terrain();
			assertEquals( a.session.playerId(), open.from );
			CoopLinks.onServerEvent( open );
			assertEquals( Terrain.OPEN_DOOR, level.map[door], "open for B" );
			System.out.println( "B applied TERRAIN cell=" + open.num( "cell" ) + " tile=" + open.num( "tile" ) );

			//A's world (door open there too): A walks out, the door shuts
			CoopLinks.link( a.link() );
			Door.leave( door );
			assertEquals( Terrain.DOOR, level.map[door], "closed for A" );
			//B's world: open, gets the close
			Level.set( door, Terrain.OPEN_DOOR, level );
			CoopLinks.link( b.link() );
			Message close = b.terrain();
			CoopLinks.onServerEvent( close );
			assertEquals( Terrain.DOOR, level.map[door], "closed for B" );
			System.out.println( "B applied TERRAIN cell=" + close.num( "cell" ) + " tile=" + close.num( "tile" ) );

			//no echo: B's applied changes never went back to A
			assertNull( a.poll( "terrain:", 1 ), "A received a TERRAIN echo" );
		} finally {
			a.stop();
			b.stop();
		}
	}

	private static Level coopFloor1() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		CoopTestRuns.begin( SEED, HeroClass.WARRIOR );
		Dungeon.init();
		assertTrue( Coop.isCoopRun() );
		Dungeon.depth = 1;
		Dungeon.branch = 0;
		Level level = Dungeon.newLevel();
		Dungeon.level = level;
		return level;
	}

	private static final class Player implements CoopSession.Listener {
		final String name;
		final BlockingQueue<String> events = new LinkedBlockingQueue<>();
		final BlockingQueue<Message> terrains = new LinkedBlockingQueue<>();
		final ExecutorService main;
		final CoopSession session;
		final CoopClient client;

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

		//session calls run on this player's main thread, like the game's GL thread
		<T> T call( Callable<T> c ) throws Exception {
			Future<T> f = main.submit( c );
			return f.get( WAIT_S, TimeUnit.SECONDS );
		}

		//this player's CoopLinks wiring: the real session, the ally on our floor, a ready world
		CoopLinks.Link link() {
			return new CoopLinks.Link() {
				@Override
				public String me() {
					return session.playerId();
				}

				@Override
				public boolean allyOn( int depth, int branch ) {
					return depth == 1 && branch == 0;
				}

				@Override
				public boolean terrain( int depth, int branch, int cell, int tile ) {
					return session.terrain( depth, branch, cell, tile );
				}

				@Override
				public void post( Runnable r ) {
					try {
						call( () -> {
							r.run();
							return null;
						} );
					} catch (Exception e) {
						throw new AssertionError( e );
					}
				}

				@Override
				public boolean worldReady() {
					return true;
				}

				@Override
				public void redraw( Level level, int cell, int tile ) {
				}
			};
		}

		Message terrain() throws InterruptedException {
			Message m = terrains.poll( WAIT_S, TimeUnit.SECONDS );
			assertNotNull( m, name + ": no TERRAIN within " + WAIT_S + "s" );
			return m;
		}

		String await( String prefix ) throws InterruptedException {
			String e = poll( prefix, WAIT_S );
			assertNotNull( e, name + ": no '" + prefix + "' event within " + WAIT_S + "s" );
			return e;
		}

		//the next event starting with prefix within s seconds (unrelated events are skipped), or null
		String poll( String prefix, long s ) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( s );
			while (System.nanoTime() < deadline) {
				String e = events.poll( 100, TimeUnit.MILLISECONDS );
				if (e != null && e.startsWith( prefix )) return e;
			}
			return null;
		}

		@Override
		public void onStateChanged( State state ) {
			events.add( "state:" + state );
		}

		@Override
		public void onCredentials( String playerId, String resumeToken, String epoch ) {
		}

		@Override
		@SuppressWarnings("unchecked")
		public void onRoomState( Map<String, Object> room ) {
			events.add( "room:" + room.get( "code" ) + ":" + room.get( "phase" ) + ":"
					+ ((List<Object>) room.get( "players" )).size() );
		}

		@Override
		public void onRunStart( Map<String, Object> run, boolean fromSnapshot ) {
			events.add( "run:" + run.get( "runId" ) );
		}

		@Override
		public void onAllyStatus( Message status ) {
		}

		@Override
		public void onPresence( Message presence ) {
			events.add( "presence:" + presence.t );
		}

		@Override
		public void onServerEvent( Message m ) {
			if (Protocol.TERRAIN.equals( m.t )) {
				events.add( "terrain:" + m.num( "cell" ) + ":" + m.num( "tile" ) );
				terrains.add( m );
			}
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
