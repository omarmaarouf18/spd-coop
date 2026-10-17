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

import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.watabou.utils.Bundle;
import com.watabou.utils.FileUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

//co-op runs get their own save slots (AGENTS.md rule 17): slots FIRST..FIRST+COUNT-1, stored in
// folders coop1..coopN (GamesInProgress.gameFolder has a COOP: hook). The vanilla start screen
// only lists slots 1..MAX_SLOTS, so co-op saves never show up as single-player games.
public final class CoopSaves {

	public static final int FIRST = 101;
	public static final int COUNT = 6;

	private CoopSaves() {}

	public static boolean isCoopSlot( int slot ) {
		return slot >= FIRST && slot < FIRST + COUNT;
	}

	public static String folder( int slot ) {
		return "coop" + (slot - FIRST + 1);
	}

	public static final class Saved {
		public final int slot;
		public final CoopRun run;
		public final int depth;

		Saved( int slot, CoopRun run, int depth ) {
			this.slot = slot;
			this.run = run;
			this.depth = depth;
		}
	}

	//every co-op save on this device, by slot
	public static List<Saved> list() {
		List<Saved> out = new ArrayList<>();
		for (int slot = FIRST; slot < FIRST + COUNT; slot++) {
			Saved s = read( slot );
			if (s != null) out.add( s );
		}
		return out;
	}

	public static Saved find( String runId ) {
		for (Saved s : list()) {
			if (s.run.runId.equals( runId )) return s;
		}
		return null;
	}

	//the slot for a run: its existing save, else the first free slot, else the first slot
	// (the oldest co-op save is overwritten only when all COUNT slots are in use)
	public static int slotFor( String runId ) {
		Saved existing = find( runId );
		if (existing != null) return existing.slot;
		for (int slot = FIRST; slot < FIRST + COUNT; slot++) {
			if (!GamesInProgress.gameExists( slot )) return slot;
		}
		return FIRST;
	}

	private static Saved read( int slot ) {
		if (!GamesInProgress.gameExists( slot )) return null;
		try {
			Bundle bundle = FileUtils.bundleFromFile( GamesInProgress.gameFile( slot ) );
			if (!bundle.contains( Coop.BUNDLE_KEY )) return null;
			return new Saved( slot, (CoopRun) bundle.get( Coop.BUNDLE_KEY ), bundle.getInt( "depth" ) );
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
