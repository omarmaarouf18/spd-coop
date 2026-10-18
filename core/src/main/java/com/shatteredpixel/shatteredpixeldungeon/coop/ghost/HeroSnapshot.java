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

//what one GHOST message says about a hero (PROTOCOL.md §5.3), plus the hero's turn it was taken on.
//Immutable: taken on the actor thread, handed to the GL thread.
public final class HeroSnapshot {

	public static final String IDLE = "idle";
	public static final String RUN = "run";
	public static final String ATTACK = "attack";
	public static final String OPERATE = "operate";
	public static final String ZAP = "zap";
	public static final String DIE = "die";

	public final int depth;
	public final int branch;
	public final int pos;
	public final String anim;
	public final int hpPct;
	public final String heroClass;
	public final int tier;
	//Actor.now() when the hero's action resolved: two snapshots of the same turn share it
	public final float turn;

	public HeroSnapshot( int depth, int branch, int pos, String anim, int hpPct, String heroClass, int tier, float turn ) {
		this.depth = depth;
		this.branch = branch;
		this.pos = pos;
		this.anim = anim;
		this.hpPct = Math.max( 0, Math.min( 100, hpPct ) );
		this.heroClass = heroClass;
		this.tier = Math.max( 0, Math.min( 6, tier ) );
		this.turn = turn;
	}

	public static int hpPct( int hp, int ht ) {
		if (ht <= 0 || hp <= 0) return 0;
		return Math.max( 1, Math.min( 100, Math.round( 100f * hp / ht ) ) );
	}

	public HeroSnapshot withAnim( String a ) {
		return new HeroSnapshot( depth, branch, pos, a, hpPct, heroClass, tier, turn );
	}

	public boolean sameFloor( int d, int b ) {
		return depth == d && branch == b;
	}

	//same message on the wire (the turn is not sent)
	public boolean sameAs( HeroSnapshot o ) {
		return o != null && depth == o.depth && branch == o.branch && pos == o.pos && anim.equals( o.anim )
				&& hpPct == o.hpPct && heroClass.equals( o.heroClass ) && tier == o.tier;
	}

	@Override
	public String toString() {
		return depth + "/" + branch + " pos " + pos + " " + anim + " " + hpPct + "% " + heroClass + " t" + tier;
	}
}
