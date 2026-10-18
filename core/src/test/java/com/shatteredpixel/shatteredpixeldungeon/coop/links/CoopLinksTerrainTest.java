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
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopTestRuns;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.BindingTrap;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.CellPrison;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TimekeepersHourglass;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.ReclaimTrap;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.WornKey;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.DistortionTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.FlockTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.GatewayTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.SummoningTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.plants.Swiftthistle;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//LINK-02: unlocks, Skeleton Key locks, consumed and revealed traps go out once as
// TERRAIN{persist:true} (whether the ally is on the floor or not, queued while offline); the
// ally's changes apply forward-only, without echo, without firing a trap or using a key; the
// Binding Trap's trigger and the Cell's sealed doors are never mirrored; TERRAIN_REQ on entry and
// TERRAINS apply idempotently; applied changes survive a save/load; nothing at all in vanilla runs
class CoopLinksTerrainTest {

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
	void eachKeyUnlockSendsTheResultingTileOnceEvenWithTheAllyAway() {
		Level level = floor1( true );
		link.allyOn = false;
		int[] cells = besideHero( level, 4 );

		unlock( level, cells[0], Terrain.LOCKED_DOOR, () -> Notes.add( new IronKey( 1 ) ) );
		unlock( level, cells[2], Terrain.LOCKED_EXIT, () -> Notes.add( new WornKey( 1 ) ) );
		//a door locked with a Skeleton Key, forced open by a hero without one
		unlock( level, cells[3], Terrain.HERO_LKD_DR, () -> {} );
		//(the crystal door: Hero's crystal branch plays a particle effect that needs a GameScene
		// before the hook, so it is covered with the hook alone in skeletonKeyLockAndUnlocksSendOnce)
		assertEquals( Terrain.DOOR, level.map[cells[0]] );
		assertEquals( Terrain.UNLOCKED_EXIT, level.map[cells[2]] );
		assertEquals( Terrain.DOOR, level.map[cells[3]] );
		assertEquals( Arrays.asList(
				"P 1 0 " + cells[0] + " 5", "P 1 0 " + cells[2] + " 22", "P 1 0 " + cells[3] + " 5" ), link.sent );
		assertEquals( 0, Notes.keyCount( new IronKey( 1 ) ), "the key is used here, as in vanilla" );

		//no key: nothing changes, nothing goes out
		link.sent.clear();
		unlock( level, cells[0], Terrain.LOCKED_DOOR, () -> {} );
		assertEquals( Terrain.LOCKED_DOOR, level.map[cells[0]] );
		assertTrue( link.sent.isEmpty() );
	}

	@Test
	void skeletonKeyLockAndUnlocksSendOnce() {
		Level level = floor1( true );
		int[] cells = besideHero( level, 3 );
		//SkeletonKey's operate callbacks: Level.set, then the hook (the callback needs a sprite)
		skeletonKey( level, cells[0], Terrain.DOOR, Terrain.HERO_LKD_DR );
		skeletonKey( level, cells[1], Terrain.LOCKED_DOOR, Terrain.DOOR );
		skeletonKey( level, cells[2], Terrain.CRYSTAL_DOOR, Terrain.EMPTY );
		assertEquals( Arrays.asList( "P 1 0 " + cells[0] + " 38", "P 1 0 " + cells[1] + " 5", "P 1 0 " + cells[2] + " 1" ), link.sent );

		//anything else at the cell isn't a lock change
		link.sent.clear();
		Level.set( cells[0], Terrain.EMPTY_SP, level );
		CoopLinks.onLockChanged( cells[0] );
		assertTrue( link.sent.isEmpty() );
	}

	@Test
	void aConsumedTrapIsSentOnceAnArmedOneIsNot() {
		Level level = floor1( true );
		int[] cells = besideHero( level, 4 );

		TestTrap visible = trap( level, cells[0], true, true );
		visible.trigger();
		visible.trigger();
		assertEquals( 1, visible.fired, "it fires here" );
		assertEquals( Arrays.asList( "P 1 0 " + cells[0] + " 19" ), link.sent );

		//hidden and consumed: consumed is enough (the cell goes straight to INACTIVE_TRAP)
		link.sent.clear();
		trap( level, cells[1], false, true ).trigger();
		assertEquals( Arrays.asList( "P 1 0 " + cells[1] + " 19" ), link.sent );

		//stays armed (gateway, flashing, gripping): not consumed in either world; revealed if hidden
		link.sent.clear();
		trap( level, cells[2], true, false ).trigger();
		assertTrue( link.sent.isEmpty() );
		trap( level, cells[3], false, false ).trigger();
		assertEquals( Arrays.asList( "P 1 0 " + cells[3] + " 18" ), link.sent );
	}

	@Test
	void discoveringAHiddenTrapSendsItOnce() {
		Level level = floor1( true );
		int cell = besideHero( level, 1 )[0];
		TestTrap t = trap( level, cell, false, true );
		level.discover( cell );
		level.discover( cell );
		assertEquals( Terrain.TRAP, level.map[cell] );
		assertTrue( t.visible );
		assertEquals( Arrays.asList( "P 1 0 " + cell + " 18" ), link.sent );

		//a secret door found the same way stays per world
		link.sent.clear();
		int door = besideHero( level, 2 )[1];
		Level.set( door, Terrain.SECRET_DOOR, level );
		level.discover( door );
		assertEquals( Terrain.DOOR, level.map[door] );
		assertTrue( link.sent.isEmpty() );
	}

	@Test
	void theBindingTrapsTriggerAndTheCellsSealedDoorsAreNotMirrored() throws Exception {
		Level level = floor1( true );
		int[] cells = besideHero( level, 2 );
		BindingTrap binding = new BindingTrap();
		binding.doors = new int[]{ cells[1] };
		Level.set( cells[0], Terrain.SECRET_TRAP, level );
		level.setTrap( binding, cells[0] );

		//its trigger goes to MEC-01 (here: nobody to bind, so nothing); never a TERRAIN 19
		binding.trigger();
		assertTrue( link.sent.isEmpty() );
		//revealing it is mirrored
		level.discover( cells[0] );
		assertEquals( Arrays.asList( "P 1 0 " + cells[0] + " 18" ), link.sent );
		//the ally's world can't consume it
		link.sent.clear();
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, cells[0], Terrain.INACTIVE_TRAP ) );
		assertEquals( Terrain.TRAP, level.map[cells[0]] );
		assertTrue( binding.active );

		//we're the prisoner: the room's door is sealed (a locked door in this world only)
		Buff.affect( Dungeon.hero, CellPrison.class );
		Level.set( cells[1], Terrain.LOCKED_DOOR, level );
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, cells[1], Terrain.DOOR ) );
		CoopLinks.onServerEvent( terrains( 1, 0, cells[1], Terrain.OPEN_DOOR ) );
		assertEquals( Terrain.LOCKED_DOOR, level.map[cells[1]], "the seal holds" );
		//the prisoner's own iron key on it isn't sent either
		Notes.add( new IronKey( 1 ) );
		unlock( level, cells[1], Terrain.LOCKED_DOOR, () -> {} );
		assertEquals( Terrain.DOOR, level.map[cells[1]] );
		assertTrue( link.sent.isEmpty() );
	}

	//Trap.disarm is the one hook for 19, whatever used the trap up
	@Test
	void everyWayATrapIsUsedUpSends19ExactlyOnce() throws Exception {
		Level level = floor1( true );

		//a time freeze ending with the trap pressed: Timekeeper's Hourglass and Swiftthistle
		int[] c = besideHero( level, 8 );
		TestTrap frozen = trap( level, c[0], true, true );
		TimekeepersHourglass.timeFreeze freeze = new TimekeepersHourglass().new timeFreeze();
		freeze.setDelayedPress( c[0] );
		freeze.disarmPresses();
		TestTrap bubbled = trap( level, c[1], false, true );
		Swiftthistle.TimeBubble bubble = new Swiftthistle.TimeBubble();
		bubble.setDelayedPress( c[1] );
		bubble.disarmPresses();
		assertEquals( Arrays.asList( "P 1 0 " + c[0] + " 19", "P 1 0 " + c[1] + " 19" ), link.sent );
		assertEquals( 0, frozen.fired + bubbled.fired );

		//a Reclaim Trap takes it (even a trap that never disarms itself)
		link.sent.clear();
		GatewayTrap gateway = new GatewayTrap();
		gateway.reveal();
		Level.set( c[2], Terrain.TRAP, level );
		level.setTrap( gateway, c[2] );
		reclaim( c[2] );
		assertEquals( Arrays.asList( "P 1 0 " + c[2] + " 19" ), link.sent );

		//the same trap disarmed again (it's inactive now) sends nothing more
		gateway.disarm();
		assertEquals( 1, link.sent.size() );
	}

	//Summoning / Distortion: their own trigger (19) and the chained traps the spawned mobs land on
	// (19), each once. Every cell around the trap has a chained trap. Headless, the visuals after a
	// chain (ScrollOfTeleportation.appear needs a sprite) stop the activation.
	@Test
	void chainedTrapsSend19Once() throws Exception {
		for (Class<? extends Trap> type : Arrays.asList( SummoningTrap.class, DistortionTrap.class )) {
			//both traps roll their mobs on the gameplay RNG, and some rolls (a Wraith) need a sprite
			// before the chain: fixed seeds, in order, until one reaches a chained trap
			boolean reached = false;
			for (long seed = 1; seed <= 50 && !reached; seed++) {
				reached = chain( type, seed );
			}
			assertTrue( reached, type.getSimpleName() + ": no seed in 1..50 reached a chained trap" );
		}
	}

	//true if a chained trap went off; then every trap that went off sent 19 exactly once
	private boolean chain( Class<? extends Trap> type, long seed ) throws Exception {
		Level level = floor1( true );
		link.sent.clear();
		int center = -1;
		for (int i = 0; i < level.length() && center < 0; i++) {
			if (i != Dungeon.hero.pos && level.distance( i, Dungeon.hero.pos ) > 2 && isOpen( level, i )) center = i;
		}
		assertTrue( center >= 0, type.getSimpleName() + ": no open test cell" );
		Trap first = type.getDeclaredConstructor().newInstance();
		first.reveal();
		Level.set( center, Terrain.TRAP, level );
		level.setTrap( first, center );
		List<TestTrap> chained = new ArrayList<>();
		for (int n : PathFinder.NEIGHBOURS8) chained.add( trap( level, center + n, true, true ) );
		Random.pushGenerator( seed );
		try {
			first.trigger();
		} catch (NullPointerException headlessVisuals) {
			//a sprite or emitter without a GameScene (ScrollOfTeleportation.appear after the chain)
		} finally {
			Random.popGenerator();
		}
		List<String> expected = new ArrayList<>();
		expected.add( "P 1 0 " + center + " 19" );
		for (TestTrap t : chained) {
			if (!t.active) {
				expected.add( "P 1 0 " + t.pos + " 19" );
				assertEquals( 1, t.fired, type.getSimpleName() + " seed " + seed );
			}
		}
		assertEquals( expected, link.sent, type.getSimpleName() + " seed " + seed );
		return expected.size() >= 2;
	}

	//Flock: FlockTrap.activate draws a wool burst (CellEmitter, needs a GameScene) before it
	// reaches its chain, so headless it can't get there. Its chain is the same statement sequence as
	// Summoning's (disarm if disarmedByActivation, reveal, activate), replayed here on a real level.
	@Test
	void flocksChainStatementsSend19Once() {
		Level level = floor1( true );
		int[] c = besideHero( level, 2 );
		new FlockTrap().reveal();
		TestTrap t = trap( level, c[0], false, true );
		Trap found = level.traps.get( c[0] );
		if (found != null && found.active) {
			if (found.disarmedByActivation) found.disarm();
			found.reveal();
			found.activate();
		}
		TestTrap stays = trap( level, c[1], true, false );
		found = level.traps.get( c[1] );
		if (found != null && found.active) {
			if (found.disarmedByActivation) found.disarm();
			found.reveal();
			found.activate();
		}
		assertEquals( Arrays.asList( "P 1 0 " + c[0] + " 19" ), link.sent );
		assertEquals( 1, t.fired );
		assertEquals( 1, stays.fired, "a permanent trap fires and sends nothing" );
	}

	@Test
	void disarmSendsOnlyForThePlayedFloor() throws Exception {
		Level level = floor1( true );
		int cell = besideHero( level, 1 )[0];
		//a trap object that isn't the floor's (a Reclaim Trap's thrown copy, another level's)
		TestTrap loose = new TestTrap();
		loose.set( cell );
		Level.set( cell, Terrain.TRAP, level );
		loose.disarm();
		//the floor isn't on screen (level load, floor switch)
		TestTrap t = trap( level, cell, true, true );
		link.ready = false;
		t.disarm();
		link.ready = true;
		//the Binding Trap disarmed by MEC-01 on MISSION_START
		BindingTrap binding = new BindingTrap();
		int b = besideHero( level, 2 )[1];
		Level.set( b, Terrain.TRAP, level );
		level.setTrap( binding, b );
		binding.disarm();
		assertTrue( link.sent.isEmpty() );

		//an applied 19 from the ally doesn't come back (no echo through disarm)
		TestTrap other = trap( level, besideHero( level, 3 )[2], true, true );
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, other.pos, Terrain.INACTIVE_TRAP ) );
		assertFalse( other.active );
		assertTrue( link.sent.isEmpty() );

		//co-op level gen doesn't send either (it never runs on the played floor)
		Dungeon.depth = 2;
		Dungeon.newLevel();
		Dungeon.depth = 1;
		assertTrue( link.sent.isEmpty() );
	}

	// ---- receiving ----

	@Test
	void theAllysChangesApplyWithoutEchoFiringOrUsingAKey() throws Exception {
		Level level = floor1( true );
		int[] c = besideHero( level, 6 );
		Notes.add( new IronKey( 1 ) );
		Level.set( c[0], Terrain.LOCKED_DOOR, level );
		Level.set( c[1], Terrain.CRYSTAL_DOOR, level );
		Level.set( c[2], Terrain.LOCKED_EXIT, level );
		Level.set( c[3], Terrain.DOOR, level );
		TestTrap armed = trap( level, c[4], true, true );
		TestTrap hidden = trap( level, c[5], false, true );

		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[0], Terrain.DOOR ) );
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[1], Terrain.EMPTY ) );
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[2], Terrain.UNLOCKED_EXIT ) );
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[3], Terrain.HERO_LKD_DR ) );
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[4], Terrain.INACTIVE_TRAP ) );
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[5], Terrain.TRAP ) );

		assertEquals( Terrain.DOOR, level.map[c[0]] );
		assertEquals( Terrain.EMPTY, level.map[c[1]] );
		assertEquals( Terrain.UNLOCKED_EXIT, level.map[c[2]] );
		assertEquals( Terrain.HERO_LKD_DR, level.map[c[3]] );
		assertEquals( Terrain.INACTIVE_TRAP, level.map[c[4]] );
		assertFalse( armed.active );
		assertTrue( armed.visible );
		assertEquals( 0, armed.fired, "never fired in the receiving world" );
		assertEquals( Terrain.TRAP, level.map[c[5]] );
		assertTrue( hidden.visible );
		assertTrue( hidden.active );
		assertTrue( !level.passable[c[3]] && level.passable[c[0]], "cell flags follow" );

		assertEquals( 1, Notes.keyCount( new IronKey( 1 ) ), "no key used here" );
		assertTrue( link.sent.isEmpty(), "no echo" );
		assertEquals( 0, CoopLinksTerrainTest.pending() );
		assertEquals( 6, link.redrawn.size() );
	}

	@Test
	void onlyForwardChangesApply() throws Exception {
		Level level = floor1( true );
		int[] c = besideHero( level, 5 );
		trap( level, c[0], true, true );
		Level.set( c[0], Terrain.INACTIVE_TRAP, level );
		Level.set( c[1], Terrain.DOOR, level );
		Level.set( c[2], Terrain.EMBERS, level );
		Level.set( c[3], Terrain.DOOR, level );
		Level.set( c[4], Terrain.LOCKED_DOOR, level );
		int[] before = level.map.clone();

		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[0], Terrain.TRAP ) );          //a used trap can't re-arm
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[1], Terrain.UNLOCKED_EXIT ) ); //not an exit here
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[2], Terrain.DOOR ) );          //embers are final
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[1], Terrain.EMPTY ) );         //not a crystal door
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[4], Terrain.HERO_LKD_DR ) );   //not from a locked door
		CoopLinks.onServerEvent( persisted( ME, 1, 0, c[4], Terrain.DOOR ) );            //our own, relayed back
		CoopLinks.onServerEvent( persisted( ALLY, 2, 0, c[4], Terrain.DOOR ) );          //another floor
		CoopLinks.onServerEvent( persisted( ALLY, 1, 1, c[4], Terrain.DOOR ) );
		link.ready = false;
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[4], Terrain.DOOR ) );          //mid floor switch
		link.ready = true;
		//locking a door on a char
		Rat rat = new Rat();
		rat.pos = c[3];
		Actor.add( rat );
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[3], Terrain.HERO_LKD_DR ) );
		Actor.remove( rat );
		//a co-op tile is never applied
		assertFalse( CoopLinks.apply( level, c[1], 200 ) );

		assertTrue( Arrays.equals( before, level.map ) );
		assertTrue( link.redrawn.isEmpty() );
		assertTrue( link.sent.isEmpty() );
	}

	// ---- floor entry, resume, offline ----

	@Test
	void floorEntryAsksForTheRecordAndItAppliesIdempotently() throws Exception {
		Level level = floor1( true );
		int[] c = besideHero( level, 3 );
		Level.set( c[0], Terrain.LOCKED_DOOR, level );
		TestTrap t = trap( level, c[1], false, true );
		Level.set( c[2], Terrain.CRYSTAL_DOOR, level );

		CoopLinks.onLevelEntered( level );
		assertEquals( Arrays.asList( "REQ 1 0" ), link.sent );

		Message record = terrains( 1, 0, c[0], Terrain.DOOR, c[1], Terrain.INACTIVE_TRAP, c[2], Terrain.EMPTY );
		CoopLinks.onServerEvent( record );
		assertEquals( Terrain.DOOR, level.map[c[0]] );
		assertEquals( Terrain.INACTIVE_TRAP, level.map[c[1]] );
		assertFalse( t.active );
		assertEquals( 0, t.fired );
		assertEquals( Terrain.EMPTY, level.map[c[2]] );
		assertEquals( 3, link.redrawn.size() );

		//again (a second entry, a resume): nothing changes
		CoopLinks.onServerEvent( record );
		assertEquals( 3, link.redrawn.size() );
		//another floor's record
		Level.set( c[0], Terrain.LOCKED_DOOR, level );
		CoopLinks.onServerEvent( terrains( 2, 0, c[0], Terrain.DOOR ) );
		assertEquals( Terrain.LOCKED_DOOR, level.map[c[0]] );

		//a resume without a complete replay asks again
		link.sent.clear();
		CoopLinks.onResumed();
		assertEquals( Arrays.asList( "REQ 1 0" ), link.sent );
		assertEquals( 1, Collections.frequency( link.sent, "REQ 1 0" ) );
	}

	@Test
	void changesMadeOfflineGoOutInOrderBeforeTheRequest() throws Exception {
		Level level = floor1( true );
		int[] c = besideHero( level, 2 );
		link.online = false;
		Notes.add( new IronKey( 1 ) );
		unlock( level, c[0], Terrain.LOCKED_DOOR, () -> {} );
		trap( level, c[1], true, true ).trigger();
		CoopLinks.onLevelEntered( level );
		assertTrue( link.sent.isEmpty() );
		assertEquals( 3, pending() );

		link.online = true;
		//the first server event after the reconnect
		CoopLinks.onServerEvent( terrains( 5, 0 ) );
		assertEquals( Arrays.asList( "P 1 0 " + c[0] + " 5", "P 1 0 " + c[1] + " 19", "REQ 1 0" ), link.sent );
		assertEquals( 0, pending() );
		CoopLinks.onServerEvent( terrains( 5, 0 ) );
		assertEquals( 3, link.sent.size(), "sent once" );
	}

	@Test
	void offlineChangesSurviveASaveAndLoad() throws Exception {
		Level level = floor1( true );
		int[] c = besideHero( level, 2 );
		link.online = false;
		Notes.add( new IronKey( 1 ) );
		unlock( level, c[0], Terrain.LOCKED_DOOR, () -> {} );
		trap( level, c[1], true, true ).trigger();
		assertEquals( 2, pending() );

		//the game is closed and loaded again, still offline
		CoopRun run = Coop.run();
		Bundle saved = new Bundle();
		run.storeInBundle( saved );
		run.restoreFromBundle( Bundle.read( new ByteArrayInputStream( saved.toString().getBytes( StandardCharsets.UTF_8 ) ) ) );
		assertEquals( 2, pending(), "kept in the save" );
		assertTrue( link.sent.isEmpty() );

		//back online: sent in order, then gone (from the save too)
		link.online = true;
		CoopLinks.onResumed();
		assertEquals( Arrays.asList( "P 1 0 " + c[0] + " 5", "P 1 0 " + c[1] + " 19", "REQ 1 0" ), link.sent );
		assertEquals( 0, pending() );
		Bundle after = new Bundle();
		run.storeInBundle( after );
		assertEquals( 0, after.getBundle( "coop_terrain_outbox" ).getIntArray( "changes" ).length );

		//an old save has no outbox
		Bundle old = new Bundle();
		run.storeInBundle( old );
		old.remove( "coop_terrain_outbox" );
		run.restoreFromBundle( old );
		assertEquals( 0, pending() );
	}

	@Test
	void aRequestInFlightIsNotSentTwice() throws Exception {
		Level level = floor1( true );
		//entering the floor and the connection reaching RUNNING at the same time (both queued for the
		// GL thread, in either order): one request
		link.defer = true;
		CoopLinks.onLevelEntered( level );
		CoopLinks.onResumed();
		link.runDeferred();
		assertEquals( 1, Collections.frequency( link.sent, "REQ 1 0" ) );
		CoopLinks.onServerEvent( terrains( 1, 0 ) );
		link.sent.clear();
		CoopLinks.onResumed();
		CoopLinks.onLevelEntered( level );
		link.runDeferred();
		link.defer = false;
		assertEquals( 1, Collections.frequency( link.sent, "REQ 1 0" ) );
		//a save loaded offline: the entry's request waits, the connection sends it once
		link.sent.clear();
		link.online = false;
		CoopLinks.onServerEvent( terrains( 1, 0 ) );
		CoopLinks.onLevelEntered( level );
		link.online = true;
		CoopLinks.onResumed();
		assertEquals( Arrays.asList( "REQ 1 0" ), link.sent );
		link.sent.clear();

		//in flight, the same floor entered again on this connection: not sent again
		CoopLinks.onLevelEntered( level );
		assertTrue( link.sent.isEmpty() );
		//answered: the next entry asks again
		CoopLinks.onServerEvent( terrains( 1, 0 ) );
		CoopLinks.onLevelEntered( level );
		assertEquals( Arrays.asList( "REQ 1 0" ), link.sent );
		//not answered and the connection dropped: the reconnect asks again, once
		CoopLinks.onResumed();
		CoopLinks.onLevelEntered( level );
		assertEquals( Arrays.asList( "REQ 1 0", "REQ 1 0" ), link.sent );
	}

	@Test
	void appliedChangesSurviveASaveAndLoad() throws Exception {
		Level level = floor1( true );
		int[] c = besideHero( level, 3 );
		Level.set( c[0], Terrain.LOCKED_DOOR, level );
		trap( level, c[1], true, true );
		trap( level, c[2], false, true );
		CoopLinks.onServerEvent( terrains( 1, 0, c[0], Terrain.DOOR, c[1], Terrain.INACTIVE_TRAP, c[2], Terrain.TRAP ) );

		Bundle b = new Bundle();
		b.put( "level", level );
		Level back = (Level) Bundle.read( new ByteArrayInputStream( b.toString().getBytes( StandardCharsets.UTF_8 ) ) ).get( "level" );
		assertEquals( Terrain.DOOR, back.map[c[0]] );
		assertEquals( Terrain.INACTIVE_TRAP, back.map[c[1]] );
		assertFalse( back.traps.get( c[1] ).active );
		assertTrue( back.traps.get( c[1] ).visible );
		assertEquals( Terrain.TRAP, back.map[c[2]] );
		assertTrue( back.traps.get( c[2] ).visible );
		assertTrue( back.traps.get( c[2] ).active );
	}

	// ---- vanilla ----

	@Test
	void aVanillaRunSendsAndAppliesNothing() throws Exception {
		Level level = floor1( false );
		int[] c = besideHero( level, 3 );
		Notes.add( new IronKey( 1 ) );
		unlock( level, c[0], Terrain.LOCKED_DOOR, () -> {} );
		assertEquals( Terrain.DOOR, level.map[c[0]], "vanilla unlocking works" );
		TestTrap t = trap( level, c[1], true, true );
		t.trigger();
		assertEquals( 1, t.fired );
		assertEquals( Terrain.INACTIVE_TRAP, level.map[c[1]] );
		trap( level, c[2], false, true );
		level.discover( c[2] );
		assertEquals( Terrain.TRAP, level.map[c[2]] );
		CoopLinks.onLevelEntered( level );
		CoopLinks.onResumed();

		Level.set( c[0], Terrain.LOCKED_DOOR, level );
		CoopLinks.onServerEvent( persisted( ALLY, 1, 0, c[0], Terrain.DOOR ) );
		CoopLinks.onServerEvent( terrains( 1, 0, c[0], Terrain.DOOR ) );
		assertEquals( Terrain.LOCKED_DOOR, level.map[c[0]] );

		assertTrue( link.sent.isEmpty() );
		assertEquals( 0, link.posts );
		assertTrue( link.redrawn.isEmpty() );
	}

	// ---- helpers ----

	private static int pending() {
		return CoopLinks.outbox().pending();
	}

	private Level floor1( boolean coop ) {
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		if (coop) CoopTestRuns.begin( SEED, HeroClass.WARRIOR );
		Dungeon.init();
		assertEquals( coop, Coop.isCoopRun() );
		Dungeon.depth = 1;
		Dungeon.branch = 0;
		Level level = Dungeon.newLevel();
		Dungeon.level = level;
		PathFinder.setMapSize( level.width(), level.height() );
		Dungeon.hero.pos = openCell( level );
		Actor.add( Dungeon.hero );
		return level;
	}

	//a free floor cell whose 8 neighbours are free floor too (room for test tiles)
	private static int openCell( Level level ) {
		for (int i = 0; i < level.length(); i++) {
			if (!freeFloor( level, i )) continue;
			boolean ok = true;
			for (int n : PathFinder.NEIGHBOURS8) {
				if (!freeFloor( level, i + n )) ok = false;
			}
			if (ok) return i;
		}
		throw new AssertionError( "no open cell" );
	}

	private static boolean freeFloor( Level level, int i ) {
		if (i < 0 || i >= level.length() || !level.insideMap( i )) return false;
		int t = level.map[i];
		return (t == Terrain.EMPTY || t == Terrain.EMPTY_SP || t == Terrain.EMPTY_DECO || t == Terrain.GRASS)
				&& level.heaps.get( i ) == null && level.traps.get( i ) == null && Actor.findChar( i ) == null;
	}

	//ReclaimTrap.affectTarget at a trap cell; headless it stops after the disarm (no sprite, no user)
	private static void reclaim( int cell ) throws Exception {
		Method affect = ReclaimTrap.class.getDeclaredMethod( "affectTarget", Ballistica.class, com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero.class );
		affect.setAccessible( true );
		try {
			affect.invoke( new ReclaimTrap(), new Ballistica( Dungeon.hero.pos, cell, Ballistica.PROJECTILE ), Dungeon.hero );
		} catch (java.lang.reflect.InvocationTargetException e) {
			if (!(e.getCause() instanceof NullPointerException)) throw e;
		}
	}

	private static boolean isOpen( Level level, int cell ) {
		if (!freeFloor( level, cell )) return false;
		for (int n : PathFinder.NEIGHBOURS8) {
			if (cell + n != Dungeon.hero.pos && !freeFloor( level, cell + n )) return false;
		}
		return true;
	}

	private static int[] besideHero( Level level, int n ) {
		int[] cells = new int[n];
		for (int i = 0; i < n; i++) cells[i] = Dungeon.hero.pos + PathFinder.NEIGHBOURS8[i];
		return cells;
	}

	//Hero.onOperateComplete for an Unlock action on cell (a tile set there first)
	private static void unlock( Level level, int cell, int tile, Runnable key ) {
		Level.set( cell, tile, level );
		key.run();
		Dungeon.hero.curAction = new HeroAction.Unlock( cell );
		Dungeon.hero.onOperateComplete();
	}

	private static void skeletonKey( Level level, int cell, int from, int to ) {
		Level.set( cell, from, level );
		Level.set( cell, to, level );
		CoopLinks.onLockChanged( cell );
	}

	private static TestTrap trap( Level level, int cell, boolean visible, boolean consumed ) {
		TestTrap t = new TestTrap();
		t.disarmedByActivation = consumed;
		if (visible) t.reveal();
		else t.hide();
		Level.set( cell, visible ? Terrain.TRAP : Terrain.SECRET_TRAP, level );
		level.setTrap( t, cell );
		return t;
	}

	public static class TestTrap extends Trap {
		int fired;

		@Override
		public void activate() {
			fired++;
		}
	}

	private static Message persisted( String from, int depth, int branch, int cell, int tile ) throws Exception {
		Message m = new Message( Protocol.TERRAIN ).put( "cell", cell ).put( "tile", tile ).put( "persist", true );
		m.from = from;
		m.depth = depth;
		m.branch = branch;
		m.seq = 1;
		return Protocol.decode( Direction.S2C, Protocol.encode( m ) );
	}

	//cell, tile pairs
	private static Message terrains( int depth, int branch, int... pairs ) throws Exception {
		List<Map<String, Object>> changes = new ArrayList<>();
		for (int i = 0; i < pairs.length; i += 2) {
			Map<String, Object> c = new LinkedHashMap<>();
			c.put( "cell", new Message().put( "x", pairs[i] ).p.get( "x" ) );
			c.put( "tile", new Message().put( "x", pairs[i + 1] ).p.get( "x" ) );
			changes.add( c );
		}
		Message m = new Message( Protocol.TERRAINS ).put( "depth", depth ).put( "branch", branch ).put( "changes", changes );
		m.seq = 1;
		return Protocol.decode( Direction.S2C, Protocol.encode( m ) );
	}

	private static final class FakeLink implements CoopLinks.Link {
		//"P depth branch cell tile" per TERRAIN{persist}, "REQ depth branch" per TERRAIN_REQ
		final List<String> sent = new ArrayList<>();
		final List<String> redrawn = new ArrayList<>();
		boolean allyOn = true, ready = true, online = true, defer = false;
		int posts;
		final List<Runnable> deferred = new ArrayList<>();

		void runDeferred() {
			while (!deferred.isEmpty()) deferred.remove( 0 ).run();
		}

		@Override
		public String me() {
			return ME;
		}

		@Override
		public boolean allyOn( int depth, int branch ) {
			return allyOn;
		}

		@Override
		public boolean terrain( int depth, int branch, int cell, int tile ) {
			sent.add( "T " + depth + " " + branch + " " + cell + " " + tile );
			return true;
		}

		@Override
		public boolean terrainPersist( int depth, int branch, int cell, int tile ) {
			if (!online) return false;
			Message m = new Message( Protocol.TERRAIN ).put( "cell", cell ).put( "tile", tile ).put( "persist", true );
			m.depth = depth;
			m.branch = branch;
			c2s( m );
			sent.add( "P " + depth + " " + branch + " " + cell + " " + tile );
			return true;
		}

		@Override
		public boolean terrainReq( int depth, int branch ) {
			if (!online) return false;
			c2s( new Message( Protocol.TERRAIN_REQ ).put( "depth", depth ).put( "branch", branch ) );
			sent.add( "REQ " + depth + " " + branch );
			return true;
		}

		private static void c2s( Message m ) {
			m.seq = 1;
			try {
				Message back = Protocol.decode( Direction.C2S, Protocol.encode( m ) );
				if (Protocol.TERRAIN.equals( back.t )) assertTrue( Protocol.reliable( back ), "persisted TERRAIN is reliable" );
			} catch (Protocol.DecodeError e) {
				throw new AssertionError( e );
			}
		}

		@Override
		public void post( Runnable r ) {
			posts++;
			if (defer) deferred.add( r );
			else r.run();
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
