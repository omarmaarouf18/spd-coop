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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Failure messages for fingerprint comparisons (TST-01).
 *
 * <p>When two generated runs differ, the message names the first differing
 * floor, which components differ, and for maps the first differing cell, so a
 * determinism break can be diagnosed without re-running by hand.
 */
final class FingerprintAssertions {

	private FingerprintAssertions() {
	}

	static void assertSameGeneration(TreeMap<LevelKey, LevelFingerprint> expected,
			TreeMap<LevelKey, LevelFingerprint> actual, String context) {
		List<String> failures = new ArrayList<>();
		Set<LevelKey> keys = new TreeSet<>();
		keys.addAll(expected.keySet());
		keys.addAll(actual.keySet());
		for (LevelKey key : keys) {
			LevelFingerprint expectedFloor = expected.get(key);
			LevelFingerprint actualFloor = actual.get(key);
			if (expectedFloor == null || actualFloor == null) {
				failures.add("floor " + key + ": " + (expectedFloor == null ? "missing in first run" : "missing in second run"));
				continue;
			}
			List<String> differing = actualFloor.diffAgainst(expectedFloor);
			if (!differing.isEmpty()) {
				failures.add(describe(key, differing, expectedFloor, actualFloor));
			}
		}
		Assertions.assertTrue(failures.isEmpty(),
				() -> context + ": generated floors differ:\n" + String.join("\n", failures));
	}

	static String describe(LevelKey key, List<String> differing,
			LevelFingerprint expected, LevelFingerprint actual) {
		StringBuilder message = new StringBuilder();
		message.append("floor ").append(key).append(": differs in ").append(differing);
		if (differing.contains("map")) {
			message.append(" (").append(actual.describeMapDiff(expected.mapRle())).append(')');
		}
		return message.toString();
	}
}
