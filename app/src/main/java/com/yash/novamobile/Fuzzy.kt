package com.yash.novamobile

object Fuzzy {
    fun normalize(s: String) =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** Rough "sounds-like" key: Rahul / Raahool / Rahool all become "rhl". */
    fun skeleton(s: String): String {
        var t = normalize(s).replace(" ", "")
        val reps = listOf(
            "ph" to "f", "bh" to "b", "dh" to "d", "gh" to "g", "kh" to "k", "th" to "t",
            "sh" to "s", "ch" to "c", "aa" to "a", "ee" to "i", "oo" to "u",
            "w" to "v", "z" to "j", "q" to "k", "x" to "ks", "y" to "i"
        )
        for ((f, r) in reps) t = t.replace(f, r)
        val sb = StringBuilder()
        for ((i, c) in t.withIndex()) {
            if (i > 0 && c in "aeiouh") continue
            if (sb.isNotEmpty() && sb.last() == c) continue
            sb.append(c)
        }
        return sb.toString()
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
