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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
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
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;

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

//LINK-02 end to end through a running server: while B is on another floor, A unlocks a door with
// an iron key and uses up a trap; B arrives (TERRAIN_REQ) and gets both in TERRAINS; with both on
// the floor, A discovers a hidden trap and B gets it live; nothing comes back to A. One JVM holds
// both worlds of the same seeded floor: B's tiles are put back to what B had before B applies.
// Skipped unless LLPD_SERVER_URL is set (see CoopLinksLiveTest).
@EnabledIfEnvironmentVariable(named = "LLPD_SERVER_URL", matches = "wss?://.+")
class CoopLinksTerrainLiveTest {

	private static final long WAIT_S = 10;
	private static final long SEED = 123456789L;

	@AfterEach
	void tearDown() {
		CoopLinks.link( null );
	}

	@Test
	void unlocksAndTrapsReachBOnArrivalAndLive() throws Exception {
		URI uri = URI.create( System.getenv( "LLPD_SERVER_URL" ) );
		Player a = new Player( "A", uri );
		Player b = new Player( "B", uri );
		Game previousGame = Game.instance;
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
			//A on depth 1, B still on depth 2
			assertTrue( a.call( () -> a.session.ghost( 1, 0, 42, "idle", 100, "WARRIOR", 0 ) ) );
			assertTrue( b.call( () -> b.session.ghost( 2, 0, 43, "idle", 100, "MAGE", 0 ) ) );
			a.await( "presence:GHOST" );

			Level level = coopFloor1();
			new Game( null, null ) {};
			int[] c = new int[3];
			for (int i = 0; i < 3; i++) c[i] = Dungeon.hero.pos + PathFinder.NEIGHBOURS8[i];
			System.out.println( "room " + code + ", cells " + c[0] + " " + c[1] + " " + c[2] );

			//A's world: an iron key opens c0, a trap on c1 goes off
			CoopLinks.link( a.link() );
			Level.set( c[0], Terrain.LOCKED_DOOR, level );
			Notes.add( new IronKey( 1 ) );
			Dungeon.hero.curAction = new HeroAction.Unlock( c[0] );
			Dungeon.hero.onOperateComplete();
			assertEquals( Terrain.DOOR, level.map[c[0]] );
			LiveTrap trap = trap( level, c[1], true );
			trap.trigger();
			assertEquals( Terrain.INACTIVE_TRAP, level.map[c[1]] );
			//B isn't on the floor: nothing relayed
			assertNull( b.poll( "terrain:", 1 ), "relayed to a player on another floor" );

			//B's world: still locked and armed; B arrives on depth 1
			Level.set( c[0], Terrain.LOCKED_DOOR, level );
			Level.set( c[1], Terrain.TRAP, level );
			trap.active = true;
			int fired = trap.fired;
			assertTrue( b.call( () -> b.session.ghost( 1, 0, 43, "idle", 100, "MAGE", 0 ) ) );
			a.await( "presence:GHOST" );
			CoopLinks.link( b.link() );
			CoopLinks.onLevelEntered( level );
			Message record = b.terrain();
			assertEquals( Protocol.TERRAINS, record.t );
			System.out.println( "B got TERRAINS " + record.p.get( "changes" ) );
			b.call( () -> {
				CoopLinks.onServerEvent( record );
				return null;
			} );
			assertEquals( Terrain.DOOR, level.map[c[0]], "unlocked for B" );
			assertEquals( Terrain.INACTIVE_TRAP, level.map[c[1]], "used up for B" );
			assertFalse( trap.active );
			assertEquals( fired, trap.fired, "never fired in B's world" );

			//both on the floor: A finds a hidden trap, B gets it live
			LiveTrap hidden = trap( level, c[2], false );
			CoopLinks.link( a.link() );
			level.discover( c[2] );
			assertEquals( Terrain.TRAP, level.map[c[2]] );
			Level.set( c[2], Terrain.SECRET_TRAP, level );
			hidden.visible = false;
			CoopLinks.link( b.link() );
			Message reveal = b.terrain();
			assertEquals( a.session.playerId(), reveal.from );
			assertTrue( reveal.bool( "persist" ) );
			b.call( () -> {
				CoopLinks.onServerEvent( reveal );
				return null;
			} );
			assertEquals( Terrain.TRAP, level.map[c[2]], "revealed for B" );
			assertTrue( hidden.visible );
			System.out.println( "B applied TERRAIN cell=" + reveal.num( "cell" ) + " tile=" + reveal.num( "tile" ) + " persist" );

			//no echo
			assertNull( a.poll( "terrain:", 1 ), "A received a TERRAIN echo" );
		} finally {
			if (Dungeon.hero != null) Actor.remove( Dungeon.hero );
			Game.instance = previousGame;
			a.stop();
			b.stop();
		}
	}

	private static LiveTrap trap( Level level, int cell, boolean visible ) {
		LiveTrap t = new LiveTrap();
		if (visible) t.reveal();
		else t.hide();
		Level.set( cell, visible ? Terrain.TRAP : Terrain.SECRET_TRAP, level );
		level.setTrap( t, cell );
		return t;
	}

	public static class LiveTrap extends Trap {
		int fired;

		@Override
		public void activate() {
			fired++;
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
		PathFinder.setMapSize( level.width(), level.height() );
		//an inside cell whose first three neighbours are plain floor
		for (int i = 0; i < level.length(); i++) {
			boolean ok = level.insideMap( i ) && level.passable[i] && level.traps.get( i ) == null && level.heaps.get( i ) == null;
			for (int n = 0; ok && n < 3; n++) {
				int j = i + PathFinder.NEIGHBOURS8[n];
				ok = level.map[j] == Terrain.EMPTY && level.traps.get( j ) == null && level.heaps.get( j ) == null;
			}
			if (ok && level.findMob( i ) == null) {
				Dungeon.hero.pos = i;
				break;
			}
		}
		Actor.add( Dungeon.hero );
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
				public boolean terrainPersist( int depth, int branch, int cell, int tile ) {
					return session.terrain( depth, branch, cell, tile, true );
				}

				@Override
				public boolean terrainReq( int depth, int branch ) {
					return session.terrainReq( depth, branch );
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
			} else if (Protocol.TERRAINS.equals( m.t )) {
				events.add( "terrains:" + m.num( "depth" ) + ":" + m.num( "branch" ) );
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
