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

import com.badlogic.gdx.Input;
import com.watabou.input.GameAction;
import com.watabou.input.KeyBindings;

//desktop keys for co-op (GHO-01). The input layer only forwards keys that are bound to an action,
// so these are real GameActions with hard (default) bindings: T = quick messages, G = ping the
// cell under the mouse. A binding the player sets for the same key wins, and both can be rebound
// in the key bindings window (their names are windows.wndkeybindings.coop_*).
//Created on the first co-op GameScene only, so single-player never registers them.
public class CoopActions extends GameAction {

	protected CoopActions( String name ) {
		super( name );
	}

	public static final GameAction QUICK_MESSAGE = new CoopActions( "coop_quick" );
	public static final GameAction PING_CURSOR = new CoopActions( "coop_ping" );

	private static boolean bound;

	public static void init() {
		if (bound) return;
		bound = true;
		KeyBindings.addHardBinding( Input.Keys.T, QUICK_MESSAGE );
		KeyBindings.addHardBinding( Input.Keys.G, PING_CURSOR );
	}
}
