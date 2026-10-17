// Pixel Dungeon
// Copyright (C) 2012-2015 Oleg Dolya
//
// Shattered Pixel Dungeon
// Copyright (C) 2014-2026 Evan Debenham
//
// Logic Link PD — Copyright (C) 2026 Logic Link PD contributors
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>

package lobby

import "time"

// limiter is the per-connection token bucket of PROTOCOL.md §9. It is only
// used by the connection's reader goroutine, so it has no lock.
type limiter struct {
	rate, burst float64
	abuse       time.Duration

	tokens    float64
	last      time.Time
	overSince time.Time // when the current run of rejected messages began
	lastDrop  time.Time // when the last message was rejected
	lastErr   time.Time // when the last RATE_LIMITED error was sent
}

func newLimiter(rate, burst int, abuse time.Duration, now time.Time) *limiter {
	return &limiter{rate: float64(rate), burst: float64(burst), abuse: abuse, tokens: float64(burst), last: now}
}

// verdict of one message.
type verdict int

const (
	allow     verdict = iota
	drop              // over the limit: drop it (and maybe send RATE_LIMITED)
	dropAbuse         // over the limit for longer than the abuse window: close
)

func (l *limiter) take(now time.Time) verdict {
	l.tokens += now.Sub(l.last).Seconds() * l.rate
	if l.tokens > l.burst {
		l.tokens = l.burst
	}
	l.last = now
	if l.tokens >= 1 {
		l.tokens--
		// A client just above the limit gets an occasional message through;
		// only a full second without drops ends the abuse window.
		if now.Sub(l.lastDrop) >= time.Second {
			l.overSince = time.Time{}
		}
		return allow
	}
	l.lastDrop = now
	if l.overSince.IsZero() {
		l.overSince = now
	}
	if now.Sub(l.overSince) >= l.abuse {
		return dropAbuse
	}
	return drop
}

// shouldReport reports whether a RATE_LIMITED error may be sent now (at
// most one per second).
func (l *limiter) shouldReport(now time.Time) bool {
	if now.Sub(l.lastErr) < time.Second {
		return false
	}
	l.lastErr = now
	return true
}
