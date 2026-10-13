// SPDX-License-Identifier: GPL-3.0-or-later
package transport

import (
	"bytes"
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type blockedBatchSender struct {
	fakeTransport
	entered chan struct{}
	cancel  chan struct{}
	once    sync.Once
	stops   atomic.Int32
}

func (s *blockedBatchSender) Send([]byte) error {
	s.once.Do(func() { close(s.entered) })
	<-s.cancel
	return context.Canceled
}

func (s *blockedBatchSender) Stop() error {
	if s.stops.Add(1) == 1 {
		close(s.cancel)
	}
	return nil
}

func TestUnifiedBatchBoundedQueueCopiesAndDiscardsOnStop(t *testing.T) {
	inner := &blockedBatchSender{entered: make(chan struct{}), cancel: make(chan struct{})}
	batch := NewBatchedTransport(inner)
	if err := batch.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = batch.Stop() })
	if err := batch.Send(make([]byte, 65535)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-inner.entered:
	case <-time.After(time.Second):
		t.Fatal("batch worker did not start")
	}
	packet := []byte{1, 2, 3}
	if err := batch.Send(packet); err != nil {
		t.Fatal(err)
	}
	packet[0] = 255
	got := <-batch.queue
	if !bytes.Equal(got, []byte{1, 2, 3}) {
		t.Fatal("queued packet aliases caller-owned memory")
	}
	batch.releaseBatch([][]byte{got})
	ack := make([]byte, 64)
	for i := 0; i < batchQueueDepth; i++ {
		if err := batch.Send(ack); err != nil {
			t.Fatal(err)
		}
	}
	if batch.Send(ack) == nil {
		t.Fatal("queue limit not enforced")
	}
	stopped := make(chan error, 1)
	go func() { stopped <- batch.Stop() }()
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("Stop did not cancel blocked inner Send")
	}
	if err := batch.Stop(); err != nil || inner.stops.Load() != 1 || len(batch.queue) != 0 || batchBufferedBytes(batch) != 0 {
		t.Fatal("repeated Stop did not preserve single cleanup or discard queued data")
	}
	if batch.Send(packet) == nil || batch.Start() == nil || batch.IsConnected() {
		t.Fatal("stopped codec was reused")
	}
}

func TestUnifiedBatchConcurrentSendStop(t *testing.T) {
	batch := NewBatchedTransport(&fakeTransport{loopback: true})
	batch.Receive(func([]byte) {})
	if err := batch.Start(); err != nil {
		t.Fatal(err)
	}
	var senders sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < 12; i++ {
		senders.Add(1)
		go func() {
			defer senders.Done()
			<-start
			for j := 0; j < 100; j++ {
				_ = batch.Send([]byte{1, 2, 3})
			}
		}()
	}
	for i := 0; i < 4; i++ {
		senders.Add(1)
		go func() { defer senders.Done(); <-start; _ = batch.Stop() }()
	}
	close(start)
	senders.Wait()
	if len(batch.queue) != 0 || batchBufferedBytes(batch) != 0 || batch.Send([]byte{1}) == nil {
		t.Fatal("Stop left queued data or accepted a new send")
	}
}

func TestUnifiedBatchConcurrentStartStop(t *testing.T) {
	for iteration := 0; iteration < 30; iteration++ {
		batch := NewBatchedTransport(&fakeTransport{})
		start := make(chan struct{})
		done := make(chan struct{}, 2)
		go func() { <-start; _ = batch.Start(); done <- struct{}{} }()
		go func() { <-start; _ = batch.Stop(); done <- struct{}{} }()
		close(start)
		for i := 0; i < 2; i++ {
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("concurrent Start/Stop did not finish")
			}
		}
		if batch.IsConnected() || batch.Send([]byte{1}) == nil || batch.Start() == nil {
			t.Fatal("startup resurrected a stopped codec")
		}
	}
}

func TestUnifiedBatchLimitsCannotBeChangedByEnvironment(t *testing.T) {
	for _, name := range []string{"OPENFLUX_BATCH_BYTES", "OPENFLUX_BATCH_COUNT", "OPENFLUX_BATCH_LINGER_MS"} {
		t.Setenv(name, "999999999")
	}
	batch := NewBatchedTransport(&fakeTransport{})
	if cap(batch.queue) != 4096 || maxBufferedBatchBytes != 2<<20 || batch.maxBatchBytes != 32768 || batch.maxBatchCount != 64 || batch.lingerMs != 2 {
		t.Fatal("fixed production limits changed")
	}
	if batch.Send([]byte{1}) == nil {
		t.Fatal("send before startup accepted")
	}
	if err := batch.Start(); err != nil {
		t.Fatal(err)
	}
	defer batch.Stop()
	if batch.Start() == nil || batch.Send(nil) == nil || batch.Send(make([]byte, 65536)) == nil {
		t.Fatal("invalid lifecycle or packet size accepted")
	}
}

func TestUnifiedBatchStrictByteBoundaryAndOversizedSingleton(t *testing.T) {
	inner := &fakeTransport{loopback: true}
	batch := NewBatchedTransport(inner)
	packets := [][]byte{bytes.Repeat([]byte{1}, 20000), bytes.Repeat([]byte{2}, 20000), bytes.Repeat([]byte{3}, 65535), {4, 5}}
	delivered := make(chan []byte, len(packets))
	batch.Receive(func(packet []byte) { delivered <- packet })
	if err := batch.Start(); err != nil {
		t.Fatal(err)
	}
	defer batch.Stop()
	for _, packet := range packets {
		if err := batch.Send(packet); err != nil {
			t.Fatal(err)
		}
	}
	for _, want := range packets {
		select {
		case got := <-delivered:
			if !bytes.Equal(got, want) {
				t.Fatal("packet order or content changed at a batch boundary")
			}
		case <-time.After(time.Second):
			t.Fatal("batch packet was lost")
		}
	}
	inner.mu.Lock()
	defer inner.mu.Unlock()
	for _, frame := range inner.sent {
		decoded, err := decodeBatch(frame)
		if err != nil {
			t.Fatal(err)
		}
		if len(frameBatch(decoded)) > defaultMaxBatchBytes && len(decoded) != 1 {
			t.Fatal("batch overshot its byte limit")
		}
	}
}

func TestUnifiedBatchDecoderRejectsMalformedAndOversizedFrames(t *testing.T) {
	bomb := zstdEnc.EncodeAll(make([]byte, maxFrameBytes+1), nil)
	tooMany := append([]byte{batchFormatVersion, 0}, make([]byte, 2*(maxFrameRecords+1))...)
	for _, frame := range [][]byte{
		{batchFormatVersion, 0x80}, {batchFormatVersion, batchFlagZstd | 2},
		{0, 0, 0, 1, 42}, {batchFormatVersion, 0, 0},
		{batchFormatVersion, 0, 0, 1, 42, 0},
		{batchFormatVersion, batchFlagZstd, 1, 2, 3},
		append([]byte{batchFormatVersion, batchFlagZstd}, bomb...),
		append([]byte{batchFormatVersion, 0}, make([]byte, maxFrameBytes+1)...),
		tooMany,
	} {
		if packets, err := decodeBatch(frame); err == nil || packets != nil {
			t.Fatal("invalid frame partially delivered or accepted")
		}
	}
	if frameBatch([][]byte{make([]byte, 65536)}) != nil || encodeBatch(make([][]byte, maxFrameRecords+1)) != nil {
		t.Fatal("encoder silently truncated record lengths or exceeded count limit")
	}
	large := make([][]byte, 17)
	for i := range large {
		large[i] = make([]byte, 65535)
	}
	if encodeBatch(large) != nil {
		t.Fatal("encoder exceeded byte limit")
	}
	assertBatchRoundTrip(t, [][]byte{bytes.Repeat([]byte{255}, 65535)})
}

func TestUnifiedBatchDecoderDoesNotAliasCallerFrames(t *testing.T) {
	frame := []byte{batchFormatVersion, 0, 0, 3, 1, 2, 3}
	packets, err := decodeBatch(frame)
	if err != nil {
		t.Fatal(err)
	}
	frame[4] = 255
	if !bytes.Equal(packets[0], []byte{1, 2, 3}) {
		t.Fatal("decoded packet aliases caller frame")
	}
}

func batchBufferedBytes(batch *BatchedTransport) int {
	batch.lifecycle.Lock()
	defer batch.lifecycle.Unlock()
	return batch.bufferedBytes
}

func waitBatchDrained(t *testing.T, batch *BatchedTransport) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for batchBufferedBytes(batch) != 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if batchBufferedBytes(batch) != 0 {
		t.Fatal("accepted packets retained their byte reservation after delivery")
	}
}

func startBlockedBatch(t *testing.T) (*BatchedTransport, *blockedBatchSender) {
	t.Helper()
	inner := &blockedBatchSender{entered: make(chan struct{}), cancel: make(chan struct{})}
	batch := NewBatchedTransport(inner)
	if err := batch.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = batch.Stop() })
	if err := batch.Send(make([]byte, 65535)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-inner.entered:
	case <-time.After(time.Second):
		t.Fatal("batch worker did not enter the blocking inner Send")
	}
	return batch, inner
}

func TestUnifiedBatchByteBudgetExactBoundary(t *testing.T) {
	batch, _ := startBlockedBatch(t)
	for remaining := maxBufferedBatchBytes - batchBufferedBytes(batch); remaining > 0; {
		size := remaining - 2
		if size > 65535 {
			size = 65535
		}
		if size < 1 {
			t.Fatal("test cannot fill the byte boundary exactly")
		}
		if err := batch.Send(make([]byte, size)); err != nil {
			t.Fatal(err)
		}
		remaining = maxBufferedBatchBytes - batchBufferedBytes(batch)
	}
	if batchBufferedBytes(batch) != maxBufferedBatchBytes || len(batch.queue) >= cap(batch.queue) {
		t.Fatal("byte limit test hit a packet-count limit instead")
	}
	if batch.Send([]byte{1}) == nil || batch.Send(make([]byte, 65536)) == nil {
		t.Fatal("a full byte budget or oversized packet was accepted")
	}
	if got := batchBufferedBytes(batch); got != maxBufferedBatchBytes {
		t.Fatal("a rejected send changed the reserved byte count")
	}
	if err := batch.Stop(); err != nil || batchBufferedBytes(batch) != 0 || len(batch.queue) != 0 {
		t.Fatal("Stop retained a blocked frame or queued packet reservation")
	}
}

func TestUnifiedBatchByteBudgetIncludesPendingAndCollectingPackets(t *testing.T) {
	inner := &blockedBatchSender{entered: make(chan struct{}), cancel: make(chan struct{})}
	batch := NewBatchedTransport(inner)
	// Prequeue both packets before starting the collector to force a strict
	// frame boundary: one is being sent and the second is held as pending.
	batch.running.Store(true)
	t.Cleanup(func() { _ = batch.Stop() })
	for i := 0; i < 2; i++ {
		if err := batch.Send(make([]byte, 20000)); err != nil {
			t.Fatal(err)
		}
	}
	batch.wg.Add(1)
	go batch.flushLoop()
	select {
	case <-inner.entered:
	case <-time.After(time.Second):
		t.Fatal("collector did not send its first frame")
	}
	if len(batch.queue) != 0 || batchBufferedBytes(batch) != 40004 {
		t.Fatal("sending or pending packets disappeared from the byte budget")
	}
	packet := make([]byte, 65535)
	count := (maxBufferedBatchBytes - 40004) / (len(packet) + 2)
	for i := 0; i < count; i++ {
		if err := batch.Send(packet); err != nil {
			t.Fatal(err)
		}
	}
	if batch.Send(packet) == nil || batchBufferedBytes(batch) != 40004+count*(len(packet)+2) {
		t.Fatal("pending packets did not reduce the available byte budget")
	}
	_ = batch.Stop()
	if batchBufferedBytes(batch) != 0 || len(batch.queue) != 0 {
		t.Fatal("Stop did not release queued, pending and collecting packets")
	}
}

func TestUnifiedBatchCanBufferTCPWindowOfMTUPackets(t *testing.T) {
	batch, _ := startBlockedBatch(t)
	packet := make([]byte, 1500)
	for i := 0; i < 700; i++ {
		if err := batch.Send(packet); err != nil {
			t.Fatalf("MTU burst rejected at packet %d: %v", i, err)
		}
	}
	if batchBufferedBytes(batch) != 65537+700*1502 {
		t.Fatal("MTU burst reservation is inaccurate")
	}
}

type failingBatchSender struct {
	fakeTransport
	calls atomic.Int32
}

func (s *failingBatchSender) Send([]byte) error {
	s.calls.Add(1)
	return context.Canceled
}

func TestUnifiedBatchSendErrorsReleaseByteBudget(t *testing.T) {
	inner := &failingBatchSender{}
	batch := NewBatchedTransport(inner)
	if err := batch.Start(); err != nil {
		t.Fatal(err)
	}
	defer batch.Stop()
	for i := 0; i < 700; i++ {
		if err := batch.Send(make([]byte, 1500)); err != nil {
			t.Fatal(err)
		}
	}
	waitBatchDrained(t, batch)
	if inner.calls.Load() == 0 {
		t.Fatal("inner Send was not exercised")
	}
}

func TestUnifiedBatchConcurrentByteReservationsStayBounded(t *testing.T) {
	batch, _ := startBlockedBatch(t)
	packet := make([]byte, 65535)
	var accepted atomic.Int32
	var senders sync.WaitGroup
	for i := 0; i < 64; i++ {
		senders.Add(1)
		go func() {
			defer senders.Done()
			if batch.Send(packet) == nil {
				accepted.Add(1)
			}
		}()
	}
	senders.Wait()
	wantPackets := maxBufferedBatchBytes / (len(packet) + 2)
	if int(accepted.Load())+1 != wantPackets || batchBufferedBytes(batch) != wantPackets*(len(packet)+2) {
		t.Fatal("concurrent sends exceeded or lost the bounded byte reservations")
	}
	_ = batch.Stop()
	if batchBufferedBytes(batch) != 0 {
		t.Fatal("concurrent reservations survived shutdown")
	}
}

func TestUnifiedBatchQueuedACKsDrainWithoutWaitingForLinger(t *testing.T) {
	inner := &fakeTransport{loopback: true}
	batch := NewBatchedTransport(inner)
	batch.lingerMs = 60000
	batch.running.Store(true)
	delivered := make(chan []byte, 192)
	batch.Receive(func(packet []byte) { delivered <- packet })
	t.Cleanup(func() { _ = batch.Stop() })
	for i := 0; i < cap(delivered); i++ {
		packet := make([]byte, 64)
		packet[0] = byte(i)
		if err := batch.Send(packet); err != nil {
			t.Fatal(err)
		}
	}
	batch.wg.Add(1)
	go batch.flushLoop()
	for i := 0; i < cap(delivered); i++ {
		select {
		case packet := <-delivered:
			if len(packet) != 64 || packet[0] != byte(i) {
				t.Fatal("ACK batch order or content changed")
			}
		case <-time.After(time.Second):
			t.Fatal("full queued ACK batches waited for the linger timeout")
		}
	}
	waitBatchDrained(t, batch)
	inner.mu.Lock()
	defer inner.mu.Unlock()
	if len(inner.sent) != 3 {
		t.Fatal("a complete queued ACK burst fragmented into small frames")
	}
	for _, frame := range inner.sent {
		packets, err := decodeBatch(frame)
		if err != nil || len(packets) != 64 {
			t.Fatal("a queued ACK frame was not filled to its packet limit")
		}
	}
}
