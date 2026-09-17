package de.drivetime.notifier.data

enum class ChargingConnectorPreference(val id: String) {
    ANY("any"),
    CCS("ccs"),
    TYPE2("type2"),
    CHADEMO("chademo");

    companion object {
        fun fromId(id: String?): ChargingConnectorPreference =
            entries.firstOrNull { it.id == id } ?: ANY
    }
}

enum class ChargingSpeedPreference(val id: String) {
    ANY("any"),
    SLOW("slow"),
    MEDIUM("medium"),
    FAST("fast"),
    HPC("hpc");

    companion object {
        fun fromId(id: String?): ChargingSpeedPreference =
            entries.firstOrNull { it.id == id } ?: ANY
    }

    fun penalty(powerKw: Double?): Int {
        if (this == ANY) return 0
        val power = powerKw ?: return 3
        val band = when {
            power <= 11.0 -> SLOW
            power <= 22.0 -> MEDIUM
            power <= 100.0 -> FAST
            else -> HPC
        }
        if (band == this) return 0
        val order = listOf(SLOW, MEDIUM, FAST, HPC)
        return kotlin.math.abs(order.indexOf(band) - order.indexOf(this)).coerceIn(1, 3)
    }
}
