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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopTestRuns;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelKey;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.VanillaRun;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Drives a whole co-op run for one simulated player (DET-01): start the run with a hero class,
 * generate floors 1..26 and every quest branch (TST-01's canonical order: a branch floor right
 * after the main floor showing its entrance), and let the player "play" between floors.
 */
public final class CoopRunSim {

	/** What the player does between two generated floors. */
	public interface Play {
		void between(int depth, int branch);
	}

	public static final Play NOTHING = (depth, branch) -> {};

	private final TreeMap<LevelKey, CoopFingerprint> floors = new TreeMap<>();
	private final TreeMap<LevelKey, Level> levels = new TreeMap<>();
	private final LinkedHashSet<LevelKey> deferred = new LinkedHashSet<>();
	private final Play play;
	private final boolean keepLevels;

	private CoopRunSim(Play play, boolean keepLevels) {
		this.play = play;
		this.keepLevels = keepLevels;
	}

	/** Starts a new co-op run in this JVM (resets the persistent harness state first). */
	public static CoopRunSim start(long seed, HeroClass heroClass, Play play) {
		return start(seed, heroClass, play, false);
	}

	public static CoopRunSim start(long seed, HeroClass heroClass, Play play, boolean keepLevels) {
		HeadlessEnv.start();
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		CoopTestRuns.begin(seed, heroClass);
		Dungeon.init();
		if (!Coop.isCoopRun()) {
			throw new IllegalStateException("the run did not start as a co-op run");
		}
		return new CoopRunSim(play, keepLevels);
	}

	/** Continues with a run that was set up by the caller (e.g. after a simulated reload). */
	public static CoopRunSim attach(Play play) {
		return new CoopRunSim(play, false);
	}

	/** Generates main-branch depths {@code from..to} (plus their quest branches), in run order. */
	public CoopRunSim generate(int from, int to) {
		return generate(from, to, true);
	}

	/**
	 * Main-branch depths {@code from..to}; quest branch floors right after their entrance if
	 * {@code branchesNow}, otherwise only remembered for {@link #generateDeferredBranches()}
	 * (a player who enters the mine or the vault late, or after the next floors).
	 */
	public CoopRunSim generate(int from, int to, boolean branchesNow) {
		for (int depth = from; depth <= to; depth++) {
			Level main = floor(depth, 0);
			LinkedHashSet<LevelKey> branches = new LinkedHashSet<>();
			for (LevelTransition transition : main.transitions) {
				if (transition.type == LevelTransition.Type.BRANCH_EXIT) {
					branches.add(new LevelKey(transition.destDepth, transition.destBranch));
				}
			}
			for (LevelKey branch : branches) {
				if (floors.containsKey(branch)) continue;
				if (branchesNow) {
					floor(branch.depth(), branch.branch());
				} else {
					deferred.add(branch);
				}
			}
		}
		return this;
	}

	/** The deferred quest branch floors, last entrance first. */
	public CoopRunSim generateDeferredBranches() {
		List<LevelKey> keys = new ArrayList<>(deferred);
		Collections.reverse(keys);
		for (LevelKey key : keys) {
			floor(key.depth(), key.branch());
		}
		deferred.clear();
		return this;
	}

	public CoopRunSim generateAll() {
		return generate(1, VanillaRun.MAX_DEPTH);
	}

	private Level floor(int depth, int branch) {
		Dungeon.depth = depth;
		Dungeon.branch = branch;
		Level level = Dungeon.newLevel();
		LevelKey key = new LevelKey(depth, branch);
		floors.put(key, CoopFingerprint.of(level, depth, branch));
		if (keepLevels) {
			levels.put(key, level);
		}
		play.between(depth, branch);
		return level;
	}

	public TreeMap<LevelKey, CoopFingerprint> floors() {
		return floors;
	}

	public TreeMap<LevelKey, Level> levels() {
		return levels;
	}

	/** One line per floor ("depth-branch all-digest"), the form forked JVMs print. */
	public String digestLines() {
		StringBuilder sb = new StringBuilder();
		floors.forEach((key, fp) -> sb.append(key.id()).append(' ').append(fp.all()).append('\n'));
		return sb.toString();
	}
}
