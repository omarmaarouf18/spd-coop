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

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopConnection;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopSaves;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;
import com.watabou.noosa.Group;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

//the co-op lobby: connect, create or join a room, pick a hero, ready up. When both players are
// ready the server sends RUN_START and Coop.startRun takes over. The window redraws itself
// from CoopConnection's state on every update.
public class WndCoop extends Window implements CoopConnection.Listener {

	private static final int WIDTH = 120;
	private static final int BTN_HEIGHT = 18;
	private static final int GAP = 2;

	//the hero this player picks, kept across redraws
	private static int heroIndex = 0;

	private Group content;

	public WndCoop() {
		super();
		CoopConnection.listener( this );
		layout();
	}

	@Override
	public void onUpdate() {
		if (parent != null) layout();
	}

	@Override
	public void destroy() {
		CoopConnection.listener( null );
		super.destroy();
	}

	@Override
	public void onBackPressed() {
		//leaving the lobby without a run closes the connection; a running run keeps it
		if (!Coop.isCoopRun()) CoopConnection.stop();
		super.onBackPressed();
	}

	private static String txt( String key, Object... args ) {
		return Messages.get( WndCoop.class, key, args );
	}

	private void layout() {
		if (content != null) {
			content.destroy();
			remove( content );
		}
		content = new Group();
		add( content );

		float pos = text( txt( "title" ), 9, Window.TITLE_COLOR, 0 );
		CoopSession.State state = CoopConnection.state();

		if (state == null) {
			pos = disconnected( pos );
		} else {
			switch (state) {
				case CONNECTING:
				case HANDSHAKE:
					pos = text( txt( "connecting", CoopConnection.serverUrl() ), 6, 0xFFFFFF, pos );
					pos = button( txt( "cancel" ), pos, CoopConnection::stop );
					break;
				case READY:
					pos = error( pos );
					pos = buttons( txt( "create" ), () -> CoopConnection.session().createRoom(),
							txt( "join" ), this::askCode, pos );
					pos = button( txt( "disconnect" ), pos, CoopConnection::stop );
					break;
				case IN_LOBBY:
					pos = lobby( pos );
					break;
				case RUNNING:
					pos = text( txt( "starting" ), 6, 0xFFFFFF, pos );
					break;
				case STOPPED:
					pos = error( pos );
					pos = button( txt( "back" ), pos, CoopConnection::stop );
					break;
			}
		}
		resize( WIDTH, (int) pos );
	}

	//not connected: connect, change server, or continue a saved co-op run
	private float disconnected( float pos ) {
		pos = error( pos );
		pos = text( txt( "server", CoopConnection.serverUrl() ), 6, 0xCCCCCC, pos );
		pos = buttons( txt( "connect" ), CoopConnection::connectLobby, txt( "change_server" ), this::askServer, pos );
		for (final CoopSaves.Saved s : CoopSaves.list()) {
			pos = button( txt( "continue", s.run.roomCode, s.depth ), pos, () -> Coop.continueRun( s.slot ) );
		}
		return pos;
	}

	@SuppressWarnings("unchecked")
	private float lobby( float pos ) {
		Map<String, Object> room = CoopConnection.room();
		if (room == null) {
			//state and room arrive together, but never draw a lobby without one
			return text( txt( "connecting", CoopConnection.serverUrl() ), 6, 0xFFFFFF, pos );
		}
		pos = text( txt( "room_code", room.get( "code" ) ), 9, Window.TITLE_COLOR, pos );
		pos = error( pos );

		boolean meReady = false;
		for (Object o : (List<Object>) room.get( "players" )) {
			Map<String, Object> p = (Map<String, Object>) o;
			boolean me = p.get( "playerId" ).equals( CoopConnection.playerId() );
			boolean ready = Boolean.TRUE.equals( p.get( "ready" ) );
			if (me) meReady = ready;
			String hero = p.containsKey( "heroClass" ) ? heroName( (String) p.get( "heroClass" ) ) : txt( "choosing" );
			String status = !Boolean.TRUE.equals( p.get( "connected" ) ) ? txt( "offline" )
					: ready ? txt( "ready" ) : txt( "not_ready" );
			pos = text( txt( me ? "you" : "ally", hero, status ), 6, me ? 0xFFFF44 : 0xFFFFFF, pos );
		}
		if (((List<Object>) room.get( "players" )).size() < 2) {
			pos = text( txt( "waiting" ), 6, 0xCCCCCC, pos );
		}

		final List<HeroClass> heroes = unlockedHeroes();
		heroIndex = Math.min( heroIndex, heroes.size() - 1 );
		final boolean ready = meReady;
		pos = buttons( txt( "hero", Messages.titleCase( heroes.get( heroIndex ).title() ) ), () -> {
					heroIndex = (heroIndex + 1) % heroes.size();
					//changing hero un-readies, so both players see the final pick before the start
					CoopConnection.session().ready( heroes.get( heroIndex ).name(), false );
				},
				ready ? txt( "set_unready" ) : txt( "set_ready" ),
				() -> CoopConnection.session().ready( heroes.get( heroIndex ).name(), !ready ), pos );
		pos = button( txt( "leave" ), pos, () -> CoopConnection.session().leaveRoom() );
		return pos;
	}

	private float error( float pos ) {
		String code = CoopConnection.lastError();
		if (code == null) return pos;
		String key = "error_" + code.toLowerCase( Locale.ROOT );
		String msg = Messages.get( WndCoop.class, key );
		if (msg.equals( Messages.NO_TEXT_FOUND ) || msg.contains( key )) msg = txt( "error", code );
		return text( msg, 6, 0xFF4444, pos );
	}

	private void askCode() {
		ShatteredPixelDungeon.scene().addToFront( new WndTextInput( txt( "join_title" ), txt( "join_body" ),
				"", 6, false, txt( "ok" ), txt( "cancel" ) ) {
			@Override
			public void onSelect( boolean positive, String text ) {
				if (positive && !text.trim().isEmpty() && CoopConnection.session() != null) {
					CoopConnection.session().joinRoom( text );
				}
			}
		} );
	}

	private void askServer() {
		ShatteredPixelDungeon.scene().addToFront( new WndTextInput( txt( "server_title" ), "",
				CoopConnection.serverUrl(), 200, false, txt( "ok" ), txt( "cancel" ) ) {
			@Override
			public void onSelect( boolean positive, String text ) {
				if (positive && !text.trim().isEmpty()) {
					CoopConnection.serverUrl( text );
					WndCoop.this.layout();
				}
			}
		} );
	}

	private static List<HeroClass> unlockedHeroes() {
		List<HeroClass> out = new ArrayList<>();
		for (HeroClass c : HeroClass.values()) {
			if (c.isUnlocked()) out.add( c );
		}
		if (out.isEmpty()) out.add( HeroClass.WARRIOR );
		return out;
	}

	private static String heroName( String name ) {
		try {
			return Messages.titleCase( HeroClass.valueOf( name ).title() );
		} catch (IllegalArgumentException e) {
			return name;
		}
	}

	// ---- layout helpers ----

	private float text( String s, int size, int color, float pos ) {
		RenderedTextBlock t = PixelScene.renderTextBlock( s, size );
		t.maxWidth( WIDTH );
		t.hardlight( color );
		t.setPos( 0, pos );
		content.add( t );
		return t.bottom() + GAP;
	}

	private float button( String label, float pos, final Runnable action ) {
		RedButton b = new RedButton( label ) {
			@Override
			protected void onClick() {
				action.run();
			}
		};
		b.setRect( 0, pos, WIDTH, BTN_HEIGHT );
		content.add( b );
		return b.bottom() + GAP;
	}

	private float buttons( String left, final Runnable leftAction, String right, final Runnable rightAction, float pos ) {
		RedButton l = new RedButton( left ) {
			@Override
			protected void onClick() {
				leftAction.run();
			}
		};
		RedButton r = new RedButton( right ) {
			@Override
			protected void onClick() {
				rightAction.run();
			}
		};
		l.setRect( 0, pos, WIDTH / 2f - 1, BTN_HEIGHT );
		r.setRect( WIDTH / 2f + 1, pos, WIDTH / 2f - 1, BTN_HEIGHT );
		content.add( l );
		content.add( r );
		return l.bottom() + GAP;
	}
}
