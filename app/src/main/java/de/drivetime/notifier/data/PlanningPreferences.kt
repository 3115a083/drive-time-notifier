package de.drivetime.notifier.data

enum class DynamicBufferLevel(val id: String) {
    LOW("low"),
    BALANCED("balanced"),
    CAUTIOUS("cautious");

    companion object {
        fun fromId(id: String?): DynamicBufferLevel =
            entries.firstOrNull { it.id == id } ?: BALANCED
    }
}
