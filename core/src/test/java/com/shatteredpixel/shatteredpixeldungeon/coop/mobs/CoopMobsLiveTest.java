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

package com.shatteredpixel.shatteredpixeldungeon.coop.mobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
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
import com.shatteredpixel.shatteredpixeldungeon.levels.features.Chasm;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.util.ArrayList;
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

//MOB-01 end to end through a running server (SRV-03's kill registry): A's world kills a shared
// mob -> MOB_DEAD to both -> the mob leaves B's world with its XP; both report another mob at once
// -> one MOB_DEAD each; MOB_KILLS lists both, applied without a second XP. One JVM holds both
// worlds: B's world is the same seeded floor generated again with B's playerId. Skipped unless
// LLPD_SERVER_URL is set, e.g.
//   make server-dev   (another terminal)
//   LLPD_SERVER_URL=ws://localhost:8080/ws ./gradlew :core:test --tests '*CoopMobsLiveTest'
@EnabledIfEnvironmentVariable(named = "LLPD_SERVER_URL", matches = "wss?://.+")
class CoopMobsLiveTest {

	private static final long WAIT_S = 10;
	private static final long SEED = 123456789L;

	@AfterEach
	void tearDown() {
		CoopMobs.link( null );
	}

	@Test
	void aKillInOneWorldRemovesTheMobInTheOther() throws Exception {
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
			assertTrue( a.call( () -> a.session.ghost( 1, 0, 42, "idle", 100, "WARRIOR", 0 ) ) );
			assertTrue( b.call( () -> b.session.ghost( 1, 0, 43, "idle", 100, "MAGE", 0 ) ) );
			a.await( "presence:GHOST" );
			b.await( "presence:GHOST" );

			//A's world: enter floor 1 (MOB_KILLS_REQ -> empty), then a shared mob dies there
			Level aWorld = coopFloor1( a.session.playerId() );
			CoopMobs.link( a.link() );
			a.call( () -> {
				CoopMobs.onLevelEntered( aWorld );
				return null;
			} );
			Message emptyKills = a.next( Protocol.MOB_KILLS );
			assertTrue( ((List<?>) emptyKills.p.get( "mobNetIds" )).isEmpty() );
			Mob killed = sorted( aWorld ).get( 0 );
			Mob raced = sorted( aWorld ).get( 1 );
			String x = CoopMobs.idOf( killed ), y = CoopMobs.idOf( raced );
			killed.maxLvl = -5; //no loot roll (it needs a heap sprite)
			a.call( () -> {
				killed.die( Chasm.class );
				return null;
			} );
			Message deadAtA = a.next( Protocol.MOB_DEAD );
			Message deadAtB = b.next( Protocol.MOB_DEAD );
			assertEquals( x, deadAtB.str( "mobNetId" ) );
			assertEquals( a.session.playerId(), deadAtB.str( "by" ) );
			assertEquals( x, deadAtA.str( "mobNetId" ) );
			System.out.println( "room " + code + ": A killed " + x + " -> MOB_DEAD by=A to both" );

			//B's world: the same floor, its own places; A's kill arrives
			Level bWorld = coopFloor1( b.session.playerId() );
			CoopMobs.link( b.link() );
			b.call( () -> {
				CoopMobs.onLevelEntered( bWorld );
				return null;
			} );
			b.next( Protocol.MOB_KILLS ); //B's floor-entry request, may or may not list x yet
			int exp = Dungeon.hero.exp;
			Mob xInB = find( bWorld, x );
			assertNotNull( xInB );
			b.call( () -> {
				CoopMobs.onServerEvent( deadAtB );
				return null;
			} );
			assertFalse( bWorld.mobs.contains( xInB ), "gone from B's world" );
			assertEquals( exp + xInB.EXP, Dungeon.hero.exp, "B got its XP" );
			System.out.println( "B applied MOB_DEAD " + x + ": removed, +" + xInB.EXP + " XP" );

			//both worlds report y at once: the server keeps the first, each gets one MOB_DEAD
			a.call( () -> a.session.mobKilled( 1, 0, y ) );
			b.call( () -> b.session.mobKilled( 1, 0, y ) );
			Message yAtA = a.next( Protocol.MOB_DEAD ), yAtB = b.next( Protocol.MOB_DEAD );
			assertEquals( y, yAtA.str( "mobNetId" ) );
			assertEquals( yAtA.str( "by" ), yAtB.str( "by" ) );
			assertNull( a.maybe( Protocol.MOB_DEAD, 1 ), "A got a second MOB_DEAD (or a DENY)" );
			assertNull( b.maybe( Protocol.MOB_DEAD, 1 ), "B got a second MOB_DEAD (or a DENY)" );
			System.out.println( "race on " + y + ": one MOB_DEAD each, by=" + (yAtA.str( "by" ).equals( a.session.playerId() ) ? "A" : "B") );

			//B asks for the floor's kills: both listed; applying them gives no second XP for x
			assertTrue( b.call( () -> b.session.mobKillsReq( 1, 0 ) ) );
			Message kills = b.next( Protocol.MOB_KILLS );
			List<?> ids = (List<?>) kills.p.get( "mobNetIds" );
			assertTrue( ids.contains( x ) && ids.contains( y ), ids.toString() );
			Mob yInB = find( bWorld, y );
			int beforeKills = Dungeon.hero.exp;
			b.call( () -> {
				CoopMobs.onServerEvent( kills );
				return null;
			} );
			assertNull( find( bWorld, y ) );
			//y: B's own report raced A's; whichever won, y leaves B's world. Its XP comes only through
			// CoopMobs here (no local death ran), and x adds nothing again.
			int yExp = yInB == null ? 0 : yInB.EXP;
			assertEquals( beforeKills + yExp, Dungeon.hero.exp );
			System.out.println( "MOB_KILLS " + ids + " applied: +" + yExp + " XP (x not again)" );
		} finally {
			a.stop();
			b.stop();
		}
	}

	private static Level coopFloor1( String playerId ) {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		CoopTestRuns.begin( SEED, HeroClass.WARRIOR );
		Dungeon.init();
		assertTrue( Coop.isCoopRun() );
		Coop.run().playerId = playerId;
		Dungeon.depth = 1;
		Dungeon.branch = 0;
		Level level = Dungeon.newLevel();
		Dungeon.level = level;
		Dungeon.hero.pos = level.entrance();
		return level;
	}

	private static List<Mob> sorted( Level level ) {
		List<Mob> out = new ArrayList<>();
		for (Mob mob : level.mobs) {
			if (CoopMobs.idOf( mob ) != null) out.add( mob );
		}
		out.sort( (p, q) -> MobPlacement.compareIds( CoopMobs.idOf( p ), CoopMobs.idOf( q ) ) );
		return out;
	}

	private static Mob find( Level level, String id ) {
		for (Mob mob : level.mobs) {
			if (id.equals( CoopMobs.idOf( mob ) )) return mob;
		}
		return null;
	}

	private static final class Player implements CoopSession.Listener {
		final String name;
		final BlockingQueue<String> events = new LinkedBlockingQueue<>();
		final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
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

		<T> T call( Callable<T> c ) throws Exception {
			Future<T> f = main.submit( c );
			return f.get( WAIT_S, TimeUnit.SECONDS );
		}

		//this player's CoopMobs wiring: the real session; posts run inline (callers are on main)
		CoopMobs.Link link() {
			return new CoopMobs.Link() {
				@Override
				public String me() {
					return session.playerId();
				}

				@Override
				public boolean running() {
					return session.state() == State.RUNNING;
				}

				@Override
				public boolean mobKilled( int depth, int branch, String mobNetId ) {
					return session.mobKilled( depth, branch, mobNetId );
				}

				@Override
				public boolean mobKillsReq( int depth, int branch ) {
					return session.mobKillsReq( depth, branch );
				}

				@Override
				public void post( Runnable r ) {
					r.run();
				}

				@Override
				public boolean worldReady() {
					return true;
				}
			};
		}

		//the next server event of type t (others of that type queued before are consumed first)
		Message next( String t ) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( WAIT_S );
			while (System.nanoTime() < deadline) {
				Message m = messages.poll( 100, TimeUnit.MILLISECONDS );
				if (m != null && t.equals( m.t )) return m;
			}
			throw new AssertionError( name + ": no " + t + " within " + WAIT_S + "s" );
		}

		//a message of type t (or a DENY) arriving within s seconds, or null; others are skipped
		Message maybe( String t, long s ) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( s );
			while (System.nanoTime() < deadline) {
				Message m = messages.poll( 100, TimeUnit.MILLISECONDS );
				if (m != null && (t.equals( m.t ) || Protocol.DENY.equals( m.t ))) return m;
			}
			return null;
		}

		String await( String prefix ) throws InterruptedException {
			String e = poll( prefix, WAIT_S );
			assertNotNull( e, name + ": no '" + prefix + "' event within " + WAIT_S + "s" );
			return e;
		}

		String poll( String prefix, long s ) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( s );
			do {
				String e = events.poll( 100, TimeUnit.MILLISECONDS );
				if (e != null && e.startsWith( prefix )) return e;
			} while (System.nanoTime() < deadline);
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
			events.add( "event:" + m.t );
			messages.add( m );
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
