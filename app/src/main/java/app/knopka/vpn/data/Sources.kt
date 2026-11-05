package app.knopka.vpn.data

/**
 * A public, auto-updated Clash/Mihomo subscription. [urls] are mirrors of the
 * same file, tried in order; raw.githubusercontent.com is often blocked in
 * Russia, so it is not first.
 *
 * The provider [id] doubles as the Mihomo proxy-provider name and file name.
 */
data class Source(val id: String, val urls: List<String>)

object Sources {
    private fun igareck(path: String) = listOf(
        "https://raw.githack.com/igareck/vpn-configs-for-russia/main/$path",
        "https://cdn.jsdelivr.net/gh/igareck/vpn-configs-for-russia@main/$path",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/$path",
        "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/$path",
        "https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/main/$path",
    )

    /**
     * Lists from github.com/igareck/vpn-configs-for-russia: nodes are re-tested
     * from a server in Russia every 2-4 hours. Mihomo's url-test then picks the
     * fastest one from the user's own network.
     */
    val all = listOf(
        // ~150 fastest mixed nodes for regular ("black list") blocking
        Source("ru_mobile", igareck("Export/Clash/RU_DIRECT/BLACK_VLESS_RUS_mobile_clash_rudirect.yaml")),
        // Hysteria2 / Trojan / VMess / SS fallback
        Source("ru_other", igareck("Export/Clash/RU_DIRECT/BLACK_SS%2BAll_RUS_clash_rudirect.yaml")),
        // Nodes that pass mobile-operator "white list" mode
        Source("ru_white", igareck("Export/Clash/GLOBAL/WHITE-CIDR-RU-checked-clash-global.yaml")),
    )
}
