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

//server time ↔ local time (MEC-00). Each WELCOME/PONG gives a sample serverTime − localReceive.
// That sample is the true offset minus the one-way delay of that message, so the largest recent
// sample is the least delayed one: we keep the max of the last SAMPLES. (No RTT/2 correction:
// the hook carries no PING send time; the residual error is the smallest one-way delay seen.)
//A WELCOME starts over: it may be another server process (new epoch) with another clock.
public final class ServerClock {

	//PONGs come every 10 s (CoopSession.PING_INTERVAL_MS): about a minute of samples
	public static final int SAMPLES = 6;

	private final long[] samples = new long[SAMPLES];
	private int count;
	private int next;

	public void reset() {
		count = 0;
		next = 0;
	}

	public void onServerTime( long serverTime, long localNow, boolean welcome ) {
		if (welcome) reset();
		samples[next] = serverTime - localNow;
		next = (next + 1) % SAMPLES;
		count = Math.min( SAMPLES, count + 1 );
	}

	public boolean known() {
		return count > 0;
	}

	//server time − local time; 0 before the first sample
	public long offset() {
		if (count == 0) return 0;
		long best = Long.MIN_VALUE;
		for (int i = 0; i < count; i++) best = Math.max( best, samples[i] );
		return best;
	}

	public long serverNow( long localNow ) {
		return localNow + offset();
	}

	public long toLocal( long serverTime ) {
		return serverTime - offset();
	}
}
