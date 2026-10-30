package com.yash.novamobile

import android.content.Context
import android.provider.ContactsContract

data class ContactMatch(val name: String, val number: String, val score: Double)

object ContactResolver {
    const val KEY_ALIASES = "aliases"   // lines like  rule=Rahul
    private const val MIN_SCORE = 0.6

    private fun aliasFor(ctx: Context, spoken: String): String? {
        val raw = ctx.getSharedPreferences(ListenerService.PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALIASES, "") ?: ""
        val key = Fuzzy.normalize(spoken)
        for (line in raw.lines()) {
            val parts = line.split("=", limit = 2)
            if (parts.size == 2 && Fuzzy.normalize(parts[0]) == key) return parts[1].trim()
        }
        return null
    }

    fun find(ctx: Context, spokenRaw: String): ContactMatch? {
        val spoken = Fuzzy.normalize(aliasFor(ctx, spokenRaw) ?: spokenRaw)
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
                val s = score(spoken, Fuzzy.normalize(name))
                if (s > (best?.score ?: 0.0)) best = ContactMatch(name, number, s)
            }
        }
        return best?.takeIf { it.score >= MIN_SCORE }
    }

    private fun score(spoken: String, name: String): Double {
        if (spoken == name) return 1.0
        val spSk = Fuzzy.skeleton(spoken)
        var best = Fuzzy.similarity(spoken, name)
        for (w in name.split(" ") + name) {
            val textSim = if (w == spoken) 0.97 else Fuzzy.similarity(spoken, w) * 0.95
            val skSim = if (spSk.length >= 2) Fuzzy.similarity(spSk, Fuzzy.skeleton(w)) * 0.9 else 0.0
            best = maxOf(best, textSim, skSim)
        }
        return best
    }
}
