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

package com.shatteredpixel.shatteredpixeldungeon.coop.net;

import java.util.Random;

//exponential reconnect delay with jitter: attempt n waits between half and all of
// min(base * 2^n, cap). Jitter uses its own java.util.Random, never the game's
// com.watabou.utils.Random, so networking can't disturb seeded generation (AGENTS.md rule 8).
public class Backoff {

	public static final long DEFAULT_BASE_MS = 1000;
	public static final long DEFAULT_CAP_MS = 30_000;

	private final long baseMs;
	private final long capMs;
	private final Random jitter;

	private int attempt = 0;
	//doubled after each attempt and clamped to capMs, so it can never overflow
	private long ceilingMs;

	public Backoff() {
		this( DEFAULT_BASE_MS, DEFAULT_CAP_MS, new Random() );
	}

	public Backoff( long baseMs, long capMs, Random jitter ) {
		if (baseMs <= 0 || capMs < baseMs) {
			throw new IllegalArgumentException( "need 0 < base <= cap" );
		}
		this.baseMs = baseMs;
		this.capMs = capMs;
		this.jitter = jitter;
		this.ceilingMs = baseMs;
	}

	public long nextDelayMs() {
		long ceiling = ceilingMs;
		ceilingMs = Math.min( ceilingMs * 2, capMs );
		attempt++;
		long half = ceiling / 2;
		return half + (long)(jitter.nextDouble() * (ceiling - half));
	}

	public void reset() {
		attempt = 0;
		ceilingMs = baseMs;
	}

	public int attempts() {
		return attempt;
	}
}
