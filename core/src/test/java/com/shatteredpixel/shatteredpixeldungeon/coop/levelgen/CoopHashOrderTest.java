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

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

//D-019: co-op floors must not depend on identity-hash order. The test JVM pins it with
// -XX:hashCode=2 (TST-01, needed for the vanilla goldens), and ART/RoboVM can't do that, so this
// test forks two JVMs with default hashing and different allocation histories, and checks they
// generate the same co-op floors as each other, as both players, and as this (pinned) JVM.
class CoopHashOrderTest {

	private static final long SEED = 123456789L;

	@Test
	void coopFloorsDontDependOnIdentityHashes() throws Exception {
		assertTrue(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:hashCode=2"),
				"expected the pinned test JVM; if the build stopped pinning it, this test needs revisiting");
		String pinned = CoopRunSim.start(SEED, HeroClass.WARRIOR, CoopRunSim.NOTHING).generateAll().digestLines().trim();

		Fork first = fork(0);
		Fork second = fork(200_000);
		for (Fork f : new Fork[]{first, second}) {
			assertFalse(f.jvmArgs.contains("hashCode"), "forked JVM must use default identity hashes: " + f.jvmArgs);
			assertEquals(f.a, f.b, "players A and B differ in a JVM with default hashing (warmup " + f.warmup + ")");
			assertEquals(pinned, f.a, "default-hash JVM (warmup " + f.warmup + ") differs from the pinned test JVM");
		}
		assertEquals(first.a, second.a);
		System.out.println("[DET-01 hash] fork 1: " + first.jvmArgs);
		System.out.println("[DET-01 hash] fork 2: " + second.jvmArgs);
		System.out.println("[DET-01 hash] co-op floors identical across pinned JVM, 2 default-hash forks, A and B: "
				+ pinned.split("\n").length + " floors");
		System.out.println("[DET-01 hash] vanilla, same seed, fork 1 vs fork 2: "
				+ (first.v.equals(second.v) ? "identical" : "DIFFERENT (" + countDiff(first.v, second.v) + " floors)"));
	}

	private static long countDiff(String x, String y) {
		String[] a = x.split("\n"), b = y.split("\n");
		long n = 0;
		for (int i = 0; i < Math.min(a.length, b.length); i++) if (!a[i].equals(b[i])) n++;
		return n + Math.abs(a.length - b.length);
	}

	private static final class Fork {
		int warmup;
		String jvmArgs = "";
		String a, b, v;
	}

	private static Fork fork(int warmup) throws IOException, InterruptedException {
		String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
		List<String> cmd = new ArrayList<>();
		cmd.add(java);
		//HeadlessEnv pins java.util.Collections' Random by reflection (TST-01); not a hash flag
		cmd.add("--add-opens");
		cmd.add("java.base/java.util=ALL-UNNAMED");
		cmd.add("-cp");
		cmd.add(System.getProperty("java.class.path"));
		cmd.add(CoopForkMain.class.getName());
		cmd.add(String.valueOf(SEED));
		cmd.add(String.valueOf(warmup));
		Process p = new ProcessBuilder(cmd).directory(new File(System.getProperty("user.dir")))
				.redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(p.waitFor(5, TimeUnit.MINUTES), "fork timed out");
		assertEquals(0, p.exitValue(), "fork failed:\n" + out);

		Fork f = new Fork();
		f.warmup = warmup;
		List<String> lines = out.lines().collect(Collectors.toList());
		for (String line : lines) {
			if (line.startsWith("JVMARGS ")) f.jvmArgs = line.substring(8);
		}
		f.a = tagged(lines, "A ");
		f.b = tagged(lines, "B ");
		f.v = tagged(lines, "V ");
		assertFalse(f.a.isEmpty(), "fork printed no floors:\n" + out);
		return f;
	}

	private static String tagged(List<String> lines, String tag) {
		return lines.stream().filter(l -> l.startsWith(tag)).map(l -> l.substring(tag.length()))
				.collect(Collectors.joining("\n"));
	}
}
