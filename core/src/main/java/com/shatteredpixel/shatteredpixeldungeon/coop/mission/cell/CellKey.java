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

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Bundle;

import java.util.ArrayList;

//the rescuer's Cell Key (MEC-01, game-design §4.2): local to the rescuer's world (CoopItems
// LOCAL), tied to one mission. USE next to a door of the ally's cell sends GIVE{kind:"cell_key"}.
//It goes away when its mission's key task is done or the mission ends; a key of a mission that
// is over crumbles when picked up.
public class CellKey extends Item {

	static {
		CoopCell.install();
	}

	public static final String AC_USE = "USE";

	{
		image = ItemSpriteSheet.IRON_KEY;
		defaultAction = AC_USE;
		unique = true;
		stackable = false;
	}

	public String missionId = "";

	public CellKey() {}

	CellKey( String missionId ) {
		this.missionId = missionId;
	}

	@Override
	public ArrayList<String> actions( Hero hero ) {
		ArrayList<String> actions = new ArrayList<>();
		actions.add( AC_USE );
		actions.add( AC_DROP );
		return actions;
	}

	@Override
	public void execute( Hero hero, String action ) {
		if (AC_USE.equals( action )) {
			CoopCell.useKey( hero, this );
		} else {
			super.execute( hero, action );
		}
	}

	@Override
	public boolean doPickUp( Hero hero, int pos ) {
		if (!CoopCell.keyValid( this )) {
			GLog.i( Messages.get( CoopCell.class, "key_stale" ) );
			hero.spendAndNext( pickupDelay() );
			//true: the heap lets go of it, and it's gone
			return true;
		}
		return super.doPickUp( hero, pos );
	}

	@Override
	public boolean isUpgradable() {
		return false;
	}

	@Override
	public boolean isIdentified() {
		return true;
	}

	@Override
	public int value() {
		return 0;
	}

	private static final String MISSION = "coop_mission";

	@Override
	public void storeInBundle( Bundle bundle ) {
		super.storeInBundle( bundle );
		bundle.put( MISSION, missionId );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		super.restoreFromBundle( bundle );
		missionId = bundle.getString( MISSION );
	}
}
