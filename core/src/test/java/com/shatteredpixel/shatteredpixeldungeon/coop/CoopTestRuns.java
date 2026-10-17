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

package com.shatteredpixel.shatteredpixeldungeon.coop;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;

//test-only door to the package-private run start, for tests in other packages (DET-01)
public final class CoopTestRuns {

	private CoopTestRuns() {}

	//prepares the next Dungeon.init() to start a co-op run with this seed and hero
	public static CoopRun begin( long seed, HeroClass heroClass ) {
		CoopRun run = new CoopRun();
		run.runId = "de7010000000" + Long.toHexString( seed & 0xffff );
		run.seed = seed;
		run.heroClass = heroClass.name();
		run.saveSlot = CoopSaves.FIRST;
		Coop.beginRun( run );
		return run;
	}

	//prepares the next Dungeon.init() to continue a co-op run read back from a save
	public static void resume( CoopRun saved ) {
		Coop.beginRun( saved );
	}
}
