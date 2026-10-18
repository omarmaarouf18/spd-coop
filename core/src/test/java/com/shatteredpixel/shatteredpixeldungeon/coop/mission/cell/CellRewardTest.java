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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell;

import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.CellTestFloors.ALLY;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.CellTestFloors.ME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.CoopItems;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.ExoticPotion;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ExoticScroll;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Game;
import com.watabou.utils.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

//MEC-01, game-design §4.6: one rare item per player, deterministic from rewardSeed + playerId and
// independent of run state and gameplay RNG, plus Bonded (+10% accuracy, 200 turns)
class CellRewardTest {

	private Game previousGame;

	@BeforeEach
	void setUp() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		previousGame = Game.instance;
		new Game( null, null ) {};
	}

	@AfterEach
	void tearDown() {
		CoopCell.link( null );
		if (Dungeon.hero != null) Actor.remove( Dungeon.hero );
		Dungeon.hero = null;
		Game.instance = previousGame;
	}

	private static String describe( Item item ) {
		return item.getClass().getName() + " +" + item.level() + (item.cursed ? " cursed" : "");
	}

	private static String describe( CellReward.Roll r ) {
		return r.kind + " " + describe( r.artifact ) + " | " + describe( r.plusTwo ) + " | " + describe( r.exotic );
	}

	@Test
	void theRollDependsOnlyOnTheSeedAndThePlayer() {
		CellTestFloors.firstTrapFloor( CellTestFloors.SEED, HeroClass.WARRIOR );
		HashSet<String> mine = new HashSet<>(), kinds = new HashSet<>();
		int differ = 0;
		for (long seed = 1; seed <= 40; seed++) {
			String a = describe( CellReward.roll( seed, ME, 7 ) );
			//gameplay RNG and the run's decks moved in between: same roll
			Random.Long();
			Random.Float();
			assertEquals( a, describe( CellReward.roll( seed, ME, 7 ) ), "seed " + seed );
			if (!a.equals( describe( CellReward.roll( seed, ALLY, 7 ) ) )) differ++;
			mine.add( a );
			CellReward.Roll r = CellReward.roll( seed, ME, 7 );
			kinds.add( "" + r.kind );
			assertFalse( r.artifact.cursed || r.plusTwo.cursed );
			assertEquals( CellReward.BONUS_LEVEL, r.plusTwo.level() );
			assertTrue( r.exotic instanceof ExoticPotion || r.exotic instanceof ExoticScroll );
		}
		assertTrue( differ > 30, "the two players get different items: " + differ + " of 40" );
		assertEquals( 3, kinds.size(), "artifact, +2 and exotic all come up" );
		assertTrue( mine.size() > 30 );
		assertNotEquals( CellReward.seed( 1, ME ), CellReward.seed( 1, ALLY ) );
	}

	@Test
	void grantGivesTheItemLocalAndBonded() {
		CellTestFloors.firstTrapFloor( CellTestFloors.SEED, HeroClass.WARRIOR );
		CoopCellTest.FakeLink link = new CoopCellTest.FakeLink();
		CoopCell.link( link );
		Hero hero = Dungeon.hero;
		hero.HP = hero.HT;
		Actor.add( hero );

		MissionState.End end = new MissionState.End();
		end.result = MissionState.SUCCESS;
		end.rewardSeed = 5;
		end.mission = new MissionState.Mission();
		end.mission.depth = Dungeon.depth;
		CellReward.Roll roll = CellReward.roll( 5, ME, Dungeon.depth );
		int before = hero.belongings.backpack.items.size();

		new CellReward().grant( end );

		//an artifact this world already generated is swapped for the +2 item
		Item free = CellReward.pick( roll, true ), taken = CellReward.pick( roll, false );
		Item got = null;
		for (Item i : hero.belongings.backpack.items) {
			if (i.getClass() == free.getClass() || i.getClass() == taken.getClass()) got = i;
		}
		assertNotNull( got, describe( free ) + " / " + describe( taken ) );
		assertEquals( before + 1, hero.belongings.backpack.items.size() );
		assertEquals( CoopItems.LOCAL, CoopItems.idOf( got ), "never shared or claimable" );
		assertNotNull( hero.buff( Bonded.class ) );
		assertEquals( Bonded.DURATION, hero.buff( Bonded.class ).cooldown(), 0.001f );
		assertEquals( Bonded.ACCURACY, CoopCell.accuracyFactor( hero ) );
	}
}
