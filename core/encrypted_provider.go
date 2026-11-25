package core

import (
	"errors"
	"fmt"
	"strings"

	"go.yaml.in/yaml/v3"
)

// FilterEncryptedProvider imports only the proxy list from a third-party YAML
// file and drops candidates without an approved encrypted transport.
func FilterEncryptedProvider(data string) (string, error) {
	const maxProviderBytes = 2 * 1024 * 1024
	if len(data) == 0 || len(data) > maxProviderBytes {
		return "", errors.New("provider size is outside 1..2097152 bytes")
	}
	var document struct {
		Proxies []map[string]any `yaml:"proxies"`
	}
	if err := yaml.Unmarshal([]byte(data), &document); err != nil {
		return "", errors.New("invalid provider YAML")
	}
	if len(document.Proxies) == 0 || len(document.Proxies) > 2000 {
		return "", errors.New("provider must contain 1..2000 proxies")
	}
	accepted := document.Proxies[:0]
	for _, item := range document.Proxies {
		proxy, unambiguous := canonicalProxy(item)
		if !unambiguous || !encryptedProxy(proxy) {
			continue
		}
		// The app owns the routing and trust settings. A profile cannot select
		// another dialer, bind a network, or load keys from the device.
		for _, key := range []string{
			"dialer-proxy", "interface-name", "routing-mark", "certificate", "private-key",
			"shadow-tls-opts", "restls-opts", "jls-opts", "tlsmirror-opts", "realm-opts",
		} {
			delete(proxy, key)
		}
		proxy["skip-cert-verify"] = false
		accepted = append(accepted, proxy)
	}
	if len(accepted) == 0 {
		return "", errors.New("provider contains no approved encrypted proxies")
	}
	document.Proxies = accepted
	encoded, err := yaml.Marshal(document)
	if err != nil || len(encoded) > maxProviderBytes {
		return "", fmt.Errorf("could not encode approved proxy list within %d bytes", maxProviderBytes)
	}
	return string(encoded), nil
}

func encryptedProxy(proxy map[string]any) bool {
	if stringField(proxy, "name") == "" || stringField(proxy, "server") == "" {
		return false
	}
	network := stringField(proxy, "network")
	tls, _ := proxy["tls"].(bool)
	switch stringField(proxy, "type") {
	case "vless":
		if !tls || stringField(proxy, "uuid") == "" {
			return false
		}
		switch network {
		case "", "tcp", "ws", "http", "h2", "grpc":
			return true
		case "xhttp":
			// A separate XHTTP download leg can override the main TLS settings.
			if proxy["xhttp-opts"] == nil {
				return true
			}
			options, ok := proxy["xhttp-opts"].(map[string]any)
			if !ok {
				return false
			}
			for key := range options {
				if canonicalKey(key) == "download-settings" {
					return false
				}
			}
			return true
		}
	case "vmess":
		if !tls || stringField(proxy, "uuid") == "" {
			return false
		}
		switch network {
		case "", "tcp", "ws", "http", "h2", "grpc":
			return true
		}
	case "trojan":
		return stringField(proxy, "password") != "" &&
			(network == "" || network == "tcp" || network == "ws" || network == "grpc")
	case "hysteria2":
		return stringField(proxy, "password") != ""
	case "ss":
		if stringField(proxy, "password") == "" || stringField(proxy, "plugin") != "" {
			return false
		}
		switch stringField(proxy, "cipher") {
		case "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
			"2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305":
			return true
		}
	}
	return false
}

// Mihomo accepts both case variants and underscores in option names.
func canonicalProxy(input map[string]any) (map[string]any, bool) {
	result := make(map[string]any, len(input))
	for key, value := range input {
		key = canonicalKey(key)
		if _, duplicate := result[key]; duplicate {
			return nil, false
		}
		result[key] = value
	}
	return result, true
}

func canonicalKey(key string) string {
	return strings.ReplaceAll(strings.ToLower(key), "_", "-")
}

func stringField(proxy map[string]any, name string) string {
	value, _ := proxy[name].(string)
	return strings.TrimSpace(value)
}
