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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission.downed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.ScrollOfRecall;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopItemIds;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopRunSim;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.HeavyPlay;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelKey;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.VanillaSeeds;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

//MEC-02, game-design §5.2: exactly one Scroll of Recall in a chest per region (floors 1-24), on
// the same floor and cell whatever the heroes do, shop stock only in shops, shared loot ids, and
// none in vanilla runs
class RecallLayoutTest {

	private static final long[] SEEDS = { 123456789L, 42L, VanillaSeeds.TEXT_HASH_SEED };

	@Test
	void onePerRegionInAChestAndShopStockInShops() {
		int shopStock = 0;
		for (long seed : SEEDS) {
			TreeMap<LevelKey, Level> levels = CoopRunSim.start( seed, HeroClass.WARRIOR, CoopRunSim.NOTHING, true )
					.generateAll().levels();
			int[] chests = new int[6];
			for (Map.Entry<LevelKey, Level> e : levels.entrySet()) {
				int depth = e.getKey().depth();
				for (Heap heap : e.getValue().heaps.valueList()) {
					for (Item item : heap.items) {
						if (!(item instanceof ScrollOfRecall)) continue;
						String where = seed + " " + e.getKey();
						assertEquals( 0, e.getKey().branch(), where );
						assertTrue( depth <= 24 && !Dungeon.bossLevel( depth ), where );
						assertNotNull( CoopItemIds.idOf( item ), where + ": shared loot id" );
						if (heap.type == Heap.Type.CHEST) {
							chests[(depth - 1) / 5]++;
							assertEquals( RecallLayout.recallFloor( depth ), depth, where );
						} else {
							assertEquals( Heap.Type.FOR_SALE, heap.type, where );
							shopStock++;
						}
					}
				}
			}
			for (int region = 0; region < 5; region++) assertEquals( 1, chests[region], seed + " region " + (region + 1) );
			assertEquals( 0, chests[5] );
		}
		assertTrue( shopStock > 0, "shops sometimes stock one" );
	}

	@Test
	void theSameFloorsAndCellsHoweverThePlayersPlay() {
		for (long seed : SEEDS) {
			String a = describe( CoopRunSim.start( seed, HeroClass.WARRIOR, CoopRunSim.NOTHING, true ).generateAll().levels() );
			String b = describe( CoopRunSim.start( seed, HeroClass.MAGE, new HeavyPlay(), true ).generateAll().levels() );
			assertEquals( a, b, "seed " + seed );
			assertTrue( a.contains( "CHEST" ) );
		}
	}

	@Test
	void vanillaRunsHaveNoScrollOfRecall() {
		HeadlessEnv.start();
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		GamesInProgress.selectedClass = HeroClass.WARRIOR;
		Dungeon.init();
		assertTrue( !Coop.isCoopRun() );
		for (int depth = 1; depth <= 12; depth++) {
			Dungeon.depth = depth;
			Dungeon.branch = 0;
			Level level = Dungeon.newLevel();
			for (Heap heap : level.heaps.valueList()) {
				for (Item item : heap.items) assertTrue( !(item instanceof ScrollOfRecall), "depth " + depth );
			}
		}
	}

	private static String describe( TreeMap<LevelKey, Level> levels ) {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<LevelKey, Level> e : levels.entrySet()) {
			for (Heap heap : e.getValue().heaps.valueList()) {
				for (Item item : heap.items) {
					if (item instanceof ScrollOfRecall) {
						out.append( e.getKey() ).append( ' ' ).append( heap.pos ).append( ' ' ).append( heap.type )
								.append( ' ' ).append( CoopItemIds.idOf( item ) ).append( '\n' );
					}
				}
			}
		}
		return out.toString();
	}
}
