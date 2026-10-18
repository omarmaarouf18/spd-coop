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

package com.shatteredpixel.shatteredpixeldungeon.coop.ghost;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;

//the ally ghost, for later co-op tasks (no gameplay in GHO-01). GL thread.
//- cell(), isVisible(): where it is in our world and whether we draw it;
//- setOpaque(): fully opaque while both players share a Twin Switches room (game-design §6A.5);
//- isTarget(cell) / targetCell(): a throw at the ghost's cell (Healing revive, §5.3, MEC-02).
//The ghost is not an Actor and is not in Dungeon.level.mobs: mobs can't see it, it never blocks a
// cell, and nothing here changes the world.
public final class GhostAlly {

	private GhostAlly() {}

	//the ally's cell on our floor, or -1 when it is elsewhere or unknown
	public static int cell() {
		HeroSnapshot g = CoopGhosts.ally().ghost();
		if (g == null || Dungeon.level == null || !g.sameFloor( Dungeon.depth, Dungeon.branch )) return -1;
		return g.pos >= 0 && g.pos < Dungeon.level.length() ? g.pos : -1;
	}

	//drawn now: same floor and on a cell we have seen
	public static boolean isVisible() {
		return GhostLayer.shouldDraw( CoopGhosts.ally().ghost(), Dungeon.level, Dungeon.depth, Dungeon.branch );
	}

	public static boolean isOnline() {
		return CoopGhosts.ally().online();
	}

	public static void setOpaque( boolean opaque ) {
		CoopGhosts.opaque( opaque );
	}

	public static boolean isOpaque() {
		return CoopGhosts.opaque();
	}

	//a target the player can aim at: the ghost's cell while it is visible, else -1
	public static int targetCell() {
		return isVisible() ? cell() : -1;
	}

	//whether a thrown item landing on this cell hits the ghost
	public static boolean isTarget( int cell ) {
		return cell >= 0 && cell == targetCell();
	}

	//for effects aimed at the ghost (null when no GameScene is up)
	public static CharSprite sprite() {
		GhostLayer layer = GhostLayer.current();
		return layer == null ? null : layer.sprite();
	}
}
