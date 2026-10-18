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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

//the return penalty of a failed mission (D-006, game-design §4.5), the same on both clients:
// lose goldPct % of the gold, then travel to the region start's entrance with the vanilla
// InterlevelScene.Mode.RETURN. Dungeon.depth is never written here: InterlevelScene.returnTo()
// saves the current floor first and then switches (the v3.3.8 prototype's save corruption came
// from setting the depth by hand).
public final class MissionPenalty {

	private MissionPenalty() {}

	public static int goldLost( int gold, int pct ) {
		if (gold <= 0 || pct <= 0) return 0;
		return (int) ((long) gold * Math.min( 100, pct ) / 100);
	}

	//switchScene: Game.switchScene( InterlevelScene.class ) in the game, a recorder in tests
	public static void apply( MissionState.End end, Runnable switchScene ) {
		int lost = goldLost( Dungeon.gold, end.goldPct );
		Dungeon.gold -= lost;
		int depth = Math.max( 1, end.regionStartDepth );
		GLog.n( Messages.get( MissionPenalty.class, "failed", lost, depth ) );
		returnTo( depth, switchScene );
	}

	//returnPos -1 = the floor's entrance (Dungeon.switchLevel)
	static void returnTo( int depth, Runnable switchScene ) {
		Level.beforeTransition();
		InterlevelScene.mode = InterlevelScene.Mode.RETURN;
		InterlevelScene.returnDepth = depth;
		InterlevelScene.returnBranch = 0;
		InterlevelScene.returnPos = -1;
		switchScene.run();
	}

	//an active boss fight seals the floor (vanilla blocks every inter-floor teleport then); the
	// return waits until the fight is over. Other floors, the mines included, are left at once.
	public static boolean travelBlocked() {
		return Dungeon.level != null && Dungeon.level.locked && Dungeon.bossLevel();
	}
}
