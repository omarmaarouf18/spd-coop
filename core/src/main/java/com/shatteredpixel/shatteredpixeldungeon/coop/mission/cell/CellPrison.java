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
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Bundle;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.Arrays;

//the prisoner's side of a Cell (MEC-01, game-design §4.1, §4.4), kept on the hero so it is saved
// with them.
//- Seal: the trap room's doors become locked doors in this world only (Level.set, so LINK-01
//  never mirrors them). A door with a char in it, or a secret door nobody found, is sealed as
//  soon as it can be; a door the prisoner unlocks (an iron key) locks again. Unseal puts back
//  what was there (an open door as a closed one).
//- Waves: CellWaves on the mission clock, at most one wave per turn, from this world's gameplay
//  RNG (the waves are the prisoner's alone). None while the clock is held or the prisoner is Downed.
//- Ends: CoopCell calls finish() on MISSION_END. Off the mission floor it waits until the
//  prisoner is back on it; a mission that is gone without an END we heard (a lost listener)
//  unseals too.
public class CellPrison extends Buff {

	static {
		CoopCell.install();
	}

	{
		type = buffType.NEGATIVE;
		announced = true;
		revivePersists = true;
	}

	String missionId = "";
	int depth, branch, trapCell = -1;
	int left, top, right, bottom;
	boolean hasRoom;
	int[] doors = new int[0];
	//the tile each door had before it was sealed; -1 = not sealed (now)
	int[] original = new int[0];
	int waves;
	//the mission's hard cap (MISSION_START's deadline − startsAt)
	long cap;
	boolean over;

	void start( MissionState.Mission m, BindingTrap trap ) {
		missionId = m.id;
		depth = m.depth;
		branch = m.branch;
		trapCell = m.trapCell;
		cap = CellWaves.cap( m );
		if (trap != null) {
			hasRoom = true;
			left = trap.left;
			top = trap.top;
			right = trap.right;
			bottom = trap.bottom;
			doors = trap.doors.clone();
		}
		original = new int[doors.length];
		Arrays.fill( original, -1 );
	}

	boolean onFloor() {
		return Dungeon.level != null && Dungeon.depth == depth && Dungeon.branch == branch;
	}

	boolean inRoom( int cell ) {
		int w = Dungeon.level.width();
		int x = cell % w, y = cell / w;
		return hasRoom && x > left && x < right && y > top && y < bottom;
	}

	@Override
	public int icon() {
		return BuffIndicator.LOCKED_FLOOR;
	}

	@Override
	public boolean act() {
		if (!onFloor()) {
			spend( TICK );
			return true;
		}
		MissionState.Mission m = CoopCell.link().mission();
		if (over || m == null || !m.id.equals( missionId )) {
			unseal();
			detach();
			return true;
		}
		seal();
		long now = CoopCell.link().serverNow();
		if (CoopCell.link().clockRunning() && !CoopCell.link().downed() && waves < CellWaves.due( m, cap, now )) {
			spawnWave( m.depth, waves, CellWaves.lethal( m, cap, now ) );
			waves++;
		}
		spend( TICK );
		return true;
	}

	//MISSION_START: lock the doors now, and bring the prisoner back in if they stepped out
	void begin() {
		if (!onFloor() || target == null) return;
		if (hasRoom && !inRoom( target.pos )) {
			int cell = freeCellInRoom();
			if (cell >= 0 && target.sprite != null) {
				ScrollOfTeleportation.appear( target, cell );
			} else if (cell >= 0) {
				target.pos = cell;
			}
		}
		seal();
		GLog.w( Messages.get( CoopCell.class, "sealed" ) );
	}

	void seal() {
		Level level = Dungeon.level;
		boolean changed = false;
		for (int i = 0; i < doors.length; i++) {
			int door = doors[i];
			int tile = level.map[door];
			if (tile == Terrain.LOCKED_DOOR || tile == Terrain.SECRET_DOOR || Actor.findChar( door ) != null) continue;
			if (original[i] < 0) original[i] = tile;
			Level.set( door, Terrain.LOCKED_DOOR, level );
			GameScene.updateMap( door );
			changed = true;
		}
		if (changed) CoopCell.refreshView();
	}

	void unseal() {
		if (!onFloor()) return;
		Level level = Dungeon.level;
		boolean changed = false;
		for (int i = 0; i < doors.length; i++) {
			if (original[i] < 0) continue;
			int door = doors[i];
			if (level.map[door] == Terrain.LOCKED_DOOR) {
				Level.set( door, original[i] == Terrain.OPEN_DOOR ? Terrain.DOOR : original[i], level );
				GameScene.updateMap( door );
				changed = true;
			}
			original[i] = -1;
		}
		if (changed) {
			GLog.i( Messages.get( CoopCell.class, "unsealed" ) );
			CoopCell.refreshView();
		}
	}

	//MISSION_END (any result): unseal now if we're on the floor, else when we're back
	void finish() {
		over = true;
		if (onFloor()) {
			unseal();
			detach();
		}
	}

	private int freeCellInRoom() {
		Level level = Dungeon.level;
		if (free( level, trapCell )) return trapCell;
		ArrayList<Integer> cells = roomCells( level );
		return cells.isEmpty() ? -1 : cells.get( 0 );
	}

	private static boolean free( Level level, int cell ) {
		return cell >= 0 && cell < level.length() && level.passable[cell] && !level.solid[cell]
				&& Actor.findChar( cell ) == null;
	}

	private ArrayList<Integer> roomCells( Level level ) {
		ArrayList<Integer> cells = new ArrayList<>();
		int w = level.width();
		for (int y = top + 1; y < bottom; y++) {
			for (int x = left + 1; x < right; x++) {
				if (free( level, x + y * w )) cells.add( x + y * w );
			}
		}
		return cells;
	}

	//cells a wave mob can appear on: free cells of the cell, away from the prisoner when there's
	// room; around the prisoner if they got out of the cell
	ArrayList<Integer> spawnCells() {
		Level level = Dungeon.level;
		int hero = target.pos;
		ArrayList<Integer> cells = new ArrayList<>();
		if (inRoom( hero )) {
			for (int cell : roomCells( level )) {
				if (level.distance( cell, hero ) >= 2) cells.add( cell );
			}
			if (cells.isEmpty()) {
				for (int cell : roomCells( level )) {
					if (cell != hero) cells.add( cell );
				}
			}
		} else {
			for (int cell = 0; cell < level.length(); cell++) {
				int d = level.distance( cell, hero );
				if (d >= 2 && d <= 3 && free( level, cell ) && !level.avoid[cell]) cells.add( cell );
			}
		}
		return cells;
	}

	//one wave: CellWaves.count mobs of the source floor's rotation
	ArrayList<Mob> spawnWave( int missionDepth, int wave, boolean lethal ) {
		ArrayList<Mob> spawned = new ArrayList<>();
		ArrayList<Class<? extends Mob>> rotation = MobSpawner.getMobRotation( CellWaves.sourceDepth( missionDepth, wave, lethal ) );
		ArrayList<Integer> cells = spawnCells();
		int count = CellWaves.count( wave, lethal );
		for (int i = 0; i < count && !cells.isEmpty() && !rotation.isEmpty(); i++) {
			int cell = cells.remove( Random.Int( cells.size() ) );
			Mob mob = Reflection.newInstance( rotation.get( Random.Int( rotation.size() ) ) );
			if (mob == null) continue;
			mob.pos = cell;
			mob.state = mob.HUNTING;
			GameScene.add( mob );
			//hunting already; the alert mark needs a sprite (none off screen)
			if (target != null && mob.sprite != null) mob.beckon( target.pos );
			spawned.add( mob );
		}
		if (!spawned.isEmpty()) GLog.w( Messages.get( CoopCell.class, lethal ? "wave_lethal" : "wave" ) );
		return spawned;
	}

	private static final String MISSION = "mission";
	private static final String DEPTH = "depth";
	private static final String BRANCH = "branch";
	private static final String TRAP = "trap";
	private static final String RECT = "rect";
	private static final String HAS_ROOM = "has_room";
	private static final String DOORS = "doors";
	private static final String ORIGINAL = "original";
	private static final String WAVES = "waves";
	private static final String OVER = "over";
	private static final String CAP = "cap";

	@Override
	public void storeInBundle( Bundle bundle ) {
		super.storeInBundle( bundle );
		bundle.put( MISSION, missionId );
		bundle.put( DEPTH, depth );
		bundle.put( BRANCH, branch );
		bundle.put( TRAP, trapCell );
		bundle.put( RECT, new int[]{ left, top, right, bottom } );
		bundle.put( HAS_ROOM, hasRoom );
		bundle.put( DOORS, doors );
		bundle.put( ORIGINAL, original );
		bundle.put( WAVES, waves );
		bundle.put( OVER, over );
		bundle.put( CAP, cap );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		super.restoreFromBundle( bundle );
		missionId = bundle.getString( MISSION );
		depth = bundle.getInt( DEPTH );
		branch = bundle.getInt( BRANCH );
		trapCell = bundle.getInt( TRAP );
		int[] rect = bundle.getIntArray( RECT );
		left = rect[0];
		top = rect[1];
		right = rect[2];
		bottom = rect[3];
		hasRoom = bundle.getBoolean( HAS_ROOM );
		doors = bundle.getIntArray( DOORS );
		original = bundle.getIntArray( ORIGINAL );
		waves = bundle.getInt( WAVES );
		over = bundle.getBoolean( OVER );
		cap = bundle.getLong( CAP );
	}
}
