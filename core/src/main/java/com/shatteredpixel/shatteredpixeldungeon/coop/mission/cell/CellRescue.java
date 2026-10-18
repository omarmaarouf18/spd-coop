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
import com.shatteredpixel.shatteredpixeldungeon.coop.items.CoopItems;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Bundle;

import java.util.ArrayList;

//the rescuer's side of a Cell (MEC-01, game-design §4.1–§4.2), kept on the hero so it is saved
// with them: the Cell Key spawns once at the keySeed's cell (CellLayout.keyCell) when the
// rescuer is on the mission floor (at once, or when they come back to it), with a local ping.
//Once the key task is done or the mission is over, every Cell Key in our hands or on this floor
// goes away and the buff ends.
public class CellRescue extends Buff {

	static {
		CoopCell.install();
	}

	{
		type = buffType.NEUTRAL;
		revivePersists = true;
	}

	String missionId = "";
	int depth, branch, trapCell;
	long keySeed;
	boolean keySpawned;
	int keyCell = -1;

	void start( MissionState.Mission m ) {
		missionId = m.id;
		depth = m.depth;
		branch = m.branch;
		trapCell = m.trapCell;
		keySeed = m.keySeed;
	}

	boolean onFloor() {
		return Dungeon.level != null && Dungeon.depth == depth && Dungeon.branch == branch;
	}

	@Override
	public boolean act() {
		MissionState.Mission m = CoopCell.link().mission();
		if (m == null || !m.id.equals( missionId ) || Boolean.TRUE.equals( m.tasks.get( CoopCell.TASK_KEY ) )) {
			finish();
			return true;
		}
		spawnKey();
		spend( TICK );
		return true;
	}

	void spawnKey() {
		if (keySpawned || !onFloor() || target == null) return;
		Level level = Dungeon.level;
		BindingTrap trap = CoopCell.trapAt( level, trapCell );
		if (trap == null) {
			//no trap here (shouldn't happen): keep the key away from the trigger cell itself
			trap = new BindingTrap();
			trap.left = trap.right = trapCell % level.width();
			trap.top = trap.bottom = trapCell / level.width();
		}
		CellKey key = new CellKey( missionId );
		CoopItems.markLocal( key );
		keySpawned = true;
		keyCell = CellLayout.keyCell( level, trap, keySeed );
		if (keyCell < 0) {
			//nowhere to put it: straight into the backpack
			if (!key.collect()) level.drop( key, target.pos );
		} else {
			level.drop( key, keyCell );
			CoopCell.link().ping( depth, branch, keyCell );
		}
		GLog.p( Messages.get( CoopCell.class, "key_spawned" ) );
	}

	//the key task is done or the mission is over
	void finish() {
		if (target != null && target == Dungeon.hero) {
			for (CellKey key : Dungeon.hero.belongings.getAllItems( CellKey.class )) {
				key.detachAll( Dungeon.hero.belongings.backpack );
			}
		}
		removeKeys( Dungeon.level );
		detach();
	}

	static void removeKeys( Level level ) {
		if (level == null) return;
		for (Heap heap : level.heaps.valueList()) {
			for (Item item : new ArrayList<>( heap.items )) {
				if (item instanceof CellKey) heap.remove( item );
			}
		}
	}

	private static final String MISSION = "mission";
	private static final String DEPTH = "depth";
	private static final String BRANCH = "branch";
	private static final String TRAP = "trap";
	private static final String KEY_SEED = "key_seed";
	private static final String SPAWNED = "spawned";
	private static final String KEY_CELL = "key_cell";

	@Override
	public void storeInBundle( Bundle bundle ) {
		super.storeInBundle( bundle );
		bundle.put( MISSION, missionId );
		bundle.put( DEPTH, depth );
		bundle.put( BRANCH, branch );
		bundle.put( TRAP, trapCell );
		bundle.put( KEY_SEED, keySeed );
		bundle.put( SPAWNED, keySpawned );
		bundle.put( KEY_CELL, keyCell );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		super.restoreFromBundle( bundle );
		missionId = bundle.getString( MISSION );
		depth = bundle.getInt( DEPTH );
		branch = bundle.getInt( BRANCH );
		trapCell = bundle.getInt( TRAP );
		keySeed = bundle.getLong( KEY_SEED );
		keySpawned = bundle.getBoolean( SPAWNED );
		keyCell = bundle.getInt( KEY_CELL );
	}
}
