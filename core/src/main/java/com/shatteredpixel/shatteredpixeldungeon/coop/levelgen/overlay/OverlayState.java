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

package com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.overlay;

import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.Arrays;
import java.util.LinkedHashSet;

//what the per-player overlay (DET-02) has already done in this player's world, saved in CoopRun
// ("coop_overlay") so a reload never spawns anything twice. Ordered collections only.
public class OverlayState implements Bundlable {

	//floors ("depth_branch") this player has entered at least once: their overlay is applied
	public final LinkedHashSet<String> floors = new LinkedHashSet<>();
	//bag classes this player's shops have offered so far (the per-player ChooseBag)
	public final LinkedHashSet<String> bags = new LinkedHashSet<>();
	//saved before DET-02: the floors already generated count as entered (see CoopOverlay)
	public boolean legacy;

	private static final String FLOORS = "floors";
	private static final String BAGS = "bags";

	@Override
	public void storeInBundle( Bundle bundle ) {
		bundle.put( FLOORS, floors.toArray( new String[0] ) );
		bundle.put( BAGS, bags.toArray( new String[0] ) );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		floors.clear();
		bags.clear();
		if (bundle.contains( FLOORS )) floors.addAll( Arrays.asList( bundle.getStringArray( FLOORS ) ) );
		if (bundle.contains( BAGS )) bags.addAll( Arrays.asList( bundle.getStringArray( BAGS ) ) );
	}
}
