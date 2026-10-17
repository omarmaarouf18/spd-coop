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

package com.shatteredpixel.shatteredpixeldungeon.coop.ui;

import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.Marks;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Image;

//the ping picker: Here / Danger / Item / Go on one cell (game-design §3)
public class WndPing extends WndOptions {

	private static final Marks.Kind[] KINDS = Marks.Kind.values();

	private final int cell;

	public WndPing( int cell ) {
		super( Messages.get( WndPing.class, "title" ), Messages.get( WndPing.class, "body" ), labels() );
		this.cell = cell;
	}

	private static String[] labels() {
		String[] out = new String[KINDS.length];
		for (int i = 0; i < KINDS.length; i++) out[i] = CoopGhosts.kindName( KINDS[i] );
		return out;
	}

	@Override
	protected boolean hasIcon( int index ) {
		return true;
	}

	@Override
	protected Image getIcon( int index ) {
		Image icon = MarkLayer.icon( KINDS[index] );
		icon.hardlight( MarkLayer.color( KINDS[index] ) );
		return icon;
	}

	@Override
	protected void onSelect( int index ) {
		if (!CoopGhosts.ping( cell, KINDS[index] )) {
			GLog.w( Messages.get( WndPing.class, "too_fast" ) );
		}
	}
}
