package com.yash.novamobile

import android.content.Context
import android.content.Intent

object AppResolver {
    /** Returns (label, packageName) of the best matching launchable app. */
    fun find(ctx: Context, spoken: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val sp = Fuzzy.normalize(spoken)
        if (sp.isEmpty()) return null
        val spSk = Fuzzy.skeleton(sp)
        var best: Pair<String, String>? = null
        var bestScore = 0.0
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val label = ri.loadLabel(pm).toString()
            val n = Fuzzy.normalize(label)
            val s = maxOf(
                Fuzzy.similarity(sp, n),
                if (spSk.length >= 2) Fuzzy.similarity(spSk, Fuzzy.skeleton(label)) * 0.9 else 0.0
            )
            if (s > bestScore) { bestScore = s; best = label to ri.activityInfo.packageName }
        }
        return best.takeIf { bestScore >= 0.6 }
    }
}
