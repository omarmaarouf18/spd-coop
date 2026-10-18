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

package com.shatteredpixel.shatteredpixeldungeon.coop;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Backoff;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopClient;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopTransport;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.JavaWebSocketTransport;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.MainThread;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

//the one live connection to the co-op server (lobby, then the running run).
//Everything here runs on the GL thread: CoopClient posts its callbacks there and the heartbeat
// timer posts tick() there. The connection only carries shared state; whether a run is a co-op
// run is decided by the saved CoopRun (Coop.isCoopRun), never by this socket.
public final class CoopConnection {

	public static final String DEFAULT_SERVER = "ws://localhost:8080/ws";
	private static final String SERVER_KEY = "coop_server_url";

	//what the lobby window shows
	public interface Listener {
		void onUpdate();
	}

	private static CoopSession session;
	private static CoopClient client;
	private static ScheduledExecutorService ticker;
	private static String url = "";
	private static Listener ui;

	//lobby state (cleared on connect)
	private static Map<String, Object> room;
	private static String lastError;
	private static String playerId, resumeToken, epoch;
	private static boolean allyConnected;

	private CoopConnection() {}

	public static String serverUrl() {
		return GameSettings.getString( SERVER_KEY, DEFAULT_SERVER, 200 );
	}

	public static void serverUrl( String value ) {
		GameSettings.put( SERVER_KEY, value.trim() );
	}

	public static void listener( Listener l ) {
		ui = l;
	}

	public static CoopSession.State state() {
		return session == null ? null : session.state();
	}

	public static CoopSession session() {
		return session;
	}

	public static Map<String, Object> room() {
		return room;
	}

	public static String lastError() {
		return lastError;
	}

	public static String playerId() {
		return playerId;
	}

	public static boolean allyConnected() {
		return allyConnected;
	}

	public static boolean active() {
		return session != null && session.state() != CoopSession.State.STOPPED;
	}

	//connect for the lobby, as a fresh player
	public static void connectLobby() {
		connect( serverUrl(), null );
	}

	//reconnect into a saved run with its credentials (Coop.restoreFromBundle)
	public static void resume( CoopRun run ) {
		connect( run.serverUrl.isEmpty() ? serverUrl() : run.serverUrl, run );
	}

	public static void stop() {
		if (client != null) client.stop();
		if (ticker != null) ticker.shutdownNow();
		client = null;
		session = null;
		ticker = null;
		room = null;
		notifyUi();
	}

	private static void connect( String serverUrl, CoopRun run ) {
		stop();
		lastError = null;
		allyConnected = false;
		URI uri;
		try {
			uri = new URI( serverUrl );
		} catch (Exception e) {
			lastError = "BAD_URL";
			notifyUi();
			return;
		}
		url = serverUrl;
		session = new CoopSession( Game.versionCode, platform(), new SessionListener(), CoopSession.SYSTEM_CLOCK );
		if (run != null) {
			session.restoreCredentials( CoopRun.orNull( run.playerId ), CoopRun.orNull( run.resumeToken ),
					CoopRun.orNull( run.epoch ), run.ack );
		}
		client = new CoopClient( uri, new CoopTransport.Factory() {
			@Override
			public CoopTransport create() {
				return new JavaWebSocketTransport();
			}
		}, MainThread.GDX, new Backoff(), session, false );
		session.attach( client );
		client.start();

		ticker = Executors.newSingleThreadScheduledExecutor( r -> {
			Thread t = new Thread( r, "coop-net-tick" );
			t.setDaemon( true );
			return t;
		} );
		final CoopSession ticking = session;
		ticker.scheduleAtFixedRate( () -> MainThread.GDX.post( () -> {
			if (session == ticking) ticking.tick( System.currentTimeMillis() );
		} ), 1, 1, TimeUnit.SECONDS );
		notifyUi();
	}

	private static String platform() {
		if (Gdx.app == null) return "desktop";
		Application.ApplicationType type = Gdx.app.getType();
		if (type == Application.ApplicationType.Android) return "android";
		if (type == Application.ApplicationType.iOS) return "ios";
		return "desktop";
	}

	private static void notifyUi() {
		if (ui != null) ui.onUpdate();
	}

	private static final class SessionListener implements CoopSession.Listener {

		@Override
		public void onStateChanged( CoopSession.State state ) {
			notifyUi();
		}

		@Override
		public void onCredentials( String id, String token, String ep ) {
			playerId = id;
			resumeToken = token;
			epoch = ep;
			CoopRun run = Coop.run();
			if (run != null) {
				run.playerId = id == null ? "" : id;
				run.resumeToken = token == null ? "" : token;
				run.epoch = ep == null ? "" : ep;
			}
		}

		@Override
		public void onRoomState( Map<String, Object> r ) {
			room = r;
			notifyUi();
		}

		@Override
		public void onRunStart( Map<String, Object> runInfo, boolean fromSnapshot ) {
			CoopRun current = Coop.run();
			if (current != null && current.runId.equals( runInfo.get( "runId" ) )) {
				//resumed into the run we're already playing
				return;
			}
			Coop.startRun( runInfo, playerId, resumeToken, epoch, url );
		}

		@Override
		public void onAllyStatus( Message status ) {
			allyConnected = status.bool( "connected" );
			CoopGhosts.onAllyStatus( status );
			notifyUi();
		}

		@Override
		public void onPresence( Message presence ) {
			CoopGhosts.onPresence( presence );
		}

		@Override
		public void onServerError( String code, String msg ) {
			lastError = code;
			notifyUi();
		}

		@Override
		public void onStopped( String code, String msg, Long minGameVersionCode ) {
			lastError = code;
			notifyUi();
		}
	}
}
