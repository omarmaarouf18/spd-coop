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

import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.journal.Document;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.utils.Bundle;

import java.util.ArrayList;

//vanilla level generation reads the player's profile (TST-01 / NET-01c findings):
// - EntranceRoom.placeEarlyGuidePages drops the guidebook / a guide page with an UNSEEDED generator
//   when GUIDE_INTRO is unread or SPDSettings.intro() is on, and that item shifts later seeded passes;
// - RegularLevel, LaboratoryRoom and RegularPainter drop guide/alchemy/lore pages by which pages
//   the player has already found.
//So two players with different profiles would get different worlds from one seed. In co-op runs
// every level is generated with a neutral profile instead: all documents read, intro off. The
// player's real journal and setting are restored right after, so nothing is lost; the price is
// that guide and lore pages don't drop in co-op runs. (Bones.get has its own COOP: hook.)
final class CoopProfile {

	//the same key Document.store/restore use
	private static final String DOCUMENTS = "documents";

	private CoopProfile() {}

	static void generate( Level level ) {
		Bundle saved = new Bundle();
		Document.store( saved );
		boolean intro = SPDSettings.intro();
		try {
			//Document.restore sets page states directly: unlike readPage/findPage it can't
			// unlock catalog badges while the neutral profile is in place
			Document.restore( allRead() );
			if (intro) SPDSettings.intro( false );
			level.create();
		} finally {
			//restore only adds found/read states, so clear first; deletePage has no side effects
			for (Document doc : Document.values()) {
				for (String page : new ArrayList<>( doc.pageNames() )) {
					doc.deletePage( page );
				}
			}
			Document.restore( saved );
			if (intro) SPDSettings.intro( true );
		}
	}

	private static Bundle allRead() {
		Bundle docs = new Bundle();
		for (Document doc : Document.values()) {
			Bundle pages = new Bundle();
			for (String page : doc.pageNames()) {
				pages.put( page, Document.READ );
			}
			docs.put( doc.name(), pages );
		}
		Bundle root = new Bundle();
		root.put( DOCUMENTS, docs );
		return root;
	}
}
