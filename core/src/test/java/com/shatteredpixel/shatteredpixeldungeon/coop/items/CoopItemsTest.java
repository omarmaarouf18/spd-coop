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

package com.shatteredpixel.shatteredpixeldungeon.coop.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Statue;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopTestRuns;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopItemIds;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.VelvetPouch;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.Bomb;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blazing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Longsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.Dart;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//ITM-01: itemNetIds travel with items, claims (optimistic pickup, server verdict), CLAIMS on
// floor entry, DROP_SHARE, and nothing at all outside co-op runs
class CoopItemsTest {

	private static final long SEED = 123456789L;
	private static final String ME = "aaaaaaaaaaaaaaaa";
	private static final String ALLY = "bbbbbbbbbbbbbbbb";

	private FakeLink link;
	private FakeServer server;
	private Game previousGame;

	@BeforeEach
	void setUp() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		previousGame = Game.instance;
		//Level.drop asks Game for the current scene; a bare Game has none
		new Game( null, null ) {};
		link = new FakeLink();
		server = new FakeServer();
		CoopItems.link( link );
	}

	@AfterEach
	void tearDown() {
		CoopItems.link( null );
		Game.instance = previousGame;
	}

	// ---- ids ----

	@Test
	void netIdSurvivesBundleSaveReloadAndThrow() throws IOException {
		Level level = coopFloor1();
		Heap heap = firstLoot( level );
		Item item = heap.peek();
		String id = CoopItems.idOf( item );
		assertNotNull( id );
		assertTrue( id.startsWith( "1:0:" ), id );

		//the item alone, and inside its heap (how a level is saved)
		assertEquals( id, CoopItems.idOf( (Item) reload( item ) ) );
		Heap reloaded = (Heap) reload( heap );
		assertEquals( id, CoopItems.idOf( reloaded.peek() ) );

		//thrown: the instance lands on the floor with its id; with the ally on the floor it is
		// re-tagged as a runtime id before going out (the seeded id stays what level gen gave)
		Item thrown = new PotionOfHealing();
		CoopItems.bind( thrown, "1:0:777" );
		int cell = emptyCell( level );
		place( level, cell, thrown );
		CoopItems.onDropped( thrown, cell );
		assertEquals( ME + ":1", CoopItems.idOf( thrown ) );
		assertEquals( ME + ":1", CoopItems.idOf( (Item) reload( thrown ) ) );
	}

	@Test
	void mobHeldItemsKeepTheirIdAcrossReload() throws IOException {
		CoopRunSim sim = new CoopRunSim();
		int checked = 0;
		for (int depth = 1; depth <= 26 && checked < 2; depth++) {
			Level level = sim.floor( depth );
			for (Mob mob : level.mobs) {
				Item carried = mob instanceof Mimic && ((Mimic) mob).items != null && !((Mimic) mob).items.isEmpty()
						? ((Mimic) mob).items.get( 0 )
						: mob instanceof Statue ? ((Statue) mob).weapon() : null;
				if (carried == null) continue;
				String id = CoopItems.idOf( carried );
				assertNotNull( id, mob.getClass().getSimpleName() + " on depth " + depth );

				Mob back = (Mob) reload( mob );
				Item again = back instanceof Mimic ? ((Mimic) back).items.get( 0 ) : ((Statue) back).weapon();
				assertEquals( id, CoopItems.idOf( again ), mob.getClass().getSimpleName() + " on depth " + depth );
				//CoopItemIds alone (DET-01) never knew the reloaded instance
				assertNull( CoopItemIds.idOf( again ) );
				checked++;
			}
		}
		assertTrue( checked > 0, "no Mimic or Statue with loot on seed " + SEED );
	}

	@Test
	void runStatePersistsInCoopRun() throws IOException {
		coopFloor1();
		CoopItemsState s = Coop.run().items;
		s.counter = 41;
		s.pending.put( "1:0:3", new CoopItemsState.Pending( "1:0:3", 1, 0, PotionOfHealing.class.getName(), 1 ) );
		s.mine.add( "1:0:4" );
		s.allyTaken.add( "1:0:5" );
		s.received.add( ALLY + ":2" );

		CoopRun back = (CoopRun) reload( Coop.run() );
		assertEquals( 41, back.items.counter );
		assertEquals( Arrays.asList( "1:0:3" ), new ArrayList<>( back.items.pending.keySet() ) );
		assertEquals( 1, back.items.pending.get( "1:0:3" ).depth );
		assertTrue( back.items.mine.contains( "1:0:4" ) );
		assertTrue( back.items.allyTaken.contains( "1:0:5" ) );
		assertTrue( back.items.received.contains( ALLY + ":2" ) );
		assertFalse( back.items.legacy );

		//a run saved before ITM-01 has no coop_items: its floors get the DET-01 table once
		Bundle old = new Bundle();
		old.put( "run", Coop.run() );
		old.getBundle( "run" ).remove( "coop_items" );
		CoopRun legacy = (CoopRun) reload( old ).get( "run" );
		assertTrue( legacy.items.legacy );
	}

	// ---- claims ----

	@Test
	void claimRaceHasOneWinnerAndTheLoserIsRolledBack() {
		Level level = coopFloor1();
		Item a = loot( level, "1:0:900", new PotionOfHealing() ), b = loot( level, "1:0:901", new Longsword() );

		//the ally's claim of a reaches the server first
		server.claim( ALLY, 1, 0, "1:0:900" );
		pickUp( level, a );
		pickUp( level, b );
		assertEquals( Arrays.asList( "CLAIM 1:0:900", "CLAIMS_REQ 1 0", "CLAIM 1:0:901", "CLAIMS_REQ 1 0" ), link.sent );
		server.process( link );
		//and the ally loses b: its claim comes after ours
		server.claim( ALLY, 1, 0, "1:0:901" );
		server.deliver();

		assertFalse( holds( a ), "the loser's pickup is rolled back" );
		assertTrue( holds( b ), "the winner keeps it" );
		assertEquals( ALLY, server.owner.get( "1:0:900" ) );
		assertEquals( ME, server.owner.get( "1:0:901" ) );
		assertEquals( 1, server.deniedTo.get( ALLY ).size() );
		CoopItemsState s = Coop.run().items;
		assertTrue( s.pending.isEmpty() );
		assertTrue( s.wire.isEmpty() );
		assertTrue( s.allyTaken.contains( "1:0:900" ) );
		assertTrue( s.mine.contains( "1:0:901" ) );
	}

	@Test
	void denyWithoutAnIdIsMatchedToItsOwnClaim() {
		Level level = coopFloor1();
		Item a = loot( level, "1:0:900", new PotionOfHealing() ), b = loot( level, "1:0:901", new Longsword() ),
				c = loot( level, "1:0:902", new PotionOfStrength() );
		server.claim( ALLY, 1, 0, "1:0:901" );
		//the ally's CLAIMED broadcast is lost: only DENY{taken} (no itemNetId in v1) tells us
		server.toMe.clear();

		pickUp( level, a );
		pickUp( level, b );
		pickUp( level, c );
		server.process( link );
		server.deliver();

		assertTrue( holds( a ) );
		assertFalse( holds( b ) );
		assertTrue( holds( c ) );
		assertEquals( Arrays.asList( "1:0:900", "1:0:902" ), new ArrayList<>( Coop.run().items.mine ) );
	}

	@Test
	void stackAndGoldClaimsAreTakenBackToo() {
		Level level = coopFloor1();
		Hero hero = Dungeon.hero;
		Dart mine = new Dart();
		mine.quantity( 3 );
		assertTrue( mine.collect( hero.belongings.backpack ) );
		Dart darts = new Dart();
		darts.quantity( 5 );
		place( level, emptyCell( level ), darts );
		CoopItems.bind( darts, "1:0:910" );
		Gold gold = new Gold( 40 );
		place( level, emptyCell( level ), gold );
		CoopItems.bind( gold, "1:0:911" );
		Dungeon.gold = 100;
		CoopItems.onLevelEntered( level );
		server.process( link );
		server.deliver();

		server.claim( ALLY, 1, 0, "1:0:910" );
		server.claim( ALLY, 1, 0, "1:0:911" );
		pickUp( level, darts );
		pickUp( level, gold );
		assertEquals( 8, hero.belongings.getItem( Dart.class ).quantity() );
		assertEquals( 140, Dungeon.gold );

		server.process( link );
		server.deliver();
		assertEquals( 3, hero.belongings.getItem( Dart.class ).quantity(), "the merged darts go back" );
		assertEquals( 100, Dungeon.gold );

		//two seeded potions of one kind: we win the first, lose the second; one stays in the stack
		Item first = loot( level, "1:0:912" ), second = loot( level, "1:0:913" );
		CoopItems.onLevelEntered( level );
		server.process( link );
		server.deliver();
		server.claim( ALLY, 1, 0, "1:0:913" );
		pickUp( level, first );
		pickUp( level, second );
		assertEquals( 2, first.quantity() );
		server.process( link );
		server.deliver();
		assertTrue( holds( first ) );
		assertEquals( 1, first.quantity() );
	}

	@Test
	void ownClaimResentAfterAReconnectIsConfirmedByClaims() {
		Level level = coopFloor1();
		Item a = loot( level, "1:0:900" );
		pickUp( level, a );
		server.process( link );
		//the connection dropped before CLAIMED and CLAIMS arrived (best-effort / not replayed)
		server.toMe.clear();
		assertEquals( 1, Coop.run().items.pending.size() );

		link.now += CoopItems.STALE_MS + 1;
		CoopItems.flush();
		assertEquals( Arrays.asList( "CLAIM 1:0:900", "CLAIMS_REQ 1 0" ), link.sent );
		server.process( link );
		//re-claiming our own: the server stays silent, CLAIMS lists it
		assertEquals( 1, server.toMe.size() );
		server.deliver();

		assertTrue( holds( a ) );
		assertTrue( Coop.run().items.pending.isEmpty() );
		assertTrue( Coop.run().items.mine.contains( "1:0:900" ) );
	}

	@Test
	void offlinePickupIsClaimedOnReconnectAndTheServerDecides() {
		Level level = coopFloor1();
		Item a = loot( level, "1:0:900" );
		link.running = false;
		pickUp( level, a );
		assertTrue( link.sent.isEmpty() );
		assertTrue( holds( a ), "picked up locally while offline" );
		assertTrue( Coop.run().items.pending.containsKey( "1:0:900" ) );

		//meanwhile the ally (online) took it
		server.claim( ALLY, 1, 0, "1:0:900" );
		server.toMe.clear();
		link.running = true;
		CoopItems.flush();
		server.process( link );
		server.deliver();
		assertFalse( holds( a ) );
		assertTrue( Messages.get( CoopItems.class, "ally_first", "x" ).startsWith( "Your ally got it first" ) );
	}

	@Test
	void mobDropsAndLocalItemsAreNeverClaimed() {
		Level level = coopFloor1();
		Item drop = new PotionOfHealing();
		place( level, emptyCell( level ), drop );
		Item local = new PotionOfStrength();
		CoopItems.markLocal( local );
		place( level, emptyCell( level ), local );
		Item key = new IronKey( 1 );
		CoopItems.bind( key, "1:0:920" );
		place( level, emptyCell( level ), key );

		pickUp( level, drop );
		pickUp( level, local );
		Heap keyHeap = level.heaps.get( heapCell( level, key ) );
		Dungeon.hero.pos = keyHeap.pos;
		Dungeon.hero.curAction = new HeroAction.PickUp( keyHeap.pos );
		keyHeap.pickUp();
		assertTrue( link.sent.isEmpty() );
		assertTrue( Coop.run().items.pending.isEmpty() );
	}

	@Test
	void trapsMovingHeapItemsDontClaim() {
		Level level = coopFloor1();
		Item a = loot( level, "1:0:900" );
		Heap heap = level.heaps.get( heapCell( level, a ) );
		//the hero is elsewhere and not picking up: a teleportation trap / well moving the item
		Dungeon.hero.curAction = null;
		heap.pickUp();
		assertTrue( link.sent.isEmpty() );
	}

	// ---- CLAIMS / CLAIMED: the ally's pickups reach our world ----

	@Test
	void claimsOnFloorEntryRemovesWhatTheAllyTook() {
		Level level = coopFloor1();
		List<Item> loot = new ArrayList<>();
		for (Heap heap : level.heaps.valueList()) {
			for (Item item : heap.items) {
				if (heap.type == Heap.Type.HEAP && !(item instanceof IronKey) && CoopItems.idOf( item ) != null) loot.add( item );
			}
		}
		assertTrue( loot.size() >= 3, "floor 1 has " + loot.size() + " loot items" );
		Item taken1 = loot.get( 0 ), taken2 = loot.get( 1 ), kept = loot.get( 2 );
		server.claim( ALLY, 1, 0, CoopItems.idOf( taken1 ) );
		server.claim( ALLY, 1, 0, CoopItems.idOf( taken2 ) );
		server.toMe.clear();

		CoopItems.onLevelEntered( level );
		assertEquals( Arrays.asList( "CLAIMS_REQ 1 0" ), link.sent );
		server.process( link );
		server.deliver();

		assertEquals( -1, heapCell( level, taken1 ) );
		assertEquals( -1, heapCell( level, taken2 ) );
		assertNotEquals( -1, heapCell( level, kept ) );

		//entering again (or after a reload) removes them locally even before the server answers
		Item again = new PotionOfHealing();
		CoopItems.bind( again, CoopItems.idOf( taken1 ) );
		place( level, emptyCell( level ), again );
		link.running = false;
		CoopItems.onLevelEntered( level );
		assertEquals( -1, heapCell( level, again ) );
	}

	@Test
	void allysClaimRemovesTheItemFromOurCurrentFloor() {
		Level level = coopFloor1();
		Item a = loot( level, "1:0:900" );
		server.claim( ALLY, 1, 0, "1:0:900" );
		server.deliver();
		assertEquals( -1, heapCell( level, a ) );
		assertTrue( Coop.run().items.allyTaken.contains( "1:0:900" ) );
	}

	// ---- DROP_SHARE ----

	@Test
	void dropShareRoundTripsAnItem() throws Exception {
		Longsword sword = new Longsword();
		sword.upgrade( 2 );
		sword.enchant( new Blazing() );
		sword.cursed = true;
		sword.cursedKnown = true;
		sword.levelKnown = true;
		Item back = ItemCodec.decode( ItemCodec.encode( sword ) );
		assertEquals( Longsword.class, back.getClass() );
		assertEquals( 2, back.level() );
		assertEquals( Blazing.class, ((Weapon) back).enchantment.getClass() );
		assertTrue( back.cursed );
		assertTrue( back.cursedKnown );
		assertTrue( back.levelKnown );

		Dart darts = new Dart();
		darts.quantity( 7 );
		Item dartsBack = ItemCodec.decode( ItemCodec.encode( darts ) );
		assertEquals( 7, dartsBack.quantity() );
		assertFalse( dartsBack.levelKnown && !darts.levelKnown );

		//never sent: containers, keys, bombs; never built: foreign classes, oversized frames
		assertNull( ItemCodec.encode( new VelvetPouch() ) );
		assertNull( ItemCodec.encode( new IronKey( 1 ) ) );
		assertNull( ItemCodec.encode( new Bomb() ) );
		assertNull( ItemCodec.decode( "{\"item\":{\"__className\":\"java.lang.Thread\",\"quantity\":1}}" ) );
		assertNull( ItemCodec.decode( ItemCodec.encode( darts ).replace( Dart.class.getName(), VelvetPouch.class.getName() ) ) );
		assertNull( ItemCodec.decode( "{\"item\":{\"__className\":\"" + Dart.class.getName() + "\",\"quantity\":1,\"x\":\""
				+ "y".repeat( ItemCodec.MAX_BYTES ) + "\"}}" ) );
	}

	@Test
	void droppedItemAppearsInTheAllysWorldAndIsClaimableOnce() throws Exception {
		Level level = coopFloor1();
		PotionOfHealing potion = new PotionOfHealing();
		potion.quantity( 2 );
		int cell = emptyCell( level );
		place( level, cell, potion );
		CoopItems.onDropped( potion, cell );

		assertEquals( 1, link.drops.size() );
		Message out = link.drops.get( 0 );
		assertEquals( ME + ":1", out.str( "itemNetId" ) );
		assertEquals( cell, out.num( "cell" ) );
		assertEquals( 1L, Coop.run().items.counter );

		//the same frame relayed to the other client: this JVM now plays the ally's world, where the
		// cell is still empty
		Message relayed = relay( out );
		level.heaps.get( cell ).remove( potion );
		assertNull( level.heaps.get( cell ) );
		CoopItems.onServerEvent( relayed );
		Item spawned = level.heaps.get( cell ).peek();
		assertNotEquals( potion, spawned );
		assertEquals( PotionOfHealing.class, spawned.getClass() );
		assertEquals( 2, spawned.quantity() );
		assertEquals( ME + ":1", CoopItems.idOf( spawned ) );
		//replayed after a reconnect: not spawned twice
		CoopItems.onServerEvent( relayed );
		assertEquals( 1, level.heaps.get( cell ).items.size() );
		assertEquals( 2, level.heaps.get( cell ).peek().quantity() );
	}

	@Test
	void receivedDropIsClaimedOnPickupAndAnAllyClaimRemovesOurCopy() throws Exception {
		Level level = coopFloor1();
		int cell = emptyCell( level );
		Message drop = new Message( Protocol.DROP_SHARE ).put( "cell", cell ).put( "itemNetId", ALLY + ":5" )
				.put( "item", ItemCodec.encode( new PotionOfStrength() ) );
		drop.from = ALLY;
		drop.depth = 1;
		drop.branch = 0;
		CoopItems.onServerEvent( decode( drop ) );
		Item spawned = level.heaps.get( cell ).peek();
		assertEquals( ALLY + ":5", CoopItems.idOf( spawned ) );

		pickUp( level, spawned );
		assertEquals( Arrays.asList( "CLAIM " + ALLY + ":5", "CLAIMS_REQ 1 0" ), link.sent );
		server.process( link );
		server.deliver();
		assertTrue( holds( spawned ) );

		//and the other way round: a shared drop of ours that the ally picks up leaves our floor
		PotionOfHealing ours = new PotionOfHealing();
		int at = emptyCell( level );
		place( level, at, ours );
		CoopItems.onDropped( ours, at );
		server.claim( ALLY, 1, 0, CoopItems.idOf( ours ) );
		server.deliver();
		assertEquals( -1, heapCell( level, ours ) );
	}

	@Test
	void onlySharedWithTheAllyOnlineOnThisFloorAndOnlyShareableItems() {
		Level level = coopFloor1();
		link.allyOn = false;
		drop( level, new PotionOfHealing() );
		link.allyOn = true;
		Item local = new PotionOfHealing();
		CoopItems.markLocal( local );
		drop( level, local );
		drop( level, new Bomb() );
		drop( level, new IronKey( 1 ) );
		Item unconfirmed = new PotionOfHealing();
		CoopItems.bind( unconfirmed, "1:0:930" );
		Coop.run().items.pending.put( "1:0:930", new CoopItemsState.Pending( "1:0:930", 1, 0, PotionOfHealing.class.getName(), 1 ) );
		drop( level, unconfirmed );
		assertTrue( link.drops.isEmpty() );
		assertEquals( 0, Coop.run().items.counter );
		assertEquals( "1:0:930", CoopItems.idOf( unconfirmed ) );
		assertEquals( CoopItems.LOCAL, CoopItems.idOf( local ) );
	}

	// ---- vanilla ----

	@Test
	void noHookFiresInVanillaRuns() throws Exception {
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

		Heap heap = level.heaps.valueList().get( 0 );
		Item item = heap.peek();
		assertNull( CoopItems.idOf( item ) );
		Bundle bundle = new Bundle();
		bundle.put( "item", item );
		assertFalse( bundle.toString().contains( CoopItems.BUNDLE_KEY ) );

		CoopItems.onLevelEntered( level );
		pickUp( level, item );
		int cell = emptyCell( level );
		PotionOfHealing potion = new PotionOfHealing();
		place( level, cell, potion );
		CoopItems.onDropped( potion, cell );
		Message claimed = new Message( Protocol.CLAIMED ).put( "itemNetId", "1:0:0" ).put( "by", ALLY );
		CoopItems.onServerEvent( decode( claimed ) );
		CoopItems.flush();

		assertTrue( link.sent.isEmpty() );
		assertTrue( link.drops.isEmpty() );
		assertEquals( 0, link.posts );
		assertNull( CoopItems.idOf( potion ) );
	}

	// ---- harness ----

	//a co-op run on floor 1 of SEED, our hero standing in it
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
		Dungeon.hero.pos = emptyCell( level );
		return level;
	}

	//generates floors of a co-op run one by one (mob-held loot needs a few)
	private final class CoopRunSim {
		CoopRunSim() {
			coopFloor1();
		}

		Level floor( int depth ) {
			if (depth == 1) return Dungeon.level;
			Dungeon.depth = depth;
			Dungeon.branch = 0;
			Dungeon.level = Dungeon.newLevel();
			return Dungeon.level;
		}
	}

	private static Heap firstLoot( Level level ) {
		for (Heap heap : level.heaps.valueList()) {
			if (heap.type == Heap.Type.HEAP && CoopItems.idOf( heap.peek() ) != null) return heap;
		}
		throw new AssertionError( "no loot heap on the floor" );
	}

	//a level-gen-like item with this id on a free cell
	private static Item loot( Level level, String id ) {
		return loot( level, id, new PotionOfHealing() );
	}

	private static Item loot( Level level, String id, Item item ) {
		CoopItems.bind( item, id );
		place( level, emptyCell( level ), item );
		return item;
	}

	private static void place( Level level, int cell, Item item ) {
		Heap heap = level.heaps.get( cell );
		if (heap == null) {
			heap = new Heap();
			heap.pos = cell;
			level.heaps.put( cell, heap );
		}
		heap.drop( item );
	}

	private static void drop( Level level, Item item ) {
		int cell = emptyCell( level );
		place( level, cell, item );
		CoopItems.onDropped( item, cell );
	}

	private static int emptyCell( Level level ) {
		for (int cell = 0; cell < level.length(); cell++) {
			if (level.passable[cell] && !level.solid[cell] && level.heaps.get( cell ) == null
					&& (Dungeon.hero == null || Dungeon.hero.pos != cell) && level.findMob( cell ) == null) {
				return cell;
			}
		}
		throw new AssertionError( "no free cell" );
	}

	private static int heapCell( Level level, Item item ) {
		for (Heap heap : level.heaps.valueList()) {
			if (heap.items.contains( item )) return heap.pos;
		}
		return -1;
	}

	//what Hero.actPickUp does, minus sound and sprites
	private static void pickUp( Level level, Item item ) {
		Hero hero = Dungeon.hero;
		Heap heap = level.heaps.get( heapCell( level, item ) );
		assertEquals( item, heap.peek() );
		hero.pos = heap.pos;
		hero.curAction = new HeroAction.PickUp( heap.pos );
		if (item instanceof Gold) {
			Dungeon.gold += item.quantity();
		} else {
			assertTrue( item.collect( hero.belongings.backpack ) );
		}
		heap.pickUp();
		hero.curAction = null;
	}

	private static boolean holds( Item item ) {
		return Dungeon.hero.belongings.contains( item );
	}

	private static Bundlable reload( Bundlable object ) throws IOException {
		Bundle bundle = new Bundle();
		bundle.put( "x", object );
		return reload( bundle ).get( "x" );
	}

	private static Bundle reload( Bundle bundle ) throws IOException {
		return Bundle.read( new ByteArrayInputStream( bundle.toString().getBytes( StandardCharsets.UTF_8 ) ) );
	}

	private static Message decode( Message m ) throws Exception {
		m.seq = 1;
		return Protocol.decode( Direction.S2C, Protocol.encode( m ) );
	}

	//a c2s DROP_SHARE as the server relays it to the other player (sent by "the ally", since this
	// JVM plays the receiver)
	private static Message relay( Message c2s ) throws Exception {
		Message s2c = new Message( Protocol.DROP_SHARE );
		s2c.p.putAll( c2s.p );
		s2c.from = ALLY;
		s2c.depth = c2s.depth;
		s2c.branch = c2s.branch;
		return decode( s2c );
	}

	private static final class FakeLink implements CoopItems.Link {
		final List<String> sent = new ArrayList<>();
		final List<Message> drops = new ArrayList<>();
		final List<int[]> floors = new ArrayList<>();
		boolean running = true;
		boolean allyOn = true;
		long now = 1_000_000;
		int posts;

		@Override
		public String me() {
			return ME;
		}

		@Override
		public boolean running() {
			return running;
		}

		@Override
		public boolean allyOn( int depth, int branch ) {
			return allyOn;
		}

		@Override
		public boolean claim( int depth, int branch, String itemNetId ) {
			if (!running) return false;
			sent.add( "CLAIM " + itemNetId );
			floors.add( new int[]{depth, branch} );
			return true;
		}

		@Override
		public boolean claimsReq( int depth, int branch ) {
			if (!running) return false;
			sent.add( "CLAIMS_REQ " + depth + " " + branch );
			floors.add( new int[]{depth, branch} );
			return true;
		}

		@Override
		public boolean dropShare( int depth, int branch, int cell, String itemNetId, String item ) {
			if (!running) return false;
			Message m = new Message( Protocol.DROP_SHARE ).put( "cell", cell ).put( "itemNetId", itemNetId ).put( "item", item );
			m.depth = depth;
			m.branch = branch;
			m.seq = 1;
			try {
				//must be a valid c2s frame (id format, opaque size)
				drops.add( Protocol.decode( Direction.C2S, Protocol.encode( m ) ) );
			} catch (Protocol.DecodeError e) {
				throw new AssertionError( e );
			}
			return true;
		}

		@Override
		public long now() {
			return now;
		}

		@Override
		public void post( Runnable r ) {
			posts++;
			r.run();
		}
	}

	//the SRV-02 claim rules (PROTOCOL.md §5.7) for one connection, answering in order
	private static final class FakeServer {
		final Map<String, String> owner = new LinkedHashMap<>();
		final Map<String, String> floorOf = new LinkedHashMap<>();
		final Map<String, List<String>> deniedTo = new LinkedHashMap<>();
		final List<Message> toMe = new ArrayList<>();

		void claim( String player, int depth, int branch, String id ) {
			String o = owner.get( id );
			if (o == null) {
				owner.put( id, player );
				floorOf.put( id, depth + "/" + branch );
				toMe.add( msg( new Message( Protocol.CLAIMED ).put( "itemNetId", id ).put( "by", player ) ) );
			} else if (!o.equals( player )) {
				deniedTo.computeIfAbsent( player, k -> new ArrayList<>() ).add( id );
				if (player.equals( ME )) toMe.add( msg( new Message( Protocol.DENY ).put( "reason", "taken" ) ) );
			}
		}

		void claimsReq( int depth, int branch ) {
			List<String> ids = new ArrayList<>();
			floorOf.forEach( ( id, floor ) -> {
				if (floor.equals( depth + "/" + branch )) ids.add( id );
			} );
			toMe.add( msg( new Message( Protocol.CLAIMS ).put( "depth", depth ).put( "branch", branch ).put( "itemNetIds", ids ) ) );
		}

		void process( FakeLink link ) {
			for (int i = 0; i < link.sent.size(); i++) {
				String[] f = link.sent.get( i ).split( " " );
				int[] floor = link.floors.get( i );
				if (f[0].equals( "CLAIM" )) claim( ME, floor[0], floor[1], f[1] );
				else claimsReq( floor[0], floor[1] );
			}
			link.sent.clear();
			link.floors.clear();
		}

		void deliver() {
			List<Message> batch = new ArrayList<>( toMe );
			toMe.clear();
			for (Message m : batch) {
				CoopItems.onServerEvent( m );
			}
		}

		private static Message msg( Message m ) {
			try {
				return decode( m );
			} catch (Exception e) {
				throw new AssertionError( e );
			}
		}
	}
}
