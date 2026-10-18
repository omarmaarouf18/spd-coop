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

package com.shatteredpixel.shatteredpixeldungeon.coop.items;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.downed.CoopDowned;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;

import java.util.ArrayList;

//Scroll of Recall (MEC-02, game-design §5.2): a co-op item. Read while the ally is Downed (any
// floor) to revive them: 25% HP, at their region start (in place inside The Cell). The server
// decides (USE_RECALL → REVIVE); CoopDowned does the work. Never usable on yourself, and with no
// Downed ally it stays in the pack. Placed by co-op level gen (one per region, plus shop stock),
// so it is shared floor loot like any generated item (ITM-01: first claim wins).
public class ScrollOfRecall extends Item {

	static {
		CoopDowned.install();
	}

	public static final String AC_READ = "READ";

	{
		image = ItemSpriteSheet.SCROLL_BERKANAN;
		stackable = true;
		defaultAction = AC_READ;
	}

	@Override
	public ArrayList<String> actions( Hero hero ) {
		ArrayList<String> actions = super.actions( hero );
		actions.add( AC_READ );
		return actions;
	}

	@Override
	public void execute( Hero hero, String action ) {
		if (AC_READ.equals( action )) {
			CoopDowned.readRecall( hero, this );
		} else {
			super.execute( hero, action );
		}
	}

	@Override
	public boolean isUpgradable() {
		return false;
	}

	@Override
	public boolean isIdentified() {
		return true;
	}

	@Override
	public int value() {
		return 60 * quantity;
	}
}
