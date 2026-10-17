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

import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.AllyState;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.GhostSprite;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.HeroSnapshot;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.noosa.Image;
import com.watabou.noosa.NinePatch;
import com.watabou.noosa.ui.Component;

//the ally status chip under the status pane (game-design §3): the ally's hero icon, HP %, depth
// and connection (online / offline + how long ago). Tapping it opens the quick messages.
public class AllyStatusChip extends Button {

	private static final float WIDTH = 58;
	private static final float HEIGHT = 20;

	private final Component pane;

	private NinePatch bg;
	private Image icon;
	private RenderedTextBlock line1;
	private RenderedTextBlock line2;

	private String lastLook = "";
	private String lastText = "";

	public AllyStatusChip( Component pane ) {
		super();
		this.pane = pane;
		setSize( WIDTH, HEIGHT );
	}

	@Override
	protected void createChildren() {
		super.createChildren();
		bg = Chrome.get( Chrome.Type.TOAST_TR );
		add( bg );
		icon = Icons.get( Icons.TALENT );
		add( icon );
		line1 = PixelScene.renderTextBlock( 6 );
		add( line1 );
		line2 = PixelScene.renderTextBlock( 6 );
		add( line2 );
	}

	@Override
	protected void onClick() {
		if (!GameScene.showingWindow()) GameScene.show( new WndQuick() );
	}

	@Override
	protected String hoverText() {
		return Messages.get( AllyStatusChip.class, "hover" );
	}

	//the chip's text, for tests and the HUD: {line1, line2}
	public static String[] lines( AllyState ally, boolean sessionUp, long now ) {
		HeroSnapshot g = ally.ghost();
		String l1 = g == null ? CoopGhosts.allyName()
				: Messages.get( AllyStatusChip.class, "hp_depth", g.hpPct, ally.depth() );
		String l2;
		if (!sessionUp) {
			l2 = Messages.get( AllyStatusChip.class, "reconnecting" );
		} else if (ally.connected() == null || ally.online()) {
			l2 = Messages.get( AllyStatusChip.class, "online" );
		} else if (ally.lastSeen() > 0) {
			l2 = Messages.get( AllyStatusChip.class, "offline_ago", ago( now - ally.lastSeen() ) );
		} else {
			l2 = Messages.get( AllyStatusChip.class, "offline" );
		}
		return new String[]{ l1, l2 };
	}

	static String ago( long ms ) {
		long s = Math.max( 0, ms / 1000 );
		if (s < 60) return Messages.get( AllyStatusChip.class, "seconds", s );
		if (s < 3600) return Messages.get( AllyStatusChip.class, "minutes", s / 60 );
		return Messages.get( AllyStatusChip.class, "hours", s / 3600 );
	}

	@Override
	public void update() {
		AllyState ally = CoopGhosts.ally();
		HeroSnapshot g = ally.ghost();
		long now = System.currentTimeMillis();

		//under the pane at the top; above it when the pane sits at the bottom (large UI)
		float px = pane.left();
		float py = pane.top() > PixelScene.uiCamera.height / 2f ? 2 : pane.bottom() + 1;
		if (px != x || py != y) setPos( px, py );

		String look = g == null ? "" : g.heroClass + g.tier;
		if (!look.equals( lastLook )) {
			lastLook = look;
			HeroClass cls = g == null ? null : CoopGhosts.heroClass( g.heroClass );
			if (cls != null) {
				icon.copy( HeroSprite.avatar( cls, g.tier ) );
				layout();
			}
		}

		String[] l = lines( ally, CoopGhosts.sessionRunning(), now );
		String text = l[0] + "\n" + l[1];
		if (!text.equals( lastText )) {
			lastText = text;
			line1.text( l[0] );
			line2.text( l[1] );
			layout();
		}
		boolean up = ally.online() && CoopGhosts.sessionRunning();
		line2.hardlight( up ? 0x88FF88 : 0xFF8866 );
		icon.resetColor();
		if (!up) icon.tint( 0x808080, 0.8f );
		else icon.tint( GhostSprite.TINT, 0.3f );
		super.update();
	}

	@Override
	protected void layout() {
		super.layout();
		bg.x = x;
		bg.y = y;
		bg.size( width, height );
		icon.x = x + 3;
		icon.y = y + (height - icon.height) / 2f;
		PixelScene.align( icon );
		line1.setPos( x + 17, y + 3 );
		line2.setPos( x + 17, y + 11 );
		PixelScene.align( line1 );
		PixelScene.align( line2 );
		width = Math.max( WIDTH, Math.max( line1.right(), line2.right() ) - x + 4 );
		bg.size( width, height );
		if (hotArea != null) {
			hotArea.x = x;
			hotArea.y = y;
			hotArea.width = width;
			hotArea.height = height;
		}
	}
}
