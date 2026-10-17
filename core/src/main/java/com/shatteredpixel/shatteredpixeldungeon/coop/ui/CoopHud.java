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
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.input.GameAction;
import com.watabou.input.KeyBindings;
import com.watabou.input.KeyEvent;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.utils.Point;
import com.watabou.utils.PointF;
import com.watabou.utils.Signal;

import java.util.ArrayList;
import java.util.List;

//the co-op layer of a GameScene on the UI camera (GHO-01):
//- drives CoopGhosts.tick() (idle resends, retries, reconnect detection) every frame;
//- an arrow at the screen edge for each ping that is off-screen;
//- a short toast for the ally's quick messages;
//- desktop keys (CoopActions, rebindable): T opens the quick messages, G pings the cell under the mouse.
public class CoopHud extends Group {

	private static final float EDGE = 10;

	private final CellSelector selector;
	private final List<Image> arrows = new ArrayList<>();
	private final RenderedTextBlock toast;
	private long toastShown;

	private final Signal.Listener<KeyEvent> keys = new Signal.Listener<KeyEvent>() {
		@Override
		public boolean onSignal( KeyEvent event ) {
			if (event.pressed || GameScene.showingWindow()) return false;
			GameAction action = KeyBindings.getActionForKey( event );
			if (action == CoopActions.QUICK_MESSAGE) {
				GameScene.show( new WndQuick() );
				return true;
			} else if (action == CoopActions.PING_CURSOR) {
				PointF hover = PointerEvent.currentHoverPos();
				PingInput.openAt( ((DungeonTilemap) selector.target).screenToTile( (int) hover.x, (int) hover.y, true ) );
				return true;
			}
			return false;
		}
	};

	public CoopHud( CellSelector selector ) {
		this.selector = selector;
		camera = PixelScene.uiCamera;
		toast = PixelScene.renderTextBlock( 8 );
		toast.hardlight( 0x99DDFF );
		toast.visible = false;
		add( toast );
		CoopActions.init();
		KeyEvent.addKeyListener( keys );
	}

	@Override
	public void update() {
		long now = System.currentTimeMillis();
		CoopGhosts.tick( now );
		updateArrows( now );
		updateToast( now );
		super.update();
	}

	private void updateArrows( long now ) {
		List<Marks.Mark> marks = CoopGhosts.marks().active( Dungeon.depth, Dungeon.branch, now );
		int used = 0;
		Camera ui = PixelScene.uiCamera;
		Camera main = Camera.main;
		for (Marks.Mark m : marks) {
			PointF world = DungeonTilemap.tileCenterToWorld( m.cell );
			Point screen = main.cameraToScreen( world.x, world.y );
			PointF p = ui.screenToCamera( screen.x, screen.y );
			if (p.x >= 0 && p.y >= 0 && p.x <= ui.width && p.y <= ui.height) continue;

			Image arrow;
			if (used < arrows.size()) {
				arrow = arrows.get( used );
			} else {
				arrow = Icons.get( Icons.COMPASS );
				arrow.origin.set( arrow.width / 2f, arrow.height / 2f );
				arrows.add( arrow );
				add( arrow );
			}
			used++;
			float cx = ui.width / 2f, cy = ui.height / 2f;
			float dx = p.x - cx, dy = p.y - cy;
			float k = Math.min( (cx - EDGE) / Math.max( 0.001f, Math.abs( dx ) ),
					(cy - EDGE) / Math.max( 0.001f, Math.abs( dy ) ) );
			arrow.x = cx + dx * k - arrow.width / 2f;
			arrow.y = cy + dy * k - arrow.height / 2f;
			//the compass icon points up at angle 0
			arrow.angle = (float) Math.toDegrees( Math.atan2( dx, -dy ) );
			arrow.hardlight( MarkLayer.color( m.kind ) );
			arrow.alpha( Math.min( 1f, m.remaining( now ) * 3f ) );
			arrow.scale.set( 2f );
			arrow.visible = true;
		}
		for (int i = used; i < arrows.size(); i++) arrows.get( i ).visible = false;
	}

	private void updateToast( long now ) {
		String text = CoopGhosts.toast( now );
		if (text == null) {
			toast.visible = false;
			return;
		}
		if (toastShown != CoopGhosts.toastAt()) {
			toastShown = CoopGhosts.toastAt();
			toast.text( text, 160 );
			toast.setPos( (PixelScene.uiCamera.width - toast.width()) / 2f, 42 );
			PixelScene.align( toast );
		}
		float left = (CoopGhosts.toastAt() + CoopGhosts.TOAST_MS - now) / (float) CoopGhosts.TOAST_MS;
		toast.alpha( Math.min( 1f, left * 3f ) );
		toast.visible = true;
	}

	@Override
	public void destroy() {
		KeyEvent.removeKeyListener( keys );
		super.destroy();
	}

	public static void attach( GameScene scene, CellSelector selector ) {
		scene.add( new PingInput( selector ) );
		//added after the fog, so markers stay readable on dim remembered cells
		scene.add( new MarkLayer() );
		scene.add( new CoopHud( selector ) );
		CoopGhosts.onLevelShown();
	}
}
