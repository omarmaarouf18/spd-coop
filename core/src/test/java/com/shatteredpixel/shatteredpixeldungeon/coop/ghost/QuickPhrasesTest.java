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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

//QUICK.phraseId <-> .properties keys: the ids are wire format and must never move
class QuickPhrasesTest {

	//phraseId -> (key, English text), game-design §3
	private static final String[][] TABLE = {
			{ "help", "Help!" },
			{ "wait", "Wait" },
			{ "found_key", "Found key" },
			{ "trapped", "Trapped" },
			{ "coming", "Coming" },
			{ "go_on", "Go on" },
			{ "danger", "Danger" },
			{ "thanks", "Thanks" },
	};

	@Test
	void allEightIdsMapToTheirKeys() {
		assertEquals( 8, QuickPhrases.COUNT );
		for (int id = 0; id < TABLE.length; id++) {
			assertEquals( TABLE[id][0], QuickPhrases.key( id ), "phraseId " + id );
			assertEquals( id, QuickPhrases.idOf( TABLE[id][0] ) );
		}
		assertNull( QuickPhrases.key( -1 ) );
		assertNull( QuickPhrases.key( 8 ), "an id from a newer client is ignored" );
		assertFalse( QuickPhrases.valid( 63 ) );
	}

	@Test
	void everyKeyIsInTheEnglishProperties() throws Exception {
		Properties p = new Properties();
		try (Reader r = new InputStreamReader( Files.newInputStream( Paths.get( "messages/windows/windows.properties" ) ),
				StandardCharsets.UTF_8 )) {
			p.load( r );
		}
		for (int id = 0; id < TABLE.length; id++) {
			assertEquals( TABLE[id][1], p.getProperty( "coop.ghost.quickphrases." + TABLE[id][0] ), "phraseId " + id );
		}
	}

	@Test
	void textComesFromMessages() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		for (int id = 0; id < TABLE.length; id++) {
			assertEquals( TABLE[id][1], QuickPhrases.text( id ), "phraseId " + id );
		}
		assertNull( QuickPhrases.text( 40 ) );
	}
}
