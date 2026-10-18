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
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopTestRuns;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;

//test helpers for MEC-01: co-op floors with a Binding Trap, and s2c mission frames decoded by the
// real protocol decoder
final class CellTestFloors {

	static final long SEED = 123456789L;
	static final String ME = "a1b2c3d4e5f60718";
	static final String ALLY = "b2c3d4e5f6071829";
	static final String M1 = "0123456789abcdef";
	static final String M2 = "fedcba9876543210";

	private static long seq = 500;

	private CellTestFloors() {}

	//starts a co-op run and generates main floors 1.. until one has a Binding Trap; that floor is
	// the current level
	static Level firstTrapFloor( long seed, HeroClass cls ) {
		HeadlessEnv.start();
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		CoopTestRuns.begin( seed, cls );
		Dungeon.init();
		if (!Coop.isCoopRun()) throw new AssertionError( "not a co-op run" );
		for (int depth = 1; depth <= 24; depth++) {
			Dungeon.depth = depth;
			Dungeon.branch = 0;
			Level level = Dungeon.newLevel();
			if (!bindingTraps( level ).isEmpty()) {
				Dungeon.level = level;
				PathFinder.setMapSize( level.width(), level.height() );
				return level;
			}
		}
		throw new AssertionError( "no Binding Trap on floors 1-24 for seed " + seed );
	}

	static List<BindingTrap> bindingTraps( Level level ) {
		ArrayList<BindingTrap> traps = new ArrayList<>();
		for (Trap trap : level.traps.valueList()) {
			if (trap instanceof BindingTrap) traps.add( (BindingTrap) trap );
		}
		return traps;
	}

	static Message s2c( String t, String payload ) {
		try {
			return Protocol.decode( Direction.S2C, "{\"v\":1,\"t\":\"" + t + "\",\"seq\":" + (++seq) + ",\"p\":" + payload + "}" );
		} catch (Protocol.DecodeError e) {
			throw new AssertionError( t + " " + payload + ": " + e.getMessage(), e );
		}
	}

	static Message start( String id, String prisoner, String rescuer, int depth, BindingTrap trap,
			long keySeed, long startsAt, long deadline, long waveMs ) {
		return s2c( Protocol.MISSION_START, "{\"missionId\":\"" + id + "\",\"kind\":\"CELL\",\"prisoner\":\"" + prisoner
				+ "\",\"rescuer\":\"" + rescuer + "\",\"depth\":" + depth + ",\"branch\":0,\"trapCell\":" + trap.pos
				+ ",\"roomId\":" + trap.roomId + ",\"keySeed\":" + keySeed + ",\"startsAt\":" + startsAt
				+ ",\"deadline\":" + deadline + ",\"waveIntervalMs\":" + waveMs + "}" );
	}

	static Message update( String id, boolean paused, long remainingMs, String tasksJson ) {
		return s2c( Protocol.MISSION_UPDATE, "{\"missionId\":\"" + id + "\",\"paused\":" + paused + ",\"remainingMs\":" + remainingMs
				+ (tasksJson == null ? "" : ",\"tasks\":" + tasksJson) + "}" );
	}

	static Message success( String id, long rewardSeed ) {
		return s2c( Protocol.MISSION_END, "{\"missionId\":\"" + id + "\",\"result\":\"success\",\"reward\":{\"rewardSeed\":" + rewardSeed + "}}" );
	}

	static Message failed( String id, int regionStart ) {
		return s2c( Protocol.MISSION_END, "{\"missionId\":\"" + id + "\",\"result\":\"failed\",\"penalty\":{\"regionStartDepth\":"
				+ regionStart + ",\"goldPct\":25}}" );
	}

	static Message ended( String id, String result ) {
		return s2c( Protocol.MISSION_END, "{\"missionId\":\"" + id + "\",\"result\":\"" + result + "\"}" );
	}
}
