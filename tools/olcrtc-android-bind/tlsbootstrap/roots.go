// Package tlsbootstrap initializes the certificate environment in the same Go
// runtime as olcRTC. Android's libc setenv does not update Go's environment copy.
package tlsbootstrap

import (
	"bytes"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sync"
)

const r46SHA256 = "4fa3126d8d3a11d1c4855a4f807cbad6cf919d3a5a88b03bea2c6372d93c40c9"
const maxCertificateBytes = 16 * 1024

var configureMu sync.Mutex

// Configure adds the bundled public R46 CA to Go's normal Android system roots.
// Call before creating any olcRTC runtime or performing any certificate checks.
// It never changes SSL_CERT_DIR or disables certificate/hostname verification.
func Configure(path string) error {
	configureMu.Lock()
	defer configureMu.Unlock()
	return configure(path, os.Setenv, x509.SystemCertPool)
}

func configure(path string, setenv func(string, string) error, systemRoots func() (*x509.CertPool, error)) error {
	certificate, err := readCertificate(path)
	if err != nil {
		return err
	}
	if err := setenv("SSL_CERT_FILE", path); err != nil {
		return errors.New("cannot initialize Go certificate environment")
	}
	roots, err := systemRoots()
	if err != nil || roots == nil {
		return errors.New("cannot load Go system certificate roots")
	}
	if _, err := certificate.Verify(x509.VerifyOptions{Roots: roots}); err != nil {
		return errors.New("supplemental TLS root is not trusted by the Go runtime")
	}
	return nil
}

func readCertificate(path string) (*x509.Certificate, error) {
	if !filepath.IsAbs(path) {
		return nil, errors.New("supplemental TLS root path must be absolute")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, errors.New("cannot open supplemental TLS root")
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() > maxCertificateBytes {
		return nil, errors.New("invalid supplemental TLS root file")
	}
	data, err := io.ReadAll(io.LimitReader(file, maxCertificateBytes+1))
	if err != nil || len(data) > maxCertificateBytes {
		return nil, errors.New("cannot read supplemental TLS root")
	}
	data = bytes.TrimSpace(data)
	if !bytes.HasPrefix(data, []byte("-----BEGIN CERTIFICATE-----")) {
		return nil, errors.New("invalid supplemental TLS root PEM")
	}
	block, rest := pem.Decode(data)
	if block == nil || block.Type != "CERTIFICATE" || len(block.Headers) != 0 || len(bytes.TrimSpace(rest)) != 0 {
		return nil, errors.New("expected exactly one supplemental TLS root")
	}
	fingerprint := sha256.Sum256(block.Bytes)
	if hex.EncodeToString(fingerprint[:]) != r46SHA256 {
		return nil, errors.New("supplemental TLS root fingerprint mismatch")
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil || !certificate.IsCA || certificate.KeyUsage&x509.KeyUsageCertSign == 0 ||
		!bytes.Equal(certificate.RawSubject, certificate.RawIssuer) || certificate.CheckSignatureFrom(certificate) != nil {
		return nil, errors.New("invalid supplemental TLS root CA")
	}
	return certificate, nil
}
