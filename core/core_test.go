package core

import (
	"os"
	"strings"
	"testing"
)

func TestCatalogSignature(t *testing.T) {
	data, err := os.ReadFile("../catalog/catalog.json")
	if err != nil {
		t.Fatal(err)
	}
	signature, err := os.ReadFile("../catalog/catalog.json.sig")
	if err != nil {
		t.Fatal(err)
	}
	const publicKey = "DRqQ4zt38yWAIHMH8FnoaZeBJ2Bi0tsbB88L7qjS3+o="
	if err := VerifyEd25519(publicKey, string(data), strings.TrimSpace(string(signature))); err != nil {
		t.Fatal(err)
	}
	if err := VerifyEd25519(publicKey, string(data)+" ", strings.TrimSpace(string(signature))); err == nil {
		t.Fatal("modified catalog unexpectedly passed signature verification")
	}
}
