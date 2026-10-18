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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.CoopItems;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionReward;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.Artifact;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.ExoticPotion;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ExoticScroll;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;

//The Cell's reward (MEC-01, game-design §4.6): on MISSION_END{success} each player gets one rare
// item, rolled from rewardSeed + their own playerId (so the two players get different items,
// and each gets the same item on any device), plus Bonded.
//The roll draws only from its own generator and never from run state (no Generator decks):
// an artifact, a +2 weapon/armor/wand/ring, or an exotic potion/scroll, a third each. The one
// world-dependent step comes after: an artifact this world already generated (artifacts are
// unique per run) is swapped for the roll's +2 item, which is always rolled too.
final class CellReward implements MissionReward {

	static final int ARTIFACT = 0, PLUS_TWO = 1, EXOTIC = 2;
	static final int BONUS_LEVEL = 2;

	static final class Roll {
		int kind;
		Artifact artifact;
		Item plusTwo;
		Item exotic;
	}

	@Override
	public void grant( MissionState.End end ) {
		Hero hero = Dungeon.hero;
		if (hero == null || !hero.isAlive()) return;
		int depth = end.mission != null ? end.mission.depth : Dungeon.depth;
		Roll roll = roll( end.rewardSeed, CoopCell.link().me(), depth );
		Item item = pick( roll, roll.kind == ARTIFACT && Generator.removeArtifact( roll.artifact.getClass() ) );

		//per-player loot: never claimable or shared (ITM-01)
		CoopItems.markLocal( item );
		GLog.p( Messages.get( CellReward.class, "reward", item.name() ) );
		if (!item.collect()) Dungeon.level.drop( item, hero.pos );
		Buff.affect( hero, Bonded.class, Bonded.DURATION );
	}

	static Item pick( Roll roll, boolean artifactFree ) {
		switch (roll.kind) {
			case ARTIFACT:
				return artifactFree ? roll.artifact : roll.plusTwo;
			case PLUS_TWO:
				return roll.plusTwo;
			default:
				return roll.exotic;
		}
	}

	static Roll roll( long rewardSeed, String playerId, int depth ) {
		Roll roll = new Roll();
		Random.pushGenerator( seed( rewardSeed, playerId ) );
		try {
			roll.kind = Random.Int( 3 );

			Class<?>[] artifacts = Generator.Category.ARTIFACT.classes;
			roll.artifact = (Artifact) ((Item) Reflection.newInstance( artifacts[Random.Int( artifacts.length )] )).random();
			roll.artifact.cursed = false;

			roll.plusTwo = plusTwo( Math.max( 0, depth / 5 ) );

			Class<?>[] regulars = Random.Int( 2 ) == 0
					? ExoticPotion.regToExo.keySet().toArray( new Class<?>[0] )
					: ExoticScroll.regToExo.keySet().toArray( new Class<?>[0] );
			Class<?> regular = regulars[Random.Int( regulars.length )];
			Class<? extends Item> exotic = ExoticPotion.regToExo.containsKey( regular )
					? ExoticPotion.regToExo.get( regular ) : ExoticScroll.regToExo.get( regular );
			roll.exotic = Reflection.newInstance( exotic );
		} finally {
			Random.popGenerator();
		}
		return roll;
	}

	private static Item plusTwo( int floorSet ) {
		Item item;
		switch (Random.Int( 4 )) {
			case 0: default:
				item = Generator.randomWeapon( floorSet, true );
				break;
			case 1:
				item = Generator.randomArmor( floorSet );
				break;
			case 2:
				item = Generator.randomUsingDefaults( Generator.Category.WAND );
				break;
			case 3:
				item = Generator.randomUsingDefaults( Generator.Category.RING );
				break;
		}
		item.cursed = false;
		if (item instanceof Weapon && ((Weapon) item).hasCurseEnchant()) ((Weapon) item).enchant( null );
		if (item instanceof Armor && ((Armor) item).hasCurseGlyph()) ((Armor) item).inscribe( null );
		item.level( BONUS_LEVEL );
		return item;
	}

	//rewardSeed mixed with a stable hash of the playerId (FNV-1a 64)
	static long seed( long rewardSeed, String playerId ) {
		long h = 0xcbf29ce484222325L;
		String id = playerId == null ? "" : playerId;
		for (int i = 0; i < id.length(); i++) {
			h ^= id.charAt( i );
			h *= 0x100000001b3L;
		}
		return rewardSeed * 0x9E3779B97F4A7C15L ^ h;
	}
}
