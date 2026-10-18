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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopTestRuns;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.Door;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

//LINK-01: the hero's door open/close (never a mob's) and any door burn go out as TERRAIN only
// while the ally is on the floor, a received TERRAIN changes only a regular door and never echoes,
// a door never shuts on a char or a heap, and nothing at all happens outside co-op runs
class CoopLinksTest {

	private static final long SEED = 123456789L;
	private static final String ME = "aaaaaaaaaaaaaaaa";
	private static final String ALLY = "bbbbbbbbbbbbbbbb";

	private FakeLink link;
	private Game previousGame;

	@BeforeEach
	void setUp() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		previousGame = Game.instance;
		//Level.drop asks Game for the current scene; a bare Game has none
		new Game( null, null ) {};
		link = new FakeLink();
		CoopLinks.link( link );
	}

	@AfterEach
	void tearDown() {
		CoopLinks.link( null );
		if (Dungeon.hero != null) Actor.remove( Dungeon.hero );
		Game.instance = previousGame;
	}

	// ---- sending ----

	@Test
	void doorOpenCloseAndBurnGoOutWhileTheAllyIsOnTheFloor() {
		Level level = coopFloor1();
		int door = doorCell( level, 0 );

		heroIn( door );
		assertEquals( Terrain.OPEN_DOOR, level.map[door] );
		heroOut( door, cellOf( level, Terrain.EMPTY ) );
		assertEquals( Terrain.DOOR, level.map[door] );
		level.destroy( door );
		assertEquals( Terrain.EMBERS, level.map[door] );

		assertEquals( Arrays.asList( "1 0 " + door + " 6", "1 0 " + door + " 5", "1 0 " + door + " 9" ), link.sent );
		assertEquals( 3, link.posts, "sends go out on the GL thread" );
	}

	@Test
	void onlyRegularDoorsGoOutAndOnlyWithTheAllyOnTheFloor() {
		Level level = coopFloor1();
		int a = doorCell( level, 0 ), b = doorCell( level, 1 );

		//ally offline or elsewhere: our door still works, nothing goes out
		link.allyOn = false;
		int away = Dungeon.hero.pos;
		heroIn( a );
		heroOut( a, away );
		level.destroy( a );
		assertEquals( Terrain.EMBERS, level.map[a] );
		assertTrue( link.sent.isEmpty() );
		assertEquals( 0, link.posts );
		link.allyOn = true;

		//a locked door (or a sealed Cell door) is not a regular door
		Level.set( b, Terrain.LOCKED_DOOR, level );
		CoopLinks.onDoor( b );
		level.destroy( b );
		assertEquals( Terrain.LOCKED_DOOR, level.map[b] );

		//other burnt terrain stays per-world
		int grass = cellOf( level, Terrain.EMPTY );
		level.destroy( grass );
		assertEquals( Terrain.EMBERS, level.map[grass] );

		//a door that stays open (a heap on it) sends no close
		int c = doorCell( level, 1 );
		heroIn( c );
		link.sent.clear();
		level.drop( new PotionOfHealing(), c );
		heroOut( c, away );
		assertEquals( Terrain.OPEN_DOOR, level.map[c] );

		assertTrue( link.sent.isEmpty(), link.sent.toString() );
	}

	@Test
	void onlyTheHerosDoorActionsGoOutButAnyBurnDoes() {
		Level level = coopFloor1();
		int a = doorCell( level, 0 ), b = doorCell( level, 1 );
		int away = Dungeon.hero.pos;

		//a mob walks through door a: it opens and shuts in our world only
		Rat rat = new Rat();
		rat.pos = a;
		Actor.add( rat );
		Door.enter( a );
		assertEquals( Terrain.OPEN_DOOR, level.map[a] );
		Door.leave( a );
		rat.pos = cellOf( level, Terrain.EMPTY );
		assertEquals( Terrain.DOOR, level.map[a] );
		assertTrue( link.sent.isEmpty(), link.sent.toString() );

		//the hero walks through door b: both go out
		heroIn( b );
		heroOut( b, away );
		assertEquals( Arrays.asList( "1 0 " + b + " 6", "1 0 " + b + " 5" ), link.sent );
		link.sent.clear();

		//knocked out of a doorway (the hero is already off the cell when Door.leave runs): sent
		heroIn( b );
		Dungeon.hero.pos = away;
		Door.leave( b );
		assertEquals( Arrays.asList( "1 0 " + b + " 6", "1 0 " + b + " 5" ), link.sent );
		link.sent.clear();

		//the hero teleported out of an open door, then a mob walks out of it: not ours
		heroIn( b );
		Dungeon.hero.pos = away;
		rat.pos = b;
		Door.leave( b );
		rat.pos = cellOf( level, Terrain.EMPTY );
		assertEquals( Terrain.DOOR, level.map[b] );
		assertEquals( Collections.singletonList( "1 0 " + b + " 6" ), link.sent );
		link.sent.clear();

		//a burn is mirrored whatever caused it, with nobody near the door
		level.destroy( a );
		assertEquals( Collections.singletonList( "1 0 " + a + " 9" ), link.sent );
		Actor.remove( rat );
	}

	// ---- receiving ----

	@Test
	void theAllysDoorChangesApplyHereWithoutEcho() throws Exception {
		Level level = coopFloor1();
		int door = doorCell( level, 0 );

		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, door, Terrain.OPEN_DOOR ) );
		assertEquals( Terrain.OPEN_DOOR, level.map[door] );
		assertFalse( level.losBlocking[door], "cell flags follow the tile" );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, door, Terrain.DOOR ) );
		assertEquals( Terrain.DOOR, level.map[door] );
		assertTrue( level.losBlocking[door] );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, door, Terrain.EMBERS ) );
		assertEquals( Terrain.EMBERS, level.map[door] );

		assertEquals( Arrays.asList( door + ":6", door + ":5", door + ":9" ), link.redrawn );
		assertTrue( link.sent.isEmpty(), "an applied change is never sent back" );
		assertEquals( 0, link.posts );

		//burnt is final: a late open/close from the ally changes nothing
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, door, Terrain.OPEN_DOOR ) );
		assertEquals( Terrain.EMBERS, level.map[door] );

		//after a received open, our own close goes out as usual (no loop: one message per change)
		int other = doorCell( level, 1 );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, other, Terrain.OPEN_DOOR ) );
		int away = Dungeon.hero.pos;
		Dungeon.hero.pos = other;
		heroOut( other, away );
		assertEquals( Collections.singletonList( "1 0 " + other + " 5" ), link.sent );
	}

	@Test
	void receivedChangesAreFilteredByFloorSenderTileAndCell() throws Exception {
		Level level = coopFloor1();
		int door = doorCell( level, 0 );

		//our own message, another depth or branch, a non-door tile, the world mid floor switch
		CoopLinks.onServerEvent( terrain( ME, 1, 0, door, Terrain.OPEN_DOOR ) );
		CoopLinks.onServerEvent( terrain( ALLY, 2, 0, door, Terrain.OPEN_DOOR ) );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 1, door, Terrain.OPEN_DOOR ) );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, door, Terrain.EMPTY ) );
		link.ready = false;
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, door, Terrain.OPEN_DOOR ) );
		link.ready = true;
		assertEquals( Terrain.DOOR, level.map[door] );

		//a locked door here (the ally unlocked theirs), and a cell that isn't a door here
		int locked = doorCell( level, 1 );
		Level.set( locked, Terrain.LOCKED_DOOR, level );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, locked, Terrain.OPEN_DOOR ) );
		assertEquals( Terrain.LOCKED_DOOR, level.map[locked] );
		int floor = cellOf( level, Terrain.EMPTY );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, floor, Terrain.EMBERS ) );
		assertEquals( Terrain.EMPTY, level.map[floor] );

		//another message type is not ours
		Message ping = terrain( ALLY, 1, 0, door, Terrain.OPEN_DOOR );
		ping.t = Protocol.DROP_SHARE;
		CoopLinks.onServerEvent( ping );
		assertEquals( Terrain.DOOR, level.map[door] );

		assertTrue( link.redrawn.isEmpty(), link.redrawn.toString() );
	}

	@Test
	void aDoorNeverShutsOnACharOrAHeap() throws Exception {
		Level level = coopFloor1();
		int a = doorCell( level, 0 ), b = doorCell( level, 1 );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, a, Terrain.OPEN_DOOR ) );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, b, Terrain.OPEN_DOOR ) );

		//our hero stands in door a
		Dungeon.hero.pos = a;
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, a, Terrain.DOOR ) );
		assertEquals( Terrain.OPEN_DOOR, level.map[a], "skipped: a char is in the doorway" );

		//an item lies in door b
		level.drop( new PotionOfHealing(), b );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, b, Terrain.DOOR ) );
		assertEquals( Terrain.OPEN_DOOR, level.map[b], "skipped: a heap is in the doorway" );

		//burning still goes through, occupied or not
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, a, Terrain.EMBERS ) );
		assertEquals( Terrain.EMBERS, level.map[a] );
		assertTrue( link.sent.isEmpty() );
	}

	// ---- vanilla ----

	@Test
	void nothingHappensInVanillaRuns() throws Exception {
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		GamesInProgress.selectedClass = HeroClass.WARRIOR;
		Dungeon.init();
		assertFalse( Coop.isCoopRun() );
		Dungeon.depth = 1;
		Dungeon.branch = 0;
		Level level = Dungeon.newLevel();
		Dungeon.level = level;
		int a = doorCell( level, 0 ), b = doorCell( level, 1 );

		Door.enter( a );
		Door.leave( a );
		level.destroy( a );
		CoopLinks.onServerEvent( terrain( ALLY, 1, 0, b, Terrain.OPEN_DOOR ) );

		assertEquals( Terrain.EMBERS, level.map[a], "vanilla doors work as before" );
		assertEquals( Terrain.DOOR, level.map[b] );
		assertTrue( link.sent.isEmpty() );
		assertEquals( 0, link.posts );
		assertEquals( 0, link.allyChecks );
		assertTrue( link.redrawn.isEmpty() );
	}

	// ---- helpers ----

	private Level coopFloor1() {
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
		Dungeon.hero.pos = cellOf( level, Terrain.EMPTY );
		Actor.add( Dungeon.hero );
		return level;
	}

	//the hero steps into a closed door (Char.move -> Level.occupyCell -> Door.enter)
	private static void heroIn( int door ) {
		Dungeon.hero.pos = door;
		Door.enter( door );
	}

	//the hero steps out of an open door (Char.move calls Door.leave before moving)
	private static void heroOut( int door, int to ) {
		Door.leave( door );
		Dungeon.hero.pos = to;
	}

	//the n-th regular closed door of the floor (seeded: the same cells every run)
	private static int doorCell( Level level, int n ) {
		for (int i = 0; i < level.length(); i++) {
			if (level.map[i] == Terrain.DOOR && n-- == 0) return i;
		}
		throw new AssertionError( "no door" );
	}

	private static int cellOf( Level level, int tile ) {
		for (int i = 0; i < level.length(); i++) {
			if (level.map[i] == tile && level.heaps.get( i ) == null && Actor.findChar( i ) == null
					&& (Dungeon.hero == null || Dungeon.hero.pos != i)) return i;
		}
		throw new AssertionError( "no cell" );
	}

	//a TERRAIN as the server relays it (PROTOCOL.md §5.10), through the real s2c decoder
	private static Message terrain( String from, int depth, int branch, int cell, int tile ) throws Exception {
		Message m = new Message( Protocol.TERRAIN ).put( "cell", cell ).put( "tile", tile );
		m.from = from;
		m.depth = depth;
		m.branch = branch;
		m.seq = 1;
		return Protocol.decode( Direction.S2C, Protocol.encode( m ) );
	}

	private static final class FakeLink implements CoopLinks.Link {
		//"depth branch cell tile" per TERRAIN sent
		final List<String> sent = new ArrayList<>();
		//"cell:tile" per redraw
		final List<String> redrawn = new ArrayList<>();
		boolean allyOn = true;
		boolean ready = true;
		int posts;
		int allyChecks;

		@Override
		public String me() {
			return ME;
		}

		@Override
		public boolean allyOn( int depth, int branch ) {
			allyChecks++;
			return allyOn;
		}

		@Override
		public boolean terrain( int depth, int branch, int cell, int tile ) {
			Message m = new Message( Protocol.TERRAIN ).put( "cell", cell ).put( "tile", tile );
			m.depth = depth;
			m.branch = branch;
			m.seq = 1;
			try {
				//must be a valid c2s frame
				Protocol.decode( Direction.C2S, Protocol.encode( m ) );
			} catch (Protocol.DecodeError e) {
				throw new AssertionError( e );
			}
			sent.add( depth + " " + branch + " " + cell + " " + tile );
			return true;
		}

		@Override
		public void post( Runnable r ) {
			posts++;
			r.run();
		}

		@Override
		public boolean worldReady() {
			return ready;
		}

		@Override
		public void redraw( Level level, int cell, int tile ) {
			redrawn.add( cell + ":" + tile );
		}
	}
}
