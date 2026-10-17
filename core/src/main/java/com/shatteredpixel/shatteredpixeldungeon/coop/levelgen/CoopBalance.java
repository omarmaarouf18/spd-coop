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

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

//Co-op balance (D-021, game-design §6A.7), applied only inside co-op level generation, so it is
// identical in both worlds and vanilla never sees it.
// - mobs placed by level gen: HT x1.2 (HP keeps its ratio);
// - FOOD deck 5.2 / 1.3 / 0 (food / pasty / mystery meat) instead of 4 / 1 / 0;
// - 30% of Generator.random() rolls are food. Vanilla's general deck has no food cards, so this
//   adds food cards to both general decks: food / (deck + food) = 30%. It is the expected 30% of
//   the design, drawn through the deck (no Generator hook): see the DET-01 worklog.
final class CoopBalance {

	static final float MOB_HT_FACTOR = 1.2f;
	static final float[] FOOD_PROBS = new float[]{ 5.2f, 1.3f, 0 };
	static final float FOOD_SHARE = 0.3f;

	private static float[] vanillaFoodProbs;
	private static float vanillaFirst, vanillaSecond;
	private static boolean applied;

	private CoopBalance() {}

	//swaps the co-op generator tables in; undo() puts the vanilla ones back
	static void apply() {
		if (applied) return;
		Generator.Category food = Generator.Category.FOOD;
		vanillaFoodProbs = food.defaultProbs;
		vanillaFirst = food.firstProb;
		vanillaSecond = food.secondProb;

		float first = 0, second = 0;
		for (Generator.Category cat : Generator.Category.values()) {
			if (cat == food) continue;
			first += cat.firstProb;
			second += cat.secondProb;
		}
		food.defaultProbs = FOOD_PROBS.clone();
		food.firstProb = first * FOOD_SHARE / (1 - FOOD_SHARE);
		food.secondProb = second * FOOD_SHARE / (1 - FOOD_SHARE);
		applied = true;
	}

	static void undo() {
		if (!applied) return;
		Generator.Category food = Generator.Category.FOOD;
		food.defaultProbs = vanillaFoodProbs;
		food.firstProb = vanillaFirst;
		food.secondProb = vanillaSecond;
		applied = false;
	}

	//a fresh co-op level-gen state starts with the co-op decks (the vanilla ones were dealt by
	// Dungeon.init before the run became co-op). Call between apply() and undo().
	static void dealCoopDecks() {
		Generator.generalReset();
		Generator.reset( Generator.Category.FOOD );
	}

	static void scaleMobs( Level level ) {
		for (Mob mob : level.mobs) {
			if (mob instanceof NPC || mob.alignment != Char.Alignment.ENEMY) continue;
			float ratio = mob.HT > 0 ? mob.HP / (float) mob.HT : 1f;
			mob.HT = Math.round( mob.HT * MOB_HT_FACTOR );
			mob.HP = Math.round( mob.HT * ratio );
		}
	}
}
