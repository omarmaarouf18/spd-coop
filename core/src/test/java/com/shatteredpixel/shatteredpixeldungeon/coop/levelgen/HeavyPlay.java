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
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Ghost;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Wandmaker;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.DriedRose;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TimekeepersHourglass;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.ExoticCrystals;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.MimicTooth;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.MossyClump;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.ParchmentScrap;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.PetrifiedSeed;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.RatSkull;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.TrapMechanism;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.Trinket;
import com.shatteredpixel.shatteredpixeldungeon.levels.VaultLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom;
import com.watabou.utils.Bundle;
import com.watabou.utils.Random;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Player B's "heavy" play between floors (DET-01): everything normal play changes in the run
 * state that vanilla level gen reads, pushed hard. Deterministic (its own seeded generator), so a
 * failure reproduces.
 * <ul>
 * <li>mob/loot drops: many {@code Generator.random*} draws, including artifacts;</li>
 * <li>{@code LimitedDrops}: every counter advanced;</li>
 * <li>identification: potions and scrolls become known;</li>
 * <li>quests: given / processed / started / completed as the player talks to and finishes them;</li>
 * <li>gold, statistics, hero level/strength;</li>
 * <li>hero belongings that vanilla level gen reads: trinkets (mossy clump, trap mechanism, exotic
 *     crystals, mimic tooth, rat skull, petrified seed, parchment scrap), an identified Timekeeper's
 *     Hourglass (shop sand bags), an identified Dried Rose (petals), a full backpack (shop bag choice);</li>
 * <li>the gameplay-reachable special-room call (pit room reset) and an artifact removal (bones);</li>
 * <li>the unseeded gameplay RNG streams: the game's base generator and the JDK's own
 *     {@code Collections.shuffle} Random (which the harness pins per run);</li>
 * <li>process history: {@code VaultLevel}'s static solve-item arrays, which upstream shuffles in
 *     place and never resets (the harness resets them per run), left in another order.</li>
 * </ul>
 */
public final class HeavyPlay implements CoopRunSim.Play {

	@Override
	public void between(int depth, int branch) {
		Random.pushGenerator(0xB0B0L * 31 + depth * 1000L + branch);
		try {
			drops(depth);
			limitedDrops(depth);
			identify();
			quests();
			stats(depth);
			if (depth == 1 && branch == 0) belongings();
			SpecialRoom.resetPitRoom(depth + 1);
			if (depth == 3) Generator.removeArtifact(TimekeepersHourglass.class);
		} finally {
			Random.popGenerator();
		}
		//gameplay RNG on the base (unseeded) generator, and unseeded JDK shuffles
		for (int i = 0; i < 200; i++) Random.Int();
		for (int i = 0; i < 3 + depth; i++) Collections.shuffle(new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8)));
		if (depth == 1 && branch == 0) reverseStaticArrays();
	}

	private static void reverseStaticArrays() {
		for (String name : new String[]{"T2SolveItems", "T3SolveItems"}) {
			try {
				Field field = VaultLevel.class.getDeclaredField(name);
				field.setAccessible(true);
				Collections.reverse(Arrays.asList((Object[]) field.get(null)));
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		}
	}

	private static void drops(int depth) {
		for (int i = 0; i < 25 + depth; i++) Generator.random();
		for (Generator.Category cat : new Generator.Category[]{
				Generator.Category.POTION, Generator.Category.SCROLL, Generator.Category.WAND,
				Generator.Category.RING, Generator.Category.SEED, Generator.Category.STONE,
				Generator.Category.FOOD, Generator.Category.TRINKET}) {
			for (int i = 0; i < 4; i++) Generator.random(cat);
		}
		Generator.randomWeapon();
		Generator.randomArmor();
		Generator.randomMissile();
		Generator.randomArtifact();
	}

	private static void limitedDrops(int depth) {
		for (Dungeon.LimitedDrops lim : Dungeon.LimitedDrops.values()) {
			lim.count += 1 + depth % 3;
		}
	}

	private static void identify() {
		for (int i = 0; i < 3; i++) {
			Item p = Generator.random(Generator.Category.POTION);
			if (p instanceof Potion) ((Potion) p).setKnown();
			Item s = Generator.random(Generator.Category.SCROLL);
			if (s instanceof Scroll) ((Scroll) s).setKnown();
		}
	}

	private static void quests() {
		Bundle quests = new Bundle();
		Ghost.Quest.storeInBundle(quests);
		Wandmaker.Quest.storeInBundle(quests);
		Blacksmith.Quest.storeInBundle(quests);
		Imp.Quest.storeInBundle(quests);
		for (String node : new String[]{"sadGhost", "wandmaker", "blacksmith", "demon"}) {
			Bundle quest = quests.getBundle(node);
			if (quest.isNull() || !quest.getBoolean("spawned")) continue;
			for (String flag : new String[]{"given", "processed", "started", "boss_beaten", "completed"}) {
				quest.put(flag, true);
			}
		}
		Ghost.Quest.restoreFromBundle(quests);
		Wandmaker.Quest.restoreFromBundle(quests);
		Blacksmith.Quest.restoreFromBundle(quests);
		Imp.Quest.restoreFromBundle(quests);
		if (Ghost.Quest.processed()) {
			//the player took the ghost's reward: the quest counts as completed (rose petals need it)
			Ghost.Quest.weapon = null;
			Ghost.Quest.armor = null;
		}
	}

	private static void stats(int depth) {
		Dungeon.gold += 137 * depth;
		Statistics.goldCollected += 137 * depth;
		Statistics.enemiesSlain += 11;
		Statistics.foodEaten++;
		Statistics.itemsCrafted += 2;
		Dungeon.hero.lvl = Math.min(30, Dungeon.hero.lvl + 1);
		Dungeon.hero.STR++;
	}

	private static void belongings() {
		for (Trinket trinket : new Trinket[]{new MossyClump(), new TrapMechanism(), new ExoticCrystals(),
				new MimicTooth(), new RatSkull(), new PetrifiedSeed(), new ParchmentScrap()}) {
			trinket.level(3);
			Dungeon.hero.belongings.backpack.items.add(trinket);
		}
		TimekeepersHourglass hourglass = new TimekeepersHourglass();
		hourglass.levelKnown = hourglass.cursedKnown = true;
		Dungeon.hero.belongings.backpack.items.add(hourglass);
		DriedRose rose = new DriedRose();
		rose.levelKnown = rose.cursedKnown = true;
		Dungeon.hero.belongings.backpack.items.add(rose);
		//lots of scrolls: vanilla's shop would pick the scroll holder for this player
		for (int i = 0; i < 10; i++) {
			Dungeon.hero.belongings.backpack.items.add(Generator.random(Generator.Category.SCROLL));
		}
	}
}
