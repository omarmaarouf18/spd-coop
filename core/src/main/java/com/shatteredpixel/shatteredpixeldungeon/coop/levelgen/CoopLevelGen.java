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
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.CoopCell;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.downed.RecallLayout;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.watabou.utils.Bundle;
import com.watabou.utils.Random;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

//Co-op level generation (D-019, DET-01): floor N depends only on the seed, depth and branch,
// never on how either player played floors 1..N-1 or which hero they picked.
// - Run state that both level gen and gameplay touch (LevelGenState) is kept twice in co-op runs:
//   level gen runs on its own copy, which only level gen advances; the runtime copy is put back
//   afterwards, plus what gen spent (LimitedDrops counters, unique artifacts).
// - Quest branch floors (mine, vault) are entered whenever each player likes, so they can't share
//   one chain with the main floors: when a main floor with a branch entrance is generated, the
//   branch floor gets its own copy of the level-gen state (a fork), and the main chain never sees
//   what the branch consumes.
// - Level gen sees a neutral hero (fixed class, no items/talents/trinkets/buffs).
// - Upstream HashMap iterations and the JDK-random Collections.shuffle calls in level gen are made
//   order-stable / seeded through the helpers below (one-line // COOP: hooks).
// - Co-op balance (CoopBalance), The Cell's Binding Trap (CoopCell) and item network ids
//   (CoopItemIds) are applied here.
//Everything is a no-op outside co-op runs: vanilla level gen is unchanged.
public final class CoopLevelGen {

	//keys of the level-gen bundle saved in the CoopRun
	private static final String STATE = "state";
	private static final String ITEM_IDS = "item_ids";
	private static final String FORK = "fork_";

	//the class the neutral level-gen hero has. Only VaultMirror's reward reads it (DET-01 worklog).
	static final HeroClass NEUTRAL_CLASS = HeroClass.WARRIOR;

	//tests only: false runs co-op floors the pre-DET-01 way (the control that proves the
	// determinism tests can see divergence)
	static boolean isolation = true;

	private CoopLevelGen() {}

	//generates a co-op floor. levelGen is the run's saved level-gen bundle (null on the first
	// floor); create runs level.create() (with the neutral profile). Returns the new bundle to save.
	public static Bundle generate( Level level, Bundle levelGen, Runnable create ) {
		if (!isolation) {
			create.run();
			return levelGen;
		}

		Bundle result = levelGen == null ? new Bundle() : LevelGenState.copy( levelGen );
		int depth = Dungeon.depth;
		int branch = Dungeon.branch;
		//main floors advance the main chain; a branch floor starts from the fork taken when its
		// entrance was generated (or from the main chain, for a save from before forks existed)
		// and never writes back into the main chain
		String chain = branch == 0 ? STATE : FORK + floorKey( depth, branch );
		String source = result.contains( chain ) ? chain : result.contains( STATE ) ? STATE : null;
		Bundle runtime = LevelGenState.capture();
		Hero player = Dungeon.hero;
		CoopBalance.apply();
		try {
			Bundle genBefore;
			if (source != null) {
				genBefore = result.getBundle( source );
				LevelGenState.apply( genBefore );
			} else {
				//first co-op floor: right after Dungeon.init, so runtime == the run's starting state
				CoopBalance.dealCoopDecks();
				genBefore = LevelGenState.capture();
			}

			Dungeon.hero = neutralHero();
			create.run();
			Dungeon.hero = player;

			Bundle genAfter = LevelGenState.capture();
			result.put( chain, genAfter );
			if (branch == 0) {
				for (LevelTransition transition : level.transitions) {
					if (transition.type == LevelTransition.Type.BRANCH_EXIT) {
						String fork = FORK + floorKey( transition.destDepth, transition.destBranch );
						if (!result.contains( fork )) result.put( fork, genAfter );
					}
				}
			}
			LevelGenState.mergeLimitedDrops( runtime, genBefore, genAfter );
			LevelGenState.mergeArtifacts( runtime, genBefore, genAfter );
		} finally {
			Dungeon.hero = player;
			CoopBalance.undo();
			LevelGenState.apply( runtime );
		}

		CoopBalance.scaleMobs( level );
		//The Cell's hidden Binding Trap (MEC-01): its own seeded stream, after the floor is built
		CoopCell.placeTrap( level );
		//Scrolls of Recall (MEC-02): own seeded streams too, before the item ids are numbered
		RecallLayout.place( level );

		Bundle tables = result.getBundle( ITEM_IDS );
		if (!result.contains( ITEM_IDS )) tables = new Bundle();
		tables.put( floorKey( depth, branch ), CoopItemIds.assign( level, depth, branch ) );
		result.put( ITEM_IDS, tables );
		return result;
	}

	//the itemNetId table of a generated floor (see CoopItemIds), or null
	public static String[] itemIdTable( Bundle levelGen, int depth, int branch ) {
		if (levelGen == null || !levelGen.contains( ITEM_IDS )) return null;
		Bundle tables = levelGen.getBundle( ITEM_IDS );
		String key = floorKey( depth, branch );
		return tables.contains( key ) ? tables.getStringArray( key ) : null;
	}

	private static String floorKey( int depth, int branch ) {
		return depth + "_" + branch;
	}

	private static Hero neutralHero() {
		Hero hero = new Hero();
		hero.heroClass = NEUTRAL_CLASS;
		return hero;
	}

	// ---- upstream hooks (no-ops / the exact upstream call outside co-op runs) ----

	//SecretLaboratoryRoom, SecretLibraryRoom, ShopRoom.ChooseBag: upstream draws from / ties on a
	// HashMap keyed by classes or fresh items, whose order follows identity hashes (differs
	// between devices). In co-op the same entries come back in a LinkedHashMap sorted by class name.
	public static <K, V> HashMap<K, V> stable( HashMap<K, V> map ) {
		if (!Coop.isCoopRun()) return map;
		ArrayList<K> keys = new ArrayList<>( map.keySet() );
		keys.sort( Comparator.comparing( CoopLevelGen::orderKey ) );
		LinkedHashMap<K, V> sorted = new LinkedHashMap<>();
		for (K key : keys) sorted.put( key, map.get( key ) );
		return sorted;
	}

	//VaultLevel.setupConsumables: upstream shuffles with the JDK's own unseeded Random; co-op
	// shuffles with the seeded level-gen generator
	public static void shuffle( List<?> list ) {
		if (Coop.isCoopRun()) {
			Random.shuffle( list );
		} else {
			Collections.shuffle( list );
		}
	}

	//VaultLevel.T2SolveItems/T3SolveItems are shuffled in place and never reset, so their order
	// depends on what this process generated before. Co-op starts every shuffle from one order.
	public static void stableOrder( Object[] array ) {
		if (Coop.isCoopRun()) Arrays.sort( array, Comparator.comparing( CoopLevelGen::orderKey ) );
	}

	private static String orderKey( Object o ) {
		return o instanceof Class ? ((Class<?>) o).getName() : o.getClass().getName();
	}
}
