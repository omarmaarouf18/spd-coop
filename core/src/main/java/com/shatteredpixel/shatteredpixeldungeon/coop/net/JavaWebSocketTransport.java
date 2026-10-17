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

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

//CoopTransport backed by Java-WebSocket (ws:// and wss://, wss uses the platform's default TLS).
public class JavaWebSocketTransport implements CoopTransport {

	//websocket ping frames detect a dead connection; the server answers them automatically
	private static final int CONNECTION_LOST_TIMEOUT_S = 30;

	private WebSocketClient socket;

	@Override
	public void connect( URI uri, final Listener listener ) {
		final AtomicBoolean closed = new AtomicBoolean( false );
		socket = new WebSocketClient( uri ) {
			@Override
			public void onOpen( ServerHandshake handshake ) {
				listener.onOpen();
			}

			@Override
			public void onMessage( String message ) {
				listener.onText( message );
			}

			@Override
			public void onClose( int code, String reason, boolean remote ) {
				if (closed.compareAndSet( false, true )) {
					listener.onClosed( code, reason, remote );
				}
			}

			@Override
			public void onError( Exception ex ) {
				//Java-WebSocket follows onError with onClose; nothing to do here
			}
		};
		socket.setConnectionLostTimeout( CONNECTION_LOST_TIMEOUT_S );
		socket.connect();
	}

	@Override
	public boolean send( String text ) {
		if (socket == null || !socket.isOpen()) {
			return false;
		}
		try {
			socket.send( text );
			return true;
		} catch (RuntimeException e) {
			//e.g. WebsocketNotConnectedException if the socket closed after isOpen()
			return false;
		}
	}

	@Override
	public void close() {
		if (socket != null) {
			socket.close();
		}
	}
}
