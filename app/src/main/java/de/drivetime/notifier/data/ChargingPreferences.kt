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
        return when (this) {
            ANY -> 0
            SLOW -> when {
                power <= 22.0 -> 0
                power <= 50.0 -> 1
                power <= 100.0 -> 2
                else -> 3
            }
            MEDIUM -> when {
                power in 23.0..99.999 -> 0
                power <= 22.0 || power < 150.0 -> 1
                else -> 2
            }
            FAST -> when {
                power in 100.0..149.999 -> 0
                power >= 150.0 -> 1
                power >= 50.0 -> 1
                power > 22.0 -> 2
                else -> 3
            }
            HPC -> when {
                power >= 150.0 -> 0
                power >= 100.0 -> 1
                power >= 50.0 -> 2
                else -> 3
            }
        }
    }
}
