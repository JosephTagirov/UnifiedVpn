// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"io"
	"testing"
	"time"
)

func TestClosingBrowserBridgeDoesNotTurnFailureIntoUserCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r, w := io.Pipe()
	defer w.Close()
	b := newStdioBrowserBootstrap(r, io.Discard, cancel)
	b.Close()
	select {
	case <-b.lines:
	case <-time.After(time.Second):
		t.Fatal("input reader did not close")
	}
	if ctx.Err() != nil {
		t.Fatal("internal close masked the native startup error")
	}
}

func TestInheritedControlCancelsPendingBrowserAndPeerWait(t *testing.T) {
	for _, stop := range []string{"STOP\n", "STOP\r\n", ""} {
		t.Run(stop, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			r, w := io.Pipe()
			b := &stdioBrowserBootstrap{input: r, output: io.Discard, lines: make(chan browserLine, 1), done: make(chan struct{}), onStop: cancel}
			defer b.Close()
			go b.readLines()
			result := make(chan error, 1)
			go func() { _, err := b.Cookies(ctx); result <- err }()
			if stop != "" {
				_, _ = io.WriteString(w, stop)
			}
			_ = w.Close()
			select {
			case <-ctx.Done():
			case <-time.After(time.Second):
				t.Fatal("STOP/EOF did not cancel run")
			}
			select {
			case err := <-result:
				if err == nil {
					t.Fatal("stopped browser accepted proof")
				}
			case <-time.After(time.Second):
				t.Fatal("browser wait survived STOP")
			}
		})
	}
}

func TestShutdownWatchdogBoundsStuckCleanup(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	forced := make(chan struct{})
	go watchShutdown(ctx, make(chan struct{}), 20*time.Millisecond, func() { close(forced) })
	cancel()
	select {
	case <-forced:
	case <-time.After(time.Second):
		t.Fatal("stuck shutdown was not bounded")
	}
}

func TestShutdownWatchdogAllowsCleanExit(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done, finished, forced := make(chan struct{}), make(chan struct{}), make(chan struct{})
	go func() { watchShutdown(ctx, done, time.Second, func() { close(forced) }); close(finished) }()
	cancel()
	close(done)
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("clean shutdown blocked")
	}
	select {
	case <-forced:
		t.Fatal("clean shutdown forced exit")
	default:
	}
}
