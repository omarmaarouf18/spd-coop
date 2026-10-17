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

import org.junit.jupiter.api.Test;

import java.util.TreeMap;

/**
 * A seed's floors must not depend on what was generated before it in the same
 * process (TST-01).
 *
 * <p>This is what makes it safe for the co-op mod to generate extra levels:
 * as long as generation starts the run the same way, leftover run state (or an
 * unlucky map iteration order) must not bleed into the next run. The build
 * forks a JVM per test class, so the first {@code generate} call here is the
 * first generation in this process.
 */
class IsolationTest {

	private static final long SEED_FIRST = VanillaSeeds.TEXT_HASH_SEED;
	private static final long SEED_SECOND = VanillaSeeds.NUMERIC_TEXT_SEED;

	@Test
	void seedOutputIsIndependentOfGenerationOrder() {
		TreeMap<LevelKey, LevelFingerprint> secondAlone = VanillaRun.generate(SEED_SECOND);
		TreeMap<LevelKey, LevelFingerprint> firstAlone = VanillaRun.generate(SEED_FIRST);

		FingerprintAssertions.assertSameGeneration(secondAlone, VanillaRun.generate(SEED_SECOND),
				"seed " + SEED_SECOND + " generated after seed " + SEED_FIRST);
		FingerprintAssertions.assertSameGeneration(firstAlone, VanillaRun.generate(SEED_FIRST),
				"seed " + SEED_FIRST + " generated after seed " + SEED_SECOND);
	}
}
