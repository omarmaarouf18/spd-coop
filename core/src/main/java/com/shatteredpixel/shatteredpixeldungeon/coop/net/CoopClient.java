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
import com.watabou.noosa.Game;
import com.watabou.utils.DeviceCompat;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

//the co-op websocket client: text frames only, no protocol knowledge (envelope, HELLO, seq/ack
// live a layer above, see architecture.md §3).
//- send() before the socket is open queues the frame; queued frames go out in order on open
//  (AGENTS.md rule 21). A full queue is reported by send() returning false, never dropped silently.
//- unexpected closes reconnect with Backoff until stop() is called.
//- every Listener callback runs on the MainThread (the GL thread in game, AGENTS.md rule 20).
//Frames sent just before a connection drops can be lost on the wire; resending un-acked
// messages after a reconnect is the protocol layer's job (seq/ack).
public class CoopClient {

	private static final String TAG = "CoopClient";

	public static final int MAX_QUEUED = 256;

	public enum State {
		IDLE,
		CONNECTING,
		OPEN,
		RETRY_WAIT,
		STOPPED
	}

	public interface Listener {
		void onConnected();
		void onMessage( String text );
		void onDisconnected( int code, String reason, boolean willRetry );
	}

	private final URI uri;
	private final CoopTransport.Factory transports;
	private final MainThread mainThread;
	private final Backoff backoff;
	private final Listener listener;
	private final ScheduledExecutorService scheduler;

	private final ArrayDeque<String> outbox = new ArrayDeque<>();
	private State state = State.IDLE;
	private CoopTransport transport;
	//bumped on every connect and on stop, so callbacks from an abandoned transport are ignored
	private int generation = 0;
	private ScheduledFuture<?> pendingRetry;

	public CoopClient( URI uri, Listener listener ) {
		this( uri, new CoopTransport.Factory() {
			@Override
			public CoopTransport create() {
				return new JavaWebSocketTransport();
			}
		}, MainThread.GDX, new Backoff(), listener );
	}

	public CoopClient( URI uri, CoopTransport.Factory transports, MainThread mainThread,
					   Backoff backoff, Listener listener ) {
		this.uri = uri;
		this.transports = transports;
		this.mainThread = mainThread;
		this.backoff = backoff;
		this.listener = listener;
		this.scheduler = Executors.newSingleThreadScheduledExecutor( new ThreadFactory() {
			@Override
			public Thread newThread( Runnable r ) {
				Thread t = new Thread( r, "coop-net-retry" );
				t.setDaemon( true );
				return t;
			}
		} );
	}

	public synchronized void start() {
		if (state != State.IDLE) {
			throw new IllegalStateException( "start() called in state " + state );
		}
		connect();
	}

	//returns true if the frame was sent or queued, false if the client is stopped or the queue is full
	public synchronized boolean send( String text ) {
		if (state == State.STOPPED) {
			log( "send() after stop(), frame not sent" );
			return false;
		}
		if (state == State.OPEN && outbox.isEmpty() && transport.send( text )) {
			return true;
		}
		if (outbox.size() >= MAX_QUEUED) {
			log( "outbound queue full (" + MAX_QUEUED + "), frame rejected" );
			return false;
		}
		outbox.addLast( text );
		return true;
	}

	//closes the connection for good: no reconnect, queued frames are discarded
	public synchronized void stop() {
		if (state == State.STOPPED) {
			return;
		}
		state = State.STOPPED;
		generation++;
		if (pendingRetry != null) {
			pendingRetry.cancel( false );
			pendingRetry = null;
		}
		if (transport != null) {
			transport.close();
			transport = null;
		}
		if (!outbox.isEmpty()) {
			log( "stop() discarded " + outbox.size() + " queued frame(s)" );
			outbox.clear();
		}
		scheduler.shutdownNow();
	}

	public synchronized State state() {
		return state;
	}

	public synchronized int queued() {
		return outbox.size();
	}

	private void connect() {
		final int gen = ++generation;
		state = State.CONNECTING;
		transport = transports.create();
		transport.connect( uri, new CoopTransport.Listener() {
			@Override
			public void onOpen() {
				opened( gen );
			}

			@Override
			public void onText( String text ) {
				received( gen, text );
			}

			@Override
			public void onClosed( int code, String reason, boolean remote ) {
				closed( gen, code, reason );
			}
		} );
	}

	private synchronized void opened( int gen ) {
		if (gen != generation || state != State.CONNECTING) {
			return;
		}
		state = State.OPEN;
		backoff.reset();
		while (!outbox.isEmpty()) {
			if (!transport.send( outbox.peekFirst() )) {
				//the socket died while flushing; onClosed follows and the rest is resent after reconnect
				break;
			}
			outbox.pollFirst();
		}
		mainThread.post( new Runnable() {
			@Override
			public void run() {
				listener.onConnected();
			}
		} );
	}

	private synchronized void received( int gen, final String text ) {
		if (gen != generation) {
			return;
		}
		mainThread.post( new Runnable() {
			@Override
			public void run() {
				listener.onMessage( text );
			}
		} );
	}

	private synchronized void closed( int gen, final int code, final String reason ) {
		if (gen != generation || state == State.STOPPED) {
			return;
		}
		transport = null;
		state = State.RETRY_WAIT;
		long delay = backoff.nextDelayMs();
		log( "closed (" + code + " " + reason + "), retry #" + backoff.attempts() + " in " + delay + "ms" );
		pendingRetry = scheduler.schedule( new Runnable() {
			@Override
			public void run() {
				retry();
			}
		}, delay, TimeUnit.MILLISECONDS );
		mainThread.post( new Runnable() {
			@Override
			public void run() {
				listener.onDisconnected( code, reason, true );
			}
		} );
	}

	private synchronized void retry() {
		if (state != State.RETRY_WAIT) {
			return;
		}
		pendingRetry = null;
		connect();
	}

	private static void log( String message ) {
		if (Gdx.app != null && Game.version != null && DeviceCompat.isDebug()) {
			Gdx.app.debug( TAG, message );
		}
	}
}
