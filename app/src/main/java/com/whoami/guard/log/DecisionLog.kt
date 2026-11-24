package com.whoami.guard.log

import android.content.Context
import java.io.File

/**
 * Small persistent decision log — every check the app performs, one line each.
 * File: filesDir/decision_log.txt (app-private). Capped so it can't grow forever.
 */
object DecisionLog {

    private const val MAX_LINES = 400

    enum class Trigger(val label: String) {
        UNLOCK("unlock"),
        WHATSAPP("whatsapp"),
        MANUAL("manual")
    }

    enum class Outcome(val label: String) {
        MATCH("MATCH"),
        NO_MATCH("NO_MATCH"),
        UNCERTAIN("uncertain"),
        ERROR("error"),
        INFO("info")
    }

    fun append(
        context: Context,
        trigger: Trigger,
        outcome: Outcome,
        detail: String
    ) {
        runCatching {
            val file = File(context.filesDir, "decision_log.txt")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val line = "$ts | ${trigger.label} | ${outcome.label} | $detail\n"

            val existing = if (file.exists()) file.readLines() else mutableListOf()
            val lines = (existing + line.trimEnd()).takeLast(MAX_LINES)
            file.writeText(lines.joinToString("\n") + "\n")
        }
    }

    /** Most recent [count] log lines, newest first. */
    fun tail(context: Context, count: Int): List<String> = runCatching {
        val file = File(context.filesDir, "decision_log.txt")
        if (!file.exists()) return emptyList()
        file.readLines().takeLast(count).reversed()
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        File(context.filesDir, "decision_log.txt").delete()
    }
}
