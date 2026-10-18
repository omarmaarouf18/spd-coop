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

package com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.overlay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.utils.Random;

import java.nio.charset.StandardCharsets;
import java.util.List;

//The per-player overlay (DET-02): what vanilla level gen would have added for *this* hero, put
// back at runtime in this player's world only. Co-op floors are generated with a neutral hero
// (DET-01, D-019), so they are identical in both worlds; the overlay runs afterwards, the first
// time this player enters a floor, and never changes the shared floor:
// - every item it adds is CoopItems.markLocal (never claimed, never shared),
// - it only draws from its own generators, seeded from (run seed, playerId, depth, branch, part),
//   so it doesn't depend on the order or timing of anything else,
// - what it did is saved in CoopRun (OverlayState), so a reload never spawns it twice.
//What it restores: Dried Rose petals, Cached Rations, Cracked Spyglass loot, Mimic Tooth's ebony
// mimic, Trap Mechanism's revealed traps (PlayerLoot); Timekeeper's Hourglass sand bags and the
// inventory-based shop bag (PlayerShop); the class's Vault Mirror reward (PlayerLoot); artifacts
// this player already owns turn into another artifact (Duplicates). Details: game-design §6A.8.
//Everything is a no-op outside co-op runs.
public final class CoopOverlay {

	//seed parts: one generator per part, so adding/removing one effect never shifts another
	static final String DUPLICATES = "duplicates";
	static final String MIRROR = "mirror";
	static final String PETALS = "petals";
	static final String RATIONS = "rations";
	static final String SPYGLASS = "spyglass";
	static final String EBONY = "ebony";

	private CoopOverlay() {}

	//Dungeon.switchLevel(), right before ITM-01's floor-entry hook: the level is in place, the
	// actors are initialized, nothing is saved or shown yet
	public static void onLevelEntered( Level level ) {
		CoopRun run = Coop.run();
		Hero hero = Dungeon.hero;
		if (!Coop.isCoopRun() || run == null || level == null || hero == null) return;
		OverlayState s = run.overlay;
		if (s.legacy) {
			//a run saved before DET-02: the floors it already generated were entered with the old
			// code; only floors generated from now on get the overlay
			for (int key : Dungeon.generatedLevels) s.floors.add( floorKey( key % 1000, key / 1000 ) );
			s.legacy = false;
		}
		if (!s.floors.add( floorKey( Dungeon.depth, Dungeon.branch ) )) return;
		apply( level, hero, s );
	}

	//tests: the overlay of one floor, without the first-entry bookkeeping
	static void apply( Level level, Hero hero, OverlayState s ) {
		//duplicates first: the effects below may add artifacts the player doesn't own yet
		Duplicates.onFloor( level, hero );
		PlayerShop.onFloor( level, hero, s );
		PlayerLoot.onFloor( level, hero );
	}

	//ImpShopRoom.spawnShop(), before its stock is placed: the Imp's shop opens at runtime (after
	// the city boss), so its stock gets the overlay while it is still a list
	public static void onImpShop( List<Item> stock ) {
		CoopRun run = Coop.run();
		Hero hero = Dungeon.hero;
		if (!Coop.isCoopRun() || run == null || stock == null || hero == null) return;
		Duplicates.inStock( stock, hero );
		PlayerShop.inStock( stock, hero, run.overlay );
	}

	static String floorKey( int depth, int branch ) {
		return depth + "_" + branch;
	}

	// ---- per-player seeds ----

	//runs body on a generator seeded from (run seed, this player, depth, branch, part)
	static void seeded( String part, Runnable body ) {
		Random.pushGenerator( seed( Dungeon.seed, player(), Dungeon.depth, Dungeon.branch, part ) );
		try {
			body.run();
		} finally {
			Random.popGenerator();
		}
	}

	//the playerId the server gave this player (stable for the run, saved in CoopRun); the slot
	// before the first WELCOME
	static String player() {
		CoopRun run = Coop.run();
		if (run == null) return "";
		return run.playerId.isEmpty() ? "slot" + run.slot : run.playerId;
	}

	static long seed( long runSeed, String player, int depth, int branch, String part ) {
		long h = mix( runSeed ^ 0x4445542d30324cL );
		h = mix( h ^ hash( player ) );
		h = mix( h ^ ((long) depth << 32 | (branch & 0xffffffffL)) );
		return mix( h ^ hash( part ) );
	}

	//FNV-1a over UTF-8: the same on every device (String.hashCode would be too, but is 32-bit)
	private static long hash( String s ) {
		long h = 0xcbf29ce484222325L;
		for (byte b : s.getBytes( StandardCharsets.UTF_8 )) {
			h ^= b & 0xff;
			h *= 0x100000001b3L;
		}
		return h;
	}

	//splitmix64 finalizer
	private static long mix( long z ) {
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		return z ^ (z >>> 31);
	}
}
