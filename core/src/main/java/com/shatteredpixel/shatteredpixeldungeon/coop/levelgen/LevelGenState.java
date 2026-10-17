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

package com.shatteredpixel.shatteredpixeldungeon.coop.levelgen;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom;
import com.watabou.utils.Bundle;
import com.watabou.utils.Random;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

//The run state that level generation reads AND that gameplay can change (DET-01 audit, see the
// worklog): Generator decks/category probabilities, Dungeon.LimitedDrops, and the special/secret
// room run lists. A snapshot is a Bundle built with each system's own store/restore methods.
//In co-op runs two snapshots exist: the runtime one (what gameplay sees) and the level-gen one,
// which only level generation advances. Swapping them around level.create() makes floor N depend
// only on the seed and on floors 1..N-1's generation, never on how the player played.
final class LevelGenState {

	private static final String GENERATOR = "generator";
	private static final String LIMITED_DROPS = "limited_drops";
	private static final String ROOMS = "rooms";

	//fixed seed for Generator.restoreFromBundle: it calls fullReset(), which draws from the active
	// generator before the bundle overwrites every drawn value. Pushing a throw-away generator keeps
	// that from consuming (and shifting) whatever stream is active.
	private static final long RESTORE_SCRATCH_SEED = 0x11F0_C0DEL;

	private LevelGenState() {}

	//the current values of every system above
	static Bundle capture() {
		Bundle state = new Bundle();

		Bundle generator = new Bundle();
		Generator.storeInBundle( generator );
		state.put( GENERATOR, generator );

		Bundle drops = new Bundle();
		Dungeon.LimitedDrops.store( drops );
		state.put( LIMITED_DROPS, drops );

		Bundle rooms = new Bundle();
		SpecialRoom.storeRoomsInBundle( rooms );
		SecretRoom.storeRoomsInBundle( rooms );
		state.put( ROOMS, rooms );

		//a deep copy: put(Bundle) shares the underlying JSON, so later edits can't alias
		return copy( state );
	}

	//makes every system above hold exactly the values in the snapshot
	static void apply( Bundle state ) {
		Random.pushGenerator( RESTORE_SCRATCH_SEED );
		try {
			Generator.restoreFromBundle( state.getBundle( GENERATOR ) );
		} finally {
			Random.popGenerator();
		}
		Dungeon.LimitedDrops.restore( state.getBundle( LIMITED_DROPS ) );
		Bundle rooms = state.getBundle( ROOMS );
		SpecialRoom.restoreRoomsFromBundle( rooms );
		SecretRoom.restoreRoomsFromBundle( rooms );
	}

	//gen -> runtime: what level gen spent must also be spent for gameplay, as in vanilla (e.g. a
	// strength potion, a lore page or a bag placed on a floor). Runtime -> gen never happens.
	//LimitedDrops: add the counters level gen advanced during this floor.
	static void mergeLimitedDrops( Bundle runtime, Bundle genBefore, Bundle genAfter ) {
		Bundle drops = runtime.getBundle( LIMITED_DROPS );
		Bundle before = genBefore.getBundle( LIMITED_DROPS );
		Bundle after = genAfter.getBundle( LIMITED_DROPS );
		for (Dungeon.LimitedDrops lim : Dungeon.LimitedDrops.values()) {
			int delta = after.getInt( lim.name() ) - before.getInt( lim.name() );
			if (delta != 0) drops.put( lim.name(), drops.getInt( lim.name() ) + delta );
		}
	}

	//Artifacts are unique per run: one that level gen placed must not drop again during gameplay.
	// (The other decks just refill, so they stay independent.)
	static void mergeArtifacts( Bundle runtime, Bundle genBefore, Bundle genAfter ) {
		String key = Generator.Category.ARTIFACT.name().toLowerCase() + "_probs";
		Bundle gen = runtime.getBundle( GENERATOR );
		float[] probs = gen.getFloatArray( key );
		float[] before = genBefore.getBundle( GENERATOR ).getFloatArray( key );
		float[] after = genAfter.getBundle( GENERATOR ).getFloatArray( key );
		if (probs == null || before == null || after == null) return;
		boolean changed = false;
		for (int i = 0; i < probs.length && i < before.length && i < after.length; i++) {
			if (after[i] < before[i] && probs[i] > 0) {
				probs[i] = 0;
				changed = true;
			}
		}
		if (changed) gen.put( key, probs );
	}

	static Bundle copy( Bundle bundle ) {
		try {
			return Bundle.read( new ByteArrayInputStream( bundle.toString().getBytes( StandardCharsets.UTF_8 ) ) );
		} catch (IOException e) {
			throw new IllegalStateException( e );
		}
	}
}
