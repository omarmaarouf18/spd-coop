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

package com.shatteredpixel.shatteredpixeldungeon.coop.ghost;

import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;

//the quick-message table (QUICK.phraseId, PROTOCOL.md §5.3: 0..63, the text lives in .properties).
//Ids are part of the wire format: never renumber, only append.
public final class QuickPhrases {

	//phraseId = index; the .properties key is coop.ghost.quickphrases.<key>
	private static final String[] KEYS = {
			"help",      //0 Help!
			"wait",      //1 Wait
			"found_key", //2 Found key
			"trapped",   //3 Trapped
			"coming",    //4 Coming
			"go_on",     //5 Go on
			"danger",    //6 Danger
			"thanks",    //7 Thanks
	};

	public static final int COUNT = KEYS.length;

	private QuickPhrases() {}

	public static boolean valid( int phraseId ) {
		return phraseId >= 0 && phraseId < COUNT;
	}

	//null for an id this client doesn't know (a newer ally client): the receiver ignores it
	public static String key( int phraseId ) {
		return valid( phraseId ) ? KEYS[phraseId] : null;
	}

	public static int idOf( String key ) {
		for (int i = 0; i < KEYS.length; i++) {
			if (KEYS[i].equals( key )) return i;
		}
		return -1;
	}

	public static String text( int phraseId ) {
		String k = key( phraseId );
		return k == null ? null : Messages.get( QuickPhrases.class, k );
	}
}
