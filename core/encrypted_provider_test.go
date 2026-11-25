package core

import (
	"reflect"
	"testing"

	"go.yaml.in/yaml/v3"
)

func TestFilterEncryptedProvider(t *testing.T) {
	const provider = `
rules: [MATCH,DIRECT]
proxy-groups: [{name: injected-group, type: select}]
proxies:
  - name: secure-vless
    type: vless
    server: vpn.example.org
    port: 443
    uuid: 1b8ff809-735d-4dc4-9db7-163b682be798
    tls: true
    network: grpc
    SKIP_CERT_VERIFY: true
    Dialer_Proxy: DIRECT
    interface-name: eth0
    routing-mark: 123
    private-key: /tmp/not-a-key
    shadow-tls-opts: {enabled: true}
  - {name: secure-trojan, type: trojan, server: vpn.example.org, port: 443, password: example}
  - {name: secure-hysteria, type: hysteria2, server: vpn.example.org, port: 443, password: example}
  - {name: secure-ss, type: ss, server: vpn.example.org, port: 443, password: example, cipher: aes-256-gcm}
  - {name: secure-vmess, type: vmess, server: vpn.example.org, port: 443, uuid: 1b8ff809-735d-4dc4-9db7-163b682be798, network: ws, tls: true}
  - {name: plain-vless, type: vless, server: vpn.example.org, port: 443, uuid: 1b8ff809-735d-4dc4-9db7-163b682be798, tls: false}
  - {name: plain-vmess, type: vmess, server: vpn.example.org, port: 443, uuid: 1b8ff809-735d-4dc4-9db7-163b682be798, cipher: none}
  - {name: alternate-transport, type: vmess, server: vpn.example.org, port: 443, uuid: 1b8ff809-735d-4dc4-9db7-163b682be798, network: kcp, tls: true}
  - {name: plain-ss, type: ss, server: vpn.example.org, port: 443, password: example, cipher: none}
  - {name: unsafe-plugin, type: ss, server: vpn.example.org, port: 443, password: example, cipher: aes-256-gcm, plugin: v2ray-plugin}
  - {name: ambiguous, type: vless, server: vpn.example.org, uuid: 1b8ff809-735d-4dc4-9db7-163b682be798, tls: true, TLS: false}
  - {name: direct-proxy, type: direct, server: vpn.example.org, port: 443}
  - name: alternate-download
    type: vless
    server: vpn.example.org
    uuid: 1b8ff809-735d-4dc4-9db7-163b682be798
    tls: true
    network: xhttp
    XHTTP_OPTS:
      Download_Settings: {tls: false}
`
	filtered, err := FilterEncryptedProvider(provider)
	if err != nil {
		t.Fatal(err)
	}
	var document struct {
		Proxies []map[string]any `yaml:"proxies"`
		Rules   []string         `yaml:"rules"`
		Groups  []map[string]any `yaml:"proxy-groups"`
	}
	if err := yaml.Unmarshal([]byte(filtered), &document); err != nil {
		t.Fatal(err)
	}
	if len(document.Rules) > 0 || len(document.Groups) > 0 {
		t.Fatal("provider routing directives were preserved")
	}
	var names []string
	for _, proxy := range document.Proxies {
		names = append(names, stringField(proxy, "name"))
		if proxy["skip-cert-verify"] != false {
			t.Fatalf("%s disabled certificate verification", proxy["name"])
		}
		for _, field := range []string{"dialer-proxy", "interface-name", "routing-mark", "private-key", "shadow-tls-opts"} {
			if _, exists := proxy[field]; exists {
				t.Fatalf("%s retained %s", proxy["name"], field)
			}
		}
	}
	want := []string{"secure-vless", "secure-trojan", "secure-hysteria", "secure-ss", "secure-vmess"}
	if !reflect.DeepEqual(names, want) {
		t.Fatalf("accepted nodes: %v, want %v", names, want)
	}
	again, err := FilterEncryptedProvider(filtered)
	if err != nil || again != filtered {
		t.Fatalf("sanitized cache should be stable: %v", err)
	}
}

func TestFilterEncryptedProviderRejectsPlainOnly(t *testing.T) {
	if _, err := FilterEncryptedProvider("proxies: [{name: plain, type: http, server: vpn.example.org, port: 80}]"); err == nil {
		t.Fatal("a plaintext-only provider was accepted")
	}
}
