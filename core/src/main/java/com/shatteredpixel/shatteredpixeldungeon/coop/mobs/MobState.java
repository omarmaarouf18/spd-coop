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

package com.shatteredpixel.shatteredpixeldungeon.coop.mobs;

import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.Arrays;
import java.util.LinkedHashSet;

//what this player's world knows about the shared mobs (MOB-01), saved in CoopRun
// ("coop_mob_kills") so a reload never relocates a floor twice, never grants XP twice and never
// forgets a kill that hasn't reached the server yet. Ordered collections only.
public class MobState implements Bundlable {

	//floors ("depth_branch") this player has entered: their mobs were relocated
	public final LinkedHashSet<String> entered = new LinkedHashSet<>();
	//floors this player entered and then left: kills learned for them later give no XP (D-023)
	public final LinkedHashSet<String> left = new LinkedHashSet<>();
	//the floor this player is on ("" before the first entry)
	public String current = "";
	//mobNetIds known dead in this run (our kills and the ally's)
	public final LinkedHashSet<String> dead = new LinkedHashSet<>();
	//mobNetIds whose XP this hero already got (own kill, or granted for the ally's)
	public final LinkedHashSet<String> xp = new LinkedHashSet<>();
	//the ally's kills on floors this player hasn't entered yet: XP on arrival
	public final LinkedHashSet<String> pendingXp = new LinkedHashSet<>();
	//our kills the server hasn't confirmed yet (MOB_KILLED not sent, or no MOB_DEAD back)
	public final LinkedHashSet<String> unsent = new LinkedHashSet<>();
	//saved before MOB-01: the floors already generated count as entered (see CoopMobs)
	public boolean legacy;

	private static final String ENTERED = "entered";
	private static final String LEFT = "left";
	private static final String CURRENT = "current";
	private static final String DEAD = "dead";
	private static final String XP = "xp";
	private static final String PENDING_XP = "pending_xp";
	private static final String UNSENT = "unsent";

	@Override
	public void storeInBundle( Bundle bundle ) {
		bundle.put( ENTERED, entered.toArray( new String[0] ) );
		bundle.put( LEFT, left.toArray( new String[0] ) );
		bundle.put( CURRENT, current );
		bundle.put( DEAD, dead.toArray( new String[0] ) );
		bundle.put( XP, xp.toArray( new String[0] ) );
		bundle.put( PENDING_XP, pendingXp.toArray( new String[0] ) );
		bundle.put( UNSENT, unsent.toArray( new String[0] ) );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		read( bundle, ENTERED, entered );
		read( bundle, LEFT, left );
		current = bundle.contains( CURRENT ) ? bundle.getString( CURRENT ) : "";
		read( bundle, DEAD, dead );
		read( bundle, XP, xp );
		read( bundle, PENDING_XP, pendingXp );
		read( bundle, UNSENT, unsent );
	}

	private static void read( Bundle bundle, String key, LinkedHashSet<String> into ) {
		into.clear();
		if (bundle.contains( key )) into.addAll( Arrays.asList( bundle.getStringArray( key ) ) );
	}
}
