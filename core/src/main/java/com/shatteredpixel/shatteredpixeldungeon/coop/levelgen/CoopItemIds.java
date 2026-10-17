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
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.ArmoredStatue;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Statue;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

//Stable network ids for the items co-op level gen places (D-021, ITM-01 shared loot):
// itemNetId = "depth:branch:genIndex". genIndex numbers the floor's generated items in a fixed
// order that only depends on the generated floor, so both worlds assign the same ids:
//   1. heaps by cell, each heap's items in heap order (floor loot, chests, shops, tombs, ...);
//   2. then mobs by (cell, class name) with the items they carry (Mimic loot, Statue weapon,
//      ArmoredStatue armor).
//The ids live in memory per Item instance, and in the co-op save as one table per floor
// ("genIndex|kind|cell|class"). After a save is loaded, rebind() re-attaches the ids of heap items
// still lying where level gen put them (ITM-01 wires the call). Mob-carried items can't be
// rebound from the table alone: see the DET-01 worklog (an Item bundle hook would make every id
// travel with its item).
//Thrown weapons also get a set id derived from their itemNetId: upstream draws MissileWeapon.setID
// (which stacks merge on) from SecureRandom, so it would differ between the two worlds.
public final class CoopItemIds {

	static final String HEAP = "H";
	static final String MOB = "M";

	private static final Map<Item, String> ids = Collections.synchronizedMap( new WeakHashMap<>() );

	private CoopItemIds() {}

	public static String netId( int depth, int branch, int genIndex ) {
		return depth + ":" + branch + ":" + genIndex;
	}

	//the item's id, or null if co-op level gen didn't place it (mob drops, runtime loot, ...)
	public static String idOf( Item item ) {
		return item == null ? null : ids.get( item );
	}

	//numbers every generated item of a fresh floor, binds the ids and returns the floor's table
	static String[] assign( Level level, int depth, int branch ) {
		ArrayList<String> table = new ArrayList<>();
		int index = 0;

		for (Heap heap : heapsByCell( level )) {
			for (Item item : heap.items) {
				bind( item, depth, branch, index );
				table.add( entry( index++, HEAP, heap.pos, item ) );
			}
		}

		ArrayList<Mob> mobs = new ArrayList<>( level.mobs );
		mobs.sort( (a, b) -> a.pos != b.pos ? Integer.compare( a.pos, b.pos )
				: a.getClass().getName().compareTo( b.getClass().getName() ) );
		for (Mob mob : mobs) {
			for (Item item : carried( mob )) {
				bind( item, depth, branch, index );
				table.add( entry( index++, MOB, mob.pos, item ) );
			}
		}
		return table.toArray( new String[0] );
	}

	//re-attaches the ids of the heap items in the table that are still on their generated cell,
	// in their generated relative order; returns how many were bound
	public static int rebind( Level level, String[] table, int depth, int branch ) {
		if (table == null) return 0;
		HashMap<String, ArrayDeque<Integer>> expected = new HashMap<>();
		for (String row : table) {
			String[] f = row.split( "\\|" );
			if (!f[1].equals( HEAP )) continue;
			String key = f[2] + "|" + f[3];
			ArrayDeque<Integer> queue = expected.get( key );
			if (queue == null) expected.put( key, queue = new ArrayDeque<>() );
			queue.add( Integer.parseInt( f[0] ) );
		}
		int bound = 0;
		for (Heap heap : heapsByCell( level )) {
			for (Item item : heap.items) {
				ArrayDeque<Integer> queue = expected.get( heap.pos + "|" + item.getClass().getName() );
				if (queue == null || queue.isEmpty()) continue;
				ids.put( item, netId( depth, branch, queue.poll() ) );
				bound++;
			}
		}
		return bound;
	}

	private static void bind( Item item, int depth, int branch, int index ) {
		ids.put( item, netId( depth, branch, index ) );
		//darts all share set id 0 upstream (they always stack); keep that
		if (item instanceof MissileWeapon && ((MissileWeapon) item).setID != 0) {
			((MissileWeapon) item).setID = setId( Dungeon.seed, depth, branch, index );
		}
	}

	//a well-mixed 64-bit value from the run seed and the item's net id (SplitMix64 finalizer)
	static long setId( long seed, int depth, int branch, int index ) {
		long z = seed * 0x9E3779B97F4A7C15L + ((long) depth << 40 ^ (long) branch << 32 ^ index);
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private static String entry( int index, String kind, int cell, Item item ) {
		return index + "|" + kind + "|" + cell + "|" + item.getClass().getName();
	}

	private static List<Heap> heapsByCell( Level level ) {
		ArrayList<Heap> heaps = new ArrayList<>( level.heaps.valueList() );
		heaps.sort( (a, b) -> Integer.compare( a.pos, b.pos ) );
		return heaps;
	}

	private static List<Item> carried( Mob mob ) {
		ArrayList<Item> items = new ArrayList<>();
		if (mob instanceof Mimic && ((Mimic) mob).items != null) {
			items.addAll( ((Mimic) mob).items );
		}
		if (mob instanceof Statue && ((Statue) mob).weapon() != null) {
			items.add( ((Statue) mob).weapon() );
		}
		if (mob instanceof ArmoredStatue) {
			Item armor = armorOf( (ArmoredStatue) mob );
			if (armor != null) items.add( armor );
		}
		return items;
	}

	//ArmoredStatue has no getter for its (protected) armor; read-only reflection keeps it hook-free
	private static Item armorOf( ArmoredStatue statue ) {
		try {
			Field field = ArmoredStatue.class.getDeclaredField( "armor" );
			field.setAccessible( true );
			return (Item) field.get( statue );
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
