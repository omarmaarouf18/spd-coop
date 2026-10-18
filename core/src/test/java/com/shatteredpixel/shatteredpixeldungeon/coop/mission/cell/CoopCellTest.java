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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell;

import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.CellTestFloors.ALLY;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.CellTestFloors.M1;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.CellTestFloors.M2;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.CellTestFloors.ME;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.CellTestFloors.SEED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.CoopItems;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.CoopMissions;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.AlarmTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

//MEC-01: the Binding Trap fires only with the ally on the floor, MISSION_START seals the
// prisoner's room (waves on the mission clock) and gives the rescuer the Cell Key at the keySeed's
// cell, the key goes out as GIVE{cell_key} only next to the cell's door, and every MISSION_END
// unseals and takes the key away
class CoopCellTest {

	static final long CAP = 240_000;
	static final long WAVE = 30_000;

	private FakeLink link;
	private Game previousGame;
	private long base;

	@BeforeEach
	void setUp() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		previousGame = Game.instance;
		new Game( null, null ) {};
		link = new FakeLink();
		CoopCell.link( link );
		CoopCell.install();
		//other suites may have swapped the reward out
		CoopMissions.reward( CoopCell.REWARD );
		CoopMissions.addListener( CoopCell.LISTENER );
		base = CoopMissions.serverNow();
		link.now = base;
	}

	@AfterEach
	void tearDown() {
		CoopCell.link( null );
		if (Dungeon.hero != null) Actor.remove( Dungeon.hero );
		Dungeon.hero = null;
		Game.instance = previousGame;
	}

	// ---- the trap ----

	@Test
	void theTrapFiresOnlyWithTheAllyOnTheFloorAndNoMission() {
		Level level = CellTestFloors.firstTrapFloor( SEED, HeroClass.WARRIOR );
		BindingTrap trap = CellTestFloors.bindingTraps( level ).get( 0 );
		genDoors = doorTiles( level, trap );
		heroOn( level, trap.pos );

		//ally away: a hidden trap stays silent (no "a hidden trap activates"), hidden and armed
		link.allyOn = false;
		assertFalse( CoopCell.armed( trap ) );
		level.pressCell( trap.pos );
		trap.trigger();
		assertTrue( link.triggers.isEmpty() );
		assertEquals( Terrain.SECRET_TRAP, level.map[trap.pos] );
		assertTrue( trap.active );
		assertFalse( trap.visible );

		//ally here: one MISSION_TRIGGER, on the GL thread, and the trap still waits for the server
		link.allyOn = true;
		assertTrue( CoopCell.armed( trap ) );
		//Level.pressCell would now log the line, interrupt the hero (needs a scene) and call this:
		trap.trigger();
		assertEquals( 1, link.triggers.size() );
		assertEquals( Dungeon.depth + " 0 " + trap.pos + " " + trap.roomId, link.triggers.get( 0 ) );
		assertEquals( 1, link.posts );
		assertTrue( trap.active );

		//DENY (nothing comes back): still armed, a second step asks again
		trap.trigger();
		assertEquals( 2, link.triggers.size() );

		//not the hero on the cell (a thrown item, a mob on a found trap): nothing
		Dungeon.hero.pos = trap.pos + 1;
		trap.trigger();
		assertEquals( 2, link.triggers.size() );

		//another trap kind is upstream's business
		Trap other = new AlarmTrap();
		assertTrue( CoopCell.armed( other ) );
		assertTrue( CoopCell.armed( null ) );
	}

	@Test
	void aRunningMissionKeepsTheTrapQuiet() {
		Level level = CellTestFloors.firstTrapFloor( SEED, HeroClass.WARRIOR );
		BindingTrap trap = CellTestFloors.bindingTraps( level ).get( 0 );
		genDoors = doorTiles( level, trap );
		heroOn( level, trap.pos );
		link.mission = new MissionState.Mission();
		assertFalse( CoopCell.armed( trap ) );
		trap.trigger();
		assertTrue( link.triggers.isEmpty() );
	}

	// ---- prisoner ----

	@Test
	void thePrisonersRoomSealsAndWavesFollowTheMissionClock() {
		Level level = CellTestFloors.firstTrapFloor( SEED, HeroClass.WARRIOR );
		BindingTrap trap = CellTestFloors.bindingTraps( level ).get( 0 );
		genDoors = doorTiles( level, trap );
		Hero hero = heroOn( level, trap.pos );
		int mobsBefore = level.mobs.size();
		HashSet<Mob> genMobs = new HashSet<>( level.mobs );

		event( CellTestFloors.start( M1, ME, ALLY, Dungeon.depth, trap, 77, base, base + CAP, WAVE ) );

		assertFalse( trap.active, "spent" );
		assertEquals( Terrain.INACTIVE_TRAP, level.map[trap.pos] );
		CellPrison prison = hero.buff( CellPrison.class );
		assertNotNull( prison );
		assertSealed( level, trap, true );

		//wave 0 at once, then nothing until the interval
		prison.act();
		assertEquals( mobsBefore + 1, level.mobs.size() );
		prison.act();
		assertEquals( mobsBefore + 1, level.mobs.size() );
		for (Mob mob : newMobs( level, genMobs )) assertTrue( trap.inRoom( mob.pos, level.width() ), "spawned inside the cell" );

		link.now = base + WAVE - 1;
		prison.act();
		assertEquals( mobsBefore + 1, level.mobs.size() );
		link.now = base + WAVE;
		prison.act();
		assertEquals( mobsBefore + 2, level.mobs.size(), "wave 1: tier 0, one mob" );

		//the clock is held (pause / link down): no waves however long
		link.running = false;
		link.now = base + 10 * WAVE;
		prison.act();
		assertEquals( mobsBefore + 2, level.mobs.size() );
		link.running = true;

		//paused by the server at 3 waves' worth: frozen there, at most one wave per turn
		event( CellTestFloors.update( M1, true, CAP - 2 * WAVE, null ) );
		prison.act();
		prison.act();
		assertEquals( mobsBefore + 2 + 2, level.mobs.size(), "wave 2 (tier 1: two mobs)" );
		prison.act();
		assertEquals( mobsBefore + 4, level.mobs.size() );

		//the doors stay locked; a door unlocked by an iron key locks again
		int plain = -1;
		for (int i = 0; i < trap.doors.length; i++) if (genDoors[i] != Terrain.SECRET_DOOR) plain = trap.doors[i];
		Level.set( plain, Terrain.DOOR, level );
		prison.act();
		assertEquals( Terrain.LOCKED_DOOR, level.map[plain] );

		event( CellTestFloors.failed( M1, 1 ) );
		assertSealed( level, trap, false );
		assertNull( hero.buff( CellPrison.class ) );
		int after = level.mobs.size();
		link.now = base + 100 * WAVE;
		assertEquals( after, level.mobs.size(), "no waves after the end" );
	}

	@Test
	void everyEndUnsealsTheRoom() {
		for (String result : new String[]{ "success", "failed", "aborted", "revived" }) {
			Level level = CellTestFloors.firstTrapFloor( SEED, HeroClass.WARRIOR );
			BindingTrap trap = CellTestFloors.bindingTraps( level ).get( 0 );
		genDoors = doorTiles( level, trap );
			Hero hero = heroOn( level, trap.pos );
			int[] before = doorTiles( level, trap );
			event( CellTestFloors.start( M1, ME, ALLY, Dungeon.depth, trap, 5, base, base + CAP, WAVE ) );
			assertSealed( level, trap, true );

			Message end = "success".equals( result ) ? CellTestFloors.success( M1, 42 )
					: "failed".equals( result ) ? CellTestFloors.failed( M1, 1 ) : CellTestFloors.ended( M1, result );
			event( end );
			assertNull( hero.buff( CellPrison.class ), result );
			int[] after = doorTiles( level, trap );
			for (int i = 0; i < before.length; i++) {
				int expected = before[i] == Terrain.OPEN_DOOR ? Terrain.DOOR : before[i];
				assertEquals( expected, after[i], result + ": door " + trap.doors[i] );
			}
			Actor.remove( hero );
		}
	}

	@Test
	void anEndOffTheFloorUnsealsWhenThePrisonerIsBack() {
		Level level = CellTestFloors.firstTrapFloor( SEED, HeroClass.WARRIOR );
		BindingTrap trap = CellTestFloors.bindingTraps( level ).get( 0 );
		genDoors = doorTiles( level, trap );
		Hero hero = heroOn( level, trap.pos );
		int depth = Dungeon.depth;
		event( CellTestFloors.start( M1, ME, ALLY, depth, trap, 5, base, base + CAP, WAVE ) );
		CellPrison prison = hero.buff( CellPrison.class );

		Dungeon.depth = depth + 1;
		event( CellTestFloors.ended( M1, "aborted" ) );
		assertNotNull( hero.buff( CellPrison.class ), "waits for the mission floor" );
		prison.act();
		assertNotNull( hero.buff( CellPrison.class ) );

		Dungeon.depth = depth;
		prison.act();
		assertNull( hero.buff( CellPrison.class ) );
		assertSealed( level, trap, false );
	}

	@Test
	void thePrisonBuffSurvivesASaveAndLoad() {
		Level level = CellTestFloors.firstTrapFloor( SEED, HeroClass.WARRIOR );
		BindingTrap trap = CellTestFloors.bindingTraps( level ).get( 0 );
		genDoors = doorTiles( level, trap );
		Hero hero = heroOn( level, trap.pos );
		event( CellTestFloors.start( M1, ME, ALLY, Dungeon.depth, trap, 5, base, base + CAP, WAVE ) );
		CellPrison prison = hero.buff( CellPrison.class );
		prison.act();

		Bundle b = new Bundle();
		b.put( "p", prison );
		CellPrison back = (CellPrison) roundTrip( b ).get( "p" );
		assertEquals( M1, back.missionId );
		assertEquals( 1, back.waves );
		assertEquals( trap.doors.length, back.doors.length );
		assertEquals( prison.original[0], back.original[0] );

		Bundle t = new Bundle();
		t.put( "t", trap );
		BindingTrap trapBack = (BindingTrap) roundTrip( t ).get( "t" );
		assertEquals( trap.roomId, trapBack.roomId );
		assertEquals( trap.left + " " + trap.top + " " + trap.right + " " + trap.bottom,
				trapBack.left + " " + trapBack.top + " " + trapBack.right + " " + trapBack.bottom );
		assertEquals( Arrays.toString( trap.doors ), Arrays.toString( trapBack.doors ) );
	}

	// ---- rescuer ----

	@Test
	void theRescuerGetsTheKeyAtTheSeedsCellAndGivesItAtTheDoor() {
		Level level = CellTestFloors.firstTrapFloor( SEED, HeroClass.MAGE );
		BindingTrap trap = CellTestFloors.bindingTraps( level ).get( 0 );
		genDoors = doorTiles( level, trap );
		Hero hero = heroOn( level, level.entrance() );
		long keySeed = 123456789012L;
		int expected = CellLayout.keyCell( level, trap, keySeed );
		assertTrue( expected >= 0 );

		event( CellTestFloors.start( M1, ALLY, ME, Dungeon.depth, trap, keySeed, base, base + CAP, WAVE ) );

		assertNull( hero.buff( CellPrison.class ), "the rescuer's room stays open" );
		assertSealed( level, trap, false );
		assertNotNull( hero.buff( CellRescue.class ) );
		Heap heap = level.heaps.get( expected );
		assertNotNull( heap, "the key lies at the keySeed's cell" );
		CellKey key = (CellKey) heap.items.getFirst();
		assertEquals( M1, key.missionId );
		assertEquals( CoopItems.LOCAL, CoopItems.idOf( key ), "local to the rescuer's world" );
		assertEquals( 1, link.pings.size() );
		assertEquals( Dungeon.depth + " 0 " + expected, link.pings.get( 0 ) );

		//the rescuer's own act never spawns a second one
		hero.buff( CellRescue.class ).act();
		assertEquals( 1, keys( level ).size() );

		//picked up; far from the door: no GIVE
		heap.pickUp();
		assertTrue( key.collect( hero.belongings.backpack ) );
		hero.pos = expected;
		assertFalse( CoopCell.useKey( hero, key ) );
		assertTrue( link.gives.isEmpty() );

		//next to a door of the cell (open in this world): GIVE{cell_key} with the key's id
		hero.pos = trap.doors[0];
		assertTrue( CoopCell.useKey( hero, key ) );
		assertEquals( 1, link.gives.size() );
		assertEquals( Dungeon.depth + " 0 " + ME + ":" + (CoopCell.KEY_COUNTER_BASE + Math.floorMod( keySeed, CoopCell.KEY_COUNTER_SPAN )),
				link.gives.get( 0 ) );
		assertTrue( link.gives.get( 0 ).split( " " )[2].matches( "[0-9a-f]{16}:[1-9][0-9]{0,9}" ), "a valid itemNetId" );

		//the key task is done: the key goes
		event( CellTestFloors.update( M1, false, CAP, "{\"key\":true}" ) );
		assertNull( hero.belongings.getItem( CellKey.class ) );
		assertNull( hero.buff( CellRescue.class ) );

		event( CellTestFloors.success( M1, 9 ) );
		assertSealed( level, trap, false );
	}

	@Test
	void theKeyCellIsTheSameEveryTimeAndAKeyGoesAtEveryEnd() {
		Level level = CellTestFloors.firstTrapFloor( SEED, HeroClass.MAGE );
		BindingTrap trap = CellTestFloors.bindingTraps( level ).get( 0 );
		genDoors = doorTiles( level, trap );
		int first = CellLayout.keyCell( level, trap, 31337 );
		//play changes nothing it reads (heaps, chars, the hero's position)
		level.drop( new Gold( 5 ), first );
		Hero hero = heroOn( level, level.entrance() );
		assertEquals( first, CellLayout.keyCell( level, trap, 31337 ) );
		assertEquals( first, CellLayout.keyCell( level, trap, 31337 + CellLayout.keyCandidates( level, trap ).length ) );

		event( CellTestFloors.start( M2, ALLY, ME, Dungeon.depth, trap, 31337, base, base + CAP, WAVE ) );
		assertEquals( 1, keys( level ).size() );
		event( CellTestFloors.failed( M2, 1 ) );
		assertTrue( keys( level ).isEmpty(), "the key goes with the mission" );
		assertNull( hero.buff( CellRescue.class ) );

		//a stray key of an old mission crumbles when picked up
		CellKey stale = new CellKey( M2 );
		assertFalse( CoopCell.keyValid( stale ) );
	}

	@Test
	void aRescuerOffTheFloorGetsTheKeyWhenTheyArrive() {
		Level level = CellTestFloors.firstTrapFloor( SEED, HeroClass.MAGE );
		BindingTrap trap = CellTestFloors.bindingTraps( level ).get( 0 );
		genDoors = doorTiles( level, trap );
		Hero hero = heroOn( level, level.entrance() );
		int depth = Dungeon.depth;
		Dungeon.depth = depth + 1;
		event( CellTestFloors.start( M1, ALLY, ME, depth, trap, 3, base, base + CAP, WAVE ) );
		assertTrue( keys( level ).isEmpty() );
		Dungeon.depth = depth;
		hero.buff( CellRescue.class ).act();
		assertEquals( 1, keys( level ).size() );
		assertNotNull( level.heaps.get( CellLayout.keyCell( level, trap, 3 ) ) );
	}

	// ---- vanilla ----

	@Test
	void vanillaRunsHaveNoBindingTrapsAndTheHooksAreNeutral() {
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		GamesInProgress.selectedClass = HeroClass.WARRIOR;
		Dungeon.init();
		assertFalse( Coop.isCoopRun() );
		for (int depth = 1; depth <= 9; depth++) {
			Dungeon.depth = depth;
			Dungeon.branch = 0;
			Level level = Dungeon.newLevel();
			assertTrue( CellTestFloors.bindingTraps( level ).isEmpty(), "depth " + depth );
		}
		assertEquals( 1f, CoopCell.accuracyFactor( Dungeon.hero ) );
		assertTrue( CoopCell.armed( new AlarmTrap() ) );
	}

	// ---- helpers ----

	private Hero heroOn( Level level, int cell ) {
		Hero hero = Dungeon.hero;
		hero.pos = cell;
		hero.HP = hero.HT;
		Actor.add( hero );
		return hero;
	}

	private void event( Message m ) {
		CoopMissions.onServerEvent( m );
	}

	//the doors as generated (a door to a vault is locked from the start and stays so)
	private int[] genDoors;

	private void assertSealed( Level level, BindingTrap trap, boolean sealed ) {
		assertTrue( trap.doors.length > 0 );
		if (genDoors == null) genDoors = doorTiles( level, trap );
		for (int i = 0; i < trap.doors.length; i++) {
			int door = trap.doors[i], tile = level.map[door], gen = genDoors[i];
			if (gen == Terrain.SECRET_DOOR) {
				assertEquals( Terrain.SECRET_DOOR, tile, "an unfound secret door stays hidden" );
			} else if (sealed) {
				assertEquals( Terrain.LOCKED_DOOR, tile, "door " + door );
			} else {
				assertEquals( gen == Terrain.OPEN_DOOR ? Terrain.DOOR : gen, tile, "door " + door );
			}
		}
	}

	private static int[] doorTiles( Level level, BindingTrap trap ) {
		int[] tiles = new int[trap.doors.length];
		for (int i = 0; i < tiles.length; i++) tiles[i] = level.map[trap.doors[i]];
		return tiles;
	}

	private static List<Mob> newMobs( Level level, HashSet<Mob> before ) {
		ArrayList<Mob> mobs = new ArrayList<>();
		for (Mob mob : level.mobs) if (!before.contains( mob )) mobs.add( mob );
		return mobs;
	}

	private static List<CellKey> keys( Level level ) {
		ArrayList<CellKey> keys = new ArrayList<>();
		for (Heap heap : level.heaps.valueList()) {
			for (Item item : heap.items) if (item instanceof CellKey) keys.add( (CellKey) item );
		}
		return keys;
	}

	//written and read back as bytes, like a save
	private static Bundle roundTrip( Bundle b ) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue( Bundle.write( b, out ) );
		try {
			return Bundle.read( new ByteArrayInputStream( out.toByteArray() ) );
		} catch (IOException e) {
			throw new AssertionError( e );
		}
	}

	static final class FakeLink implements CoopCell.Link {
		boolean allyOn = true;
		boolean running = true;
		boolean downed = false;
		long now;
		MissionState.Mission mission;
		int posts;
		final List<String> triggers = new ArrayList<>();
		final List<String> gives = new ArrayList<>();
		final List<String> pings = new ArrayList<>();

		@Override
		public String me() {
			return ME;
		}

		@Override
		public boolean allyOn( int depth, int branch ) {
			return allyOn;
		}

		@Override
		public boolean trigger( int depth, int branch, int trapCell, long roomId ) {
			triggers.add( depth + " " + branch + " " + trapCell + " " + roomId );
			return true;
		}

		@Override
		public boolean giveKey( int depth, int branch, String itemNetId ) {
			gives.add( depth + " " + branch + " " + itemNetId );
			return true;
		}

		@Override
		public MissionState.Mission mission() {
			return mission != null ? mission : CoopMissions.mission();
		}

		@Override
		public long serverNow() {
			return now;
		}

		@Override
		public boolean clockRunning() {
			return running;
		}

		@Override
		public boolean downed() {
			return downed;
		}

		@Override
		public void ping( int depth, int branch, int cell ) {
			pings.add( depth + " " + branch + " " + cell );
		}

		@Override
		public void post( Runnable r ) {
			posts++;
			r.run();
		}
	}
}
