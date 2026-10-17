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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.VanillaRun;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfInvisibility;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfMindVision;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfBlink;
import com.shatteredpixel.shatteredpixeldungeon.levels.VaultLevel;
import com.watabou.utils.Random;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

//the one-line upstream hooks (DET-01): order-stable in co-op runs, the exact upstream behavior otherwise
class CoopStableIterationTest {

	@Test
	void stableSortsByClassNameInCoopAndIsTheSameMapInVanilla() {
		HashMap<Class<?>, Float> map = new HashMap<>();
		map.put(PotionOfMindVision.class, 2f);
		map.put(PotionOfHealing.class, 1f);
		map.put(PotionOfInvisibility.class, 4f);

		VanillaRun.startRun(1);
		assertSame(map, CoopLevelGen.stable(map));

		CoopRunSim.start(1, HeroClass.WARRIOR, CoopRunSim.NOTHING);
		HashMap<Class<?>, Float> stable = CoopLevelGen.stable(map);
		assertEquals(LinkedHashMap.class, stable.getClass());
		assertEquals(List.of(PotionOfHealing.class, PotionOfInvisibility.class, PotionOfMindVision.class),
				new ArrayList<>(stable.keySet()));
		assertEquals(map, stable);
	}

	@Test
	void shuffleIsSeededInCoop() {
		CoopRunSim.start(1, HeroClass.WARRIOR, CoopRunSim.NOTHING);
		List<List<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			//a different JDK shuffle history each time: must not matter
			for (int j = 0; j < i * 5 + 1; j++) Collections.shuffle(new ArrayList<>(List.of(1, 2, 3)));
			List<Integer> list = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));
			Random.pushGenerator(99);
			CoopLevelGen.shuffle(list);
			Random.popGenerator();
			results.add(list);
		}
		assertEquals(results.get(0), results.get(1));
	}

	@Test
	void vaultSolveItemsDontDependOnProcessHistory() throws Exception {
		CoopRunSim.start(1, HeroClass.WARRIOR, CoopRunSim.NOTHING);
		Dungeon.depth = 17;
		assertEquals(solveItems(false), solveItems(true),
				"VaultLevel's in-place shuffled arrays must start from one order in co-op");

		//and vanilla keeps upstream's history-dependent behavior (what the hook fixes)
		VanillaRun.startRun(1);
		Dungeon.depth = 17;
		assertNotEquals(solveItems(false), solveItems(true));
	}

	//which solve item 32 vault rooms get, from the declared or reversed starting array order
	private static List<String> solveItems(boolean reversed) throws Exception {
		List<String> picked = new ArrayList<>();
		for (int seed = 0; seed < 32; seed++) {
			for (String name : new String[]{"T2SolveItems", "T3SolveItems"}) {
				Field field = VaultLevel.class.getDeclaredField(name);
				field.setAccessible(true);
				Object[] array = (Object[]) field.get(null);
				Arrays.sort(array, (a, b) -> ((Class<?>) a).getName().compareTo(((Class<?>) b).getName()));
				if (reversed) Collections.reverse(Arrays.asList(array));
			}
			VaultLevel vault = new VaultLevel();
			vault.addItemToSpawn(new StoneOfBlink());
			vault.addItemToSpawn(new PotionOfInvisibility());
			Random.pushGenerator(seed);
			Item item = seed % 2 == 0 ? vault.findT2SolveItem() : vault.findT3SolveItem();
			Random.popGenerator();
			picked.add(item.getClass().getSimpleName());
		}
		return picked;
	}
}
