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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission.downed;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.watabou.utils.Bundle;

//a Scroll of Recall we read and the server hasn't answered yet (MEC-02), kept on our hero so it
// is saved: the scroll waits here. REVIVE of the target uses it up; a DENY, the target's death,
// the end of their window by other means or no answer at all gives it back.
public class RecallPending extends Buff {

	static {
		CoopDowned.install();
	}

	{
		type = buffType.NEUTRAL;
		revivePersists = true;
	}

	String targetId = "";
	Item scroll;
	//local ms of the USE_RECALL (not saved: a resumed client waits a full period again)
	long sentAt = -1;

	@Override
	public boolean act() {
		CoopDowned.checkPending( this );
		spend( TICK );
		return true;
	}

	private static final String TARGET = "target";
	private static final String SCROLL = "scroll";

	@Override
	public void storeInBundle( Bundle bundle ) {
		super.storeInBundle( bundle );
		bundle.put( TARGET, targetId );
		if (scroll != null) bundle.put( SCROLL, scroll );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		super.restoreFromBundle( bundle );
		targetId = bundle.getString( TARGET );
		scroll = bundle.contains( SCROLL ) ? (Item) bundle.get( SCROLL ) : null;
	}
}
