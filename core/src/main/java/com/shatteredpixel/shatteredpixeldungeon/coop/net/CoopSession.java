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

package com.shatteredpixel.shatteredpixeldungeon.coop.net;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.DecodeError;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.watabou.noosa.Game;
import com.watabou.utils.DeviceCompat;

import java.util.List;
import java.util.Locale;
import java.util.Map;

//the client side of protocol v1 (PROTOCOL.md §2, §5–§9) on top of a FrameLink.
//- every connection starts with HELLO (c2s seq 1), with resumeToken/epoch/ack once we have them;
//- s2c seq is tracked per server epoch from WELCOME on (messages before WELCOME are numbered
//  per connection and don't count, SRV-01);
//- REJECT{RESUME_FAILED} drops the credentials and the next connection starts fresh;
//  REJECT{VERSION_MISMATCH|BAD_MESSAGE} and ERROR{SESSION_REPLACED} stop for good;
//- tick(now) sends PING every 10 s and reconnects after 30 s without any server message.
//All methods run on the main (GL) thread: CoopClient posts its callbacks there, and the game
// calls tick() and the request methods from there too.
//The resume token is a secret: it is handed to the Listener for saving and never logged.
public class CoopSession implements CoopClient.Listener {

	private static final String TAG = "CoopSession";

	public static final long PING_INTERVAL_MS = 10_000;
	public static final long DEAD_AFTER_MS = 30_000;

	public enum State {
		//waiting for a socket
		CONNECTING,
		//socket open, HELLO sent, no WELCOME yet
		HANDSHAKE,
		//WELCOME received, not in a room
		READY,
		IN_LOBBY,
		RUNNING,
		//REJECT / SESSION_REPLACED / stop(): no more reconnects
		STOPPED
	}

	public interface Listener {
		void onStateChanged( State state );
		//new or cleared (all null) credentials, to persist with the co-op save
		void onCredentials( String playerId, String resumeToken, String epoch );
		//ROOM_STATE, or the room in a WELCOME snapshot
		void onRoomState( Map<String, Object> room );
		//RUN_START (fromSnapshot false) or the run in a WELCOME snapshot (true); idempotent by runId
		void onRunStart( Map<String, Object> run, boolean fromSnapshot );
		void onAllyStatus( Message status );
		//relayed GHOST / MARK / QUICK
		void onPresence( Message presence );
		//every remaining s2c type (Protocol.event): mission/item/downed/sync/boss/twin
		// broadcasts and the relayed DROP_SHARE / SYNC_VIEW / SYNC_CMD / TERRAIN.
		// The receiver ignores messages for a floor it is not on (AGENTS.md §3.6).
		default void onServerEvent( Message m ) {}
		//WELCOME.serverTime / PONG.serverTime (server Unix ms) and our clock when it arrived (MEC-00:
		// the offset that turns the server's mission deadlines into local time)
		default void onServerTime( long serverTime, long localNow ) {}
		//ERROR: the request failed, the connection stays open
		void onServerError( String code, String msg );
		//stopped for good: REJECT{VERSION_MISMATCH|BAD_MESSAGE} or ERROR{SESSION_REPLACED}
		void onStopped( String code, String msg, Long minGameVersionCode );
	}

	public interface Clock {
		long now();
	}

	public static final Clock SYSTEM_CLOCK = new Clock() {
		@Override
		public long now() {
			return System.currentTimeMillis();
		}
	};

	private final int gameVersionCode;
	private final String platform;
	private final Listener listener;
	private final Clock clock;
	private FrameLink link;

	private State state = State.CONNECTING;
	private String playerId;
	private String resumeToken;
	private String epoch;
	//highest s2c seq received in the current epoch
	private long ack = 0;
	private long c2sSeq = 0;
	private boolean welcomed = false;
	private long lastServerMessage;
	private long lastPing;

	public CoopSession( int gameVersionCode, String platform, Listener listener, Clock clock ) {
		this.gameVersionCode = gameVersionCode;
		this.platform = platform;
		this.listener = listener;
		this.clock = clock;
		this.lastServerMessage = clock.now();
	}

	public void attach( FrameLink link ) {
		this.link = link;
	}

	//credentials saved with a co-op run, to resume it (all null for a fresh player)
	public void restoreCredentials( String playerId, String resumeToken, String epoch, long ack ) {
		this.playerId = playerId;
		this.resumeToken = resumeToken;
		this.epoch = resumeToken == null ? null : epoch;
		this.ack = resumeToken == null ? 0 : ack;
	}

	public State state() {
		return state;
	}

	public String playerId() {
		return playerId;
	}

	public long ack() {
		return ack;
	}

	// ---- requests (false if not sent: no WELCOME yet, stopped, or socket down) ----

	public boolean createRoom() {
		return send( new Message( Protocol.ROOM_CREATE ) );
	}

	public boolean joinRoom( String code ) {
		return send( new Message( Protocol.ROOM_JOIN ).put( "code", code.trim().toUpperCase( Locale.ROOT ) ) );
	}

	public boolean leaveRoom() {
		return send( new Message( Protocol.ROOM_LEAVE ) );
	}

	public boolean ready( String heroClass, boolean ready ) {
		return send( new Message( Protocol.READY ).put( "heroClass", heroClass ).put( "ready", ready ) );
	}

	public boolean ghost( int depth, int branch, int pos, String anim, int hpPct, String heroClass, int tier ) {
		Message m = new Message( Protocol.GHOST ).put( "pos", pos ).put( "anim", anim ).put( "hpPct", hpPct )
				.put( "heroClass", heroClass ).put( "tier", tier );
		m.depth = depth;
		m.branch = branch;
		return send( m );
	}

	public boolean mark( int depth, int branch, int cell, String kind ) {
		Message m = new Message( Protocol.MARK ).put( "cell", cell ).put( "kind", kind );
		m.depth = depth;
		m.branch = branch;
		return send( m );
	}

		//depth/branch may be null (a QUICK isn't tied to a floor)
		public boolean quick( int phraseId, Integer depth, Integer branch ) {
			Message m = new Message( Protocol.QUICK ).put( "phraseId", phraseId );
			m.depth = depth;
			m.branch = depth == null ? null : branch;
			return send( m );
		}

		// ---- co-op gameplay requests (§5.6–§5.10), all floor-scoped ----

		public boolean missionTrigger( int depth, int branch, String kind, int trapCell, long roomId ) {
			return send( floor( depth, branch, new Message( Protocol.MISSION_TRIGGER ) )
					.put( "kind", kind ).put( "trapCell", trapCell ).put( "roomId", roomId ) );
		}

		public boolean claim( int depth, int branch, String itemNetId ) {
			return send( floor( depth, branch, new Message( Protocol.CLAIM ) ).put( "itemNetId", itemNetId ) );
		}

		//the payload names the floor; the envelope stays clean (§5.7)
		public boolean claimsReq( int depth, int branch ) {
			return send( new Message( Protocol.CLAIMS_REQ )
					.put( "depth", depth ).put( "branch", branch ) );
		}

		//item is required with kind "item" and must be null with "cell_key"
		public boolean give( int depth, int branch, String itemNetId, String kind, String item, Integer toCell ) {
			Message m = floor( depth, branch, new Message( Protocol.GIVE ) )
					.put( "itemNetId", itemNetId ).put( "kind", kind );
			if (item != null) m.put( "item", item );
			if (toCell != null) m.put( "toCell", toCell );
			return send( m );
		}

		public boolean dropShare( int depth, int branch, int cell, String itemNetId, String item ) {
			return send( floor( depth, branch, new Message( Protocol.DROP_SHARE ) )
					.put( "cell", cell ).put( "itemNetId", itemNetId ).put( "item", item ) );
		}

		public boolean downed( int depth, int branch, String cause ) {
			Message m = floor( depth, branch, new Message( Protocol.DOWNED ) );
			if (cause != null) m.put( "cause", cause );
			return send( m );
		}

		public boolean useRecall( int depth, int branch, String target, String via ) {
			return send( floor( depth, branch, new Message( Protocol.USE_RECALL ) )
					.put( "target", target ).put( "via", via ) );
		}

		public boolean syncDrink( int depth, int branch, String snapshot, long turns ) {
			return send( floor( depth, branch, new Message( Protocol.SYNC_DRINK ) )
					.put( "snapshot", snapshot ).put( "turns", turns ) );
		}

		public boolean syncView( int depth, int branch, String syncId, long turn, List<Integer> terrainDiff, List<Map<String, Object>> entities ) {
			return send( floor( depth, branch, new Message( Protocol.SYNC_VIEW ) )
					.put( "syncId", syncId ).put( "turn", turn )
					.put( "terrainDiff", terrainDiff ).put( "entities", entities ) );
		}

		//cell is required with action move/attack and must be null with wait
		public boolean syncCmd( int depth, int branch, String syncId, long turn, String action, Integer cell ) {
			Message m = floor( depth, branch, new Message( Protocol.SYNC_CMD ) )
					.put( "syncId", syncId ).put( "turn", turn ).put( "action", action );
			if (cell != null) m.put( "cell", cell );
			return send( m );
		}

		public boolean syncReport( int depth, int branch, String syncId, long hpLost, String reason ) {
			return send( floor( depth, branch, new Message( Protocol.SYNC_REPORT ) )
					.put( "syncId", syncId ).put( "hpLost", hpLost ).put( "reason", reason ) );
		}

		public boolean bossEngage( int depth, int branch, long bossId, long ht ) {
			return send( floor( depth, branch, new Message( Protocol.BOSS_ENGAGE ) )
					.put( "bossId", bossId ).put( "ht", ht ) );
		}

		public boolean bossHit( int depth, int branch, long bossId, long dmg ) {
			return send( floor( depth, branch, new Message( Protocol.BOSS_HIT ) )
					.put( "bossId", bossId ).put( "dmg", dmg ) );
		}

		//groupId is required with kind "switch" and must be null with "door"
		public boolean twinAct( int depth, int branch, long objectId, String kind, int cell, boolean active, Long groupId ) {
			Message m = floor( depth, branch, new Message( Protocol.TWIN_ACT ) )
					.put( "objectId", objectId ).put( "kind", kind ).put( "cell", cell ).put( "active", active );
			if (groupId != null) m.put( "groupId", groupId );
			return send( m );
		}

		public boolean terrain( int depth, int branch, int cell, int tile ) {
			return send( floor( depth, branch, new Message( Protocol.TERRAIN ) )
					.put( "cell", cell ).put( "tile", tile ) );
		}

		private static Message floor( int depth, int branch, Message m ) {
			m.depth = depth;
			m.branch = branch;
			return m;
		}

	public void stop() {
		setState( State.STOPPED );
		if (link != null) link.stop();
	}

	//heartbeat and liveness; call regularly (every frame is fine)
	public void tick( long now ) {
		if (state == State.STOPPED || state == State.CONNECTING) {
			return;
		}
		if (welcomed && now - lastPing >= PING_INTERVAL_MS) {
			lastPing = now;
			send( new Message( Protocol.PING ).put( "t", now ) );
		}
		if (now - lastServerMessage > DEAD_AFTER_MS) {
			log( "no server message for " + (now - lastServerMessage) + " ms, reconnecting" );
			lastServerMessage = now;
			if (link != null) link.reconnect();
		}
	}

	// ---- CoopClient.Listener ----

	@Override
	public void onConnected() {
		if (state == State.STOPPED) return;
		c2sSeq = 0;
		welcomed = false;
		lastServerMessage = clock.now();
		setState( State.HANDSHAKE );
		Message hello = new Message( Protocol.HELLO ).put( "protocol", Protocol.VERSION )
				.put( "gameVersionCode", gameVersionCode ).put( "platform", platform );
		if (resumeToken != null) {
			hello.put( "resumeToken", resumeToken );
			if (epoch != null) {
				hello.put( "epoch", epoch );
				hello.ack = ack;
			}
		}
		write( hello );
	}

	@Override
	public void onMessage( String text ) {
		if (state == State.STOPPED) return;
		lastServerMessage = clock.now();
		Message m;
		try {
			m = Protocol.decode( Direction.S2C, text );
		} catch (DecodeError e) {
			//unknown s2c types are ignored by design (§5.4); anything else is a server bug
			if (!Protocol.KIND_UNKNOWN_TYPE.equals( e.kind )) log( "dropped an invalid server message: " + e.getMessage() );
			return;
		}

		if (!welcomed) {
			if (Protocol.WELCOME.equals( m.t )) welcome( m );
			else if (Protocol.REJECT.equals( m.t )) reject( m );
			else if (Protocol.ERROR.equals( m.t )) error( m );
			return;
		}

		ack = Math.max( ack, m.seq );
		String t = m.t;
		if (Protocol.ROOM_STATE.equals( t )) {
			roomState( m.p );
		} else if (Protocol.RUN_START.equals( t )) {
			listener.onRunStart( m.p, false );
			setState( State.RUNNING );
		} else if (Protocol.ALLY_STATUS.equals( t )) {
			listener.onAllyStatus( m );
		} else if (Protocol.presence( t )) {
			listener.onPresence( m );
		} else if (Protocol.event( t )) {
			listener.onServerEvent( m );
		} else if (Protocol.ERROR.equals( t )) {
			error( m );
		} else if (Protocol.REJECT.equals( t )) {
			reject( m );
		} else if (Protocol.PONG.equals( t )) {
			//receiving it already refreshed lastServerMessage
			listener.onServerTime( m.num( "serverTime" ), clock.now() );
		}
	}

	@Override
	public void onDisconnected( int code, String reason, boolean willRetry ) {
		welcomed = false;
		if (state != State.STOPPED) setState( State.CONNECTING );
	}

	// ---- internals ----

	private void welcome( Message m ) {
		welcomed = true;
		String newEpoch = m.str( "epoch" );
		if (epoch == null || !epoch.equals( newEpoch )) {
			//new server process: the ack baseline restarts in its epoch (§7.4)
			ack = 0;
		}
		ack = Math.max( ack, m.seq );
		playerId = m.str( "playerId" );
		resumeToken = m.str( "resumeToken" );
		epoch = newEpoch;
		lastPing = clock.now();
		listener.onCredentials( playerId, resumeToken, epoch );
		//before the snapshot and the replayed events, so their deadlines convert with this epoch's clock
		listener.onServerTime( m.num( "serverTime" ), clock.now() );

		Map<String, Object> room = m.obj( "room" );
		Map<String, Object> run = m.obj( "run" );
		if (room != null) {
			roomState( room );
		} else {
			setState( State.READY );
		}
		if (run != null) {
			listener.onRunStart( run, true );
			setState( State.RUNNING );
		}
	}

	//data before the state change: a UI redrawing on onStateChanged must already see the room
	private void roomState( Map<String, Object> room ) {
		listener.onRoomState( room );
		setState( "running".equals( room.get( "phase" ) ) ? State.RUNNING : State.IN_LOBBY );
	}

	private void reject( Message m ) {
		String code = m.str( "code" );
		if (Protocol.RESUME_FAILED.equals( code )) {
			//the server forgot us: start fresh on the next connection (it closes this one)
			restoreCredentials( null, null, null, 0 );
			listener.onCredentials( null, null, null );
			return;
		}
		Long min = m.p.containsKey( "minGameVersionCode" ) ? m.num( "minGameVersionCode" ) : null;
		stop();
		listener.onStopped( code, m.str( "msg" ), min );
	}

	private void error( Message m ) {
		String code = m.str( "code" );
		if (Protocol.SESSION_REPLACED.equals( code )) {
			//another connection of ours took over; don't fight it
			stop();
			listener.onStopped( code, m.str( "msg" ), null );
			return;
		}
		listener.onServerError( code, m.str( "msg" ) );
	}

	private boolean send( Message m ) {
		if (!welcomed || state == State.STOPPED) {
			return false;
		}
		return write( m );
	}

	private boolean write( Message m ) {
		if (link == null) return false;
		m.seq = ++c2sSeq;
		if (m.ack == null && ack > 0 && welcomed) m.ack = ack;
		if (link.send( Protocol.encode( m ) )) {
			return true;
		}
		//not sent: keep seq contiguous for the next message on this connection
		c2sSeq--;
		return false;
	}

	private void setState( State s ) {
		if (state == s) return;
		state = s;
		listener.onStateChanged( s );
	}

	private static void log( String message ) {
		if (Gdx.app != null && Game.version != null && DeviceCompat.isDebug()) {
			Gdx.app.debug( TAG, message );
		}
	}
}
