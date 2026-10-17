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
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Game;
import com.watabou.noosa.PointerArea;
import com.watabou.utils.PointF;

//ping input on cells: hold a cell for HOLD_TIME (touch or mouse), or middle-click it (desktop),
// to open the ping picker.
//GameScene adds this right after its CellSelector, so pointer events reach it after the HUD
// (toolbar, status pane, windows take theirs first) and before the CellSelector. It never takes
// a normal click: a hold only resets the CellSelector's press (so letting go doesn't also walk
// there), and only the middle button is consumed.
public class PingInput extends PointerArea {

	public static final float HOLD_TIME = 0.5f;

	private final CellSelector selector;
	private final float dragThreshold;

	private PointerEvent held;
	private float heldTime;
	private PointerEvent middle;

	public PingInput( CellSelector selector ) {
		super( selector.target );
		this.selector = selector;
		this.camera = selector.camera;
		dragThreshold = PixelScene.defaultZoom * DungeonTilemap.SIZE / 2;
	}

	@Override
	public boolean onSignal( PointerEvent event ) {
		if (event == null) {
			//a drag tick for the pressed pointer
			if (held != null && PointF.distance( held.current, held.start ) > dragThreshold) held = null;
			return false;
		}
		if (!isActive() || event.type == PointerEvent.Type.HOVER) return false;
		boolean hit = target.overlapsScreenPoint( (int) event.current.x, (int) event.current.y );

		if (event.type == PointerEvent.Type.DOWN) {
			if (!hit) return false;
			if (event.button == PointerEvent.MIDDLE) {
				middle = event;
				return true;
			}
			//a second finger means a pinch, not a hold
			held = held == null ? event : null;
			heldTime = 0;
			return false;
		}

		//UP or CANCEL
		if (event == middle) {
			middle = null;
			if (event.type == PointerEvent.Type.UP && hit) open( event );
			return true;
		}
		if (event == held) held = null;
		return false;
	}

	@Override
	public void update() {
		super.update();
		if (held == null) return;
		heldTime += Game.elapsed;
		if (heldTime >= HOLD_TIME) {
			PointerEvent e = held;
			held = null;
			//the CellSelector forgets this press: releasing it won't move the hero
			selector.reset();
			open( e );
		}
	}

	private void open( PointerEvent e ) {
		int cell = ((DungeonTilemap) target).screenToTile( (int) e.current.x, (int) e.current.y, true );
		openAt( cell );
	}

	//also used by the desktop hotkey (the hovered cell)
	public static void openAt( int cell ) {
		if (Dungeon.level == null || cell < 0 || cell >= Dungeon.level.length()) return;
		//only cells we know: a ping on darkness says nothing
		if (!Dungeon.level.visited[cell] && !Dungeon.level.mapped[cell]) return;
		if (GameScene.showingWindow()) return;
		GameScene.show( new WndPing( cell ) );
	}
}
