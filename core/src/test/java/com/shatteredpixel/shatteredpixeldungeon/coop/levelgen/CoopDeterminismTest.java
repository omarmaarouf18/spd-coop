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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Ghost;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Wandmaker;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopTestRuns;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelKey;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.VanillaSeeds;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.watabou.utils.Bundle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

//D-019 / DET-01: in a co-op run, floor N is the same for both players however differently they
// played floors 1..N-1 and whichever hero they picked
class CoopDeterminismTest {

	//three of the vanilla golden seeds: they reach shops, secret rooms, both quest branches
	static final long[] SEEDS = {123456789L, 42L, VanillaSeeds.TEXT_HASH_SEED};

	@Test
	void bothPlayersGetIdenticalFloors() {
		for (long seed : SEEDS) {
			TreeMap<LevelKey, CoopFingerprint> a = CoopRunSim.start(seed, HeroClass.WARRIOR, CoopRunSim.NOTHING)
					.generateAll().floors();
			TreeMap<LevelKey, CoopFingerprint> b = CoopRunSim.start(seed, HeroClass.MAGE, new HeavyPlay())
					.generateAll().floors();
			List<String> diffs = diff(a, b);
			assertTrue(diffs.isEmpty(), "seed " + seed + ": player A and B floors differ:\n" + String.join("\n", diffs));
			assertTrue(a.keySet().stream().anyMatch(k -> k.branch() == 1),
					"seed " + seed + ": no quest branch floor was generated, the test covers less than it claims");
		}
	}

	@Test
	void branchFloorsDontDependOnWhenTheyAreEntered() {
		//A enters every quest branch as soon as its entrance appears; B finishes the main dungeon
		// first and enters the branches at the very end, last one first
		for (long seed : SEEDS) {
			TreeMap<LevelKey, CoopFingerprint> a = CoopRunSim.start(seed, HeroClass.WARRIOR, CoopRunSim.NOTHING)
					.generateAll().floors();
			TreeMap<LevelKey, CoopFingerprint> b = CoopRunSim.start(seed, HeroClass.ROGUE, new HeavyPlay())
					.generate(1, 26, false).generateDeferredBranches().floors();
			List<String> diffs = diff(a, b);
			assertTrue(diffs.isEmpty(), "seed " + seed + ": entering the branches late changes floors:\n" + String.join("\n", diffs));
		}
	}

	@Test
	void withoutIsolationHeavyPlayChangesTheFloors() {
		//the control: the same perturbations with the pre-DET-01 co-op generation must diverge,
		// otherwise bothPlayersGetIdenticalFloors proves nothing
		CoopLevelGen.isolation = false;
		try {
			long seed = SEEDS[0];
			TreeMap<LevelKey, CoopFingerprint> a = CoopRunSim.start(seed, HeroClass.WARRIOR, CoopRunSim.NOTHING)
					.generateAll().floors();
			TreeMap<LevelKey, CoopFingerprint> b = CoopRunSim.start(seed, HeroClass.MAGE, new HeavyPlay())
					.generateAll().floors();
			List<String> diffs = diff(a, b);
			assertFalse(diffs.isEmpty(), "heavy play didn't change any floor without the isolation");
			System.out.println("[DET-01 control] floors that differ without isolation: " + diffs.size()
					+ " of " + a.size() + "\n  " + String.join("\n  ", diffs));
		} finally {
			CoopLevelGen.isolation = true;
		}
	}

	@Test
	void saveAndLoadMidRunContinuesIdentically() throws IOException {
		long seed = SEEDS[0];
		int k = 9;
		TreeMap<LevelKey, CoopFingerprint> straight = CoopRunSim.start(seed, HeroClass.MAGE, new HeavyPlay())
				.generateAll().floors();

		CoopRunSim first = CoopRunSim.start(seed, HeroClass.MAGE, new HeavyPlay()).generate(1, k);
		//"save": what Dungeon.saveGame persists of the systems involved, plus the co-op run
		Bundle save = new Bundle();
		save.put("runtime", LevelGenState.capture());
		Bundle quests = new Bundle();
		Ghost.Quest.storeInBundle(quests);
		Wandmaker.Quest.storeInBundle(quests);
		Blacksmith.Quest.storeInBundle(quests);
		Imp.Quest.storeInBundle(quests);
		save.put("quests", quests);
		save.put(Coop.BUNDLE_KEY, Coop.run());
		Bundle loaded = Bundle.read(new ByteArrayInputStream(save.toString().getBytes(StandardCharsets.UTF_8)));

		//"new process": dirty every static the save doesn't carry with a different co-op run
		CoopRunSim.start(seed + 1, HeroClass.ROGUE, new HeavyPlay()).generate(1, 20);
		HeadlessEnv.resetPersistentState();

		//"load": Dungeon.loadGame restores these systems and the CoopRun from the save
		CoopTestRuns.resume((CoopRun) loaded.get(Coop.BUNDLE_KEY));
		Dungeon.hero = null;
		Dungeon.init();
		LevelGenState.apply(loaded.getBundle("runtime"));
		Bundle q = loaded.getBundle("quests");
		Ghost.Quest.restoreFromBundle(q);
		Wandmaker.Quest.restoreFromBundle(q);
		Blacksmith.Quest.restoreFromBundle(q);
		Imp.Quest.restoreFromBundle(q);
		assertTrue(Coop.isCoopRun());
		assertTrue(Coop.run().levelGen != null, "the level-gen state must be in the co-op save");

		TreeMap<LevelKey, CoopFingerprint> resumed = new TreeMap<>(first.floors());
		resumed.putAll(CoopRunSim.attach(new HeavyPlay()).generate(k + 1, 26).floors());
		List<String> diffs = diff(straight, resumed);
		assertTrue(diffs.isEmpty(), "floors after a save/load at depth " + k + " differ:\n" + String.join("\n", diffs));
	}

	static List<String> diff(Map<LevelKey, CoopFingerprint> a, Map<LevelKey, CoopFingerprint> b) {
		List<String> diffs = new ArrayList<>();
		for (LevelKey key : a.keySet()) {
			if (!b.containsKey(key)) {
				diffs.add(key + ": only in the first run");
			} else if (!a.get(key).all().equals(b.get(key).all())) {
				diffs.add(key + ": differs in " + a.get(key).diffAgainst(b.get(key)) + firstLineDiff(a.get(key), b.get(key)));
			}
		}
		for (LevelKey key : b.keySet()) {
			if (!a.containsKey(key)) diffs.add(key + ": only in the second run");
		}
		return diffs;
	}

	//the first differing line of a text component, so a failure says what differs
	private static String firstLineDiff(CoopFingerprint a, CoopFingerprint b) {
		for (String component : a.diffAgainst(b)) {
			String x = a.canonical(component), y = b.canonical(component);
			if (x == null || y == null) continue;
			String[] xl = x.split("\n"), yl = y.split("\n");
			for (int i = 0; i < Math.max(xl.length, yl.length); i++) {
				String l = i < xl.length ? xl[i] : "<none>", r = i < yl.length ? yl[i] : "<none>";
				if (!l.equals(r)) return "\n    " + component + " A: " + l + "\n    " + component + " B: " + r;
			}
		}
		return "";
	}

	@Test
	void runStateIsRestoredAfterACoopFloor() {
		CoopRunSim.start(SEEDS[0], HeroClass.HUNTRESS, CoopRunSim.NOTHING).generate(1, 3);
		Bundle before = LevelGenState.capture();
		Object hero = Dungeon.hero;
		CoopRunSim.attach(CoopRunSim.NOTHING).generate(4, 4);
		Bundle after = LevelGenState.capture();

		assertTrue(Dungeon.hero == hero, "the player's hero must be put back");
		//the gameplay decks are untouched by level gen (artifacts aside, see mergeArtifacts)
		Bundle genBefore = before.getBundle("generator");
		Bundle genAfter = after.getBundle("generator");
		for (String key : genBefore.getKeys()) {
			if (key.startsWith("artifact")) continue;
			assertEquals(extract(genBefore, key), extract(genAfter, key), "runtime Generator." + key + " changed");
		}
		assertEquals(4f, Generator.Category.FOOD.defaultProbs[0]);
		assertEquals(0f, Generator.Category.FOOD.firstProb);
	}

	private static String extract(Bundle bundle, String key) {
		//Bundle has no generic getter; the JSON text of one key is enough to compare
		String json = bundle.toString();
		int at = json.indexOf("\"" + key + "\":");
		int end = at + key.length() + 3;
		int depth = 0;
		for (; end < json.length(); end++) {
			char c = json.charAt(end);
			if (c == '[' || c == '{') depth++;
			if (c == ']' || c == '}') {
				if (depth == 0) break;
				depth--;
			}
			if (c == ',' && depth == 0) break;
		}
		return json.substring(at, end);
	}
}
