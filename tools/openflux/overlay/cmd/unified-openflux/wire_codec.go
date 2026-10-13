// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"errors"

	"openflux/transport"
	"openflux/tunnel"
)

func newWireTransport(c configuration, base transport.Transport) (transport.Transport, error) {
	if err := c.validateWireCodec(); err != nil {
		return nil, err
	}
	encrypted, err := transport.NewEncryptedTransport(base, c.EncryptionKey, c.DocumentURL, c.Mode == "server")
	if err != nil {
		return nil, errors.New("cannot initialize mandatory AES-256-GCM encryption")
	}
	if c.effectiveWireCodec() == batchWireCodec {
		// Compress and frame before encryption; document transports see only ciphertext.
		return transport.NewBatchedTransport(encrypted), nil
	}
	return transport.NewCompressedTransport(encrypted), nil
}

func newWireNetwork(c configuration, wire transport.Transport) (*tunnel.UnifiedTCPTunnel, error) {
	if err := c.validateWireCodec(); err != nil {
		return nil, err
	}
	if c.effectiveWireCodec() == batchWireCodec {
		return tunnel.NewUnifiedBatchTCPTunnel(wire, c.Mode == "server")
	}
	return tunnel.NewUnifiedTCPTunnel(wire, c.Mode == "server")
}
