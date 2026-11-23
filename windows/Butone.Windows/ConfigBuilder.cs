using System.Text;

namespace Butone.Windows;

internal static class ConfigBuilder
{
    public const string AutoGroup = "AUTO";
    public const int ControllerPort = 19090;
    public const int MixedPort = 17890;

    public static readonly string[] ProviderNames =
    [
        "ru_mobile",
        "ru_vless_full",
        "ru_other",
        "ru_white",
        "global_reserve",
    ];

    public static string Build(string secret)
    {
        var providers = new StringBuilder();
        foreach (var id in ProviderNames)
        {
            var lazy = id is "ru_vless_full" or "global_reserve" ? "true" : "false";
            providers.AppendLine($"  {id}:");
            providers.AppendLine("    type: file");
            providers.AppendLine($"    path: ./providers/{id}.yaml");
            providers.AppendLine("    override:");
            providers.AppendLine("      skip-cert-verify: false");
            providers.AppendLine($"      additional-prefix: \"[{id}] \"");
            providers.AppendLine("    health-check:");
            providers.AppendLine("      enable: true");
            providers.AppendLine("      url: https://www.youtube.com/generate_204");
            providers.AppendLine("      interval: 600");
            providers.AppendLine($"      lazy: {lazy}");
            providers.AppendLine("      timeout: 5000");
            providers.AppendLine("      expected-status: 204");
        }

        var use = string.Join(", ", ProviderNames);
        return $$"""
            mixed-port: {{MixedPort}}
            bind-address: 127.0.0.1
            allow-lan: false
            mode: rule
            log-level: info
            ipv6: false
            unified-delay: true
            tcp-concurrent: true
            find-process-mode: off
            external-controller: 127.0.0.1:{{ControllerPort}}
            secret: "{{secret}}"
            profile:
              store-selected: false
              store-fake-ip: false

            tun:
              enable: true
              device: Butone
              stack: gvisor
              inet4-address: [198.19.0.1/30]
              mtu: 1400
              auto-route: true
              auto-detect-interface: true
              strict-route: true
              dns-mode: hijack
              dns-hijack: ["any:53"]

            dns:
              enable: true
              ipv6: false
              respect-rules: true
              enhanced-mode: fake-ip
              fake-ip-range: 198.18.0.1/16
              fake-ip-filter: ['*.lan', '*.local', 'localhost', '+.pool.ntp.org', 'time.*.com', 'ntp.*.com', '+.msftconnecttest.com', '+.msftncsi.com', '+.stun.*.*', '+.stun.*.*.*']
              default-nameserver: [77.88.8.8, 8.8.8.8, 1.1.1.1]
              nameserver: [tls://1.1.1.1, tls://8.8.8.8, https://dns.google/dns-query, https://cloudflare-dns.com/dns-query]
              proxy-server-nameserver: [77.88.8.8, 8.8.8.8, 1.1.1.1]
              direct-nameserver: [77.88.8.8, 77.88.8.1]

            sniffer:
              enable: true
              force-dns-mapping: true
              parse-pure-ip: true
              sniff:
                HTTP: { ports: [80, 8080-8880], override-destination: true }
                TLS: { ports: [443, 8443] }
                QUIC: { ports: [443, 8443] }

            proxy-providers:
            {{providers.ToString().TrimEnd()}}

            proxy-groups:
              - name: {{AutoGroup}}
                type: select
                use: [{{use}}]

            rule-providers:
              ru-sites: { type: file, behavior: domain, format: mrs, path: ./rules/category-ru.mrs }
              ru-ip: { type: file, behavior: ipcidr, format: mrs, path: ./rules/geoip-ru.mrs }

            rules:
              - DOMAIN-SUFFIX,local,DIRECT
              - DOMAIN-SUFFIX,lan,DIRECT
              - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve
              - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve
              - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve
              - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
              - IP-CIDR,169.254.0.0/16,DIRECT,no-resolve
              - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve
              - DOMAIN-SUFFIX,yandex.com,DIRECT
              - DOMAIN-SUFFIX,yandex.net,DIRECT
              - DOMAIN-SUFFIX,yastatic.net,DIRECT
              - DOMAIN-SUFFIX,ya.ru,DIRECT
              - DOMAIN-SUFFIX,vk.com,DIRECT
              - DOMAIN-SUFFIX,userapi.com,DIRECT
              - DOMAIN-SUFFIX,vkuser.net,DIRECT
              - DOMAIN-SUFFIX,mycdn.me,DIRECT
              - DOMAIN-SUFFIX,ozonusercontent.com,DIRECT
              - DOMAIN-SUFFIX,avito.st,DIRECT
              - DOMAIN-SUFFIX,sberbank.com,DIRECT
              - DOMAIN-SUFFIX,alfabank.com,DIRECT
              - DOMAIN-SUFFIX,ru,DIRECT
              - DOMAIN-SUFFIX,su,DIRECT
              - DOMAIN-SUFFIX,xn--p1ai,DIRECT
              - RULE-SET,ru-sites,DIRECT
              - RULE-SET,ru-ip,DIRECT
              - MATCH,{{AutoGroup}}
            """;
    }
}
