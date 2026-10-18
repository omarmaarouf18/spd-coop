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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.CoopItems;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TimekeepersHourglass;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.MagicalHolster;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.PotionBandolier;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.ScrollHolder;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.VelvetPouch;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.ShopRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.ImpShopRoom;
import com.watabou.utils.Point;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

//Shop stock that vanilla picks from the hero (ShopRoom.generateItems), per player:
// - Timekeeper's Hourglass sand bags: vanilla's formula on this player's hourglass, added as
//   extra stock (ShopRoom reserves 4 free cells for them in every shop),
// - the bag: vanilla offers the bag that would hold most of the backpack (every hero starts with
//   the velvet pouch, so it's never sold). Co-op level gen offers the other 3 in a fixed order; in
//   this world the shop's bag becomes the one this player's backpack wants most among the bags
//   their shops haven't offered yet (OverlayState.bags), so each player still sees each bag once.
//   Without a preference (a tie with the shop's bag) the shop's bag stays.
//Shop stock is per world anyway (ITM-01: FOR_SALE is never claimed); added/swapped items are LOCAL.
final class PlayerShop {

	private PlayerShop() {}

	//a shop placed by level gen (depths 6, 11, 16): first entry of the floor
	static void onFloor( Level level, Hero hero, OverlayState s ) {
		if (!(level instanceof RegularLevel)) return;
		for (Room room : ((RegularLevel) level).rooms()) {
			//the Imp's shop isn't placed yet: CoopOverlay.onImpShop
			if (!(room instanceof ShopRoom) || room instanceof ImpShopRoom) continue;

			ArrayList<Heap> stock = new ArrayList<>();
			for (Heap heap : level.heaps.valueList()) {
				if (heap.type == Heap.Type.FOR_SALE && room.inside( level.cellToPoint( heap.pos ) )) stock.add( heap );
			}
			stock.sort( Comparator.comparingInt( h -> h.pos ) );
			for (Heap heap : stock) {
				if (swapBag( heap.items, hero.belongings, s )) break;
			}

			for (int i = sandBags( hero ); i > 0; i--) {
				int cell = freeCell( level, room, hero );
				if (cell == -1) break;
				Item bag = new TimekeepersHourglass.sandBag();
				CoopItems.markLocal( bag );
				level.drop( bag, cell ).type = Heap.Type.FOR_SALE;
			}
		}
	}

	//the Imp's shop: its stock list, right before it's placed
	static void inStock( List<Item> stock, Hero hero, OverlayState s ) {
		swapBag( stock, hero.belongings, s );
		for (int i = sandBags( hero ); i > 0; i--) {
			Item bag = new TimekeepersHourglass.sandBag();
			CoopItems.markLocal( bag );
			stock.add( bag );
		}
	}

	//replaces the shop's bag in items (if there is one) by this player's choice. True if items had one.
	private static boolean swapBag( List<Item> items, Belongings pack, OverlayState s ) {
		for (int i = 0; i < items.size(); i++) {
			Item shared = items.get( i );
			if (!isShopBag( shared )) continue;
			Bag mine = chooseBag( pack, s.bags, shared.getClass() );
			if (mine == null) return true;
			s.bags.add( mine.getClass().getName() );
			if (mine.getClass() != shared.getClass()) {
				CoopItems.markLocal( mine );
				items.set( i, mine );
			}
			return true;
		}
		return false;
	}

	private static boolean isShopBag( Item item ) {
		return item instanceof VelvetPouch || item instanceof ScrollHolder
				|| item instanceof PotionBandolier || item instanceof MagicalHolster;
	}

	//ShopRoom.ChooseBag on this player's backpack, minus the bags already offered to them (and the
	// pouch: HeroClass.initHero drops it for every hero). A tie keeps the shop's bag (shared), else
	// goes to the first bag in class-name order (as in co-op level gen, CoopLevelGen.stable).
	static Bag chooseBag( Belongings pack, Set<String> offered, Class<?> shared ) {
		Bag[] bags = { new MagicalHolster(), new PotionBandolier(), new ScrollHolder() };
		Bag best = null;
		int bestCount = 0;
		for (Bag bag : bags) {
			if (offered.contains( bag.getClass().getName() )) continue;
			int count = 0;
			for (Item item : pack.backpack.items) {
				if (bag.canHold( item )) count++;
			}
			if (best == null || count > bestCount
					|| (count == bestCount && bag.getClass() == shared && best.getClass() != shared)) {
				best = bag;
				bestCount = count;
			}
		}
		return best;
	}

	//vanilla's share of the remaining sand bags per shop; counted on the hourglass at once
	static int sandBags( Hero hero ) {
		TimekeepersHourglass hourglass = hero.belongings.getItem( TimekeepersHourglass.class );
		if (hourglass == null || !hourglass.isIdentified() || hourglass.cursed) return 0;
		float share;
		switch (Dungeon.depth) {
			case 6: share = 0.20f; break;
			case 11: share = 0.25f; break;
			case 16: share = 0.50f; break;
			case 20: case 21: share = 0.80f; break;
			default: return 0;
		}
		int bags = (int) Math.ceil( (5 - hourglass.sandBags) * share );
		hourglass.sandBags += bags;
		return bags;
	}

	private static int freeCell( Level level, Room room, Hero hero ) {
		for (Point p : room.getPoints()) {
			int cell = level.pointToCell( p );
			if (level.map[cell] == Terrain.EMPTY_SP && level.heaps.get( cell ) == null
					&& level.findMob( cell ) == null && Actor.findChar( cell ) == null && cell != hero.pos) {
				return cell;
			}
		}
		return -1;
	}
}
