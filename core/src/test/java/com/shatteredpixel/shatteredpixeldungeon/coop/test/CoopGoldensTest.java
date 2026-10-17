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

package com.shatteredpixel.shatteredpixeldungeon.coop.test;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopFingerprint;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopRunSim;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Co-op determinism goldens (DET-01): the same seeds as the vanilla goldens, generated as a co-op
 * run (co-op level gen, balance, itemNetIds), fingerprinted with {@link CoopFingerprint}. They lock
 * co-op level gen against regressions; {@code CoopDeterminismTest} proves both players match them.
 *
 * <p>Same regeneration rules as the vanilla goldens (WORKFLOW §6): never casually. The
 * {@code regenGoldens} task only covers the vanilla file, so regenerate this one with
 * {@code ./gradlew :core:test --tests '*CoopGoldensTest' -Dgoldens.regen=true} and review the diff.
 */
class CoopGoldensTest {

	static final String FORMAT = "llpd-coop-goldens";
	static final String HERO = "WARRIOR";

	@Test
	void coopFloorFingerprintsMatchGolden() throws IOException {
		Path path = Path.of(System.getProperty("llpd.goldens.file")).resolveSibling("coop-v4.0.2.json");
		Map<String, Object> generated = generate();

		if (Boolean.getBoolean("goldens.regen")) {
			Files.writeString(path, Json.write(generated), StandardCharsets.UTF_8);
			return;
		}

		Map<String, Object> golden;
		try {
			golden = Json.parseObject(Files.readString(path, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException("cannot read co-op goldens from " + path, e);
		}
		Assertions.assertEquals(FORMAT, golden.get("format"));
		Assertions.assertEquals(GoldenDoc.UPSTREAM, golden.get("upstream"));
		Assertions.assertEquals(HERO, golden.get("hero"));

		List<String> failures = new ArrayList<>();
		Map<?, ?> goldenSeeds = (Map<?, ?>) golden.get("seeds");
		Map<?, ?> generatedSeeds = (Map<?, ?>) generated.get("seeds");
		Assertions.assertEquals(goldenSeeds.keySet(), generatedSeeds.keySet(), "co-op goldens cover different seeds");
		for (Object seed : goldenSeeds.keySet()) {
			Map<?, ?> expectedFloors = (Map<?, ?>) goldenSeeds.get(seed);
			Map<?, ?> actualFloors = (Map<?, ?>) generatedSeeds.get(seed);
			for (Object floor : expectedFloors.keySet()) {
				Map<?, ?> expected = (Map<?, ?>) expectedFloors.get(floor);
				Map<?, ?> actual = (Map<?, ?>) actualFloors.get(floor);
				if (actual == null) {
					failures.add("seed=" + seed + " floor " + floor + ": not generated");
				} else if (!expected.equals(actual)) {
					List<String> differing = new ArrayList<>();
					for (Object component : expected.keySet()) {
						if (!expected.get(component).equals(actual.get(component))) differing.add(String.valueOf(component));
					}
					failures.add("seed=" + seed + " floor " + floor + ": differs in " + differing);
				}
			}
			for (Object floor : actualFloors.keySet()) {
				if (!expectedFloors.containsKey(floor)) failures.add("seed=" + seed + " floor " + floor + ": not in golden");
			}
		}
		Assertions.assertTrue(failures.isEmpty(), () -> "co-op generation differs from " + path + ":\n" + String.join("\n", failures));
	}

	static Map<String, Object> generate() {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("format", FORMAT);
		root.put("upstream", GoldenDoc.UPSTREAM);
		root.put("hero", HERO);
		root.put("generatedBy", "DET-01 (core/src/test)");
		TreeMap<Long, String> origins = VanillaSeeds.seeds();
		Map<String, Object> originsJson = new LinkedHashMap<>();
		Map<String, Object> seeds = new LinkedHashMap<>();
		for (Map.Entry<Long, String> seed : origins.entrySet()) {
			originsJson.put(String.valueOf(seed.getKey()), seed.getValue());
			Map<String, Object> floors = new LinkedHashMap<>();
			TreeMap<LevelKey, CoopFingerprint> run = CoopRunSim.start(seed.getKey(), HeroClass.valueOf(HERO),
					CoopRunSim.NOTHING).generateAll().floors();
			for (Map.Entry<LevelKey, CoopFingerprint> floor : run.entrySet()) {
				floors.put(floor.getKey().id(), new LinkedHashMap<>(floor.getValue().asGoldenEntry()));
			}
			seeds.put(String.valueOf(seed.getKey()), floors);
		}
		root.put("seedOrigins", originsJson);
		root.put("seeds", seeds);
		return root;
	}
}
