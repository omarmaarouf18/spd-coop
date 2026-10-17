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

import java.util.Objects;

/**
 * Identifies one generated floor as {@code "<depth>-<branch>"} (TST-01).
 *
 * <p>Natural ordering is by depth, then branch, so golden files list floors in
 * the order a run generates them.
 */
public final class LevelKey implements Comparable<LevelKey> {

	private final int depth;
	private final int branch;

	public LevelKey(int depth, int branch) {
		this.depth = depth;
		this.branch = branch;
	}

	public static LevelKey parse(String text) {
		int dash = text.indexOf('-');
		if (dash < 0) {
			throw new IllegalArgumentException("not a level key: " + text);
		}
		return new LevelKey(
				Integer.parseInt(text.substring(0, dash)),
				Integer.parseInt(text.substring(dash + 1)));
	}

	public int depth() {
		return depth;
	}

	public int branch() {
		return branch;
	}

	public String id() {
		return depth + "-" + branch;
	}

	@Override
	public int compareTo(LevelKey other) {
		int byDepth = Integer.compare(depth, other.depth);
		return byDepth != 0 ? byDepth : Integer.compare(branch, other.branch);
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof LevelKey)) {
			return false;
		}
		LevelKey key = (LevelKey) other;
		return depth == key.depth && branch == key.branch;
	}

	@Override
	public int hashCode() {
		return Objects.hash(depth, branch);
	}

	@Override
	public String toString() {
		return id();
	}
}
