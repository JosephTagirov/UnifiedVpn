// SPDX-License-Identifier: GPL-3.0-or-later
package transport

import (
	"testing"
	"time"
)

func TestSessionStripeRetryCounterDoesNotWrap(t *testing.T) {
	var q sender
	q.init()
	now := time.Now()
	if !q.enqueue([]byte("regression fixture")) || len(q.pull(0, 1, now)) != 1 {
		t.Fatal("initial send failed")
	}
	for retry := 0; retry < 1024; retry++ {
		now = now.Add(3 * time.Second)
		if !q.detectLoss(now) || len(q.pull(0, 1, now)) != 1 {
			t.Fatalf("retransmission stopped at retry %d", retry)
		}
		entry := q.ring[q.seq%txRingSize]
		if entry.tries < 2 || (retry >= 253 && entry.tries != 255) {
			t.Fatalf("retry counter wrapped at retry %d: %d", retry, entry.tries)
		}
	}
	if q.rtx != 1024 {
		t.Fatal("saturation changed the aggregate retransmission counter")
	}
}

func TestSessionStripeZeroTriesCannotCauseNegativeShift(t *testing.T) {
	var q sender
	q.init()
	now := time.Now()
	q.enqueue([]byte("regression fixture"))
	q.pull(0, 1, now)
	q.ring[q.seq%txRingSize].tries = 0
	if !q.detectLoss(now.Add(3 * time.Second)) {
		t.Fatal("zero-count in-flight entry was not retried")
	}
}
