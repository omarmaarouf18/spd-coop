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

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelFingerprint;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelKey;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.VanillaRun;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Entry point of the forked JVMs in {@link CoopHashOrderTest}: runs WITHOUT the test JVM's
 * {@code -XX:hashCode=2}, i.e. with the default identity hashes a phone has. Prints its JVM
 * arguments, then each run's floor digests: {@code A}/{@code B} (co-op players), {@code V}
 * (vanilla, for the record: upstream's HashMap iterations can make it vary).
 *
 * <p>args: seed, warm-up allocations (a different warm-up shifts identity hashes).
 */
public final class CoopForkMain {

	private static final List<Object> KEEP = new ArrayList<>();

	public static void main(String[] args) {
		long seed = Long.parseLong(args[0]);
		int warmup = Integer.parseInt(args[1]);
		long sink = 0;
		for (int i = 0; i < warmup; i++) {
			Object o = new Object();
			sink += System.identityHashCode(o);
			if (i % 7 == 0) KEEP.add(o);
		}
		System.out.println("JVMARGS " + ManagementFactory.getRuntimeMXBean().getInputArguments() + " warmup=" + warmup + " sink=" + (sink != 1));

		print("A", CoopRunSim.start(seed, HeroClass.WARRIOR, CoopRunSim.NOTHING).generateAll().digestLines());
		print("B", CoopRunSim.start(seed, HeroClass.MAGE, new HeavyPlay()).generateAll().digestLines());

		StringBuilder vanilla = new StringBuilder();
		for (Map.Entry<LevelKey, LevelFingerprint> floor : new TreeMap<>(VanillaRun.generate(seed)).entrySet()) {
			vanilla.append(floor.getKey().id()).append(' ').append(floor.getValue().all()).append('\n');
		}
		print("V", vanilla.toString());
		System.out.flush();
		//the headless libGDX application keeps a non-daemon thread alive
		System.exit(0);
	}

	private static void print(String tag, String lines) {
		for (String line : lines.split("\n")) {
			if (!line.isEmpty()) System.out.println(tag + " " + line);
		}
	}
}
