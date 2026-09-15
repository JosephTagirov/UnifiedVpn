// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"sync"
	"time"

	"universal-bypass-tool/transport"
)

// Control records are inside upstream authenticated encryption, not a second
// cryptographic protocol. A fresh challenge prevents stale welcome replay.
var controlPrefix = []byte{0, 'U', 'O', 'F', 1}

type peerSession struct {
	transport.Transport
	server     bool
	mu         sync.RWMutex
	callback   func([]byte)
	attempt    *peerAttempt
	confirmed  bool
	stopped    chan struct{}
	stopOnce   sync.Once
	stopErr    error
	deliveries sync.WaitGroup
}

type peerAttempt struct {
	challenge [32]byte
	ready     chan struct{}
	accepted  bool
	ctx       context.Context
}

func newPeerSession(inner transport.Transport, server bool) *peerSession {
	s := &peerSession{Transport: inner, server: server, stopped: make(chan struct{})}
	inner.Receive(s.receive)
	return s
}

func (s *peerSession) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.isStopped() {
		return errors.New("encrypted peer session is stopped")
	}
	return s.Transport.Start()
}

func (s *peerSession) isStopped() bool {
	select {
	case <-s.stopped:
		return true
	default:
		return false
	}
}

func (s *peerSession) Stop() error {
	s.stopOnce.Do(func() {
		s.mu.Lock()
		close(s.stopped)
		s.confirmed, s.callback, s.attempt = false, nil, nil
		s.mu.Unlock()
		s.stopErr = s.Transport.Stop()
		// Drain deliveries before the caller closes the gVisor network stack.
		s.deliveries.Wait()
	})
	return s.stopErr
}

func (s *peerSession) Send(packet []byte) error {
	s.mu.RLock()
	ready := s.confirmed && !s.isStopped()
	s.mu.RUnlock()
	if !ready {
		return errors.New("encrypted peer is not ready")
	}
	return s.Transport.Send(packet)
}

func (s *peerSession) Receive(callback func([]byte)) {
	s.mu.Lock()
	if !s.isStopped() {
		s.callback = callback
	}
	s.mu.Unlock()
}

func controlRecord(kind byte, nonce []byte) []byte {
	packet := append([]byte{}, controlPrefix...)
	packet = append(packet, kind)
	return append(packet, nonce...)
}

func (s *peerSession) receive(packet []byte) {
	if bytes.HasPrefix(packet, controlPrefix) {
		if len(packet) != len(controlPrefix)+1+32 {
			return
		}
		kind, nonce := packet[len(controlPrefix)], packet[len(controlPrefix)+1:]
		if s.server && kind == 'H' {
			s.mu.Lock()
			if s.isStopped() {
				s.mu.Unlock()
				return
			}
			s.confirmed = true
			s.mu.Unlock()
			_ = s.Transport.Send(controlRecord('W', nonce))
		} else if !s.server && kind == 'W' {
			s.mu.Lock()
			a := s.attempt
			if !s.isStopped() && a != nil && a.ctx.Err() == nil && !a.accepted && bytes.Equal(nonce, a.challenge[:]) {
				a.accepted = true
				close(a.ready)
			}
			s.mu.Unlock()
		}
		return
	}
	s.mu.Lock()
	callback := s.callback
	if s.isStopped() || !s.confirmed || callback == nil {
		s.mu.Unlock()
		return
	}
	s.deliveries.Add(1)
	s.mu.Unlock()
	defer s.deliveries.Done()
	callback(packet)
}

func (s *peerSession) waitPeer(ctx context.Context) (result error) {
	if err := ctx.Err(); err != nil {
		return err
	}
	if s.server {
		return errors.New("only a client can initiate the peer handshake")
	}
	a := &peerAttempt{ready: make(chan struct{}), ctx: ctx}
	if _, err := rand.Read(a.challenge[:]); err != nil {
		return errors.New("cannot generate peer challenge")
	}
	s.mu.Lock()
	if s.isStopped() {
		s.mu.Unlock()
		return errors.New("encrypted peer session is stopped")
	}
	if s.attempt != nil {
		s.mu.Unlock()
		return errors.New("encrypted peer handshake is already pending")
	}
	s.attempt = a
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.attempt == a {
			s.attempt = nil
		}
		if result != nil {
			s.confirmed = false
		}
		s.mu.Unlock()
	}()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		if s.isStopped() {
			return errors.New("encrypted peer session is stopped")
		}
		_ = s.Transport.Send(controlRecord('H', a.challenge[:]))
		select {
		case <-ctx.Done():
			return errors.New("encrypted peer handshake timed out; check the server, document access, and matching key")
		case <-s.stopped:
			return errors.New("encrypted peer session is stopped")
		case <-a.ready:
			s.mu.Lock()
			if err := ctx.Err(); err != nil {
				s.mu.Unlock()
				return err
			}
			if s.isStopped() || s.attempt != a {
				s.mu.Unlock()
				return errors.New("encrypted peer session is stopped")
			}
			s.confirmed = true
			s.mu.Unlock()
			return nil
		case <-ticker.C:
		}
	}
}
