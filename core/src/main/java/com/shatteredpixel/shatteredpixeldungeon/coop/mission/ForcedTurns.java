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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission;

import java.util.function.LongSupplier;

//forced turns (D-005, game-design §4.4): while a server clock runs, a hero that has been waiting
// for input for WAIT_MS gets one automatic wait turn, the same as pressing rest once.
//Pure logic with an injectable clock. onHeroReady() runs on the actor thread (Hero.ready()),
// tick() on the GL thread every frame; tick() returning true means "rest once now".
//The wait only counts while the clock runs: a wait that began before the clock started (or was
// paused) counts from the moment it (re)started, so a resume never forces a turn at once.
public final class ForcedTurns {

	public static final long WAIT_MS = 5_000;

	private final LongSupplier clock;

	//when the hero last started waiting for input; armed until a forced turn uses it
	private volatile long readyAt;
	private volatile boolean armed;
	//when the clock was last seen (re)starting; -1 while not running
	private long runningSince = -1;

	public ForcedTurns( LongSupplier clock ) {
		this.clock = clock;
	}

	//Hero.ready(): the hero now waits for input
	public void onHeroReady() {
		readyAt = clock.getAsLong();
		armed = true;
	}

	public void reset() {
		armed = false;
		runningSince = -1;
	}

	//heroWaiting: our hero is alive and waits for input (Hero.ready);
	//clockRunning: MissionState.clockRunning(linkUp)
	public boolean tick( boolean heroWaiting, boolean clockRunning ) {
		long now = clock.getAsLong();
		if (!clockRunning) {
			runningSince = -1;
			return false;
		}
		if (runningSince < 0) runningSince = now;
		if (!heroWaiting) return false;
		if (!armed) {
			//waiting without a Hero.ready() we saw (or a forced rest hasn't landed yet): count from now
			readyAt = now;
			armed = true;
		}
		if (now - Math.max( readyAt, runningSince ) < WAIT_MS) return false;
		armed = false;
		return true;
	}

	//ms until the next forced turn, for the HUD; -1 when none is pending
	public long msUntilForced( boolean heroWaiting, boolean clockRunning ) {
		if (!clockRunning || !heroWaiting || !armed || runningSince < 0) return -1;
		return Math.max( 0, WAIT_MS - (clock.getAsLong() - Math.max( readyAt, runningSince )) );
	}
}
