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

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.EbonyMimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Ghost;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultMirror;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.CoopItems;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.DriedRose;
import com.shatteredpixel.shatteredpixeldungeon.items.food.SupplyRation;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.CrackedSpyglass;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.MimicTooth;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.TrapMechanism;
import com.shatteredpixel.shatteredpixeldungeon.levels.LastShopLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.MiningLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.VaultLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.ShopRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.StandardRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.BlazingTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.BurningTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.ChillingTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.DisintegrationTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.ExplosiveTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.FrostTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.PitfallTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.watabou.utils.Random;

import java.util.ArrayList;
import java.util.Comparator;

//Floor loot that vanilla level gen derives from the hero, per player (RegularLevel.createItems,
// VaultTokensRoom), re-run at this player's first entry with vanilla's rules and this player's
// own generator. Everything spawned is LOCAL. Trinkets whose gen effect would change the shared
// floor itself (Mossy Clump / Trap Mechanism level feelings, Mimic Tooth turning chests into
// mimics, Exotic Crystals / Rat Skull / Parchment Scrap changing generated items) stay off in
// co-op: see game-design §6A.8.
final class PlayerLoot {

	private PlayerLoot() {}

	static void onFloor( Level level, Hero hero ) {
		vaultMirror( level, hero );
		if (!regularLoot( level )) return;
		RegularLevel regular = (RegularLevel) level;
		CoopOverlay.seeded( CoopOverlay.PETALS, () -> petals( regular, hero ) );
		CoopOverlay.seeded( CoopOverlay.RATIONS, () -> cachedRations( regular, hero ) );
		CoopOverlay.seeded( CoopOverlay.SPYGLASS, () -> spyglass( regular, hero ) );
		CoopOverlay.seeded( CoopOverlay.EBONY, () -> ebonyMimic( regular, hero ) );
		revealTraps( regular );
	}

	//the floors that run RegularLevel.createItems (the others override it without these drops)
	private static boolean regularLoot( Level level ) {
		return level instanceof RegularLevel && !(level instanceof LastShopLevel)
				&& !(level instanceof MiningLevel) && !(level instanceof VaultLevel);
	}

	//VaultTokensRoom: the mirror's reward is made for the hero's class. Level gen made the neutral
	// hero's; in this world it becomes this player's class reward.
	private static void vaultMirror( Level level, Hero hero ) {
		for (Mob mob : level.mobs) {
			if (!(mob instanceof VaultMirror) || ((VaultMirror) mob).reward == null) continue;
			VaultMirror mirror = (VaultMirror) mob;
			CoopOverlay.seeded( CoopOverlay.MIRROR, () -> mirror.createReward( hero.heroClass ) );
			CoopItems.markLocal( mirror.reward );
		}
	}

	//RegularLevel.createItems: "aim to drop 1 petal every 2 floors"
	private static void petals( RegularLevel level, Hero hero ) {
		DriedRose rose = hero.belongings.getItem( DriedRose.class );
		if (rose == null || !rose.isIdentified() || rose.cursed || !Ghost.Quest.completed()) return;
		int petalsNeeded = (int) Math.ceil( (float) ((Dungeon.depth / 2) - rose.droppedPetals) / 3 );
		for (int i = 1; i <= petalsNeeded; i++) {
			if (rose.droppedPetals >= 11) break;
			int cell = dropCell( level, hero, StandardRoom.class );
			if (cell == -1) break;
			drop( level, new DriedRose.Petal(), cell ).type = Heap.Type.HEAP;
			rose.droppedPetals++;
		}
	}

	//RegularLevel.createItems: a supply ration in a special room on floors 2/4/7
	private static void cachedRations( RegularLevel level, Hero hero ) {
		if (!hero.hasTalent( Talent.CACHED_RATIONS )) return;
		Talent.CachedRationsDropped dropped = Buff.affect( hero, Talent.CachedRationsDropped.class );
		int targetFloor = (int) (2 + dropped.count());
		if (dropped.count() > 4) targetFloor++;
		if (Dungeon.depth < targetFloor || dropped.count() >= 2 + 2 * hero.pointsInTalent( Talent.CACHED_RATIONS )) return;
		int cell;
		int tries = 100;
		boolean valid;
		do {
			cell = dropCell( level, hero, SpecialRoom.class );
			valid = cell != -1 && !(level.room( cell ) instanceof SecretRoom)
					&& !(level.room( cell ) instanceof ShopRoom)
					&& level.map[cell] != Terrain.EMPTY_SP
					&& level.map[cell] != Terrain.WATER
					&& level.map[cell] != Terrain.PEDESTAL;
		} while (tries-- > 0 && !valid);
		if (!valid) return;
		drop( level, new SupplyRation(), cell ).type = Heap.Type.CHEST;
		dropped.countUp( 2 );
	}

	//Cracked Spyglass: hidden extra loot
	private static void spyglass( RegularLevel level, Hero hero ) {
		int items = (int) (Random.Float() + CrackedSpyglass.extraLootChance());
		for (int i = 0; i < items; i++) {
			int cell = dropCell( level, hero, StandardRoom.class );
			if (cell == -1) break;
			drop( level, Generator.randomUsingDefaults(), cell ).hidden = true;
		}
	}

	//Mimic Tooth: an ebony mimic on a loose heap, the exit or a door
	private static void ebonyMimic( RegularLevel level, Hero hero ) {
		if (Random.Float() >= MimicTooth.ebonyMimicChance()) return;
		ArrayList<Integer> candidates = new ArrayList<>();
		if (Random.Int( 2 ) == 0) {
			ArrayList<Heap> heaps = new ArrayList<>( level.heaps.valueList() );
			heaps.sort( Comparator.comparingInt( h -> h.pos ) );
			for (Heap h : heaps) {
				if (h.type == Heap.Type.HEAP && !(level.room( h.pos ) instanceof SpecialRoom) && free( level, hero, h.pos )) {
					candidates.add( h.pos );
				}
			}
		}
		if (candidates.isEmpty()) {
			if (Random.Int( 5 ) == 0 && free( level, hero, level.exit() )) {
				candidates.add( level.exit() );
			} else {
				for (int i = 0; i < level.length(); i++) {
					if (level.map[i] == Terrain.DOOR && free( level, hero, i )) candidates.add( i );
				}
			}
		}
		if (candidates.isEmpty()) return;
		Mimic mimic = Mimic.spawnAt( Random.element( candidates ), EbonyMimic.class, false );
		for (Item item : mimic.items) CoopItems.markLocal( item );
		level.mobs.add( mimic );
		//the floor's actors are already initialized (Dungeon.switchLevel); the scene adds the sprite
		Actor.add( mimic );
	}

	//Trap Mechanism: the same share of the floor's hidden traps is revealed as vanilla's painter
	// would (every 1/chance-th trap, no RNG). Only upstream trap classes: never a co-op trap.
	private static void revealTraps( RegularLevel level ) {
		float chance = TrapMechanism.revealHiddenTrapChance();
		if (chance <= 0) return;
		ArrayList<Trap> traps = new ArrayList<>( level.traps.valueList() );
		traps.sort( Comparator.comparingInt( t -> t.pos ) );
		float inc = 0;
		for (Trap trap : traps) {
			if (trap.visible || !upstreamTrap( trap )) continue;
			inc += chance;
			if (inc >= 1) {
				Level.set( trap.pos, Terrain.discover( level.map[trap.pos] ), level );
				trap.reveal();
				inc--;
			}
		}
	}

	//a top-level class of levels.traps (what the painters place); not a co-op or quest trap
	private static boolean upstreamTrap( Trap trap ) {
		String name = trap.getClass().getName();
		String pkg = Trap.class.getName().substring( 0, Trap.class.getName().lastIndexOf( '.' ) + 1 );
		return name.startsWith( pkg ) && name.indexOf( '.', pkg.length() ) == -1 && name.indexOf( '$' ) == -1;
	}

	private static Heap drop( RegularLevel level, Item item, int cell ) {
		CoopItems.markLocal( item );
		if (level.map[cell] == Terrain.HIGH_GRASS || level.map[cell] == Terrain.FURROWED_GRASS) {
			Level.set( cell, Terrain.GRASS, level );
		}
		return level.drop( item, cell );
	}

	//RegularLevel.randomDropCell( roomType ), on this player's generator, without reshuffling the
	// level's room list, and never under a character
	private static int dropCell( RegularLevel level, Hero hero, Class<? extends Room> roomType ) {
		ArrayList<Room> rooms = new ArrayList<>();
		for (Room room : level.rooms()) {
			if (roomType.isInstance( room )) rooms.add( room );
		}
		if (rooms.isEmpty()) return -1;
		Room entrance = level.room( level.entrance() );
		int tries = 100;
		while (tries-- > 0) {
			Room room = Random.element( rooms );
			if (room == entrance) continue;
			int pos = level.pointToCell( room.random() );
			if (level.passable[pos] && !level.solid[pos]
					&& pos != level.exit()
					&& level.heaps.get( pos ) == null
					&& room.canPlaceItem( level.cellToPoint( pos ), level )
					&& free( level, hero, pos )) {
				Trap t = level.traps.get( pos );
				if (t == null || !(t instanceof BurningTrap || t instanceof BlazingTrap
						|| t instanceof ChillingTrap || t instanceof FrostTrap
						|| t instanceof ExplosiveTrap || t instanceof DisintegrationTrap
						|| t instanceof PitfallTrap)) {
					return pos;
				}
			}
		}
		return -1;
	}

	private static boolean free( Level level, Hero hero, int pos ) {
		return pos != hero.pos && level.findMob( pos ) == null && Actor.findChar( pos ) == null;
	}
}
