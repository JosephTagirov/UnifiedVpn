package jitsi

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"os"
	"strconv"
	"testing"

	"github.com/zarazaex69/j"
)

const unifiedVPNEnvelopePayload = "OLC2-synthetic-envelope-compatibility-only"

func TestUnifiedVPNEnvelopeReceiverModern(t *testing.T) {
	wantAccepted, err := strconv.ParseBool(os.Getenv("UNIFIEDVPN_WIRE_ACCEPT_MODERN"))
	if err != nil {
		t.Fatal("explicit expected modern-envelope compatibility is required")
	}
	wire, err := os.ReadFile(os.Getenv("UNIFIEDVPN_WIRE_FIXTURE"))
	if err != nil {
		t.Fatal(err)
	}
	if len(wire) > 4096 {
		t.Fatal("unexpected synthetic fixture size")
	}
	var fields map[string]any
	if err := json.Unmarshal(wire, &fields); err != nil {
		t.Fatal(err)
	}
	class, _ := fields["colibriClass"].(string)
	if class != "EndpointMessage" || fields["raw"] != nil || fields["msgPayload"] == nil {
		t.Fatal("fixture is not the latest sender's nested envelope")
	}
	// The pinned Jitsi library retains the JSON fields without flattening msgPayload.
	got := decodeRaw(j.BridgeMessage{Class: class, Fields: fields, RawJSON: wire})
	accepted := bytes.Equal(got, []byte(unifiedVPNEnvelopePayload))
	if accepted != wantAccepted || (!accepted && got != nil) {
		t.Fatalf("modern envelope accepted=%v, expected=%v, bytes=%d", accepted, wantAccepted, len(got))
	}
	t.Logf("modern_envelope_accepted=%v", accepted)
}

func TestUnifiedVPNEnvelopeReceiverLegacy(t *testing.T) {
	// Fixed protocol fixture matching j@v0.0.1 SendRaw's top-level raw field.
	wire, err := json.Marshal(map[string]any{
		"colibriClass": "EndpointMessage",
		"to":           "synthetic-peer",
		"raw":          base64.StdEncoding.EncodeToString([]byte(unifiedVPNEnvelopePayload)),
	})
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]any
	if err := json.Unmarshal(wire, &fields); err != nil {
		t.Fatal(err)
	}
	got := decodeRaw(j.BridgeMessage{Class: "EndpointMessage", Fields: fields, RawJSON: wire})
	if !bytes.Equal(got, []byte(unifiedVPNEnvelopePayload)) {
		t.Fatalf("legacy envelope rejected, bytes=%d", len(got))
	}
	t.Log("legacy_envelope_accepted=true")
}
