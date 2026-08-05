package com.ogul.plakakayit.ml

import java.util.Locale

object PlateParser {
    private val candidateRegex = Regex("(0[1-9]|[1-7][0-9]|8[01])[A-Z]{1,3}[0-9]{2,4}")
    private val exactRegex = Regex("^(0[1-9]|[1-7][0-9]|8[01])([A-Z]{1,3})([0-9]{2,4})$")

    fun findPlates(lines: List<String>): Set<String> = buildSet {
        lines.forEach { rawLine ->
            val compact = rawLine
                .uppercase(Locale.US)
                .replace(Regex("[^A-Z0-9]"), "")

            candidateRegex.findAll(compact).forEach { match ->
                val candidate = match.value
                if (isPlausible(candidate)) add(format(candidate))
            }
        }
    }

    internal fun isPlausible(compact: String): Boolean {
        val match = exactRegex.matchEntire(compact) ?: return false
        val letters = match.groupValues[2].length
        val digits = match.groupValues[3].length

        return when (letters) {
            1 -> digits == 4
            2 -> digits in 3..4
            3 -> digits in 2..3
            else -> false
        }
    }

    private fun format(compact: String): String {
        val match = exactRegex.matchEntire(compact) ?: return compact
        return "${match.groupValues[1]} ${match.groupValues[2]} ${match.groupValues[3]}"
    }
}
