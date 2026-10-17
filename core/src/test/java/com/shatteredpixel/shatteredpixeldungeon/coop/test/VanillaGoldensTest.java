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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares fresh vanilla generation against the recorded goldens (TST-01).
 *
 * <p>Plain {@code ./gradlew :core:test} never writes goldens. Regeneration is
 * explicit: run {@code ./gradlew regenGoldens} (which passes
 * {@code -Dgoldens.regen=true}) and commit the diff.
 */
class VanillaGoldensTest {

	@Test
	void floorFingerprintsMatchGolden() {
		Path goldenPath = Path.of(System.getProperty("llpd.goldens.file"));

		if (Boolean.getBoolean("goldens.regen")) {
			GoldenDoc.generate(VanillaSeeds.seeds()).write(goldenPath);
			return;
		}

		GoldenDoc golden = GoldenDoc.read(goldenPath);
		Assertions.assertEquals(GoldenDoc.UPSTREAM, golden.upstream,
				"golden file " + goldenPath + " was generated from a different upstream version");
		Assertions.assertEquals(GoldenDoc.HERO, golden.hero,
				"golden file " + goldenPath + " was generated with a different hero");
		Assertions.assertEquals(GoldenDoc.CHALLENGES, golden.challenges,
				"golden file " + goldenPath + " was generated with different challenges");
		Assertions.assertEquals(new TreeSet<>(VanillaSeeds.seeds().keySet()), golden.seedOrigins.keySet(),
				"golden file " + goldenPath + " covers different seeds than VanillaSeeds");

		List<String> failures = new ArrayList<>();
		for (Map.Entry<Long, TreeMap<LevelKey, Map<String, String>>> seed : golden.seeds.entrySet()) {
			TreeMap<LevelKey, LevelFingerprint> generated = VanillaRun.generate(seed.getKey());
			for (Map.Entry<LevelKey, Map<String, String>> expectedFloor : seed.getValue().entrySet()) {
				LevelFingerprint actual = generated.get(expectedFloor.getKey());
				if (actual == null) {
					failures.add("seed=" + seed.getKey() + " floor " + expectedFloor.getKey() + ": not generated");
					continue;
				}
				List<String> differing = actual.diffComponents(expectedFloor.getValue());
				if (!differing.isEmpty()) {
					StringBuilder failure = new StringBuilder("seed=" + seed.getKey() + " floor "
							+ expectedFloor.getKey() + ": differs in " + differing);
					if (differing.contains("map")) {
						failure.append(" (").append(actual.describeMapDiff(expectedFloor.getValue().get("mapRle"))).append(')');
					}
					failures.add(failure.toString());
				}
			}
			for (LevelKey generatedFloor : generated.keySet()) {
				if (!seed.getValue().containsKey(generatedFloor)) {
					failures.add("seed=" + seed.getKey() + " floor " + generatedFloor + ": generated but not in golden");
				}
			}
		}

		Assertions.assertTrue(failures.isEmpty(), () ->
				"vanilla deterministic generation differs from " + goldenPath + " (upstream " + golden.upstream + "):\n"
						+ String.join("\n", failures));
	}
}
