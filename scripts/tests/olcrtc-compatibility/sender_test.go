package jitsi

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"testing"
)

func TestUnifiedVPNEnvelopeSender(t *testing.T) {
	path := os.Getenv("UNIFIEDVPN_WIRE_FIXTURE")
	if path == "" {
		t.Fatal("an isolated fixture output path is required")
	}
	// Use the real latest core type and JSON serializer, not a copied encoder.
	message := endpointMessage{
		ColibriClass: colibriClassEndpointMessage,
		To:           "synthetic-peer",
		MsgPayload: endpointRawPayload{
			Raw: base64.StdEncoding.EncodeToString([]byte(unifiedVPNEnvelopePayload)),
		},
	}
	wire, err := json.Marshal(message)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, wire, 0600); err != nil {
		t.Fatal(err)
	}
}
