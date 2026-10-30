package com.yash.novamobile

sealed class Command {
    data class CallName(val name: String) : Command()
    data class CallNumber(val number: String) : Command()
    object Unlock : Command()
    data class OpenApp(val name: String) : Command()
}

object CommandParser {
    private val WAKE = Regex("""\b(hey|hay|hi|a)\s+boy\b""")
    private val CALL = Regex("""^(?:please\s+)?(?:call|phone|dial|ring)\s+(.+)$""")
    private val OPEN = Regex("""(?:open|launch|start)\s+(.+)$""")
    private val UNLOCK = Regex("""^(?:please\s+)?unlock(?:\s+(?:my|the))?(?:\s+(?:phone|screen|device))?$""")

    private val DIGITS = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "one" to "1", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9"
    )

    fun parse(raw: String): Command? {
        var text = raw.lowercase().trim()
        WAKE.find(text)?.let { text = text.substring(it.range.last + 1).trim() }

        CALL.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { target ->
            val tokens = target.split(" ").filter { it.isNotBlank() }
            val digits = tokens.map { t -> if (t.all { it.isDigit() }) t else DIGITS[t] }
            if (digits.all { it != null } && digits.joinToString("").length >= 5)
                return Command.CallNumber(digits.joinToString(""))
            return Command.CallName(target)
        }

        OPEN.find(text)?.groupValues?.get(1)?.trim()?.removeSuffix(" app")?.takeIf { it.isNotEmpty() }
            ?.let { return Command.OpenApp(it) }

        if (UNLOCK.matches(text)) return Command.Unlock
        return null
    }
}
