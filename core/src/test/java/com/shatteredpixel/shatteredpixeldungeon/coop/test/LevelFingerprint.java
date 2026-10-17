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

import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.tiles.CustomTilemap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic fingerprint of one generated floor (TST-01).
 *
 * <p>A floor is serialized to a canonical, order-stable description split into
 * named components, and each component is hashed with SHA-256. Comparing the
 * per-component hashes tells a test <em>what</em> differs (map, mobs, loot, …)
 * instead of only that the floor differs.
 *
 * <p>The fingerprint deliberately does not use the save {@code Bundle}: bundle
 * output is not promised to be stable across versions and it serializes
 * runtime bookkeeping. Only generation-relevant state is included, and every
 * collection is sorted or listed in a fixed order so that {@code HashSet} and
 * {@code HashMap} iteration order can never leak into a hash.
 */
public final class LevelFingerprint {

	/** Components in fixed order; {@code all} hashes them in this same order. */
	public static final List<String> COMPONENTS = List.of(
			"meta", "map", "mobs", "heaps", "traps", "plants", "blobs", "custom", "transitions");

	private final Map<String, String> canonical;
	private final Map<String, String> hashes;
	private final String all;
	private final String mapRle;

	private LevelFingerprint(Map<String, String> canonical, Map<String, String> hashes, String all, String mapRle) {
		this.canonical = canonical;
		this.hashes = hashes;
		this.all = all;
		this.mapRle = mapRle;
	}

	public static LevelFingerprint of(Level level, int depth, int branch) {
		Map<String, String> canonical = new TreeMap<>();
		canonical.put("meta", meta(level, depth, branch));
		canonical.put("map", mapRle(level));
		canonical.put("mobs", mobs(level));
		canonical.put("heaps", heaps(level));
		canonical.put("traps", traps(level));
		canonical.put("plants", plants(level));
		canonical.put("blobs", blobs(level));
		canonical.put("custom", custom(level));
		canonical.put("transitions", transitions(level));

		Map<String, String> hashes = new TreeMap<>();
		StringBuilder allInput = new StringBuilder();
		for (String component : COMPONENTS) {
			String hash = sha256Hex(canonical.get(component));
			hashes.put(component, hash);
			allInput.append(component).append('=').append(hash).append('\n');
		}
		return new LevelFingerprint(canonical, hashes,
				sha256Hex(allInput.toString()), canonical.get("map"));
	}

	public String all() {
		return all;
	}

	public String hash(String component) {
		String hash = hashes.get(component);
		if (hash == null) {
			throw new IllegalArgumentException("unknown component: " + component);
		}
		return hash;
	}

	public String canonical(String component) {
		return canonical.get(component);
	}

	public String mapRle() {
		return mapRle;
	}

	/**
	 * This level's golden-file entry: the {@code all} digest, one digest per
	 * component, and {@code mapRle} for map-level failure diagnostics. The
	 * order is fixed and matches the golden format.
	 */
	public Map<String, String> asGoldenEntry() {
		Map<String, String> entry = new java.util.LinkedHashMap<>();
		entry.put("all", all);
		for (String component : COMPONENTS) {
			entry.put(component, hashes.get(component));
			if (component.equals("map")) {
				entry.put("mapRle", mapRle);
			}
		}
		return entry;
	}

	/** Components whose hashes differ from another fingerprint, in fixed order. */
	public List<String> diffAgainst(LevelFingerprint other) {
		List<String> differing = new ArrayList<>();
		for (String component : COMPONENTS) {
			if (!hashes.get(component).equals(other.hashes.get(component))) {
				differing.add(component);
			}
		}
		return differing;
	}

	/**
	 * The components whose hashes differ from a golden entry, or an empty list
	 * if this floor matches it. Throws if the golden entry has no {@code all}
	 * component (i.e. is not a fingerprint entry).
	 */
	public List<String> diffComponents(Map<String, String> goldenEntry) {
		List<String> differing = new ArrayList<>();
		for (String component : COMPONENTS) {
			String goldenHash = goldenEntry.get(component);
			if (goldenHash == null) {
				differing.add(component + "(missing)");
			} else if (!goldenHash.equals(hashes.get(component))) {
				differing.add(component);
			}
		}
		return differing;
	}

	/**
	 * Human-readable detail for a differing map: first differing cell index
	 * and both tiles. Empty if the decoded maps are equal (should not happen).
	 */
	public String describeMapDiff(String goldenRle) {
		int[] golden = decodeRle(goldenRle);
		int[] actual = decodeRle(mapRle);
		if (golden.length != actual.length) {
			return "map length differs: golden=" + golden.length + ", generated=" + actual.length;
		}
		for (int cell = 0; cell < golden.length; cell++) {
			if (golden[cell] != actual[cell]) {
				return "first differing cell " + cell + ": golden tile=" + golden[cell]
						+ ", generated tile=" + actual[cell];
			}
		}
		return "map hashes differ but decoded maps are equal";
	}

	// ----- components ------------------------------------------------------

	private static String meta(Level level, int depth, int branch) {
		return "class=" + level.getClass().getSimpleName()
				+ ";depth=" + depth
				+ ";branch=" + branch
				+ ";width=" + level.width()
				+ ";height=" + level.height()
				+ ";feeling=" + level.feeling.name()
				+ ";viewDistance=" + level.viewDistance
				+ ";locked=" + level.locked
				+ ";entrance=" + level.entrance
				+ ";exit=" + level.exit;
	}

	/** Run-length encoded tile map: {@code count:tile,count:tile,…}. */
	private static String mapRle(Level level) {
		int[] map = level.map;
		StringBuilder sb = new StringBuilder();
		int run = 0;
		for (int cell = 0; cell < map.length; cell++) {
			run++;
			if (cell == map.length - 1 || map[cell + 1] != map[cell]) {
				if (sb.length() > 0) {
					sb.append(',');
				}
				sb.append(run).append(':').append(map[cell]);
				run = 0;
			}
		}
		return sb.toString();
	}

	private static int[] decodeRle(String rle) {
		List<Integer> cells = new ArrayList<>();
		for (String run : rle.split(",")) {
			int colon = run.indexOf(':');
			int count = Integer.parseInt(run.substring(0, colon));
			int tile = Integer.parseInt(run.substring(colon + 1));
			for (int i = 0; i < count; i++) {
				cells.add(tile);
			}
		}
		int[] map = new int[cells.size()];
		for (int i = 0; i < map.length; i++) {
			map[i] = cells.get(i);
		}
		return map;
	}

	private static String mobs(Level level) {
		List<String> entries = new ArrayList<>();
		for (Mob mob : level.mobs) {
			entries.add(mob.getClass().getSimpleName()
					+ "@" + mob.pos
					+ "(hp=" + mob.HP
					+ ",ht=" + mob.HT
					+ ",state=" + mob.state.getClass().getSimpleName()
					+ ",align=" + mob.alignment.name()
					+ ")");
		}
		entries.sort(null);
		return String.join(";", entries);
	}

	private static String heaps(Level level) {
		List<String> entries = new ArrayList<>();
		for (Heap heap : level.heaps.valueList()) {
			StringBuilder sb = new StringBuilder(String.valueOf(heap.pos));
			sb.append(':').append(heap.type.name()).append(':');
			for (int i = 0; i < heap.items.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(item(heap.items.get(i)));
			}
			entries.add(sb.toString());
		}
		entries.sort(null);
		return String.join(";", entries);
	}

	private static String item(Item item) {
		StringBuilder sb = new StringBuilder();
		// ShopRoom.ChooseBag picks between bag candidates by iterating a
		// HashMap keyed by fresh Bag instances, so equally good bags are
		// resolved by identity hash order. A shop's bag slot is pinned, but
		// which bag type landed in it is not reproducible (and depends on
		// hero inventory even in vanilla play).
		sb.append(item instanceof Bag ? "Bag" : item.getClass().getSimpleName())
				.append("(lvl=").append(item.level())
				.append(",qty=").append(item.quantity())
				.append(",cursed=").append(item.cursed)
				.append(",cursedKnown=").append(item.cursedKnown)
				.append(",levelKnown=").append(item.levelKnown)
				.append(",identified=").append(item.isIdentified())
				.append(",unique=").append(item.unique);
		if (item instanceof Weapon && ((Weapon) item).enchantment != null) {
			sb.append(",enchant=").append(((Weapon) item).enchantment.getClass().getSimpleName());
		}
		if (item instanceof Armor && ((Armor) item).glyph != null) {
			sb.append(",glyph=").append(((Armor) item).glyph.getClass().getSimpleName());
		}
		return sb.append(')').toString();
	}

	private static String traps(Level level) {
		List<String> entries = new ArrayList<>();
		for (Trap trap : level.traps.valueList()) {
			entries.add(trap.getClass().getSimpleName()
					+ "@" + trap.pos
					+ "(visible=" + trap.visible
					+ ",active=" + trap.active
					+ ",color=" + trap.color
					+ ",shape=" + trap.shape
					+ ")");
		}
		entries.sort(null);
		return String.join(";", entries);
	}

	private static String plants(Level level) {
		List<String> entries = new ArrayList<>();
		for (Plant plant : level.plants.valueList()) {
			entries.add(plant.getClass().getSimpleName() + "@" + plant.pos);
		}
		entries.sort(null);
		return String.join(";", entries);
	}

	private static String blobs(Level level) {
		List<String> entries = new ArrayList<>();
		for (Map.Entry<Class<? extends Blob>, Blob> entry : level.blobs.entrySet()) {
			Blob blob = entry.getValue();
			entries.add(blob.getClass().getSimpleName()
					+ "(volume=" + blob.volume
					+ ",area=" + blob.area.left + "," + blob.area.top + "," + blob.area.right + "," + blob.area.bottom
					+ ")");
		}
		entries.sort(null);
		return String.join(";", entries);
	}

	private static String custom(Level level) {
		return "tiles=" + customList(level.customTiles)
				+ ";terrain=" + customList(level.customTerrain)
				+ ";walls=" + customList(level.customWalls);
	}

	private static String customList(List<CustomTilemap> tilemaps) {
		List<String> entries = new ArrayList<>();
		for (CustomTilemap tilemap : tilemaps) {
			entries.add(tilemap.getClass().getSimpleName()
					+ "@" + tilemap.tileX + "," + tilemap.tileY
					+ "+" + tilemap.tileW + "," + tilemap.tileH);
		}
		return "[" + String.join("|", entries) + "]";
	}

	private static String transitions(Level level) {
		List<String> entries = new ArrayList<>();
		for (LevelTransition transition : level.transitions) {
			entries.add(transition.type.name()
					+ "@" + transition.cell()
					+ "->" + transition.destDepth + ":" + transition.destBranch + ":"
					+ (transition.destType == null ? "none" : transition.destType.name()));
		}
		entries.sort(null);
		return String.join(";", entries);
	}

	// ----- hashing ---------------------------------------------------------

	static String sha256Hex(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				sb.append(Character.forDigit((b >> 4) & 0xf, 16));
				sb.append(Character.forDigit(b & 0xf, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 unavailable", e);
		}
	}
}
