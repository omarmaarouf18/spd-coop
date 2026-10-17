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

import java.net.URI;

//one websocket connection attempt. A transport is used for a single connect() and is then
// discarded; CoopClient creates a fresh one for every reconnect.
//Listener methods are called on the transport's own (socket) thread, never the GL thread.
public interface CoopTransport {

	interface Listener {
		void onOpen();
		void onText( String text );
		//called exactly once per transport, after onOpen or instead of it
		void onClosed( int code, String reason, boolean remote );
	}

	void connect( URI uri, Listener listener );

	//returns false if the frame could not be handed to the socket
	boolean send( String text );

	void close();

	interface Factory {
		CoopTransport create();
	}
}
