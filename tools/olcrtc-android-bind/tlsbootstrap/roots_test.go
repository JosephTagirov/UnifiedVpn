package tlsbootstrap

import (
	"bytes"
	"crypto/x509"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fixture(t *testing.T) ([]byte, string) {
	t.Helper()
	data, err := os.ReadFile("testdata/globalsign-r46.pem")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "root.pem")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	return data, path
}

func TestConfigureSetsGoEnvironmentBeforeLoadingRoots(t *testing.T) {
	data, path := fixture(t)
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(data) {
		t.Fatal("invalid fixture")
	}
	t.Setenv("SSL_CERT_DIR", "preserve-system-root-directory")
	wasSet := false
	err := configure(path, func(key, value string) error {
		if key != "SSL_CERT_FILE" || value != path {
			t.Fatal("unexpected environment change")
		}
		wasSet = true
		return nil
	}, func() (*x509.CertPool, error) {
		if !wasSet {
			t.Fatal("roots loaded before Go environment initialized")
		}
		return pool, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if os.Getenv("SSL_CERT_DIR") != "preserve-system-root-directory" {
		t.Fatal("system root directory changed")
	}
}

func TestConfigureRejectsMissingEffectiveTrust(t *testing.T) {
	_, path := fixture(t)
	for _, test := range []struct {
		name string
		pool *x509.CertPool
		err  error
	}{
		{"cached-without-supplement", x509.NewCertPool(), nil},
		{"nil-pool", nil, nil},
		{"load-error", nil, errors.New("not available")},
	} {
		t.Run(test.name, func(t *testing.T) {
			err := configure(path, func(string, string) error { return nil }, func() (*x509.CertPool, error) { return test.pool, test.err })
			if err == nil {
				t.Fatal("untrusted root must fail closed")
			}
		})
	}
}

func TestConfigureSetenvFailureDoesNotLoadRoots(t *testing.T) {
	_, path := fixture(t)
	err := configure(path, func(string, string) error { return errors.New("denied") }, func() (*x509.CertPool, error) {
		t.Fatal("loaded roots after failed environment initialization")
		return nil, nil
	})
	if err == nil {
		t.Fatal("expected environment failure")
	}
}

func TestReadCertificateRejectsInvalidInputs(t *testing.T) {
	data, _ := fixture(t)
	for name, content := range map[string][]byte{
		"empty":     nil,
		"text":      []byte("not a certificate"),
		"multiple":  append(append([]byte(nil), data...), data...),
		"prefix":    append([]byte("ignored junk\n"), data...),
		"suffix":    append(append([]byte(nil), data...), []byte("junk")...),
		"oversized": bytes.Repeat([]byte("x"), maxCertificateBytes+1),
		"altered":   bytes.Replace(data, []byte("MIIFWj"), []byte("MIIFXj"), 1),
	} {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "invalid.pem")
			if err := os.WriteFile(path, content, 0600); err != nil {
				t.Fatal(err)
			}
			err := configure(path, func(string, string) error {
				t.Fatal("invalid certificate changed environment")
				return nil
			}, func() (*x509.CertPool, error) {
				t.Fatal("invalid certificate initialized root cache")
				return nil, nil
			})
			if err == nil || strings.Contains(err.Error(), path) {
				t.Fatal("must reject input without exposing local path")
			}
		})
	}
}

func TestReadCertificateRejectsRelativeMissingAndDirectoryPaths(t *testing.T) {
	for _, path := range []string{"relative.pem", filepath.Join(t.TempDir(), "missing.pem"), t.TempDir()} {
		if _, err := readCertificate(path); err == nil {
			t.Fatal("unsafe file path accepted")
		}
	}
}
