package core

import (
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"errors"
)

// zjuAtrustNodeSPKIPins are SHA-256 digests of SubjectPublicKeyInfo values
// observed on ZJU's aTrust data-plane cluster. Those nodes use expired,
// self-signed appliance certificates, so normal PKI validation cannot prove
// their identity. Every accepted certificate remains pinned: an unexpected
// node fails closed instead of accepting the old upstream
// InsecureSkipVerify-only behavior. Update this set only after independently
// validating a planned gateway certificate rotation.
var zjuAtrustNodeSPKIPins = [][sha256.Size]byte{
	{
		0xbc, 0xaf, 0x7e, 0x9b, 0xf3, 0xd5, 0xa6, 0x5b,
		0x03, 0x3f, 0x67, 0x55, 0x6a, 0x1e, 0xcc, 0xd7,
		0x1d, 0x9b, 0xaf, 0x16, 0x47, 0xe9, 0xc4, 0x96,
		0xd5, 0x65, 0x3d, 0xd6, 0x88, 0xb2, 0x8e, 0xf5,
	},
	{
		0x2e, 0x63, 0xa6, 0xa1, 0xd9, 0xe1, 0xc4, 0x53,
		0xc1, 0x62, 0xce, 0x78, 0xb3, 0x3e, 0xd7, 0xac,
		0xb6, 0x38, 0x5e, 0xbb, 0x15, 0xd3, 0xaf, 0x01,
		0x2d, 0x5b, 0x1b, 0xb2, 0xfc, 0x31, 0x2e, 0x37,
	},
}

func zjuAtrustPortalTLSConfig() *tls.Config {
	return &tls.Config{}
}

func zjuAtrustNodeTLSConfig() *tls.Config {
	return &tls.Config{
		// The appliance certificate cannot pass Web PKI verification. The
		// VerifyConnection callback below replaces it with a mandatory SPKI pin.
		InsecureSkipVerify:     true, // #nosec G402 -- verification is enforced by VerifyConnection.
		SessionTicketsDisabled: true,
		VerifyConnection: func(state tls.ConnectionState) error {
			return verifyPinnedNodeSPKI(state.PeerCertificates, zjuAtrustNodeSPKIPins)
		},
	}
}

func verifyPinnedNodeSPKI(peerCertificates []*x509.Certificate, expectedPins [][sha256.Size]byte) error {
	if len(peerCertificates) == 0 {
		return errors.New("aTrust node did not present a certificate")
	}
	actual := sha256.Sum256(peerCertificates[0].RawSubjectPublicKeyInfo)
	for _, expected := range expectedPins {
		if subtle.ConstantTimeCompare(actual[:], expected[:]) == 1 {
			return nil
		}
	}
	return errors.New("aTrust node certificate pin mismatch")
}
