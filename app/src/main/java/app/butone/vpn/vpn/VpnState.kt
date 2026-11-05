package app.butone.vpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface VpnStatus {
    data object Disconnected : VpnStatus
    data object Connecting : VpnStatus

    /** [node] is null until Mihomo has finished the first round of ping tests. */
    data class Connected(
        val node: String? = null,
        val delayMs: Int? = null,
        val alive: Int = 0,
        val total: Int = 0,
        /** When a working node was first found (System.currentTimeMillis), for the session timer. */
        val since: Long? = null,
    ) : VpnStatus

    data class Error(val message: String) : VpnStatus
}

/** Process-wide VPN status shared by the service, the activity and the QS tile. */
object VpnState {
    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status: StateFlow<VpnStatus> = _status

    fun set(status: VpnStatus) {
        _status.value = status
    }

    val isActive: Boolean
        get() = status.value is VpnStatus.Connected || status.value is VpnStatus.Connecting
}
