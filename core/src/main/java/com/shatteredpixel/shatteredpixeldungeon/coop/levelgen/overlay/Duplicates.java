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

package com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.overlay;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.CoopItems;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.Artifact;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

//Artifacts are unique in vanilla. Co-op level gen keeps its own Generator (DET-01), so it never
// learns about an artifact this player got at runtime (a mob drop, transmutation, the starting
// cloak) and can place it again. On this player's first entry such a duplicate turns, in this
// world only, into an artifact from this player's runtime deck (which already excludes what they
// were given or saw generated), or a ring once that deck is empty, like Generator does.
//The converted item keeps the floor slot's itemNetId: the slot stays one shared item (first
// claim wins, the other world's copy vanishes as for any claimed item), only its content is
// per world. So nothing new goes over the network.
final class Duplicates {

	//draws until one isn't owned; the runtime deck removes each draw, so this ends quickly
	private static final int TRIES = 20;

	private Duplicates() {}

	static void onFloor( Level level, Hero hero ) {
		Set<Class<?>> owned = owned( hero );
		if (owned.isEmpty()) return;
		CoopOverlay.seeded( CoopOverlay.DUPLICATES, () -> {
			ArrayList<Heap> heaps = new ArrayList<>( level.heaps.valueList() );
			heaps.sort( Comparator.comparingInt( h -> h.pos ) );
			for (Heap heap : heaps) convert( heap.items, owned );
			ArrayList<Mob> mobs = new ArrayList<>( level.mobs );
			mobs.sort( Comparator.comparingInt( m -> m.pos ) );
			for (Mob mob : mobs) {
				if (mob instanceof Mimic && ((Mimic) mob).items != null) convert( ((Mimic) mob).items, owned );
			}
		} );
	}

	static void inStock( List<Item> stock, Hero hero ) {
		Set<Class<?>> owned = owned( hero );
		if (owned.isEmpty()) return;
		CoopOverlay.seeded( CoopOverlay.DUPLICATES, () -> convert( stock, owned ) );
	}

	private static Set<Class<?>> owned( Hero hero ) {
		Set<Class<?>> owned = new LinkedHashSet<>();
		for (Item item : hero.belongings) {
			if (item instanceof Artifact) owned.add( item.getClass() );
		}
		return owned;
	}

	private static void convert( List<Item> items, Set<Class<?>> owned ) {
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get( i );
			if (!(item instanceof Artifact) || !owned.contains( item.getClass() )) continue;
			Item other = draw( owned );
			if (other == null) continue;
			keepId( item, other );
			items.set( i, other );
		}
	}

	private static Item draw( Set<Class<?>> owned ) {
		for (int i = 0; i < TRIES; i++) {
			Item item = Generator.random( Generator.Category.ARTIFACT );
			if (item != null && !owned.contains( item.getClass() )) return item;
		}
		return null;
	}

	//the slot's id (shared floor item) or LOCAL (overlay/shop item) moves to the new instance
	private static void keepId( Item from, Item to ) {
		String id = CoopItems.idOf( from );
		if (id == null) return;
		if (CoopItems.LOCAL.equals( id )) {
			CoopItems.markLocal( to );
		} else {
			Bundle b = new Bundle();
			CoopItems.storeId( from, b );
			CoopItems.restoreId( to, b );
		}
	}
}
