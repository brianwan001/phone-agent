package tech.handsy.phoneagent.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Long-term semantic memory: every finished run triggers a cheap LLM retrospective
 * that distills reusable lessons ("on XHS, the send button stays grey until the text
 * registers"), stored with the app + situation. Future runs recall the most relevant
 * lessons — this is the difference between replaying keystrokes and knowing things.
 */
class MemoryStore(context: Context) {

    private val file = File(context.filesDir, "memory.json")

    data class Lesson(
        val app: String,
        val situation: String,
        var advice: String,
        val verdict: String,
        var hits: Int = 0,
    )

    fun load(): MutableList<Lesson> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<Lesson>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Lesson(
                        app = o.optString("app"),
                        situation = o.optString("situation"),
                        advice = o.optString("advice"),
                        verdict = o.optString("verdict"),
                        hits = o.getInt("hits"),
                    ),
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(list: List<Lesson>) {
        val arr = JSONArray()
        for (l in list) {
            arr.put(
                JSONObject()
                    .put("app", l.app)
                    .put("situation", l.situation)
                    .put("advice", l.advice)
                    .put("verdict", l.verdict)
                    .put("hits", l.hits),
            )
        }
        file.writeText(arr.toString(2))
    }

    /** Adds lessons from a retrospective; merges near-duplicates by advice similarity. */
    fun absorb(lessons: List<Lesson>, maxTotal: Int = 60) {
        if (lessons.isEmpty()) return
        val all = load()
        for (new in lessons) {
            if (new.advice.isBlank() || new.advice.length < 6) continue
            val dup = all.firstOrNull { old ->
                overlap(old.advice, new.advice) > 0.55 || (
                    old.app == new.app && overlap(old.situation, new.situation) > 0.7
                    )
            }
            if (dup != null) {
                dup.hits++
                if (new.advice.length > dup.advice.length) dup.advice = new.advice
            } else {
                all.add(new)
            }
        }
        write(all.sortedByDescending { it.hits }.take(maxTotal))
    }

    /** Most relevant lessons for a new goal on a given app (token overlap scoring). */
    fun recall(goal: String, app: String, max: Int = 3): List<Lesson> {
        val all = load()
        if (all.isEmpty()) return emptyList()
        val goalTokens = tokens(goal)
        return all
            .map { l ->
                var score = l.hits.toFloat() * 0.3f
                if (app.isNotBlank() && l.app == app) score += 3f
                score += 2f * overlap(l.situation, goal)
                score += 1f * (l.advice.split(Regex("\\s+")).count { goalTokens.contains(it.lowercase()) } /
                    8f.coerceAtLeast(1f))
                l to score
            }
            .filter { it.second > 0.8f }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
    }

    private fun tokens(s: String) =
        s.lowercase().split(Regex("[^\\w\\u4e00-\\u9fa5]+")).filter { it.length > 1 }.toSet()

    private fun overlap(a: String, b: String): Float {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        return 2f * ta.intersect(tb).size / (ta.size + tb.size)
    }
}
