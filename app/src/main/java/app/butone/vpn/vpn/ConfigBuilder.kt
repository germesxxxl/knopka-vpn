package app.butone.vpn.vpn

import app.butone.vpn.data.Source

/**
 * Builds the complete Mihomo config. Design choices:
 * - a selector group [AUTO_GROUP] over all providers: Android shortlists by
 *   Mihomo latency and selects a node only after real service checks;
 * - Russian sites and IPs go DIRECT (banks, gov services, marketplaces never
 *   touch public nodes), same as igareck's "rudirect" profiles;
 * - TUN uses the fd from VpnService; our own app is excluded from the VPN,
 *   so Mihomo's outbound sockets don't loop back into the tunnel.
 */
object ConfigBuilder {
    const val AUTO_GROUP = "AUTO"
    const val CONTROLLER_PORT = 9090
    const val MIXED_PORT = 7890
    private const val TEST_URL = "https://www.youtube.com/generate_204"

    fun build(sources: List<Source>, secret: String): String {
        val providers = sources.joinToString("\n") { s ->
            val lazy = s.id == "ru_vless_full"
            """
            |  ${s.id}:
            |    type: file
            |    path: ./providers/${s.id}.yaml
            |    override:
            |      skip-cert-verify: false
            |      additional-prefix: "[${s.id}] "
            |    health-check:
            |      enable: true
            |      url: $TEST_URL
            |      interval: 600
            |      lazy: $lazy
            |      timeout: 5000
            |      expected-status: 204
            """.trimMargin()
        }
        val use = sources.joinToString(", ") { it.id }

        return """
            |mixed-port: $MIXED_PORT
            |bind-address: 127.0.0.1
            |allow-lan: false
            |mode: rule
            |log-level: info
            |ipv6: false
            |unified-delay: true
            |tcp-concurrent: true
            |find-process-mode: off
            |external-controller: 127.0.0.1:$CONTROLLER_PORT
            |secret: "$secret"
            |profile:
            |  store-selected: false
            |  store-fake-ip: false
            |
            |tun:
            |  enable: true
            |  device: butone
            |  stack: gvisor
            |  inet4-address: [172.19.0.1/30]
            |  mtu: 1400
            |  auto-route: false
            |  auto-detect-interface: false
            |  dns-hijack: ["any:53"]
            |
            |dns:
            |  enable: true
            |  ipv6: false
            |  respect-rules: true
            |  enhanced-mode: fake-ip
            |  fake-ip-range: 198.18.0.1/16
            |  fake-ip-filter: ['*.lan', '*.local', 'localhost', '+.pool.ntp.org', 'time.*.com', 'ntp.*.com', '+.msftconnecttest.com', '+.msftncsi.com', '+.stun.*.*', '+.stun.*.*.*']
            |  default-nameserver: [77.88.8.8, 8.8.8.8, 1.1.1.1]
            |  nameserver: [https://dns.google/dns-query, https://cloudflare-dns.com/dns-query, https://dns.quad9.net/dns-query]
            |  proxy-server-nameserver: [77.88.8.8, 8.8.8.8, 1.1.1.1]
            |  direct-nameserver: [77.88.8.8, 77.88.8.1]
            |
            |sniffer:
            |  enable: true
            |  force-dns-mapping: true
            |  parse-pure-ip: true
            |  sniff:
            |    HTTP: { ports: [80, 8080-8880], override-destination: true }
            |    TLS: { ports: [443, 8443] }
            |    QUIC: { ports: [443, 8443] }
            |
            |proxy-providers:
            |$providers
            |
            |proxy-groups:
            |  - name: $AUTO_GROUP
            |    type: select
            |    use: [$use]
            |
            |rule-providers:
            |  ru-sites: { type: file, behavior: domain, format: mrs, path: ./rules/category-ru.mrs }
            |  ru-ip: { type: file, behavior: ipcidr, format: mrs, path: ./rules/geoip-ru.mrs }
            |
            |rules:
            |  - DOMAIN-SUFFIX,local,DIRECT
            |  - DOMAIN-SUFFIX,lan,DIRECT
            |  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve
            |  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve
            |  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve
            |  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
            |  - IP-CIDR,169.254.0.0/16,DIRECT,no-resolve
            |  - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve
            |  - DOMAIN-SUFFIX,ru,DIRECT
            |  - DOMAIN-SUFFIX,su,DIRECT
            |  - DOMAIN-SUFFIX,xn--p1ai,DIRECT
            |  - RULE-SET,ru-sites,DIRECT
            |  - RULE-SET,ru-ip,DIRECT
            |  - MATCH,$AUTO_GROUP
            |""".trimMargin()
    }
}
