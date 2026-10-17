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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.noosa.Group;

//the ghost's place in GameScene: above the floor and items, under the mobs and the local hero
// (GameScene adds it between the heap and mob groups). One per GameScene; it rebuilds the
// ghost from the last received GHOST, so the ghost survives level changes, save/load and
// reconnects. It reads level.visited/mapped only: it never reveals a cell we haven't seen.
public class GhostLayer extends Group {

	private static GhostLayer current;

	private final GhostSprite sprite = new GhostSprite();
	//the GHOST the sprite shows now (identity: a new message is a new object)
	private HeroSnapshot shown;

	public GhostLayer() {
		sprite.visible = false;
		add( sprite );
		current = this;
	}

	public static GhostLayer current() {
		return current;
	}

	public GhostSprite sprite() {
		return sprite;
	}

	@Override
	public void update() {
		AllyState ally = CoopGhosts.ally();
		HeroSnapshot g = ally.ghost();
		Level level = Dungeon.level;
		if (g != null && level != null && g != shown) {
			show( g, level );
		}
		sprite.visible = shouldDraw( g, level, Dungeon.depth, Dungeon.branch );
		sprite.setOffline( !ally.online() );
		sprite.setOpaque( CoopGhosts.opaque() );
		super.update();
	}

	private void show( HeroSnapshot g, Level level ) {
		HeroClass cls = CoopGhosts.heroClass( g.heroClass );
		if (cls != null) sprite.setLook( cls, g.tier );
		if (!sprite.hasLook() || !inLevel( g.pos, level )) {
			shown = g;
			return;
		}
		HeroSnapshot prev = shown;
		boolean sameFloor = prev != null && prev.depth == g.depth && prev.branch == g.branch
				&& inLevel( prev.pos, level );
		if (sprite.dead() && !HeroSnapshot.DIE.equals( g.anim )) {
			sprite.idleNow();
		}
		if (sameFloor && prev.pos != g.pos) {
			sprite.moveBetween( prev.pos, g.pos, level.adjacent( prev.pos, g.pos ) );
		} else if (!sameFloor) {
			sprite.placeAt( g.pos );
		}
		if (!HeroSnapshot.RUN.equals( g.anim )) {
			sprite.playAnim( g.anim, -1, -1 );
		}
		shown = g;
	}

	//the rule behind GhostAlly.isVisible(): same depth+branch, and a cell we have seen
	public static boolean shouldDraw( HeroSnapshot g, Level level, int depth, int branch ) {
		if (g == null || level == null || !g.sameFloor( depth, branch )) return false;
		if (!inLevel( g.pos, level )) return false;
		return level.visited[g.pos] || level.mapped[g.pos];
	}

	private static boolean inLevel( int cell, Level level ) {
		return cell >= 0 && cell < level.length();
	}

	@Override
	public void destroy() {
		if (current == this) current = null;
		super.destroy();
	}
}
