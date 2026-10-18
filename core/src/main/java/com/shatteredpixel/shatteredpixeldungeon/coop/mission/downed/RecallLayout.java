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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission.downed;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.ScrollOfRecall;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.ShopRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.ImpShopRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.StandardRoom;
import com.watabou.utils.Random;

import java.util.ArrayList;
import java.util.List;

//where Scrolls of Recall come from (MEC-02, game-design §5.2), placed by co-op level gen right
// after the floor is built, so both worlds get them on the same cell and they become shared
// floor loot with an itemNetId (CoopItemIds numbers them with the rest):
//- one guaranteed per region, in a chest on one of the region's four regular floors (which one
//  is drawn from the region start's seed, so every floor of the region agrees);
//- shop stock: a chance of one for sale in a floor's shop.
//Everything draws from its own generators seeded from the run seed and the depth (never the
// level's stream), so the rest of the floor is exactly what it was without them.
public final class RecallLayout {

	static final long SALT_FLOOR = 0x4C4C50445F52434CL;
	static final long SALT_PLACE = 0x4C4C50445F524331L;
	static final float SHOP_CHANCE = 0.5f;
	static final int REGION_FLOORS = 4;

	//tests only: false skips the placement (to compare floors with and without it)
	static boolean enabled = true;

	private RecallLayout() {}

	//CoopLevelGen, after the floor is built (co-op runs only)
	public static void place( Level level ) {
		if (Coop.isCoopRun() && enabled) place( level, Dungeon.depth, Dungeon.branch );
	}

	//the region's Recall floor: one of its four regular floors (depth 1..24)
	static int recallFloor( int depth ) {
		int start = ((depth - 1) / 5) * 5 + 1;
		Random.pushGenerator( Dungeon.seedForDepth( start, 0 ) ^ SALT_FLOOR );
		try {
			return start + Random.Int( REGION_FLOORS );
		} finally {
			Random.popGenerator();
		}
	}

	//what was placed: 0, 1 (chest or shop) or 2
	static int place( Level level, int depth, int branch ) {
		if (branch != 0 || depth < 1 || depth > 24 || !(level instanceof RegularLevel)) return 0;
		RegularLevel regular = (RegularLevel) level;
		int placed = 0;
		Random.pushGenerator( Dungeon.seedForDepth( depth, branch ) ^ SALT_PLACE );
		try {
			//both draws always happen, whatever this floor has
			float shopRoll = Random.Float();
			long pick = Random.Long();
			if (depth == recallFloor( depth )) {
				List<Integer> cells = chestCells( regular );
				if (!cells.isEmpty()) {
					int cell = cells.get( (int) Math.floorMod( pick, (long) cells.size() ) );
					level.drop( new ScrollOfRecall(), cell ).type = Heap.Type.CHEST;
					placed++;
				}
			}
			if (shopRoll < SHOP_CHANCE) {
				List<Integer> cells = shopCells( regular );
				if (!cells.isEmpty()) {
					int cell = cells.get( (int) Math.floorMod( pick >>> 17, (long) cells.size() ) );
					level.drop( new ScrollOfRecall(), cell ).type = Heap.Type.FOR_SALE;
					placed++;
				}
			}
		} finally {
			Random.popGenerator();
		}
		return placed;
	}

	//plain floor in the standard rooms (not entrance/exit/quest/Imp's shop), nothing on it,
	// in room order then cell order
	static List<Integer> chestCells( RegularLevel level ) {
		ArrayList<Integer> cells = new ArrayList<>();
		for (Room room : level.rooms()) {
			if (!(room instanceof StandardRoom) || room.getClass().getPackage() != StandardRoom.class.getPackage()
					|| room instanceof ImpShopRoom) continue;
			addFree( level, room, Terrain.EMPTY, cells );
		}
		//no such room has plain floor left: any free plain floor of the level
		if (cells.isEmpty()) {
			for (int cell = 0; cell < level.length(); cell++) {
				if (level.map[cell] == Terrain.EMPTY && free( level, cell )) cells.add( cell );
			}
		}
		return cells;
	}

	//the shop's own floor, nothing on it, not next to its door
	static List<Integer> shopCells( RegularLevel level ) {
		ArrayList<Integer> cells = new ArrayList<>();
		for (Room room : level.rooms()) {
			if (room instanceof ShopRoom) addFree( level, room, Terrain.EMPTY_SP, cells );
		}
		return cells;
	}

	private static void addFree( Level level, Room room, int tile, List<Integer> cells ) {
		int w = level.width();
		for (int y = room.top + 1; y < room.bottom; y++) {
			for (int x = room.left + 1; x < room.right; x++) {
				int cell = x + y * w;
				if (level.map[cell] != tile || !free( level, cell )) continue;
				boolean byDoor = false;
				for (Room.Door door : room.connected.values()) {
					if (door != null && level.distance( door.x + door.y * w, cell ) <= 1) byDoor = true;
				}
				if (!byDoor) cells.add( cell );
			}
		}
	}

	private static boolean free( Level level, int cell ) {
		return level.heaps.get( cell ) == null && level.traps.get( cell ) == null && level.plants.get( cell ) == null
				&& level.findMob( cell ) == null && !transition( level, cell );
	}

	//Level.getTransition reads Dungeon.level, which isn't this level during gen
	private static boolean transition( Level level, int cell ) {
		int w = level.width(), x = cell % w, y = cell / w;
		for (LevelTransition t : level.transitions) {
			if (x >= t.left && x <= t.right && y >= t.top && y <= t.bottom) return true;
		}
		return false;
	}
}
