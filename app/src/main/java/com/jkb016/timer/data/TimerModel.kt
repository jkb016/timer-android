package com.jkb016.timer.data

import java.util.UUID

enum class TimerStatus {
    IDLE,
    RUNNING,
    PAUSED,
    FINISHED
}

data class TimerHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val totalSeconds: Long,
    val label: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class ActiveTimerState(
    val id: String = UUID.randomUUID().toString(),
    val originalSeconds: Long = 0L,
    val currentTotalSeconds: Long = 0L,
    val remainingMillis: Long = 0L,
    val status: TimerStatus = TimerStatus.IDLE,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val endTimeMillis: Long = 0L
) {
    val progress: Float
        get() = if (currentTotalSeconds > 0) {
            (remainingMillis.toFloat() / (currentTotalSeconds * 1000f)).coerceIn(0f, 1f)
        } else 0f

    val displayOriginalLabel: String
        get() = "${formatSeconds(originalSeconds)} timer"

    companion object {
        fun formatSeconds(totalSeconds: Long): String {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return when {
                h > 0 -> String.format("%d:%02d:%02d", h, m, s)
                else -> String.format("%d:%02d", m, s)
            }
        }
    }
}
