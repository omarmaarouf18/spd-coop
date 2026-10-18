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

import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Statue;
import com.shatteredpixel.shatteredpixeldungeon.coop.mobs.CoopMobs;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelFingerprint;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.utils.Bundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A co-op floor fingerprint (DET-01): stricter than TST-01's {@link LevelFingerprint}, and blind to
 * what each player knows.
 *
 * <p>It keeps TST-01's map/mobs/traps/plants/blobs/custom/transitions/meta components, and replaces
 * {@code heaps} (which reads {@code isIdentified()}, i.e. the player's potion/scroll knowledge, and
 * folds every bag to "Bag") with:
 * <ul>
 * <li>{@code items}: every heap item as its full saved state (Item bundle, exact class);</li>
 * <li>{@code mobState}: every mob's full saved state (carried loot, quest NPC rewards, HT);</li>
 * <li>{@code netIds}: the itemNetId of every generated item (D-021);</li>
 * <li>{@code mobIds}: the mobNetId of every shared mob (D-023, MOB-01).</li>
 * </ul>
 */
public final class CoopFingerprint {

	public static final List<String> COMPONENTS = List.of(
			"meta", "map", "mobs", "traps", "plants", "blobs", "custom", "transitions",
			"items", "mobState", "netIds", "mobIds");

	private final Map<String, String> hashes;
	private final Map<String, String> canonical;
	private final String all;

	private CoopFingerprint(Map<String, String> hashes, Map<String, String> canonical, String all) {
		this.hashes = hashes;
		this.canonical = canonical;
		this.all = all;
	}

	public static CoopFingerprint of(Level level, int depth, int branch) {
		LevelFingerprint base = LevelFingerprint.of(level, depth, branch);
		Map<String, String> canonical = new LinkedHashMap<>();
		canonical.put("items", items(level));
		canonical.put("mobState", mobState(level));
		canonical.put("netIds", netIds(level));
		canonical.put("mobIds", mobIds(level));

		Map<String, String> hashes = new LinkedHashMap<>();
		StringBuilder allInput = new StringBuilder();
		for (String component : COMPONENTS) {
			String hash = canonical.containsKey(component) ? sha256(canonical.get(component)) : base.hash(component);
			hashes.put(component, hash);
			allInput.append(component).append('=').append(hash).append('\n');
		}
		return new CoopFingerprint(hashes, canonical, sha256(allInput.toString()));
	}

	public String all() {
		return all;
	}

	public Map<String, String> hashes() {
		return hashes;
	}

	public String canonical(String component) {
		return canonical.get(component);
	}

	/** Golden entry: {@code all} first, then one hash per component, in a fixed order. */
	public Map<String, String> asGoldenEntry() {
		Map<String, String> entry = new LinkedHashMap<>();
		entry.put("all", all);
		entry.putAll(hashes);
		return entry;
	}

	public List<String> diffAgainst(CoopFingerprint other) {
		List<String> differing = new ArrayList<>();
		for (String component : COMPONENTS) {
			if (!hashes.get(component).equals(other.hashes.get(component))) {
				differing.add(component);
			}
		}
		return differing;
	}

	private static String items(Level level) {
		List<String> entries = new ArrayList<>();
		for (Heap heap : level.heaps.valueList()) {
			StringBuilder sb = new StringBuilder().append(heap.pos).append(':').append(heap.type.name());
			for (Item item : heap.items) {
				sb.append('|').append(state(item));
			}
			entries.add(sb.toString());
		}
		entries.sort(null);
		return String.join("\n", entries);
	}

	private static String mobState(Level level) {
		List<String> entries = new ArrayList<>();
		for (Mob mob : level.mobs) {
			Bundle bundle = new Bundle();
			bundle.put("mob", mob);
			entries.add(mob.pos + ":" + withoutNetIds(bundle.toString()));
		}
		entries.sort(null);
		return String.join("\n", entries);
	}

	private static String netIds(Level level) {
		List<String> entries = new ArrayList<>();
		for (Heap heap : level.heaps.valueList()) {
			for (Item item : heap.items) {
				entries.add(CoopItemIds.idOf(item) + "=" + heap.pos + ":" + item.getClass().getName());
			}
		}
		for (Mob mob : level.mobs) {
			List<Item> carried = new ArrayList<>();
			if (mob instanceof Mimic && ((Mimic) mob).items != null) carried.addAll(((Mimic) mob).items);
			if (mob instanceof Statue && ((Statue) mob).weapon() != null) carried.add(((Statue) mob).weapon());
			for (Item item : carried) {
				entries.add(CoopItemIds.idOf(item) + "=mob@" + mob.pos + ":" + item.getClass().getName());
			}
		}
		entries.sort(null);
		return String.join("\n", entries);
	}

	private static String mobIds(Level level) {
		List<String> entries = new ArrayList<>();
		for (Mob mob : level.mobs) {
			String id = CoopMobs.idOf(mob);
			if (id != null) entries.add(id + "=" + mob.pos + ":" + mob.getClass().getName());
		}
		entries.sort(null);
		return String.join("\n", entries);
	}

	static String state(Item item) {
		Bundle bundle = new Bundle();
		bundle.put("item", item);
		return withoutNetIds(bundle.toString());
	}

	//since ITM-01 an item's bundle carries its itemNetId ("coop_net_id"), since MOB-01 a mob's its
	// mobNetId ("coop_mob_id"); the ids are the netIds/mobIds components, so the state components
	// stay what DET-01 recorded
	static String withoutNetIds(String json) {
		return json.replaceAll("\"coop_(net|mob)_id\":\"[^\"]*\",?", "").replace(",}", "}");
	}

	private static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (byte b : digest) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
