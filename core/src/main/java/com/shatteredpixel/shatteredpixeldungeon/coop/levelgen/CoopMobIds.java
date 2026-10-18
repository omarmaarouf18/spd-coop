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
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.RotHeart;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.RotLasher;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.coop.mobs.CoopMobs;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

import java.util.ArrayList;

//Stable network ids for the mobs co-op level gen places (D-023, MOB-01 shared monsters):
// mobNetId = "depth:branch:mobIndex" (PROTOCOL.md §3), mobIndex numbering the floor's shared mobs
// by (cell, class name), the same order CoopItemIds uses, so both worlds assign the same ids.
//Not shared (no id, local to each world): bosses and minibosses (BOSS-01 owns them), NPCs, the
// Wandmaker's rot garden (a per-world quest), every mob on a quest branch floor (mine, vault:
// their mobs are the quests), and everything spawned at runtime.
//The id travels in the mob's own bundle (Mob.java hook, CoopMobs.store/restore).
public final class CoopMobIds {

	private CoopMobIds() {}

	public static String netId( int depth, int branch, int mobIndex ) {
		return depth + ":" + branch + ":" + mobIndex;
	}

	//numbers the shared mobs of a fresh floor and binds their ids; returns how many
	static int assign( Level level, int depth, int branch ) {
		if (branch != 0) return 0;
		ArrayList<Mob> mobs = new ArrayList<>( level.mobs );
		mobs.sort( (a, b) -> a.pos != b.pos ? Integer.compare( a.pos, b.pos )
				: a.getClass().getName().compareTo( b.getClass().getName() ) );
		int index = 0;
		for (Mob mob : mobs) {
			if (!shareable( mob )) continue;
			CoopMobs.bind( mob, netId( depth, branch, index++ ) );
		}
		return index;
	}

	static boolean shareable( Mob mob ) {
		return !(mob instanceof NPC)
				&& !Char.hasProp( mob, Char.Property.BOSS )
				&& !Char.hasProp( mob, Char.Property.MINIBOSS )
				&& !(mob instanceof RotHeart) && !(mob instanceof RotLasher);
	}
}
