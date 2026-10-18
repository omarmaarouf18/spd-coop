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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopRunSim;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.HeavyPlay;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelKey;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.VanillaSeeds;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.entrance.EntranceRoom;
import com.watabou.utils.PathFinder;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

//MEC-01, game-design §4.1/§4.7: 0–1 hidden Binding Trap per eligible floor (not floor 1, no boss
// floor, no quest branch, standard rooms only, never the entrance/exit room), on the same cell in
// both worlds however each player plays, and the key's candidate cells
class CellLayoutTest {

	private static final long[] SEEDS = { CellTestFloors.SEED, 42L, VanillaSeeds.TEXT_HASH_SEED };

	@Test
	void trapsFollowThePlacementRules() {
		int eligible = 0, withTrap = 0;
		for (long seed : SEEDS) {
			TreeMap<LevelKey, Level> levels = CoopRunSim.start( seed, HeroClass.WARRIOR, CoopRunSim.NOTHING, true )
					.generateAll().levels();
			for (Map.Entry<LevelKey, Level> e : levels.entrySet()) {
				int depth = e.getKey().depth(), branch = e.getKey().branch();
				Level level = e.getValue();
				List<BindingTrap> traps = CellTestFloors.bindingTraps( level );
				String where = seed + " " + e.getKey();
				assertTrue( traps.size() <= 1, where );
				boolean floorOk = CellLayout.eligibleFloor( level, depth, branch );
				if (depth == 1 || Dungeon.bossLevel( depth ) || branch != 0 || depth == 26) assertFalse( floorOk, where );
				if (!floorOk) {
					assertTrue( traps.isEmpty(), where );
					continue;
				}
				eligible++;
				if (traps.isEmpty()) continue;
				withTrap++;

				BindingTrap trap = traps.get( 0 );
				assertFalse( trap.visible, where );
				assertTrue( trap.active, where );
				assertEquals( Terrain.SECRET_TRAP, level.map[trap.pos], where );
				Room room = roomOf( (RegularLevel) level, trap );
				assertNotNull( room, where + ": the trap's rect is a room of the level" );
				assertTrue( CellLayout.eligibleRoom( room ), where + " " + room.getClass().getSimpleName() );
				assertFalse( room == ((RegularLevel) level).room( EntranceRoom.class ), where );
				assertTrue( trap.inRoom( trap.pos, level.width() ), where );
				assertTrue( trap.doors.length > 0, where );
				for (int door : trap.doors) {
					assertTrue( trap.onRect( door, level.width() ) && !trap.inRoom( door, level.width() ), where + " door on the wall" );
					assertTrue( level.distance( door, trap.pos ) > 1, where + " not next to a door" );
				}
				assertEquals( CellLayout.roomId( depth, branch, room.left, room.top, level.width() ), trap.roomId, where );
				assertTrue( trap.roomId >= 1 && trap.roomId < (1L << 53), where );
			}
		}
		//§4.7: about one floor in two
		assertTrue( withTrap > eligible / 4 && withTrap < eligible * 3 / 4, withTrap + " of " + eligible );
	}

	@Test
	void bothWorldsGetTheSameTrapHoweverTheyPlay() {
		for (long seed : SEEDS) {
			TreeMap<LevelKey, Level> a = CoopRunSim.start( seed, HeroClass.WARRIOR, CoopRunSim.NOTHING, true ).generateAll().levels();
			TreeMap<LevelKey, String> traps = describe( a );
			TreeMap<LevelKey, Level> b = CoopRunSim.start( seed, HeroClass.MAGE, new HeavyPlay(), true ).generateAll().levels();
			assertEquals( traps, describe( b ), "seed " + seed );
			TreeMap<LevelKey, Level> again = CoopRunSim.start( seed, HeroClass.WARRIOR, CoopRunSim.NOTHING, true ).generateAll().levels();
			assertEquals( traps, describe( again ), "seed " + seed + " again" );
		}
	}

	@Test
	void keyCandidatesAreReachableGroundAwayFromTheCell() {
		Level level = CellTestFloors.firstTrapFloor( CellTestFloors.SEED, HeroClass.WARRIOR );
		BindingTrap trap = CellTestFloors.bindingTraps( level ).get( 0 );
		int[] cells = CellLayout.keyCandidates( level, trap );
		assertTrue( cells.length > 10, "candidates: " + cells.length );
		boolean[] walk = new boolean[level.length()];
		for (int i = 0; i < walk.length; i++) walk[i] = level.passable[i] && !level.secret[i];
		PathFinder.buildDistanceMap( level.entrance(), walk );
		for (int i = 0; i < cells.length; i++) {
			int c = cells[i];
			if (i > 0) assertTrue( cells[i - 1] < c, "ascending" );
			assertTrue( PathFinder.distance[c] < Integer.MAX_VALUE, "reachable " + c );
			assertTrue( CellLayout.rectDistance( trap, c, level.width() ) >= CellLayout.KEY_MIN_DISTANCE );
			assertFalse( level.avoid[c] || level.pit[c], "safe ground " + c );
		}
		for (long seed = -5; seed < 50; seed++) {
			int cell = CellLayout.keyCell( level, trap, seed );
			assertEquals( cells[(int) Math.floorMod( seed, (long) cells.length )], cell );
			assertEquals( cell, CellLayout.keyCell( level, trap, seed ), "same keySeed, same cell" );
		}
	}

	private static TreeMap<LevelKey, String> describe( TreeMap<LevelKey, Level> levels ) {
		TreeMap<LevelKey, String> out = new TreeMap<>();
		for (Map.Entry<LevelKey, Level> e : levels.entrySet()) {
			for (BindingTrap t : CellTestFloors.bindingTraps( e.getValue() )) {
				out.put( e.getKey(), t.pos + " " + t.roomId + " " + t.left + "," + t.top + "," + t.right + "," + t.bottom
						+ " " + Arrays.toString( t.doors ) );
			}
		}
		return out;
	}

	private static Room roomOf( RegularLevel level, BindingTrap trap ) {
		for (Room r : level.rooms()) {
			if (r.left == trap.left && r.top == trap.top && r.right == trap.right && r.bottom == trap.bottom) return r;
		}
		return null;
	}
}
