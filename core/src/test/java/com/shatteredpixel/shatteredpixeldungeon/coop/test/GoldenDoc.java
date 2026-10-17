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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads, writes and generates the vanilla determinism golden file (TST-01).
 *
 * <p>The golden records, for a fixed upstream version / hero / challenge setup,
 * one entry per generated floor per seed: the floor digest, per-component
 * digests and the map's run-length encoding (for readable map diffs).
 */
public final class GoldenDoc {

	/** Upstream version the goldens were generated from. */
	public static final String UPSTREAM = "v4.0.2";
	/** Hero class used for generation. */
	public static final String HERO = "WARRIOR";
	/** Challenges enabled (none) for generation. */
	public static final int CHALLENGES = 0;

	public String upstream = UPSTREAM;
	public String hero = HERO;
	public int challenges = CHALLENGES;

	/** Seed value to human description of where it comes from. */
	public TreeMap<Long, String> seedOrigins = new TreeMap<>();
	/** Seed value to floors, floors keyed by depth and branch. */
	public TreeMap<Long, TreeMap<LevelKey, Map<String, String>>> seeds = new TreeMap<>();

	public static GoldenDoc generate(TreeMap<Long, String> origins) {
		GoldenDoc doc = new GoldenDoc();
		doc.seedOrigins.putAll(origins);
		for (long seed : origins.keySet()) {
			TreeMap<LevelKey, LevelFingerprint> generated = VanillaRun.generate(seed);
			TreeMap<LevelKey, Map<String, String>> floors = new TreeMap<>();
			for (Map.Entry<LevelKey, LevelFingerprint> floor : generated.entrySet()) {
				floors.put(floor.getKey(), floor.getValue().asGoldenEntry());
			}
			doc.seeds.put(seed, floors);
		}
		return doc;
	}

	public static GoldenDoc read(Path path) {
		try {
			return fromJson(Json.parseObject(Files.readString(path, StandardCharsets.UTF_8)));
		} catch (IOException e) {
			throw new UncheckedIOException("cannot read goldens from " + path, e);
		}
	}

	public void write(Path path) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, Json.write(toJson()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("cannot write goldens to " + path, e);
		}
	}

	// ----- json ------------------------------------------------------------

	Map<String, Object> toJson() {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("format", "llpd-vanilla-goldens");
		root.put("upstream", upstream);
		root.put("hero", hero);
		root.put("challenges", (long) challenges);
		root.put("generatedBy", "TST-01 (core/src/test)");

		Map<String, Object> origins = new LinkedHashMap<>();
		for (Map.Entry<Long, String> origin : seedOrigins.entrySet()) {
			origins.put(String.valueOf(origin.getKey()), origin.getValue());
		}
		root.put("seedOrigins", origins);

		Map<String, Object> seedsJson = new LinkedHashMap<>();
		for (Map.Entry<Long, TreeMap<LevelKey, Map<String, String>>> seed : seeds.entrySet()) {
			Map<String, Object> floorsJson = new LinkedHashMap<>();
			for (Map.Entry<LevelKey, Map<String, String>> floor : seed.getValue().entrySet()) {
				floorsJson.put(floor.getKey().id(), new LinkedHashMap<>(floor.getValue()));
			}
			seedsJson.put(String.valueOf(seed.getKey()), floorsJson);
		}
		root.put("seeds", seedsJson);

		return root;
	}

	@SuppressWarnings("unchecked")
	static GoldenDoc fromJson(Map<String, Object> root) {
		GoldenDoc doc = new GoldenDoc();
		doc.upstream = (String) root.get("upstream");
		doc.hero = (String) root.get("hero");
		doc.challenges = (int) (long) (Long) root.get("challenges");

		Map<String, Object> origins = (Map<String, Object>) root.get("seedOrigins");
		for (Map.Entry<String, Object> origin : origins.entrySet()) {
			doc.seedOrigins.put(Long.parseLong(origin.getKey()), (String) origin.getValue());
		}

		Map<String, Object> seedsJson = (Map<String, Object>) root.get("seeds");
		for (Map.Entry<String, Object> seed : seedsJson.entrySet()) {
			Map<String, Object> floorsJson = (Map<String, Object>) seed.getValue();
			TreeMap<LevelKey, Map<String, String>> floors = new TreeMap<>();
			for (Map.Entry<String, Object> floor : floorsJson.entrySet()) {
				Map<String, Object> entry = (Map<String, Object>) floor.getValue();
				Map<String, String> components = new LinkedHashMap<>();
				for (Map.Entry<String, Object> component : entry.entrySet()) {
					components.put(component.getKey(), (String) component.getValue());
				}
				floors.put(LevelKey.parse(floor.getKey()), components);
			}
			doc.seeds.put(Long.parseLong(seed.getKey()), floors);
		}

		if (doc.seedOrigins.isEmpty() || doc.seeds.isEmpty()) {
			throw new IllegalArgumentException("golden file has no seeds");
		}
		return doc;
	}
}
