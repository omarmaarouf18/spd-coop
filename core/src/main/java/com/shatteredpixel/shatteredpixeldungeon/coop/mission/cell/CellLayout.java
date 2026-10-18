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
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.ImpShopRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.StandardRoom;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

//where The Cell happens (MEC-01, game-design §4.1): the Binding Trap's placement in co-op level
// gen, and the rescuer's key cell. Pure functions of the level and the seeds, so both worlds
// agree (placement) and the rescuer gets the same cell every time (key).
final class CellLayout {

	//§4.7: ~1 Cell per 2 floors
	static final float TRAP_CHANCE = 0.5f;
	//mixed into the floor's seed so the trap roll is its own stream (never the level's)
	static final long SALT = 0x4C4C50445F43454CL;
	//the key never lands this close to the cell's walls (in the rescuer's world)
	static final int KEY_MIN_DISTANCE = 3;

	private CellLayout() {}

	// ---- placement (co-op level gen) ----

	//eligible floor (§4.1): main dungeon, not floor 1, not a boss floor, a room-based level
	static boolean eligibleFloor( Level level, int depth, int branch ) {
		return level instanceof RegularLevel && branch == 0 && depth > 1 && depth < 26 && !Dungeon.bossLevel( depth );
	}

	//eligible room (§4.1): a plain standard room. Entrance/exit rooms (sub-packages), quest rooms
	// (other packages: the mines, the Blacksmith), special and secret rooms (not StandardRooms)
	// and the Imp's shop are excluded.
	static boolean eligibleRoom( Room room ) {
		return room instanceof StandardRoom
				&& room.getClass().getPackage() == StandardRoom.class.getPackage()
				&& !(room instanceof ImpShopRoom)
				&& !doors( room, 1 ).isEmpty();
	}

	//places 0 or 1 hidden Binding Trap. Draws only from its own generator, seeded from the seed,
	// depth and branch, and always draws the same amount for the same floor.
	static BindingTrap place( Level level, int depth, int branch ) {
		if (!eligibleFloor( level, depth, branch )) return null;

		Random.pushGenerator( Dungeon.seedForDepth( depth, branch ) ^ SALT );
		try {
			if (Random.Float() >= TRAP_CHANCE) return null;

			ArrayList<Room> rooms = new ArrayList<>();
			for (Room room : ((RegularLevel) level).rooms()) {
				if (eligibleRoom( room ) && !trapCells( level, room ).isEmpty()) rooms.add( room );
			}
			if (rooms.isEmpty()) return null;
			Room room = rooms.get( Random.Int( rooms.size() ) );
			List<Integer> cells = trapCells( level, room );
			int cell = cells.get( Random.Int( cells.size() ) );

			BindingTrap trap = new BindingTrap();
			trap.left = room.left;
			trap.top = room.top;
			trap.right = room.right;
			trap.bottom = room.bottom;
			trap.roomId = roomId( depth, branch, room.left, room.top, level.width() );
			List<Integer> doors = doors( room, level.width() );
			trap.doors = new int[doors.size()];
			for (int i = 0; i < trap.doors.length; i++) trap.doors[i] = doors.get( i );
			trap.visible = false;
			level.setTrap( trap, cell );
			Level.set( cell, Terrain.SECRET_TRAP, level );
			return trap;
		} finally {
			Random.popGenerator();
		}
	}

	//objectId (PROTOCOL.md §3): content-defined, the same in both worlds, 1..2^53-1
	static long roomId( int depth, int branch, int left, int top, int width ) {
		return 1 + (long) (depth * 10 + branch) * 65536L + (top * width + left);
	}

	//the room's door cells, sorted (width 1: the door's x/y as given, for "has a door")
	static List<Integer> doors( Room room, int width ) {
		TreeSet<Integer> cells = new TreeSet<>();
		for (Room.Door door : room.connected.values()) {
			if (door != null) cells.add( door.x + door.y * width );
		}
		return new ArrayList<>( cells );
	}

	//plain floor inside the room, nothing on it, not next to a door, ascending
	static List<Integer> trapCells( Level level, Room room ) {
		int w = level.width();
		List<Integer> doors = doors( room, w );
		ArrayList<Integer> cells = new ArrayList<>();
		for (int y = room.top + 1; y < room.bottom; y++) {
			for (int x = room.left + 1; x < room.right; x++) {
				int cell = x + y * w;
				if (level.map[cell] != Terrain.EMPTY || level.traps.get( cell ) != null
						|| level.heaps.get( cell ) != null || level.plants.get( cell ) != null
						|| level.findMob( cell ) != null || transition( level, cell )) continue;
				boolean byDoor = false;
				for (int door : doors) {
					if (level.distance( door, cell ) <= 1) byDoor = true;
				}
				if (!byDoor) cells.add( cell );
			}
		}
		return cells;
	}

	// ---- the rescuer's key ----

	//cells the key can land on in this world: reachable on foot from the entrance (closed doors
	// count as passable; locked/secret doors and walls don't), plain walkable ground, at least
	// KEY_MIN_DISTANCE from the cell's rect. Ascending, from the terrain only (never chars or
	// heaps), so it doesn't depend on when it is asked.
	static int[] keyCandidates( Level level, BindingTrap trap ) {
		int len = level.length();
		boolean[] walk = new boolean[len];
		for (int i = 0; i < len; i++) {
			int flags = Terrain.flags[level.map[i]];
			walk[i] = (flags & Terrain.PASSABLE) != 0 && (flags & Terrain.SECRET) == 0;
		}
		int entrance = level.entrance();
		if (entrance < 0 || entrance >= len || !walk[entrance]) return new int[0];
		PathFinder.buildDistanceMap( entrance, walk );

		int w = level.width();
		ArrayList<Integer> cells = new ArrayList<>();
		for (int i = 0; i < len; i++) {
			if (PathFinder.distance[i] == Integer.MAX_VALUE || !walk[i]) continue;
			int tile = level.map[i];
			if ((Terrain.flags[tile] & (Terrain.AVOID | Terrain.PIT)) != 0) continue;
			if (tile == Terrain.DOOR || tile == Terrain.OPEN_DOOR || tile == Terrain.INACTIVE_TRAP
					|| tile == Terrain.ENTRANCE || tile == Terrain.ENTRANCE_SP || tile == Terrain.EXIT
					|| transition( level, i )) continue;
			if (rectDistance( trap, i, w ) < KEY_MIN_DISTANCE) continue;
			cells.add( i );
		}
		int[] result = new int[cells.size()];
		for (int i = 0; i < result.length; i++) result[i] = cells.get( i );
		return result;
	}

	//the key's cell: candidate number keySeed mod count; -1 if none
	static int keyCell( Level level, BindingTrap trap, long keySeed ) {
		int[] cells = keyCandidates( level, trap );
		if (cells.length == 0) return -1;
		return cells[(int) Math.floorMod( keySeed, (long) cells.length )];
	}

	//a level transition covers the cell (Level.getTransition reads Dungeon.level, which isn't
	// this level during gen)
	static boolean transition( Level level, int cell ) {
		int w = level.width(), x = cell % w, y = cell / w;
		for (LevelTransition t : level.transitions) {
			if (x >= t.left && x <= t.right && y >= t.top && y <= t.bottom) return true;
		}
		return false;
	}

	//Chebyshev distance from a cell to the rect (0 on or inside it)
	static int rectDistance( BindingTrap trap, int cell, int width ) {
		int x = cell % width, y = cell / width;
		int dx = Math.max( 0, Math.max( trap.left - x, x - trap.right ) );
		int dy = Math.max( 0, Math.max( trap.top - y, y - trap.bottom ) );
		return Math.max( dx, dy );
	}

}
