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

import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.watabou.utils.Bundle;

//The Cell's Binding Trap (MEC-01, D-020, game-design §4.1): a hidden trap placed by co-op level
// gen, so it sits on the same cell in both worlds. It knows its room (the cell that seals): the
// room's rect, its id and its door cells, all fixed at gen.
//Stepping on it does nothing by itself: CoopCell sends MISSION_TRIGGER when the ally is online on
// this floor, and the trap is only spent (revealed, inactive) when the server starts the mission.
// Otherwise it stays armed, and hidden if it was hidden.
public class BindingTrap extends Trap {

	static {
		//a loaded floor with a Binding Trap is where a Cell can start: hear the missions
		CoopCell.install();
	}

	{
		color = VIOLET;
		shape = GRILL;
		//the server decides when it's spent (MISSION_START)
		disarmedByActivation = false;
	}

	//the room that seals (inclusive rect, like Room), its objectId, its door cells
	public int left, top, right, bottom;
	public long roomId;
	public int[] doors = new int[0];

	public boolean inRoom( int cell, int width ) {
		int x = cell % width, y = cell / width;
		return x > left && x < right && y > top && y < bottom;
	}

	public boolean onRect( int cell, int width ) {
		int x = cell % width, y = cell / width;
		return x >= left && x <= right && y >= top && y <= bottom;
	}

	@Override
	public void trigger() {
		if (active) CoopCell.onTriggered( this );
	}

	@Override
	public void activate() {
		//nothing happens in this world until the server answers (CoopCell)
	}

	private static final String LEFT = "coop_left";
	private static final String TOP = "coop_top";
	private static final String RIGHT = "coop_right";
	private static final String BOTTOM = "coop_bottom";
	private static final String ROOM_ID = "coop_room_id";
	private static final String DOORS = "coop_doors";

	@Override
	public void storeInBundle( Bundle bundle ) {
		super.storeInBundle( bundle );
		bundle.put( LEFT, left );
		bundle.put( TOP, top );
		bundle.put( RIGHT, right );
		bundle.put( BOTTOM, bottom );
		bundle.put( ROOM_ID, roomId );
		bundle.put( DOORS, doors );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		super.restoreFromBundle( bundle );
		left = bundle.getInt( LEFT );
		top = bundle.getInt( TOP );
		right = bundle.getInt( RIGHT );
		bottom = bundle.getInt( BOTTOM );
		roomId = bundle.getLong( ROOM_ID );
		doors = bundle.contains( DOORS ) ? bundle.getIntArray( DOORS ) : new int[0];
	}
}
