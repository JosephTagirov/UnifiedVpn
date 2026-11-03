// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"time"
)

// Bound process shutdown even if an upstream transport close gets stuck.
// Only the executable's main calls this with os.Exit; library tests own their context.
func watchShutdown(ctx context.Context, done <-chan struct{}, grace time.Duration, force func()) {
	select {
	case <-done:
		return
	case <-ctx.Done():
	}
	timer := time.NewTimer(grace)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
		force()
	}
}
