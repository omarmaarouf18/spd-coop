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

package com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.EbonyMimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Ghost;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultMirror;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.CoopItems;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopRunSim;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelKey;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.Artifact;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.ChaliceOfBlood;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.CloakOfShadows;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.DriedRose;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TimekeepersHourglass;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.MagicalHolster;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.PotionBandolier;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.ScrollHolder;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.VelvetPouch;
import com.shatteredpixel.shatteredpixeldungeon.items.food.SupplyRation;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.CrackedSpyglass;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.MimicTooth;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.TrapMechanism;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.Trinket;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Game;
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

//DET-02: hero-dependent level-gen effects come back as a per-player overlay on first entry,
// local to this world (LOCAL items, own seeds), saved so a reload never repeats it, and the shared
// floor stays what co-op level gen made
class CoopOverlayTest {

	private static final long SEED = 123456789L;

	private Game previousGame;
	private CoopRunSim sim;

	@BeforeEach
	void setUp() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		previousGame = Game.instance;
		//Level.drop asks Game for the current scene; a bare Game has none
		new Game( null, null ) {};
	}

	@AfterEach
	void tearDown() {
		Game.instance = previousGame;
	}

	// ---- qualifies vs not ----

	@Test
	void petalsOnlyForAnIdentifiedRoseAfterTheGhostQuest() {
		Level plain = run( HeroClass.WARRIOR, 4 ).enter( 4 );
		assertEquals( 0, local( plain ).size() );

		run( HeroClass.WARRIOR, 4 );
		DriedRose rose = carry( identified( new DriedRose() ) );
		Level unfinished = enter( 4 );
		assertEquals( 0, local( unfinished ).size(), "the ghost quest isn't done" );

		run( HeroClass.WARRIOR, 4 );
		rose = carry( identified( new DriedRose() ) );
		ghostQuestDone();
		Level level = enter( 4 );
		//ceil((4/2 - 0) / 3) = 1 petal on depth 4
		assertEquals( Arrays.asList( DriedRose.Petal.class ), classes( local( level ) ) );
		assertEquals( 1, rose.droppedPetals );
	}

	@Test
	void cachedRationsOnlyWithTheTalent() {
		run( HeroClass.WARRIOR, 2 );
		assertEquals( 0, local( enter( 2 ) ).size() );

		run( HeroClass.ROGUE, 2 );
		Dungeon.hero.upgradeTalent( Talent.CACHED_RATIONS );
		Level level = enter( 2 );
		List<Heap> rations = local( level );
		assertEquals( Arrays.asList( SupplyRation.class ), classes( rations ) );
		assertEquals( Heap.Type.CHEST, rations.get( 0 ).type );
		assertEquals( 2, (int) Dungeon.hero.buff( Talent.CachedRationsDropped.class ).count() );
	}

	@Test
	void shopGetsSandBagsAndThePlayersBag() {
		//no preference, no hourglass: the shared shop as level gen made it (seed 123456789: the
		// scroll holder on depth 6)
		run( HeroClass.WARRIOR, 6 );
		Dungeon.hero.belongings.backpack.items.clear();
		Level plain = enter( 6 );
		List<Item> stock = stock( plain );
		assertEquals( 1, bags( stock ).size(), "co-op shops sell one bag" );
		assertFalse( stock.stream().anyMatch( i -> i instanceof TimekeepersHourglass.sandBag ) );
		assertEquals( ScrollHolder.class, bags( stock ).get( 0 ).getClass() );
		assertNotEquals( CoopItems.LOCAL, CoopItems.idOf( bags( stock ).get( 0 ) ) );

		//an identified hourglass and a backpack full of potions (as vanilla's ChooseBag sees it)
		run( HeroClass.WARRIOR, 6 );
		TimekeepersHourglass hourglass = carry( identified( new TimekeepersHourglass() ) );
		for (int i = 0; i < 10; i++) carry( Generator.random( Generator.Category.POTION ) );
		stock = stock( enter( 6 ) );
		List<Item> sand = new ArrayList<>();
		for (Item i : stock) if (i instanceof TimekeepersHourglass.sandBag) sand.add( i );
		//ceil(5 * 0.2) = 1 on depth 6
		assertEquals( 1, sand.size() );
		assertEquals( 1, hourglass.sandBags );
		assertEquals( CoopItems.LOCAL, CoopItems.idOf( sand.get( 0 ) ) );
		assertEquals( 1, bags( stock ).size() );
		Bag bag = bags( stock ).get( 0 );
		assertEquals( PotionBandolier.class, bag.getClass() );
		assertEquals( CoopItems.LOCAL, CoopItems.idOf( bag ) );
		assertEquals( Arrays.asList( PotionBandolier.class.getName() ), new ArrayList<>( Coop.run().overlay.bags ) );
	}

	@Test
	void eachPlayerSeesEachBagOnce() {
		run( HeroClass.WARRIOR, 1 );
		OverlayState s = Coop.run().overlay;
		List<Class<?>> offered = new ArrayList<>();
		//the pouch is every hero's starting bag: never sold
		for (int i = 0; i < 3; i++) {
			Bag bag = PlayerShop.chooseBag( Dungeon.hero.belongings, s.bags, ScrollHolder.class );
			assertNotNull( bag );
			s.bags.add( bag.getClass().getName() );
			offered.add( bag.getClass() );
		}
		assertEquals( 3, offered.stream().distinct().count() );
		assertFalse( offered.contains( VelvetPouch.class ) );
		assertEquals( null, PlayerShop.chooseBag( Dungeon.hero.belongings, s.bags, ScrollHolder.class ) );
	}

	@Test
	void impShopStockGetsTheOverlayBeforeItIsPlaced() {
		run( HeroClass.WARRIOR, 1 );
		Dungeon.depth = 20;
		carry( identified( new TimekeepersHourglass() ) );
		for (int i = 0; i < 6; i++) carry( Generator.random( Generator.Category.POTION ) );
		ArrayList<Item> stock = new ArrayList<>( Arrays.asList( new PotionOfHealing(), new MagicalHolster() ) );
		CoopOverlay.onImpShop( stock );
		//ceil(5 * 0.8) = 4 on depth 20; the bag the potions want
		assertEquals( 4, stock.stream().filter( i -> i instanceof TimekeepersHourglass.sandBag ).count() );
		assertEquals( PotionBandolier.class, stock.get( 1 ).getClass() );
		for (Item i : stock.subList( 1, stock.size() )) assertEquals( CoopItems.LOCAL, CoopItems.idOf( i ) );
	}

	@Test
	void vaultMirrorGivesThisPlayersClassReward() {
		for (long seed : new long[]{ SEED, 42L }) {
			sim = CoopRunSim.start( seed, HeroClass.MAGE, CoopRunSim.NOTHING, true ).generateAll();
			for (Map.Entry<LevelKey, Level> e : sim.levels().entrySet()) {
				VaultMirror mirror = mirror( e.getValue() );
				if (mirror == null) continue;
				//level gen made the neutral hero's (warrior's) reward
				assertEquals( "BrokenSeal", mirror.reward.getClass().getSimpleName() );
				enter( e.getKey().depth(), e.getKey().branch() );
				assertEquals( "MagesStaff", mirror.reward.getClass().getSimpleName() );
				assertEquals( CoopItems.LOCAL, CoopItems.idOf( mirror.reward ) );
				return;
			}
		}
		throw new AssertionError( "no Vault Mirror on the test seeds: the test covers less than it claims" );
	}

	@Test
	void anArtifactThePlayerOwnsTurnsIntoAnotherOneInTheSameSlot() {
		//the rogue starts with the cloak; co-op level gen doesn't know that
		run( HeroClass.ROGUE, 1 );
		Level level = Dungeon.level = sim.levels().get( new LevelKey( 1, 0 ) );
		Item cloak = new CloakOfShadows();
		Item chalice = new ChaliceOfBlood();
		int a = freeCell( level, -1 ), b = freeCell( level, a );
		withId( cloak, "1:0:900" );
		withId( chalice, "1:0:901" );
		level.drop( cloak, a );
		level.drop( chalice, b );

		enter( 1 );
		Item other = level.heaps.get( a ).peek();
		assertFalse( other instanceof CloakOfShadows );
		assertTrue( other instanceof Artifact || other instanceof Ring, other.getClass().getName() );
		assertEquals( "1:0:900", CoopItems.idOf( other ), "the shared slot keeps its id" );
		assertEquals( chalice, level.heaps.get( b ).peek(), "an artifact the player doesn't own stays" );
	}

	@Test
	void trinketsAddLocalLootAndRevealTraps() {
		run( HeroClass.WARRIOR, 10 );
		carry( trinket( new CrackedSpyglass() ) );
		carry( trinket( new MimicTooth() ) );
		carry( trinket( new TrapMechanism() ) );
		int hiddenLoot = 0, ebony = 0, revealed = 0, hiddenBefore = 0;
		for (int depth = 2; depth <= 9; depth++) {
			if (depth == 5) continue; //the boss floor
			Level level = sim.levels().get( new LevelKey( depth, 0 ) );
			int hidden = hiddenTraps( level ).size();
			hiddenBefore += hidden;
			enter( depth );
			for (Heap h : local( level )) if (h.hidden) hiddenLoot++;
			for (Mob m : level.mobs) {
				if (m instanceof EbonyMimic) {
					ebony++;
					for (Item i : ((Mimic) m).items) assertEquals( CoopItems.LOCAL, CoopItems.idOf( i ) );
				}
			}
			revealed += hidden - hiddenTraps( level ).size();
		}
		//spyglass +3: int(Float + 1.5) >= 1 hidden item per floor
		assertTrue( hiddenLoot >= 7, "spyglass loot: " + hiddenLoot );
		assertTrue( ebony > 0, "mimic tooth +3 (50% per floor) gave no ebony mimic on 7 floors" );
		//trap mechanism +3: every 1/0.4-th hidden trap of a floor (none on a floor with < 3, as in
		// vanilla's painter)
		assertTrue( hiddenBefore > 0 && revealed > 0, "revealed " + revealed + " of " + hiddenBefore );
		assertTrue( revealed <= hiddenBefore / 2, "revealed " + revealed + " of " + hiddenBefore );
	}

	// ---- local only ----

	@Test
	void theSharedFloorStaysAsLevelGenMadeIt() {
		run( HeroClass.ROGUE, 8 );
		Dungeon.hero.upgradeTalent( Talent.CACHED_RATIONS );
		carry( identified( new DriedRose() ) );
		ghostQuestDone();
		carry( identified( new TimekeepersHourglass() ) );
		carry( trinket( new CrackedSpyglass() ) );
		carry( trinket( new MimicTooth() ) );
		carry( trinket( new TrapMechanism() ) );
		for (int i = 0; i < 10; i++) carry( Generator.random( Generator.Category.SCROLL ) );

		int added = 0;
		for (int depth = 1; depth <= 8; depth++) {
			Level level = sim.levels().get( new LevelKey( depth, 0 ) );
			Map<String, String> shared = sharedItems( level );
			int[] map = level.map.clone();
			List<Mob> mobs = new ArrayList<>( level.mobs );

			enter( depth );

			Map<String, String> after = sharedItems( level );
			for (Map.Entry<String, String> e : shared.entrySet()) {
				//a converted duplicate keeps its slot (id) and becomes another artifact
				String now = after.get( e.getKey() );
				assertNotNull( now, "depth " + depth + ": shared item " + e.getKey() + " is gone" );
				if (!now.equals( e.getValue() )) {
					assertTrue( e.getValue().contains( "artifacts" ), depth + ": " + e.getValue() + " -> " + now );
				}
			}
			assertEquals( shared.keySet(), after.keySet(), "depth " + depth + ": no new shared item" );
			for (int cell = 0; cell < map.length; cell++) {
				if (map[cell] == level.map[cell]) continue;
				boolean trap = map[cell] == Terrain.SECRET_TRAP && level.map[cell] == Terrain.TRAP;
				boolean trampled = (map[cell] == Terrain.HIGH_GRASS || map[cell] == Terrain.FURROWED_GRASS)
						&& level.map[cell] == Terrain.GRASS && level.heaps.get( cell ) != null;
				assertTrue( trap || trampled, "depth " + depth + ": terrain " + map[cell] + " -> " + level.map[cell] + " at " + cell );
			}
			for (Mob m : level.mobs) {
				if (!mobs.contains( m )) assertTrue( m instanceof EbonyMimic, m.getClass().getName() );
			}
			assertTrue( level.mobs.containsAll( mobs ) );
			added += local( level ).size();
		}
		assertTrue( added > 0 );
	}

	@Test
	void nothingHappensOutsideCoopRuns() {
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		Dungeon.init();
		assertFalse( Coop.isCoopRun() );
		Dungeon.depth = 4;
		Dungeon.branch = 0;
		Level level = Dungeon.level = Dungeon.newLevel();
		Dungeon.hero.pos = level.entrance();
		carry( identified( new DriedRose() ) );
		ghostQuestDone();
		int heaps = level.heaps.valueList().size();
		CoopOverlay.onLevelEntered( level );
		assertEquals( heaps, level.heaps.valueList().size() );
		ArrayList<Item> stock = new ArrayList<>( Arrays.asList( new MagicalHolster() ) );
		CoopOverlay.onImpShop( stock );
		assertEquals( 1, stock.size() );
		assertNull( CoopItems.idOf( stock.get( 0 ) ) );
	}

	// ---- persistence and seeds ----

	@Test
	void noDoubleSpawnAcrossSaveAndLoad() throws IOException {
		run( HeroClass.WARRIOR, 4 );
		DriedRose rose = carry( identified( new DriedRose() ) );
		ghostQuestDone();
		Level level = enter( 4 );
		assertEquals( 1, local( level ).size() );

		//save and load: the run state (with the overlay) and the floor, through Bundle text
		CoopRun run = Coop.run();
		run.restoreFromBundle( reload( bundleOf( run ) ) );
		assertEquals( new ArrayList<>( Arrays.asList( "4_0" ) ), new ArrayList<>( run.overlay.floors ) );
		Level reloaded = (Level) reload( wrap( level ) ).get( "x" );
		Dungeon.level = reloaded;
		CoopOverlay.onLevelEntered( reloaded );
		assertEquals( 1, local( reloaded ).size() );
		assertEquals( 1, rose.droppedPetals );

		//entering again in the same session does nothing either
		CoopOverlay.onLevelEntered( reloaded );
		assertEquals( 1, local( reloaded ).size() );
	}

	@Test
	void aRunSavedBeforeTheOverlayDoesntGetItOnOldFloors() throws IOException {
		run( HeroClass.WARRIOR, 4 );
		carry( identified( new DriedRose() ) );
		ghostQuestDone();
		CoopRun run = Coop.run();
		Bundle old = bundleOf( run );
		old.remove( "coop_overlay" );
		run.restoreFromBundle( reload( old ) );
		assertTrue( run.overlay.legacy );

		Level level = enter( 4 );
		assertEquals( 0, local( level ).size() );
		assertFalse( run.overlay.legacy );
		assertTrue( run.overlay.floors.contains( "1_0" ) && run.overlay.floors.contains( "4_0" ) );
	}

	@Test
	void spawnsDependOnSeedPlayerAndFloorOnly() {
		Map<Integer, String> first = petalCells( "aaaaaaaaaaaaaaaa" );
		assertFalse( first.isEmpty() );
		assertEquals( first, petalCells( "aaaaaaaaaaaaaaaa" ), "same player, same floors: same spawns" );
		assertNotEquals( CoopOverlay.seed( SEED, "aaaaaaaaaaaaaaaa", 4, 0, CoopOverlay.PETALS ),
				CoopOverlay.seed( SEED, "bbbbbbbbbbbbbbbb", 4, 0, CoopOverlay.PETALS ) );
		assertNotEquals( CoopOverlay.seed( SEED, "aaaaaaaaaaaaaaaa", 4, 0, CoopOverlay.PETALS ),
				CoopOverlay.seed( SEED, "aaaaaaaaaaaaaaaa", 4, 1, CoopOverlay.PETALS ) );
		assertNotEquals( CoopOverlay.seed( SEED, "aaaaaaaaaaaaaaaa", 4, 0, CoopOverlay.PETALS ),
				CoopOverlay.seed( SEED, "aaaaaaaaaaaaaaaa", 4, 0, CoopOverlay.RATIONS ) );
	}

	// ---- helpers ----

	//petal cells on depths 2..8 for this player, in a fresh run
	private Map<Integer, String> petalCells( String playerId ) {
		run( HeroClass.WARRIOR, 8 );
		Coop.run().playerId = playerId;
		carry( identified( new DriedRose() ) );
		ghostQuestDone();
		Map<Integer, String> cells = new LinkedHashMap<>();
		for (int depth = 2; depth <= 8; depth++) {
			StringBuilder sb = new StringBuilder();
			for (Heap h : local( enter( depth ) )) sb.append( h.pos ).append( ' ' );
			if (sb.length() > 0) cells.put( depth, sb.toString() );
		}
		return cells;
	}

	//a fresh co-op run with floors 1..depth generated (the player plays nothing)
	private CoopOverlayTest run( HeroClass cls, int depth ) {
		sim = CoopRunSim.start( SEED, cls, CoopRunSim.NOTHING, true ).generate( 1, depth, false );
		Coop.run().playerId = "aaaaaaaaaaaaaaaa";
		Dungeon.level = sim.levels().get( new LevelKey( 1, 0 ) );
		return this;
	}

	private Level enter( int depth ) {
		return enter( depth, 0 );
	}

	//what Dungeon.switchLevel does around the hook
	private Level enter( int depth, int branch ) {
		Level level = sim.levels().get( new LevelKey( depth, branch ) );
		Dungeon.depth = depth;
		Dungeon.branch = branch;
		Dungeon.level = level;
		Dungeon.hero.pos = level.entrance();
		CoopOverlay.onLevelEntered( level );
		return level;
	}

	private static <T extends Item> T carry( T item ) {
		Dungeon.hero.belongings.backpack.items.add( item );
		return item;
	}

	private static <T extends Item> T identified( T item ) {
		item.levelKnown = item.cursedKnown = true;
		item.cursed = false;
		return item;
	}

	private static Trinket trinket( Trinket trinket ) {
		trinket.level( 3 );
		return trinket;
	}

	private static void ghostQuestDone() {
		Bundle node = new Bundle();
		node.put( "spawned", true );
		node.put( "given", true );
		node.put( "processed", true );
		node.put( "depth", 2 );
		Bundle quests = new Bundle();
		quests.put( "sadGhost", node );
		Ghost.Quest.restoreFromBundle( quests );
		Ghost.Quest.weapon = null;
		Ghost.Quest.armor = null;
		assertTrue( Ghost.Quest.completed() );
	}

	private static List<Heap> local( Level level ) {
		List<Heap> heaps = new ArrayList<>();
		for (Heap h : level.heaps.valueList()) {
			if (h.type != Heap.Type.FOR_SALE && CoopItems.LOCAL.equals( CoopItems.idOf( h.peek() ) )) heaps.add( h );
		}
		heaps.sort( ( x, y ) -> Integer.compare( x.pos, y.pos ) );
		return heaps;
	}

	private static List<Class<?>> classes( List<Heap> heaps ) {
		List<Class<?>> classes = new ArrayList<>();
		for (Heap h : heaps) classes.add( h.peek().getClass() );
		return classes;
	}

	private static List<Item> stock( Level level ) {
		List<Item> items = new ArrayList<>();
		for (Heap h : level.heaps.valueList()) {
			if (h.type == Heap.Type.FOR_SALE) items.addAll( h.items );
		}
		return items;
	}

	private static List<Bag> bags( List<Item> items ) {
		List<Bag> bags = new ArrayList<>();
		for (Item i : items) {
			if (i instanceof VelvetPouch || i instanceof ScrollHolder || i instanceof PotionBandolier || i instanceof MagicalHolster) {
				bags.add( (Bag) i );
			}
		}
		return bags;
	}

	//shared (non-local) items with an id, on heaps and inside mimics: id -> class
	private static Map<String, String> sharedItems( Level level ) {
		Map<String, String> items = new LinkedHashMap<>();
		List<Item> all = new ArrayList<>();
		for (Heap h : level.heaps.valueList()) all.addAll( h.items );
		for (Mob m : level.mobs) if (m instanceof Mimic && ((Mimic) m).items != null) all.addAll( ((Mimic) m).items );
		for (Item i : all) {
			String id = CoopItems.idOf( i );
			if (id != null && !CoopItems.LOCAL.equals( id )) items.put( id, i.getClass().getName() );
		}
		return items;
	}

	private static VaultMirror mirror( Level level ) {
		for (Mob m : level.mobs) if (m instanceof VaultMirror && ((VaultMirror) m).reward != null) return (VaultMirror) m;
		return null;
	}

	private static List<Trap> hiddenTraps( Level level ) {
		List<Trap> traps = new ArrayList<>();
		for (Trap t : level.traps.valueList()) if (!t.visible) traps.add( t );
		return traps;
	}

	private static int freeCell( Level level, int not ) {
		for (int cell = 0; cell < level.length(); cell++) {
			if (cell != not && level.passable[cell] && level.heaps.get( cell ) == null
					&& level.findMob( cell ) == null && cell != level.entrance()) return cell;
		}
		throw new AssertionError( "no free cell" );
	}

	private static void withId( Item item, String id ) {
		Bundle b = new Bundle();
		b.put( "coop_net_id", id );
		CoopItems.restoreId( item, b );
	}

	private static Bundle bundleOf( CoopRun run ) {
		Bundle b = new Bundle();
		run.storeInBundle( b );
		return b;
	}

	private static Bundle wrap( Level level ) {
		Bundle b = new Bundle();
		b.put( "x", level );
		return b;
	}

	private static Bundle reload( Bundle bundle ) throws IOException {
		return Bundle.read( new ByteArrayInputStream( bundle.toString().getBytes( StandardCharsets.UTF_8 ) ) );
	}
}
