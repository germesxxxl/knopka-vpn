package app.butone.vpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ServiceAvailability(
    val name: String,
    val available: Boolean,
    val latencyMs: Long,
)

enum class ConnectionStage {
    PREPARING,
    LOADING_SERVERS,
    STARTING_CORE,
    CHECKING_SERVERS,
    CHECKING_SERVICES,
    TRYING_NEXT,
    RECOVERING,
}

data class ConnectionProgress(
    val stage: ConnectionStage = ConnectionStage.PREPARING,
    val detail: String = "Подготавливаем защищённое соединение",
    val startedAt: Long = System.currentTimeMillis(),
    val candidateIndex: Int = 0,
    val candidateTotal: Int = 0,
    val alive: Int = 0,
    val total: Int = 0,
    val node: String? = null,
    /** Completed checks; missing services are still pending. */
    val services: List<ServiceAvailability> = emptyList(),
)

sealed interface VpnStatus {
    data object Disconnected : VpnStatus
    data class Connecting(val progress: ConnectionProgress = ConnectionProgress()) : VpnStatus

    /** [node] is null until Mihomo has finished the first round of ping tests. */
    data class Connected(
        val node: String? = null,
        val delayMs: Int? = null,
        val alive: Int = 0,
        val total: Int = 0,
        val serviceOk: Int = 0,
        val serviceTotal: Int = 4,
        val services: List<ServiceAvailability> = emptyList(),
        /** regular, restricted or reserve; shown in diagnostics, not as a protocol detail. */
        val pool: String? = null,
        /** When a working node was first found (System.currentTimeMillis), for the session timer. */
        val since: Long? = null,
        /** Present only while an existing connection is being re-checked or replaced. */
        val progress: ConnectionProgress? = null,
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

    /** Secret for Mihomo's local controller API of the current session (for diagnostics). */
    @Volatile
    var apiSecret: String? = null

    val isActive: Boolean
        get() = status.value is VpnStatus.Connected || status.value is VpnStatus.Connecting
}
