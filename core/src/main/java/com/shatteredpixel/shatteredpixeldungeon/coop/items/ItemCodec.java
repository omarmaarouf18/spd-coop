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

package com.shatteredpixel.shatteredpixeldungeon.coop.items;

import com.shatteredpixel.shatteredpixeldungeon.items.Amulet;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.LostBackpack;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.Bomb;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.Key;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;
import com.watabou.utils.Reflection;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//one item <-> the opaque "item" string of DROP_SHARE / TRANSFER: SPD's own Bundle JSON
// ({"item":{"__className":...,...}}), at most 16 KiB (PROTOCOL.md §3 opaque).
//What is never sent (game-design §6A.2): containers (a bag and everything in it), keys (they open
// doors of one world), the Amulet, bombs (a lit fuse lives outside the item), quest items (each
// world runs its own quests) and the lost-backpack marker. The receiver re-checks the same rules
// and only instantiates game classes, so a bad frame can't make the client build anything else.
public final class ItemCodec {

	public static final int MAX_BYTES = 16384;

	private static final String KEY = "item";
	private static final String CLASS_NAME = "__className";
	private static final String GAME_PACKAGE = "com.shatteredpixel.shatteredpixeldungeon.";
	private static final String QUEST_PACKAGE = GAME_PACKAGE + "items.quest.";
	private static final Pattern CLASS_FIELD = Pattern.compile( "\"" + CLASS_NAME + "\"\\s*:\\s*\"([^\"]*)\"" );

	private ItemCodec() {}

	public static boolean shareable( Item item ) {
		return item != null && item.quantity() > 0 && shareable( item.getClass() );
	}

	static boolean shareable( Class<?> cls ) {
		return Item.class.isAssignableFrom( cls )
				&& !Bag.class.isAssignableFrom( cls )
				&& !Key.class.isAssignableFrom( cls )
				&& !Bomb.class.isAssignableFrom( cls )
				&& cls != Amulet.class
				&& cls != LostBackpack.class
				&& !cls.getName().startsWith( QUEST_PACKAGE );
	}

	//null if the item can't be shared or is too big
	public static String encode( Item item ) {
		if (!shareable( item )) return null;
		Bundle bundle = new Bundle();
		bundle.put( KEY, item );
		String text = bundle.toString();
		return text.getBytes( StandardCharsets.UTF_8 ).length <= MAX_BYTES ? text : null;
	}

	//null for anything encode() wouldn't have produced
	public static Item decode( String text ) {
		if (text == null) return null;
		byte[] bytes = text.getBytes( StandardCharsets.UTF_8 );
		if (bytes.length > MAX_BYTES || !text.startsWith( "{" )) return null;

		//every class named anywhere in the item (enchantments, glyphs, ...) must be a game class
		Matcher m = CLASS_FIELD.matcher( text );
		while (m.find()) {
			if (gameBundlable( m.group( 1 ) ) == null) return null;
		}
		try {
			Bundle bundle = Bundle.read( new ByteArrayInputStream( bytes ) );
			if (!bundle.contains( KEY )) return null;
			Class<?> root = gameBundlable( bundle.getBundle( KEY ).getString( CLASS_NAME ) );
			if (root == null || !shareable( root )) return null;
			Bundlable item = bundle.get( KEY );
			return item instanceof Item && ((Item) item).quantity() > 0 ? (Item) item : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static Class<?> gameBundlable( String name ) {
		if (name == null || !name.startsWith( GAME_PACKAGE )) return null;
		try {
			Class<?> cls = Reflection.forNameUnhandled( name );
			return Bundlable.class.isAssignableFrom( cls ) ? cls : null;
		} catch (Exception e) {
			return null;
		}
	}
}
