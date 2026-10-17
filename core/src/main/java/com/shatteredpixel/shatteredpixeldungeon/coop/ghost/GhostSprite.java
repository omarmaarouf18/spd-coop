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

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.watabou.noosa.Game;
import com.watabou.noosa.TextureFilm;
import com.watabou.utils.PointF;

//the ally drawn in our world: the hero spritesheet + armor tier of the ally's class, translucent
// and tinted. Never linked to a Char (ch stays null) and never touches Dungeon.hero or its
// sprite: HeroSprite reads Dungeon.hero in its constructor and relinks Dungeon.hero.sprite, which
// was the v3.3.8 prototype bug. Motion is our own tween, not CharSprite's (it would call back into
// a Char). Nothing here draws gameplay Random.
public class GhostSprite extends CharSprite {

	//HeroSprite's frame size (private there)
	private static final int FRAME_WIDTH = 12;
	private static final int FRAME_HEIGHT = 15;
	private static final int RUN_FRAMERATE = 20;

	public static final int TINT = 0x66CCFF;
	public static final float ALPHA = 0.5f;
	public static final float OFFLINE_ALPHA = 0.25f;
	//how long a one-cell move takes (CharSprite.DEFAULT_MOVE_INTERVAL)
	private static final float MOVE_TIME = 0.1f;

	private HeroClass heroClass;
	private int tier = -1;

	private boolean offline;
	private boolean opaque;

	private final PointF moveFrom = new PointF();
	private final PointF moveTo = new PointF();
	private float moveLeft;

	public GhostSprite() {
		super();
	}

	//class/armor from the ally's GHOST; cheap when nothing changed
	public void setLook( HeroClass cls, int armorTier ) {
		if (cls == heroClass && armorTier == tier) return;
		heroClass = cls;
		tier = armorTier;
		texture( cls.spritesheet() );
		TextureFilm film = new TextureFilm( HeroSprite.tiers(), armorTier, FRAME_WIDTH, FRAME_HEIGHT );

		idle = new Animation( 1, true );
		idle.frames( film, 0, 0, 0, 1, 0, 0, 1, 1 );

		run = new Animation( RUN_FRAMERATE, true );
		run.frames( film, 2, 3, 4, 5, 6, 7 );

		die = new Animation( 20, false );
		die.frames( film, 8, 9, 10, 11, 12, 11 );

		attack = new Animation( 15, false );
		attack.frames( film, 13, 14, 15, 0 );

		zap = attack.clone();

		operate = new Animation( 8, false );
		operate.frames( film, 16, 17, 16, 17 );

		curAnim = null;
		play( idle );
		applyLook();
	}

	public boolean hasLook() {
		return heroClass != null;
	}

	public void setOffline( boolean value ) {
		if (offline == value) return;
		offline = value;
		applyLook();
	}

	public void setOpaque( boolean value ) {
		if (opaque == value) return;
		opaque = value;
		applyLook();
	}

	public boolean opaque() {
		return opaque;
	}

	private void applyLook() {
		resetColor();
		if (offline) {
			//greyed out: last known place of an ally who dropped
			tint( 0x808080, 0.8f );
			alpha( OFFLINE_ALPHA );
		} else if (opaque) {
			//physical co-presence (Twin Switches room, game-design §6A.5)
			tint( TINT, 0.15f );
			alpha( 1f );
		} else {
			tint( TINT, 0.45f );
			alpha( ALPHA );
		}
	}

	//jump to a cell without animating
	public void placeAt( int cell ) {
		moveLeft = 0;
		isMoving = false;
		place( cell );
	}

	//smooth one-step move; a longer jump (teleport, stairs) just places
	public void moveBetween( int from, int to, boolean adjacent ) {
		if (!adjacent) {
			placeAt( to );
			return;
		}
		turnTo( from, to );
		moveFrom.set( isMoving ? point() : worldToCamera( from ) );
		moveTo.set( worldToCamera( to ) );
		point( moveFrom );
		moveLeft = MOVE_TIME;
		isMoving = true;
		play( run );
	}

	public void playAnim( String anim, int facing, int from ) {
		if (facing >= 0 && from >= 0 && facing != from) turnTo( from, facing );
		if (HeroSnapshot.ATTACK.equals( anim )) {
			play( attack );
		} else if (HeroSnapshot.OPERATE.equals( anim )) {
			play( operate );
		} else if (HeroSnapshot.ZAP.equals( anim )) {
			play( zap );
		} else if (HeroSnapshot.DIE.equals( anim )) {
			play( die );
		} else if (HeroSnapshot.IDLE.equals( anim ) && !isMoving) {
			idleNow();
		}
	}

	//back from a "die" (the ally revived/reloaded): CharSprite.play never interrupts die
	public void idleNow() {
		curAnim = null;
		play( idle );
	}

	public boolean dead() {
		return curAnim == die;
	}

	@Override
	public void update() {
		super.update();
		if (moveLeft > 0) {
			moveLeft -= Game.elapsed;
			float p = moveLeft <= 0 ? 1f : 1f - moveLeft / MOVE_TIME;
			point( new PointF( moveFrom.x + (moveTo.x - moveFrom.x) * p, moveFrom.y + (moveTo.y - moveFrom.y) * p ) );
			if (moveLeft <= 0) {
				isMoving = false;
				if (curAnim == run) play( idle );
			}
		}
	}

	//CharSprite would call back into ch (null here) after attack/operate
	@Override
	public synchronized void onComplete( Animation anim ) {
		if (anim == attack || anim == operate || anim == zap) {
			play( idle );
		}
	}
}
