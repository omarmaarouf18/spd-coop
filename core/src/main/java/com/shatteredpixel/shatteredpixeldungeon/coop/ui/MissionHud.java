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
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopConnection;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.CoopMissions;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.noosa.Image;
import com.watabou.noosa.NinePatch;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

//the mission bar (MEC-00, game-design §4.4): centered under the boss bar, on both players'
// screens. Line 1: the mission kind and its real-time countdown, or why it is paused. Line 2: a
// checkbox per task from MISSION_UPDATE.tasks. A Downed window adds its own countdown line.
//Hidden when no clock is active. Every frame it also drives CoopMissions.tick().
public class MissionHud extends Component {

	private static final int TITLE_COLOR = 0xFFCC66;
	private static final int PAUSED_COLOR = 0xFF8866;
	private static final int DOWNED_COLOR = 0xFF6666;

	private final Component under;

	private NinePatch bg;
	private RenderedTextBlock title;
	private RenderedTextBlock downedLine;
	private final List<Image> boxes = new ArrayList<>();
	private final List<RenderedTextBlock> labels = new ArrayList<>();

	private String lastTitle = "";
	private String lastDowned = "";
	private String lastTasks = "";

	public MissionHud( Component under ) {
		super();
		this.under = under;
		camera = PixelScene.uiCamera;
		visible = false;
	}

	@Override
	protected void createChildren() {
		bg = Chrome.get( Chrome.Type.TOAST_TR );
		add( bg );
		title = PixelScene.renderTextBlock( 7 );
		add( title );
		downedLine = PixelScene.renderTextBlock( 6 );
		downedLine.hardlight( DOWNED_COLOR );
		add( downedLine );
	}

	// ---- text (static, for tests) ----

	//m:ss, rounded up so 0:00 only shows when the time is really up
	public static String countdown( long ms ) {
		long s = (Math.max( 0, ms ) + 999) / 1000;
		return String.format( Locale.ROOT, "%d:%02d", s / 60, s % 60 );
	}

	public static String kindName( String kind ) {
		if (MissionState.CELL.equals( kind )) return Messages.get( MissionHud.class, "kind_cell" );
		return Messages.get( MissionHud.class, "kind_other" );
	}

	//why a clock is held: we are reconnecting, the ally is offline, or the server says so
	public static String pausedReason( boolean selfUp, boolean allyUp ) {
		if (!selfUp) return Messages.get( MissionHud.class, "paused_self" );
		if (!allyUp) return Messages.get( MissionHud.class, "paused_ally" );
		return Messages.get( MissionHud.class, "paused" );
	}

	public static String titleLine( MissionState.Mission m, long serverNow, boolean selfUp, boolean allyUp ) {
		String kind = kindName( m.kind );
		if (m.paused || !selfUp || !allyUp) return kind + "  " + pausedReason( selfUp, allyUp );
		return kind + "  " + countdown( m.remainingMs( serverNow ) );
	}

	public static String taskName( String task ) {
		String name = Messages.get( MissionHud.class, "task_" + task.toLowerCase( Locale.ROOT ) );
		//a task flag without a label in the .properties: show its wire name
		return Messages.NO_TEXT_FOUND.equals( name ) ? task : name;
	}

	public static String downedText( String playerId, String me, long remainingMs, boolean held ) {
		String time = held ? Messages.get( MissionHud.class, "paused" ) : countdown( remainingMs );
		if (playerId.equals( me )) return Messages.get( MissionHud.class, "downed_self", time );
		return Messages.get( MissionHud.class, "downed_ally", CoopGhosts.allyName(), time );
	}

	// ---- frame ----

	@Override
	public void update() {
		CoopMissions.tick( System.currentTimeMillis() );

		MissionState s = CoopMissions.state();
		MissionState.Mission m = s == null ? null : s.mission();
		boolean downed = s != null && !s.downed().isEmpty();
		visible = m != null || downed;
		if (!visible) {
			super.update();
			return;
		}

		boolean selfUp = CoopConnection.state() == CoopSession.State.RUNNING;
		boolean allyUp = CoopConnection.allyConnected();
		long serverNow = CoopMissions.serverNow();

		boolean relayout = false;
		String t = m == null ? "" : titleLine( m, serverNow, selfUp, allyUp );
		if (!t.equals( lastTitle )) {
			lastTitle = t;
			title.text( t );
			title.hardlight( m != null && (m.paused || !selfUp || !allyUp) ? PAUSED_COLOR : TITLE_COLOR );
			relayout = true;
		}

		StringBuilder d = new StringBuilder();
		if (downed) {
			for (Map.Entry<String, Long> e : s.downed().entrySet()) {
				if (d.length() > 0) d.append( '\n' );
				d.append( downedText( e.getKey(), CoopMissions.me(),
						Math.max( 0, e.getValue() - serverNow ), !selfUp || !allyUp ) );
			}
		}
		if (!d.toString().equals( lastDowned )) {
			lastDowned = d.toString();
			downedLine.text( lastDowned );
			relayout = true;
		}

		String tasks = m == null ? "" : m.tasks.toString();
		if (!tasks.equals( lastTasks )) {
			lastTasks = tasks;
			rebuildTasks( m );
			relayout = true;
		}

		float top = under.bottom() + 2;
		if (relayout || y != top) {
			y = top;
			layout();
		}
		super.update();
	}

	private void rebuildTasks( MissionState.Mission m ) {
		for (Image i : boxes) i.killAndErase();
		for (RenderedTextBlock l : labels) l.killAndErase();
		boxes.clear();
		labels.clear();
		if (m == null) return;
		for (Map.Entry<String, Boolean> e : m.tasks.entrySet()) {
			Image box = Icons.get( e.getValue() ? Icons.CHECKED : Icons.UNCHECKED );
			box.scale.set( 0.5f );
			add( box );
			boxes.add( box );
			RenderedTextBlock label = PixelScene.renderTextBlock( 6 );
			label.text( taskName( e.getKey() ) );
			add( label );
			labels.add( label );
		}
	}

	@Override
	protected void layout() {
		float w = 0;
		float h = 3;
		boolean hasTitle = lastTitle.length() > 0;
		if (hasTitle) {
			w = Math.max( w, title.width() );
			h += title.height() + 2;
		}
		float tasksW = 0;
		for (int i = 0; i < boxes.size(); i++) {
			tasksW += boxes.get( i ).width() + 2 + labels.get( i ).width() + 6;
		}
		if (!boxes.isEmpty()) {
			w = Math.max( w, tasksW - 6 );
			h += 8;
		}
		if (lastDowned.length() > 0) {
			w = Math.max( w, downedLine.width() );
			h += downedLine.height() + 2;
		}
		width = w + 10;
		height = h + 1;
		x = (PixelScene.uiCamera.width - width) / 2f;
		PixelScene.align( this );

		bg.x = x;
		bg.y = y;
		bg.size( width, height );

		float cy = y + 3;
		if (hasTitle) {
			title.setPos( x + (width - title.width()) / 2f, cy );
			PixelScene.align( title );
			cy += title.height() + 2;
		}
		if (!boxes.isEmpty()) {
			float cx = x + (width - (tasksW - 6)) / 2f;
			for (int i = 0; i < boxes.size(); i++) {
				Image box = boxes.get( i );
				box.x = cx;
				box.y = cy;
				PixelScene.align( box );
				cx += box.width() + 2;
				RenderedTextBlock label = labels.get( i );
				label.setPos( cx, cy );
				PixelScene.align( label );
				cx += label.width() + 6;
			}
			cy += 8;
		}
		if (lastDowned.length() > 0) {
			downedLine.setPos( x + (width - downedLine.width()) / 2f, cy );
			PixelScene.align( downedLine );
		}
	}
}
