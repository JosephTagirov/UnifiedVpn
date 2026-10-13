// SPDX-License-Identifier: GPL-3.0-or-later
package transport

import (
	"errors"
	"sync"
	"sync/atomic"
	"time"
)

const (
	defaultMaxBatchBytes  = 32768
	defaultMaxBatchCount  = 64
	defaultLingerMs       = 2
	batchQueueDepth       = 4096
	maxBufferedBatchBytes = 2 << 20
)

// BatchedTransport implements the explicitly selected batch-zstd-v2 codec.
// Its inner transport must provide authenticated encryption.
type BatchedTransport struct {
	Transport
	queue         chan []byte
	lingerMs      int
	maxBatchBytes int
	maxBatchCount int
	// Includes accepted packet payloads and record lengths, even while collecting
	// or sending a frame. The lifecycle mutex guards this reservation.
	bufferedBytes int
	running       atomic.Bool
	lifecycle     sync.Mutex
	started       bool
	stopped       bool
	startDone     chan struct{}
	stop          chan struct{}
	stopOnce      sync.Once
	stopError     error
	wg            sync.WaitGroup
	mu            sync.RWMutex
	userCb        func([]byte)
}

func NewBatchedTransport(inner Transport) *BatchedTransport {
	return &BatchedTransport{
		Transport: inner, stop: make(chan struct{}), queue: make(chan []byte, batchQueueDepth),
		lingerMs: defaultLingerMs, maxBatchBytes: defaultMaxBatchBytes, maxBatchCount: defaultMaxBatchCount,
	}
}

func (b *BatchedTransport) Start() error {
	b.lifecycle.Lock()
	if b.started || b.stopped {
		b.lifecycle.Unlock()
		return errors.New("batch transport already started or stopped")
	}
	b.started = true
	b.startDone = make(chan struct{})
	done := b.startDone
	b.lifecycle.Unlock()
	defer close(done)

	// Stop must be able to cancel an inner browser bootstrap while Start waits.
	err := b.Transport.Start()
	b.lifecycle.Lock()
	stopped := b.stopped
	if err == nil && !stopped {
		b.running.Store(true)
		b.wg.Add(1)
		go b.flushLoop()
	}
	b.lifecycle.Unlock()
	if err != nil || stopped {
		_ = b.Transport.Stop()
		if stopped {
			return errors.New("batch transport stopped")
		}
	}
	return err
}

func (b *BatchedTransport) Stop() error {
	b.stopOnce.Do(func() {
		b.lifecycle.Lock()
		b.stopped = true
		b.running.Store(false)
		close(b.stop)
		started := b.startDone
		b.lifecycle.Unlock()
		b.stopError = b.Transport.Stop()
		if started != nil {
			<-started
		}
		b.wg.Wait()
		// Discard unsent packets; shutdown must never flush into a stopped relay.
		b.lifecycle.Lock()
		defer b.lifecycle.Unlock()
		for {
			select {
			case <-b.queue:
			default:
				b.bufferedBytes = 0
				return
			}
		}
	})
	return b.stopError
}

func (b *BatchedTransport) Send(data []byte) error {
	if len(data) == 0 || len(data) > 65535 {
		return errors.New("invalid batch packet size")
	}
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	if !b.running.Load() || b.stopped {
		return errors.New("batch transport stopped")
	}
	cost := len(data) + 2
	if cost > maxBufferedBatchBytes-b.bufferedBytes || len(b.queue) == cap(b.queue) {
		return errors.New("batch queue full")
	}
	// gVisor reuses outgoing buffers. The queue always owns its copy.
	packet := append([]byte(nil), data...)
	b.bufferedBytes += cost
	select {
	case b.queue <- packet:
		return nil
	default:
		b.bufferedBytes -= cost
		return errors.New("batch queue full")
	}
}

func (b *BatchedTransport) releaseBatch(packets [][]byte) {
	cost := 0
	for _, packet := range packets {
		cost += len(packet) + 2
	}
	b.lifecycle.Lock()
	b.bufferedBytes -= cost
	b.lifecycle.Unlock()
}

func (b *BatchedTransport) Receive(callback func([]byte)) {
	b.mu.Lock()
	b.userCb = callback
	b.mu.Unlock()
	b.Transport.Receive(func(data []byte) {
		if !b.running.Load() {
			return
		}
		packets, err := decodeBatch(data)
		if err != nil {
			return
		}
		b.mu.RLock()
		cb := b.userCb
		b.mu.RUnlock()
		if cb != nil {
			for _, packet := range packets {
				if !b.running.Load() {
					return
				}
				cb(packet)
			}
		}
	})
}

func (b *BatchedTransport) IsConnected() bool {
	return b.running.Load() && b.Transport.IsConnected()
}

func (b *BatchedTransport) flushLoop() {
	defer b.wg.Done()
	var pending []byte
	for {
		if !b.running.Load() {
			return
		}
		first := pending
		pending = nil
		if first == nil {
			select {
			case <-b.stop:
				return
			case first = <-b.queue:
			}
		}
		batch := [][]byte{first}
		size := 2 + len(first)
		// Drain ready packets before starting the linger timer. A delayed worker
		// must not flush a tiny frame just because its timer expired while queued.
	drain:
		for size < b.maxBatchBytes && len(batch) < b.maxBatchCount {
			select {
			case <-b.stop:
				return
			case packet := <-b.queue:
				if size+2+len(packet) > b.maxBatchBytes {
					pending = packet
					break drain
				}
				batch = append(batch, packet)
				size += 2 + len(packet)
			default:
				break drain
			}
		}
		if pending == nil && size < b.maxBatchBytes && len(batch) < b.maxBatchCount {
			timer := time.NewTimer(time.Duration(b.lingerMs) * time.Millisecond)
		collect:
			for size < b.maxBatchBytes && len(batch) < b.maxBatchCount {
				select {
				case <-b.stop:
					timer.Stop()
					return
				case packet := <-b.queue:
					if size+2+len(packet) > b.maxBatchBytes {
						pending = packet
						break collect
					}
					batch = append(batch, packet)
					size += 2 + len(packet)
				case <-timer.C:
					break collect
				}
			}
			timer.Stop()
		}
		if !b.running.Load() {
			return
		}
		// Oversized individual packets remain intact in a bounded singleton frame.
		_ = b.Transport.Send(encodeBatch(batch))
		b.releaseBatch(batch)
	}
}
