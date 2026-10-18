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
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.AllyState;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.GhostSprite;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.HeroSnapshot;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.PointF;

//the ally compass around our portrait (game-design §2.1): a second, tinted arrow toward the ghost
// on the same floor; "↓N" / "↑N" (floors away) when the ally is on another floor.
public class AllyCompass extends Component {

	private static final float RADIUS = 12;

	private final Image avatar;
	private Image arrow;
	private RenderedTextBlock floors;
	private String floorsText = "";

	public AllyCompass( Image avatar ) {
		super();
		this.avatar = avatar;
	}

	@Override
	protected void createChildren() {
		arrow = Icons.get( Icons.COMPASS );
		arrow.origin.set( arrow.width / 2f, RADIUS );
		arrow.hardlight( GhostSprite.TINT );
		arrow.visible = false;
		add( arrow );

		floors = PixelScene.renderTextBlock( 6 );
		floors.hardlight( GhostSprite.TINT );
		floors.visible = false;
		add( floors );
	}

	//"↓2", "↑1", "≠" (other branch), or "" (same floor / unknown): what the compass shows
	public static String floorsText( AllyState ally, int myDepth, int myBranch ) {
		switch (ally.floor( myDepth, myBranch )) {
			case BELOW: return Messages.get( AllyCompass.class, "below", ally.floorsAway( myDepth ) );
			case ABOVE: return Messages.get( AllyCompass.class, "above", ally.floorsAway( myDepth ) );
			case OTHER_BRANCH: return Messages.get( AllyCompass.class, "other_branch" );
			default: return "";
		}
	}

	@Override
	public void update() {
		super.update();
		AllyState ally = CoopGhosts.ally();
		HeroSnapshot g = ally.ghost();
		float cx = avatar.x + avatar.width / 2f;
		float cy = avatar.y + avatar.height / 2f;

		boolean same = ally.floor( Dungeon.depth, Dungeon.branch ) == AllyState.Floor.SAME && g != null
				&& g.sameFloor( Dungeon.depth, Dungeon.branch ) && Dungeon.level != null
				&& g.pos >= 0 && g.pos < Dungeon.level.length();
		arrow.visible = same;
		if (same) {
			arrow.x = cx - arrow.origin.x;
			arrow.y = cy - arrow.origin.y;
			PointF cell = DungeonTilemap.tileCenterToWorld( g.pos );
			PointF center = Camera.main.center().offset( Camera.main.scroll );
			arrow.angle = (float) Math.atan2( cell.x - center.x, center.y - cell.y ) * 180f / (float) Math.PI;
			arrow.alpha( ally.online() ? 1f : 0.4f );
		}

		String text = same ? "" : floorsText( ally, Dungeon.depth, Dungeon.branch );
		if (!text.equals( floorsText )) {
			floorsText = text;
			floors.text( text );
		}
		floors.visible = !text.isEmpty();
		if (floors.visible) {
			floors.setPos( avatar.x + avatar.width - floors.width() + 2, avatar.y - 1 );
			PixelScene.align( floors );
			floors.alpha( ally.online() ? 1f : 0.5f );
		}
	}

	public String shownText() {
		return floorsText;
	}
}
