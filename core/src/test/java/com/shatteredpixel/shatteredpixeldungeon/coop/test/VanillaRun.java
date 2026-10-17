/*
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
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
 *
 * Logic Link PD — Copyright (C) 2026 Logic Link PD contributors
 */

package com.shatteredpixel.shatteredpixeldungeon.coop.test;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;

import java.util.LinkedHashSet;
import java.util.TreeMap;

/**
 * Generates a whole vanilla (upstream v4.0.2) seeded run and fingerprints
 * every floor (TST-01).
 *
 * <p>This calls upstream generation directly and only adds the run setup a
 * player start would perform, without touching any game code. Floors are
 * generated in run order, main branch first, and quest branches (caves mine,
 * city vaults) are generated immediately when their entrance appears on the
 * main-branch floor. Generating a branch floor when its entrance is found is a
 * convention: in a real run the order depends on when the player enters, and
 * {@code Generator}'s decks are order-sensitive, so the golden only promises
 * this canonical order.
 */
public final class VanillaRun {

	/** Depth 26 is the final floor; goldens cover a full run. */
	public static final int MAX_DEPTH = 26;

	private VanillaRun() {
	}

	/** Seed setup mirrored from what a new seeded run does before its first floor. */
	public static void startRun(long seed) {
		if (seed < 0 || seed >= DungeonSeed.TOTAL_SEEDS) {
			throw new IllegalArgumentException("seed outside [0, TOTAL_SEEDS): " + seed);
		}
		HeadlessEnv.start();
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = false;
		Dungeon.dailyReplay = false;
		Dungeon.customSeedText = "";
		Dungeon.seed = seed;
		Dungeon.init();
	}

	/** All floors of a whole run for one seed, keyed by depth and branch. */
	public static TreeMap<LevelKey, LevelFingerprint> generate(long seed) {
		startRun(seed);

		TreeMap<LevelKey, LevelFingerprint> floors = new TreeMap<>();
		for (int depth = 1; depth <= MAX_DEPTH; depth++) {
			Level main = generateFloor(depth, 0);
			floors.put(new LevelKey(depth, 0), LevelFingerprint.of(main, depth, 0));

			LinkedHashSet<LevelKey> questFloors = new LinkedHashSet<>();
			for (LevelTransition transition : main.transitions) {
				if (transition.type == LevelTransition.Type.BRANCH_EXIT) {
					questFloors.add(new LevelKey(transition.destDepth, transition.destBranch));
				}
			}
			for (LevelKey questFloor : questFloors) {
				if (floors.containsKey(questFloor)) {
					continue;
				}
				Level branch = generateFloor(questFloor.depth(), questFloor.branch());
				floors.put(questFloor, LevelFingerprint.of(branch, questFloor.depth(), questFloor.branch()));
			}
		}
		return floors;
	}

	private static Level generateFloor(int depth, int branch) {
		Dungeon.depth = depth;
		Dungeon.branch = branch;
		return Dungeon.newLevel();
	}
}
