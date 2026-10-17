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

import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;

import java.util.TreeMap;

/**
 * The seeds recorded in the vanilla goldens (TST-01).
 *
 * <p>Nine seeds covering the seed range boundaries, ordinary values, and all
 * three {@link DungeonSeed#convertFromText(String)} input paths (seed code,
 * numeric text, arbitrary text). Every origin is stored in the golden file so
 * a failure report can say which seed was meant.
 */
public final class VanillaSeeds {

	public static final long MAX_SEED = DungeonSeed.TOTAL_SEEDS - 1;

	public static final long TEXT_HASH_SEED = DungeonSeed.convertFromText("logic link pd");
	public static final long NUMERIC_TEXT_SEED = DungeonSeed.convertFromText("2026-10-17");
	public static final long CODE_SEED = DungeonSeed.convertFromText("ABC-ABC-ABC");

	private VanillaSeeds() {
	}

	/** Seed value to origin description, in seed order. */
	public static TreeMap<Long, String> seeds() {
		TreeMap<Long, String> seeds = new TreeMap<>();
		add(seeds, 0L, "numeric zero (low boundary)");
		add(seeds, 1L, "numeric one (low boundary)");
		add(seeds, 42L, "numeric 42");
		add(seeds, 123456789L, "numeric 123456789");
		add(seeds, DungeonSeed.TOTAL_SEEDS / 2, "numeric middle of the seed range");
		add(seeds, MAX_SEED, "numeric TOTAL_SEEDS-1 (high boundary)");
		add(seeds, TEXT_HASH_SEED, "text-hash seed \"logic link pd\"");
		add(seeds, NUMERIC_TEXT_SEED, "text seed \"2026-10-17\"");
		add(seeds, CODE_SEED, "seed code \"ABC-ABC-ABC\"");
		return seeds;
	}

	private static void add(TreeMap<Long, String> seeds, long seed, String origin) {
		String previous = seeds.put(seed, origin + " = " + seed + " (" + DungeonSeed.convertToCode(seed) + ")");
		if (previous != null) {
			throw new IllegalStateException("duplicate seed " + seed + ": " + previous + " vs " + origin);
		}
	}
}
