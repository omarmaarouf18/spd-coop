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

package com.shatteredpixel.shatteredpixeldungeon.coop.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelKey;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.VanillaRun;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MysteryMeat;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.watabou.utils.Bundle;
import com.watabou.utils.Reflection;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

//D-021 parts of DET-01: co-op balance inside co-op level gen, and stable itemNetIds
class CoopBalanceAndIdsTest {

	private static final long SEED = 123456789L;

	//mobs whose vanilla HT is a constant of their class (no depth or level scaling)
	private static final Set<String> FIXED_HT = Set.of("Rat", "Gnoll", "Crab", "Slime", "Swarm", "Skeleton",
			"Thief", "Bat", "Brute", "Spinner", "DM200", "Ghoul", "Golem", "Monk", "Succubus", "Eye", "Scorpio",
			"Warlock", "Necromancer", "DM100", "Guard", "Snake");

	@Test
	void coopMobsHave20PercentMoreHT() {
		CoopRunSim run = CoopRunSim.start(SEED, HeroClass.WARRIOR, CoopRunSim.NOTHING, true).generateAll();
		int checked = 0;
		for (Map.Entry<LevelKey, Level> floor : run.levels().entrySet()) {
			Dungeon.depth = floor.getKey().depth();
			for (Mob mob : floor.getValue().mobs) {
				if (!FIXED_HT.contains(mob.getClass().getSimpleName())) continue;
				int vanillaHT = Reflection.newInstance(mob.getClass()).HT;
				assertEquals(Math.round(vanillaHT * CoopBalance.MOB_HT_FACTOR), mob.HT,
						floor.getKey() + " " + mob.getClass().getSimpleName() + " HT");
				assertEquals(mob.HT, mob.HP, floor.getKey() + " " + mob.getClass().getSimpleName() + " starts at full HP");
				checked++;
			}
		}
		assertTrue(checked >= 30, "only " + checked + " mobs checked");
	}

	@Test
	void coopFloorsHaveMoreFoodAndTheVanillaTablesStayVanilla() {
		int coop = food(CoopRunSim.start(SEED, HeroClass.WARRIOR, CoopRunSim.NOTHING, true).generateAll().levels());
		TreeMap<LevelKey, Level> vanillaLevels = new TreeMap<>();
		VanillaRun.startRun(SEED);
		assertTrue(!Coop.isCoopRun());
		for (int depth = 1; depth <= VanillaRun.MAX_DEPTH; depth++) {
			Dungeon.depth = depth;
			Dungeon.branch = 0;
			vanillaLevels.put(new LevelKey(depth, 0), Dungeon.newLevel());
		}
		int vanilla = food(vanillaLevels);
		System.out.println("[DET-01 balance] food items on main floors 1-26, seed " + SEED
				+ ": co-op " + coop + ", vanilla " + vanilla);
		assertTrue(coop > vanilla, "co-op " + coop + " vs vanilla " + vanilla);

		assertEquals(List.of(4f, 1f, 0f), floats(Generator.Category.FOOD.defaultProbs));
	}

	private static int food(Map<LevelKey, Level> levels) {
		int count = 0;
		for (Map.Entry<LevelKey, Level> floor : levels.entrySet()) {
			if (floor.getKey().branch() != 0) continue;
			for (Heap heap : floor.getValue().heaps.valueList()) {
				for (Item item : heap.items) {
					if (item instanceof Food || item instanceof MysteryMeat) count += item.quantity();
				}
			}
		}
		return count;
	}

	private static List<Float> floats(float[] a) {
		List<Float> out = new ArrayList<>();
		for (float f : a) out.add(f);
		return out;
	}

	@Test
	void everyGeneratedItemHasAUniqueStableNetId() {
		CoopRunSim run = CoopRunSim.start(SEED, HeroClass.WARRIOR, CoopRunSim.NOTHING, true).generateAll();
		Set<String> seen = new HashSet<>();
		int items = 0;
		for (Map.Entry<LevelKey, Level> floor : run.levels().entrySet()) {
			String prefix = floor.getKey().depth() + ":" + floor.getKey().branch() + ":";
			for (Heap heap : floor.getValue().heaps.valueList()) {
				for (Item item : heap.items) {
					String id = CoopItemIds.idOf(item);
					assertNotNull(id, floor.getKey() + " " + item.getClass().getSimpleName() + "@" + heap.pos + " has no itemNetId");
					assertTrue(id.startsWith(prefix), id + " on floor " + floor.getKey());
					assertTrue(seen.add(id), "duplicate itemNetId " + id);
					items++;
				}
			}
		}
		assertTrue(items > 200, "only " + items + " items");
		//ids are part of the co-op fingerprint, so CoopDeterminismTest also proves A == B for them
	}

	@Test
	void netIdsCanBeReboundAfterTheFloorIsSavedAndLoaded() throws IOException {
		CoopRunSim run = CoopRunSim.start(SEED, HeroClass.WARRIOR, CoopRunSim.NOTHING, true).generate(1, 6);
		Level shop = run.levels().get(new LevelKey(6, 0));
		List<String> before = idsByCell(shop);

		//Level/Blob bundling reads Dungeon.level, as when the game saves the current floor
		Dungeon.level = shop;
		Bundle saved = new Bundle();
		saved.put("level", shop);
		Bundle read = Bundle.read(new ByteArrayInputStream(saved.toString().getBytes(StandardCharsets.UTF_8)));
		Dungeon.depth = 6;
		Level loaded = (Level) read.get("level");
		//a fresh load: nothing is bound until rebind
		assertTrue(idsByCell(loaded).stream().allMatch(s -> s.startsWith("null")));

		//the player picked up the first heap meanwhile: it must not be rebound to anything
		Heap taken = loaded.heaps.valueList().get(0);
		loaded.heaps.remove(taken.pos);
		String[] table = CoopLevelGen.itemIdTable(Coop.run().levelGen, 6, 0);
		assertNotNull(table, "the floor's itemNetId table must be in the co-op level-gen state");
		int bound = CoopItemIds.rebind(loaded, table, 6, 0);

		List<String> expected = new ArrayList<>();
		for (String row : before) {
			if (!row.contains("@" + taken.pos + ":")) expected.add(row);
		}
		assertEquals(expected, idsByCell(loaded));
		assertEquals(expected.size(), bound);
	}

	private static List<String> idsByCell(Level level) {
		List<String> out = new ArrayList<>();
		List<Heap> heaps = new ArrayList<>(level.heaps.valueList());
		heaps.sort((a, b) -> Integer.compare(a.pos, b.pos));
		for (Heap heap : heaps) {
			for (Item item : heap.items) {
				out.add(CoopItemIds.idOf(item) + "@" + heap.pos + ":" + item.getClass().getSimpleName());
			}
		}
		return out;
	}

	@Test
	void everyHeroClassGetsTheSameFloors() {
		TreeMap<LevelKey, CoopFingerprint> reference = null;
		for (HeroClass cls : HeroClass.values()) {
			TreeMap<LevelKey, CoopFingerprint> floors = CoopRunSim.start(SEED, cls, CoopRunSim.NOTHING).generate(1, 20).floors();
			if (reference == null) {
				reference = floors;
			} else {
				List<String> diffs = CoopDeterminismTest.diff(reference, floors);
				assertTrue(diffs.isEmpty(), cls + " differs from " + HeroClass.values()[0] + ":\n" + String.join("\n", diffs));
			}
		}
	}

	@Test
	void branchTransitionsArePresent() {
		CoopRunSim run = CoopRunSim.start(SEED, HeroClass.WARRIOR, CoopRunSim.NOTHING, true).generateAll();
		boolean anyBranch = false;
		for (Level level : run.levels().values()) {
			for (LevelTransition t : level.transitions) {
				anyBranch |= t.type == LevelTransition.Type.BRANCH_EXIT;
			}
		}
		assertTrue(anyBranch);
	}
}
