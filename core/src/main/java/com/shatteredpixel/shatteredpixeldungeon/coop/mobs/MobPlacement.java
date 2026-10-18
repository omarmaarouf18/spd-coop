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

package com.shatteredpixel.shatteredpixeldungeon.coop.mobs;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Piranha;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Statue;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.VaultLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.AquariumRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.ImpShopRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.StandardRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.SuspiciousChestRoom;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.ShadowCaster;
import com.watabou.utils.BArray;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Point;
import com.watabou.utils.Random;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

//Different places (D-023, game-design §6A.9): on this player's first entry to a floor, the
// shared mobs RegularLevel.createMobs placed move to per-player seeded cells, under the same rules
// createMobs uses. Both worlds keep the same ids and classes; only positions differ.
//- Only "wandering" mobs move: in a standard room that isn't the entrance room or a room that
//  places its own mob (aquarium, Imp's shop, suspicious chest), and not a room-anchored class
//  (mimics, statues, piranhas). Everything else keeps its generated cell.
//- One generator for the floor, seeded from (floor seed, salt, playerId); it is pushed around the
//  whole pass, so the level's own RNG never moves (AGENTS.md §3.3).
//- The new cells are saved with the level; CoopMobs records the floor, so a reload never moves
//  them again.
final class MobPlacement {

	//the salt that separates this stream from every other use of the floor seed
	static final long SALT = 0x4d4f422d30315aL; //"MOB-01Z"

	//how many rooms to try per mob before it keeps its generated cell
	private static final int ROOM_TRIES = 10;
	private static final int CELL_TRIES = 30;

	private MobPlacement() {}

	static long seed( long floorSeed, String player ) {
		return mix( mix( floorSeed ^ SALT ) ^ hash( player ) );
	}

	//relocates the movable shared mobs of level, sorted by id; returns how many moved
	static int relocate( Level level, long floorSeed, String player, int heroPos ) {
		if (!(level instanceof RegularLevel) || level instanceof VaultLevel) return 0;
		RegularLevel regular = (RegularLevel) level;
		Room entranceRoom = regular.room( level.entrance() );

		List<Mob> movable = new ArrayList<>();
		for (Mob mob : level.mobs) {
			if (CoopMobs.idOf( mob ) != null && movable( regular, entranceRoom, mob )) movable.add( mob );
		}
		if (movable.isEmpty()) return 0;
		movable.sort( (a, b) -> compareIds( CoopMobs.idOf( a ), CoopMobs.idOf( b ) ) );

		ArrayList<Room> rooms = new ArrayList<>();
		for (Room room : regular.rooms()) {
			if (room instanceof StandardRoom && room != entranceRoom && !placesOwnMob( room )) {
				for (int i = 0; i < ((StandardRoom) room).mobSpawnWeight(); i++) rooms.add( room );
			}
		}
		if (rooms.isEmpty()) return 0;

		boolean[] blocked = blockedCells( level, heroPos );
		int moved = 0;
		Random.pushGenerator( seed( floorSeed, player ) );
		try {
			for (Mob mob : movable) {
				int cell = pick( level, rooms, blocked, mob );
				if (cell == -1) continue;
				mob.pos = cell;
				moved++;
			}
		} finally {
			Random.popGenerator();
		}
		return moved;
	}

	static boolean movable( RegularLevel level, Room entranceRoom, Mob mob ) {
		if (mob instanceof Mimic || mob instanceof Statue || mob instanceof Piranha) return false;
		Room room = level.room( mob.pos );
		return room instanceof StandardRoom && room != entranceRoom && !placesOwnMob( room );
	}

	private static boolean placesOwnMob( Room room ) {
		return room instanceof AquariumRoom || room instanceof ImpShopRoom || room instanceof SuspiciousChestRoom;
	}

	//createMobs' rule: never within an 8-tile FOV or an 8-tile walk of the entrance; also never in
	// sight of where the hero arrived (a fall or a teleport lands away from the entrance)
	private static boolean[] blockedCells( Level level, int heroPos ) {
		boolean[] blocked = new boolean[level.length()];
		Point c = level.cellToPoint( level.entrance() );
		ShadowCaster.castShadow( c.x, c.y, level.width(), blocked, level.losBlocking, 8 );
		if (heroPos >= 0 && heroPos < level.length()) {
			boolean[] heroFov = new boolean[level.length()];
			Point h = level.cellToPoint( heroPos );
			ShadowCaster.castShadow( h.x, h.y, level.width(), heroFov, level.losBlocking, 8 );
			BArray.or( blocked, heroFov, blocked );
		}
		PathFinder.buildDistanceMap( level.entrance(), BArray.not( level.solid, null ), 8 );
		for (int i = 0; i < blocked.length; i++) {
			if (PathFinder.distance[i] != Integer.MAX_VALUE) blocked[i] = true;
		}
		return blocked;
	}

	private static int pick( Level level, ArrayList<Room> rooms, boolean[] blocked, Mob mob ) {
		for (int r = 0; r < ROOM_TRIES; r++) {
			Room room = Random.element( rooms );
			for (int t = 0; t < CELL_TRIES; t++) {
				int cell = level.pointToCell( room.random() );
				if (valid( level, room, blocked, mob, cell )) return cell;
			}
		}
		return -1;
	}

	private static boolean valid( Level level, Room room, boolean[] blocked, Mob mob, int cell ) {
		return cell >= 0 && cell < level.length()
				&& !blocked[cell]
				&& level.passable[cell] && !level.solid[cell]
				&& room.canPlaceCharacter( level.cellToPoint( cell ), level )
				&& cell != level.exit()
				&& level.map[cell] != Terrain.DOOR && level.map[cell] != Terrain.OPEN_DOOR
				&& level.traps.get( cell ) == null && level.plants.get( cell ) == null
				&& level.heaps.get( cell ) == null
				&& level.findMob( cell ) == null && Actor.findChar( cell ) == null
				&& (level.openSpace[cell] || !mob.properties().contains( Char.Property.LARGE ));
	}

	//"depth:branch:index" ordered numerically by index (all ids of one floor share depth:branch)
	static int compareIds( String a, String b ) {
		long ia = Long.parseLong( a.substring( a.lastIndexOf( ':' ) + 1 ) );
		long ib = Long.parseLong( b.substring( b.lastIndexOf( ':' ) + 1 ) );
		return Long.compare( ia, ib );
	}

	//FNV-1a over UTF-8: the same on every device
	private static long hash( String s ) {
		long h = 0xcbf29ce484222325L;
		for (byte b : s.getBytes( StandardCharsets.UTF_8 )) {
			h ^= b & 0xff;
			h *= 0x100000001b3L;
		}
		return h;
	}

	//splitmix64 finalizer
	private static long mix( long z ) {
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		return z ^ (z >>> 31);
	}
}
