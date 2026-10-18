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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AllyBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Corruption;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Ghoul;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopTestRuns;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopRunSim;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelKey;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.Chasm;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.ShadowCaster;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GhoulSprite;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;
import com.watabou.utils.Point;
import com.watabou.utils.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

//MOB-01: shared mob ids, per-player places, kill sync (MOB_KILLED / MOB_DEAD / MOB_KILLS), XP
// exactly once, and nothing at all outside co-op runs
class CoopMobsTest {

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
		CoopMobs.link( link );
	}

	@AfterEach
	void tearDown() {
		CoopMobs.link( null );
		Game.instance = previousGame;
	}

	// ---- ids ----

	@Test
	void idsAreTheSameForEveryHeroClassAndSkipBosses() {
		Map<String, String> warrior = ids( CoopRunSim.start( SEED, HeroClass.WARRIOR, CoopRunSim.NOTHING, true ).generate( 1, 5 ) );
		Map<String, String> mage = ids( CoopRunSim.start( SEED, HeroClass.MAGE, CoopRunSim.NOTHING, true ).generate( 1, 5 ) );
		assertFalse( warrior.isEmpty() );
		assertEquals( warrior, mage );
		//floor 1: 8 pre-set mobs, numbered 0..7
		for (int i = 0; i < 8; i++) assertTrue( warrior.containsKey( "1:0:" + i ), "1:0:" + i );
		//floor 5 is Goo's: the boss has no id
		for (String cls : warrior.values()) assertFalse( cls.contains( "Goo" ), cls );
		//every id is a valid wire id
		for (String id : warrior.keySet()) assertTrue( validWireId( id ), id );
	}

	@Test
	void idTravelsWithTheMobBundle() throws IOException {
		Level level = coopFloor( 1, ME );
		Mob mob = shared( level ).get( 0 );
		String id = CoopMobs.idOf( mob );
		assertNotNull( id );
		Mob back = (Mob) reload( mob );
		assertEquals( id, CoopMobs.idOf( back ) );
	}

	@Test
	void runtimeMobsHaveNoIdAndAreNeverSent() {
		Level level = coopFloor( 1, ME );
		Rat rat = new Rat();
		rat.pos = freeCell( level );
		rat.maxLvl = -5;
		level.mobs.add( rat );
		Actor.add( rat );
		assertNull( CoopMobs.idOf( rat ) );
		rat.die( Chasm.class );
		assertTrue( link.sent.isEmpty(), link.sent.toString() );
	}

	// ---- different places ----

	@Test
	void relocationDiffersPerPlayerKeepsIdsAndIsValid() {
		Map<String, Integer> gen = positions( coopFloor( 3, ME ) );

		Level mine = coopFloor( 3, ME );
		assertEquals( gen, positions( mine ), "generation alone is the same for both players" );
		CoopMobs.onLevelEntered( mine );
		Map<String, Integer> minePos = positions( mine );
		assertValid( mine, minePos );

		Level theirs = coopFloor( 3, ALLY );
		CoopMobs.onLevelEntered( theirs );
		Map<String, Integer> theirPos = positions( theirs );
		assertValid( theirs, theirPos );

		assertEquals( minePos.keySet(), theirPos.keySet(), "same set of ids in both worlds" );
		assertEquals( classes( mine ), classes( theirs ), "same classes in both worlds" );
		assertNotEquals( minePos, theirPos, "the two players meet the mobs in different places" );
		assertNotEquals( gen, minePos, "some mobs moved" );

		//the same player gets the same places again (seeded, not random per launch)
		Level again = coopFloor( 3, ME );
		CoopMobs.onLevelEntered( again );
		assertEquals( minePos, positions( again ) );
	}

	@Test
	void relocationDoesNotTouchTheActiveGenerator() {
		Level level = coopFloor( 4, ME );
		Random.pushGenerator( 4242 );
		try {
			CoopMobs.onLevelEntered( level );
			long after = Random.Long();
			Random.pushGenerator( 4242 );
			long fresh = Random.Long();
			Random.popGenerator();
			assertEquals( fresh, after );
		} finally {
			Random.popGenerator();
		}
	}

	@Test
	void roomAnchoredMobsKeepTheirCell() {
		//every shared mob that isn't movable stays exactly where level gen put it
		for (int depth = 1; depth <= 4; depth++) {
			Level level = coopFloor( depth, ME );
			Map<Mob, Integer> before = new java.util.HashMap<>();
			RegularLevel regular = (RegularLevel) level;
			for (Mob mob : shared( level )) {
				if (!MobPlacement.movable( regular, regular.room( level.entrance() ), mob )) before.put( mob, mob.pos );
			}
			CoopMobs.onLevelEntered( level );
			for (Map.Entry<Mob, Integer> e : before.entrySet()) {
				assertEquals( (int) e.getValue(), e.getKey().pos, e.getKey().getClass().getSimpleName() );
			}
		}
	}

	@Test
	void aReloadedFloorIsNotMovedAgain() throws IOException {
		Level level = coopFloor( 3, ME );
		CoopMobs.onLevelEntered( level );
		Map<String, Integer> placed = positions( level );
		CoopRun saved = (CoopRun) reload( Coop.run() );
		Level back = (Level) reload( level );
		CoopTestRuns.resume( saved );
		Dungeon.init();
		Coop.run().playerId = ME;
		Dungeon.depth = 3;
		Dungeon.level = back;
		CoopMobs.onLevelEntered( back );
		assertEquals( placed, positions( back ) );
	}

	// ---- kills in this world ----

	@Test
	void aRealDeathSendsMobKilledOnce() {
		Level level = coopFloor( 1, ME );
		Mob mob = shared( level ).get( 0 );
		String id = CoopMobs.idOf( mob );
		mob.maxLvl = -5; //no loot roll (it needs a heap sprite)
		mob.die( Chasm.class );
		assertEquals( List.of( "MOB_KILLED " + id ), link.sent );
		assertEquals( 1, link.frames.size() );
		assertEquals( Integer.valueOf( 1 ), link.frames.get( 0 ).depth );
		//the server's MOB_DEAD of our own kill confirms it; nothing goes out again
		CoopMobs.onServerEvent( mobDead( id, ME ) );
		CoopMobs.flush();
		assertEquals( 1, link.sent.size() );
	}

	@Test
	void anUnsentKillWaitsForTheConnectionAndSurvivesASave() throws IOException {
		Level level = coopFloor( 1, ME );
		Mob mob = shared( level ).get( 1 );
		String id = CoopMobs.idOf( mob );
		mob.maxLvl = -5;
		link.running = false;
		mob.die( Chasm.class );
		assertTrue( link.sent.isEmpty() );
		CoopRun back = (CoopRun) reload( Coop.run() );
		assertTrue( back.mobs.unsent.contains( id ) );
		link.running = true;
		CoopMobs.onResumed();
		assertEquals( List.of( "MOB_KILLED " + id, "MOB_KILLS_REQ 1 0" ), link.sent );
	}

	@Test
	void corruptionCountsAsAKill() {
		Level level = coopFloor( 1, ME );
		Mob mob = shared( level ).get( 2 );
		String id = CoopMobs.idOf( mob );
		mob.maxLvl = -5; //no loot roll, no XP popup (it needs sprites)
		AllyBuff.affectAndLoot( mob, Dungeon.hero, Corruption.class );
		assertEquals( Char.Alignment.ALLY, mob.alignment );
		assertEquals( List.of( "MOB_KILLED " + id ), link.sent );
		//our corrupted ally stays in our world even when the server lists it as dead
		CoopMobs.onServerEvent( mobKills( 1, 0, id ) );
		assertTrue( level.mobs.contains( mob ) );
		//or when we come back to the floor
		CoopMobs.onLevelEntered( level );
		assertTrue( level.mobs.contains( mob ) );
		//and its later death sends nothing more
		mob.die( Chasm.class );
		assertEquals( 1, link.sent.stream().filter( s -> s.startsWith( "MOB_KILLED" ) ).count() );
	}

	@Test
	void aGhoulsFakeDeathIsNotAKill() throws Exception {
		Level level = coopFloor( 16, ME );
		Ghoul dying = new Ghoul(), host = new Ghoul();
		dying.pos = freeCell( level );
		host.pos = freeNeighbour( level, dying.pos );
		for (Ghoul g : new Ghoul[]{ dying, host }) {
			level.mobs.add( g );
			Actor.add( g );
		}
		CoopMobs.bind( dying, "16:0:900" );
		dying.sprite = (GhoulSprite) unsafe().allocateInstance( QuietGhoulSprite.class );
		dying.die( Dungeon.hero );
		assertTrue( dying.isAlive(), "the Ghoul is only down, waiting for its host" );
		assertTrue( link.sent.isEmpty(), link.sent.toString() );
	}

	// ---- the ally's kills ----

	@Test
	void allyKillOnOurFloorRemovesTheMobAndGrantsItsXpOnce() {
		Level level = coopFloor( 1, ME );
		CoopMobs.onLevelEntered( level );
		link.sent.clear();
		Mob mob = shared( level ).get( 0 );
		String id = CoopMobs.idOf( mob );
		int exp = Dungeon.hero.exp;

		CoopMobs.onServerEvent( mobDead( id, ALLY ) );
		assertFalse( level.mobs.contains( mob ) );
		assertFalse( Actor.all().contains( mob ) );
		assertTrue( level.heaps.valueList().stream().noneMatch( h -> h.pos == mob.pos && h.items.isEmpty() ) );
		assertEquals( exp + mob.EXP, Dungeon.hero.exp );

		//repeated MOB_DEAD, then the floor's MOB_KILLS: no second XP
		CoopMobs.onServerEvent( mobDead( id, ALLY ) );
		CoopMobs.onServerEvent( mobKills( 1, 0, id ) );
		assertEquals( exp + mob.EXP, Dungeon.hero.exp );
		assertTrue( Coop.run().mobs.xp.contains( id ) );
	}

	@Test
	void ourOwnKillThenTheAllysReportGivesNoSecondXp() {
		Level level = coopFloor( 1, ME );
		CoopMobs.onLevelEntered( level );
		Mob mine = shared( level ).get( 0 );
		String id = CoopMobs.idOf( mine );
		//the Mob.die hook as our world kills it (vanilla Mob.destroy then gives its XP; it needs
		// sprites, so the death itself isn't run here)
		CoopMobs.onDeath( mine );
		int exp = Dungeon.hero.exp;
		assertTrue( Coop.run().mobs.xp.contains( id ) );
		CoopMobs.onServerEvent( mobDead( id, ALLY ) ); //both worlds killed it at once
		assertEquals( exp, Dungeon.hero.exp );
	}

	@Test
	void allyKillOnAFloorNotEnteredYetGivesXpOnArrival() {
		coopFloor( 1, ME );
		CoopMobs.onLevelEntered( Dungeon.level );
		//the ally killed a mob of floor 2 before we got there
		Level two = generate( 2 );
		Mob mob = shared( two ).get( 0 );
		String id = CoopMobs.idOf( mob );
		CoopMobs.onServerEvent( mobDead( id, ALLY ) );
		assertTrue( Coop.run().mobs.pendingXp.contains( id ) );
		int exp = Dungeon.hero.exp;
		enter( two );
		assertFalse( two.mobs.contains( mob ) );
		assertEquals( exp + mob.EXP, Dungeon.hero.exp );
		assertFalse( Coop.run().mobs.pendingXp.contains( id ) );
	}

	@Test
	void allyKillOnAFloorWeLeftIsRemovedLaterWithoutXp() {
		Level one = coopFloor( 1, ME );
		CoopMobs.onLevelEntered( one );
		Mob mob = shared( one ).get( 3 );
		String id = CoopMobs.idOf( mob );
		Level two = generate( 2 );
		enter( two ); //we left floor 1
		link.ready = false; //(the floor-1 event arrives while we are on floor 2)
		CoopMobs.onServerEvent( mobDead( id, ALLY ) );
		link.ready = true;
		int exp = Dungeon.hero.exp;
		Dungeon.depth = 1;
		enter( one ); //back on floor 1
		assertFalse( one.mobs.contains( mob ) );
		assertEquals( exp, Dungeon.hero.exp );
	}

	@Test
	void mobKillsOnEntryAndAfterAResumeAreIdempotent() {
		Level level = coopFloor( 1, ME );
		CoopMobs.onLevelEntered( level );
		assertEquals( "MOB_KILLS_REQ 1 0", link.sent.get( link.sent.size() - 1 ) );
		Mob a = shared( level ).get( 0 ), b = shared( level ).get( 1 );
		String ia = CoopMobs.idOf( a ), ib = CoopMobs.idOf( b );
		int exp = Dungeon.hero.exp;
		//still our first stay on this floor: kills we missed while offline give their XP
		CoopMobs.onServerEvent( mobKills( 1, 0, ia, ib ) );
		assertFalse( level.mobs.contains( a ) );
		assertFalse( level.mobs.contains( b ) );
		assertEquals( exp + a.EXP + b.EXP, Dungeon.hero.exp );

		link.sent.clear();
		CoopMobs.onResumed();
		assertEquals( List.of( "MOB_KILLS_REQ 1 0" ), link.sent );
		CoopMobs.onServerEvent( mobKills( 1, 0, ia, ib ) );
		assertEquals( exp + a.EXP + b.EXP, Dungeon.hero.exp );
	}

	@Test
	void theGrantedSetSurvivesASave() throws IOException {
		Level level = coopFloor( 1, ME );
		CoopMobs.onLevelEntered( level );
		String id = CoopMobs.idOf( shared( level ).get( 0 ) );
		CoopMobs.onServerEvent( mobDead( id, ALLY ) );
		CoopRun back = (CoopRun) reload( Coop.run() );
		assertTrue( back.mobs.xp.contains( id ) );
		assertTrue( back.mobs.dead.contains( id ) );
		assertTrue( back.mobs.entered.contains( "1_0" ) );
	}

	// ---- vanilla ----

	@Test
	void nothingHappensOutsideCoopRuns() {
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
		for (Mob mob : level.mobs) assertNull( CoopMobs.idOf( mob ) );
		Mob mob = level.mobs.iterator().next();
		mob.maxLvl = -5;
		mob.die( Chasm.class );
		CoopMobs.onLevelEntered( level );
		CoopMobs.onServerEvent( mobDead( "1:0:0", ALLY ) );
		assertTrue( link.sent.isEmpty() );
	}

	// ---- helpers ----

	private Level coopFloor( int depth, String player ) {
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		CoopTestRuns.begin( SEED, HeroClass.WARRIOR );
		Dungeon.init();
		assertTrue( Coop.isCoopRun() );
		Coop.run().playerId = player;
		Level level = generate( depth );
		Dungeon.level = level;
		Dungeon.hero.pos = level.entrance();
		link.sent.clear();
		link.frames.clear();
		return level;
	}

	//generates depth of the current run (the cursor ends there)
	private static Level generate( int depth ) {
		Dungeon.depth = depth;
		Dungeon.branch = 0;
		return Dungeon.newLevel();
	}

	private static void enter( Level level ) {
		Dungeon.level = level;
		Dungeon.hero.pos = level.entrance();
		CoopMobs.onLevelEntered( level );
	}

	private static List<Mob> shared( Level level ) {
		List<Mob> out = new ArrayList<>();
		for (Mob mob : level.mobs) {
			if (CoopMobs.idOf( mob ) != null) out.add( mob );
		}
		out.sort( (a, b) -> MobPlacement.compareIds( CoopMobs.idOf( a ), CoopMobs.idOf( b ) ) );
		return out;
	}

	private static Map<String, Integer> positions( Level level ) {
		Map<String, Integer> out = new TreeMap<>();
		for (Mob mob : shared( level )) out.put( CoopMobs.idOf( mob ), mob.pos );
		return out;
	}

	private static Map<String, String> classes( Level level ) {
		Map<String, String> out = new TreeMap<>();
		for (Mob mob : shared( level )) out.put( CoopMobs.idOf( mob ), mob.getClass().getName() );
		return out;
	}

	private static Map<String, String> ids( CoopRunSim sim ) {
		Map<String, String> out = new TreeMap<>();
		for (Map.Entry<LevelKey, Level> e : sim.levels().entrySet()) {
			for (Mob mob : e.getValue().mobs) {
				String id = CoopMobs.idOf( mob );
				if (id != null) out.put( id, mob.pos + ":" + mob.getClass().getName() );
			}
		}
		return out;
	}

	private static boolean validWireId( String id ) {
		Message m = new Message( Protocol.MOB_KILLED ).put( "mobNetId", id );
		m.depth = 1;
		m.branch = 0;
		m.seq = 1;
		try {
			Protocol.decode( Direction.C2S, Protocol.encode( m ) );
			return true;
		} catch (Protocol.DecodeError e) {
			return false;
		}
	}

	private static void assertValid( Level level, Map<String, Integer> pos ) {
		boolean[] entranceFov = new boolean[level.length()];
		Point c = level.cellToPoint( level.entrance() );
		ShadowCaster.castShadow( c.x, c.y, level.width(), entranceFov, level.losBlocking, 8 );
		HashSet<Integer> used = new HashSet<>();
		for (Map.Entry<String, Integer> e : pos.entrySet()) {
			int cell = e.getValue();
			assertTrue( used.add( cell ), "two mobs on " + cell );
			assertTrue( level.passable[cell] && !level.solid[cell], e.getKey() + " on a blocked cell" );
			assertNull( level.traps.get( cell ), e.getKey() + " on a trap" );
			assertFalse( entranceFov[cell] && !entranceFovAllowed( level, cell ), e.getKey() + " in sight of the entrance" );
		}
	}

	//room-anchored mobs keep their generated cell, which createMobs' own rule already allowed
	private static boolean entranceFovAllowed( Level level, int cell ) {
		return level.heaps.get( cell ) != null || !(((RegularLevel) level).room( cell ) instanceof
				com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.StandardRoom);
	}

	private static int freeCell( Level level ) {
		for (int cell = 0; cell < level.length(); cell++) {
			if (level.passable[cell] && !level.solid[cell] && !level.pit[cell] && level.heaps.get( cell ) == null
					&& Dungeon.hero.pos != cell && level.findMob( cell ) == null) {
				return cell;
			}
		}
		throw new AssertionError( "no free cell" );
	}

	private static int freeNeighbour( Level level, int cell ) {
		for (int n : com.watabou.utils.PathFinder.NEIGHBOURS8) {
			int c = cell + n;
			if (level.passable[c] && !level.solid[c] && level.findMob( c ) == null && Dungeon.hero.pos != c) return c;
		}
		throw new AssertionError( "no free neighbour" );
	}

	private static Message mobDead( String id, String by ) {
		Message m = new Message( Protocol.MOB_DEAD ).put( "mobNetId", id ).put( "by", by );
		m.seq = 1;
		return roundTrip( m );
	}

	private static Message mobKills( int depth, int branch, String... ids ) {
		Message m = new Message( Protocol.MOB_KILLS ).put( "depth", depth ).put( "branch", branch )
				.put( "mobNetIds", java.util.Arrays.asList( ids ) );
		m.seq = 1;
		return roundTrip( m );
	}

	//through the real decoder, as CoopSession delivers it
	private static Message roundTrip( Message m ) {
		try {
			return Protocol.decode( Direction.S2C, Protocol.encode( m ) );
		} catch (Protocol.DecodeError e) {
			throw new AssertionError( e );
		}
	}

	private static Bundlable reload( Bundlable object ) throws IOException {
		Bundle bundle = new Bundle();
		bundle.put( "x", object );
		return Bundle.read( new ByteArrayInputStream( bundle.toString().getBytes( StandardCharsets.UTF_8 ) ) ).get( "x" );
	}

	private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
		Field f = sun.misc.Unsafe.class.getDeclaredField( "theUnsafe" );
		f.setAccessible( true );
		return (sun.misc.Unsafe) f.get( null );
	}

	//a GhoulSprite with no textures (allocated without its constructor): only crumple() is called
	static class QuietGhoulSprite extends GhoulSprite {
		@Override
		public void crumple() {}
	}

	private static final class FakeLink implements CoopMobs.Link {
		final List<String> sent = new ArrayList<>();
		final List<Message> frames = new ArrayList<>();
		boolean running = true;
		boolean ready = true;

		@Override
		public String me() {
			return Coop.run() == null ? ME : Coop.run().playerId;
		}

		@Override
		public boolean running() {
			return running;
		}

		@Override
		public boolean mobKilled( int depth, int branch, String mobNetId ) {
			if (!running) return false;
			Message m = new Message( Protocol.MOB_KILLED ).put( "mobNetId", mobNetId );
			m.depth = depth;
			m.branch = branch;
			m.seq = 1;
			try {
				frames.add( Protocol.decode( Direction.C2S, Protocol.encode( m ) ) );
			} catch (Protocol.DecodeError e) {
				throw new AssertionError( e );
			}
			sent.add( "MOB_KILLED " + mobNetId );
			return true;
		}

		@Override
		public boolean mobKillsReq( int depth, int branch ) {
			if (!running) return false;
			sent.add( "MOB_KILLS_REQ " + depth + " " + branch );
			return true;
		}

		@Override
		public void post( Runnable r ) {
			r.run();
		}

		@Override
		public boolean worldReady() {
			return ready;
		}
	}
}
