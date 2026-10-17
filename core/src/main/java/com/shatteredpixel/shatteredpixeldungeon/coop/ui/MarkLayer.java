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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.Marks;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.utils.PointF;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

//ping markers on their cells, drawn over the fog (added after it) so a ping on a dim,
// remembered cell still reads. A marker only shows where it was placed; it reveals no terrain.
public class MarkLayer extends Group {

	private final Map<Marks.Mark, MarkSprite> sprites = new IdentityHashMap<>();

	@Override
	public void update() {
		long now = System.currentTimeMillis();
		List<Marks.Mark> active = CoopGhosts.marks().active( Dungeon.depth, Dungeon.branch, now );
		for (Iterator<Map.Entry<Marks.Mark, MarkSprite>> it = sprites.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Marks.Mark, MarkSprite> e = it.next();
			if (!active.contains( e.getKey() )) {
				e.getValue().killAndErase();
				it.remove();
			}
		}
		for (Marks.Mark m : active) {
			if (!sprites.containsKey( m ) && m.cell < Dungeon.level.length()) {
				MarkSprite s = new MarkSprite( m );
				sprites.put( m, s );
				add( s );
			}
		}
		super.update();
	}

	public static int color( Marks.Kind kind ) {
		switch (kind) {
			case DANGER: return 0xFF3333;
			case ITEM:   return 0xFFDD33;
			case GO:     return 0x33FF66;
			case HERE: default: return 0x66CCFF;
		}
	}

	public static Image icon( Marks.Kind kind ) {
		switch (kind) {
			case DANGER: return Icons.get( Icons.WARNING );
			case ITEM:   return Icons.get( Icons.BACKPACK );
			case GO:     return Icons.get( Icons.CHEVRON );
			case HERE: default: return Icons.get( Icons.TARGET );
		}
	}

	private static class MarkSprite extends Group {

		private final Marks.Mark mark;
		private final Image ring;
		private final Image symbol;
		private float time;

		MarkSprite( Marks.Mark mark ) {
			this.mark = mark;
			int color = color( mark.kind );
			PointF c = DungeonTilemap.tileCenterToWorld( mark.cell );

			ring = Icons.get( Icons.TARGET );
			ring.hardlight( color );
			ring.origin.set( ring.width / 2f, ring.height / 2f );
			ring.point( new PointF( c.x - ring.width / 2f, c.y - ring.height / 2f ) );
			add( ring );

			symbol = icon( mark.kind );
			symbol.hardlight( color );
			symbol.origin.set( symbol.width / 2f, symbol.height / 2f );
			symbol.point( new PointF( c.x - symbol.width / 2f, c.y - DungeonTilemap.SIZE - symbol.height / 2f ) );
			add( symbol );
		}

		@Override
		public void update() {
			super.update();
			time += Game.elapsed;
			float left = mark.remaining( System.currentTimeMillis() );
			float pulse = 1f + 0.15f * (float) Math.sin( time * 8f );
			ring.scale.set( pulse );
			ring.alpha( Math.min( 1f, left * 3f ) );
			symbol.alpha( Math.min( 1f, left * 3f ) );
		}
	}
}
