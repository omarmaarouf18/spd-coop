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

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.utils.Bundle;

//the Downed state (MEC-02, game-design §5.1) on our hero, saved with them: paralysed and hidden
// from mobs while attached (CoopDowned adds Invulnerability next to it). The window is the
// server's: this buff ends only on REVIVE, on MISSION_END{failed} (D-022) or on DOWNED_EXPIRED,
// which sets expired so the real death runs on the actor thread (never a free revive).
//Turns: a paralysed hero would let the world race through turns, so each of our turns waits
// one forced-turn period of running mission clock (ForcedTurns.WAIT_MS, D-005) on the actor
// thread (DownedPace): the world moves at the pace forced turns give the ally.
public class Downed extends Buff {

	static {
		CoopDowned.install();
	}

	{
		type = buffType.NEGATIVE;
		announced = true;
		revivePersists = true;
	}

	//a free label for the ally's UI (DOWNED.cause)
	String cause = "";
	//DOWNED_EXPIRED came: die on the next act
	volatile boolean expired;
	//attached right now (the actor thread checks it while it waits)
	volatile boolean active;
	//local ms of the last DOWNED we sent (not saved: a resumed client asks again)
	long sentAt = -1;

	@Override
	public boolean attachTo( Char target ) {
		if (super.attachTo( target )) {
			target.paralysed++;
			target.invisible++;
			active = true;
			return true;
		}
		return false;
	}

	@Override
	public void detach() {
		boolean was = active;
		active = false;
		super.detach();
		if (was) {
			target.paralysed--;
			target.invisible--;
		}
	}

	@Override
	public boolean act() {
		if (expired) {
			CoopDowned.expireNow( this );
			return true;
		}
		CoopDowned.resend( this );
		spend( TICK );
		return CoopDowned.pace( this );
	}

	@Override
	public void fx( boolean on ) {
		if (on) target.sprite.add( CharSprite.State.PARALYSED );
		else target.sprite.remove( CharSprite.State.PARALYSED );
	}

	@Override
	public int icon() {
		return BuffIndicator.PARALYSIS;
	}

	private static final String CAUSE = "cause";
	private static final String EXPIRED = "expired";

	@Override
	public void storeInBundle( Bundle bundle ) {
		super.storeInBundle( bundle );
		bundle.put( CAUSE, cause );
		bundle.put( EXPIRED, expired );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		super.restoreFromBundle( bundle );
		cause = bundle.getString( CAUSE );
		expired = bundle.getBoolean( EXPIRED );
	}
}
