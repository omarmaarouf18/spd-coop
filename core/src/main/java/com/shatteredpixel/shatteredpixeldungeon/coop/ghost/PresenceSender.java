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

//when to send our GHOST (game-design §8, PROTOCOL.md §5.3). Pure logic, GL thread only.
//- offer() after each resolved hero action: sends if the message changed, at most once per hero turn;
//- a change that lands on an already-sent turn waits (the newest one wins) for the next turn or the tick;
//- tick() resends the latest state every IDLE_RESEND_MS while nothing changes, with anim "idle"
//  (never replays an attack), so an ally who (re)connects finds us within a few seconds;
//- a send the socket refuses stays pending and is retried by tick() (once per RETRY_MS);
//- force() makes the next offer/tick send right away (level shown, reconnect, ally back online).
public final class PresenceSender {

	public static final long IDLE_RESEND_MS = 5_000;
	public static final long RETRY_MS = 1_000;

	public interface Sink {
		//false when not sent (no live session, socket down)
		boolean send( HeroSnapshot s );
	}

	private final Sink sink;

	private HeroSnapshot lastSent;
	private float lastSentTurn = Float.NaN;
	private long lastSentAt;
	private HeroSnapshot pending;
	private long lastAttempt = Long.MIN_VALUE / 2;
	private boolean forced;

	public PresenceSender( Sink sink ) {
		this.sink = sink;
	}

	public HeroSnapshot lastSent() {
		return lastSent;
	}

	public HeroSnapshot pending() {
		return pending;
	}

	public void force() {
		forced = true;
	}

	public void reset() {
		lastSent = null;
		lastSentTurn = Float.NaN;
		pending = null;
		forced = false;
		lastAttempt = Long.MIN_VALUE / 2;
	}

	public void offer( HeroSnapshot s, long now ) {
		boolean urgent = forced || HeroSnapshot.DIE.equals( s.anim );
		if (!urgent && s.sameAs( lastSent )) {
			pending = null;
			return;
		}
		if (!urgent && s.turn == lastSentTurn) {
			pending = s;
			return;
		}
		attempt( s, now );
	}

	public void tick( long now ) {
		if (pending != null) {
			boolean newTurn = pending.turn != lastSentTurn;
			if ((forced || newTurn || now - lastSentAt >= IDLE_RESEND_MS) && now - lastAttempt >= RETRY_MS) {
				attempt( pending, now );
			}
			return;
		}
		if (lastSent == null) return;
		if (forced || now - lastSentAt >= IDLE_RESEND_MS) {
			if (now - lastAttempt < RETRY_MS) return;
			String idle = HeroSnapshot.DIE.equals( lastSent.anim ) ? HeroSnapshot.DIE : HeroSnapshot.IDLE;
			attempt( lastSent.withAnim( idle ), now );
		}
	}

	private void attempt( HeroSnapshot s, long now ) {
		lastAttempt = now;
		if (sink.send( s )) {
			lastSent = s;
			lastSentTurn = s.turn;
			lastSentAt = now;
			pending = null;
			forced = false;
		} else {
			pending = s;
		}
	}
}
