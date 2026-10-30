package com.yash.novamobile

import android.content.Context
import android.provider.ContactsContract

data class ContactMatch(val name: String, val number: String, val score: Double)

object ContactResolver {

    // Vosk's small English model mishears many names. Map "what Vosk hears" -> "contact name".
    // Example: "rahool" to "Rahul"
    val ALIASES: Map<String, String> = mapOf(
        // "mom" to "Mummy",
    )

    private const val MIN_SCORE = 0.6

    fun find(ctx: Context, spokenRaw: String): ContactMatch? {
        val spoken = normalize(ALIASES[spokenRaw.lowercase().trim()] ?: spokenRaw)
        if (spoken.isEmpty()) return null

        var best: ContactMatch? = null
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null

        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                val s = score(spoken, normalize(name))
                if (s > (best?.score ?: 0.0)) best = ContactMatch(name, number, s)
            }
        }
        return best?.takeIf { it.score >= MIN_SCORE }
    }

    private fun normalize(s: String) =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun score(spoken: String, name: String): Double {
        if (spoken == name) return 1.0
        var best = similarity(spoken, name)
        // match against single words too (so "rahul" matches "Rahul Sharma")
        for (w in name.split(" ")) {
            if (w == spoken) best = maxOf(best, 0.95)
            else best = maxOf(best, similarity(spoken, w) * 0.95)
        }
        return best
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
