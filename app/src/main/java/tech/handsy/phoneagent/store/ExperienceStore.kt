package tech.handsy.phoneagent.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone's long-term memory: every successful run is distilled into a compact
 * "lesson" (goal => action path). Future runs with similar goals recall the most
 * relevant lessons, so the agent gets faster and more reliable over time.
 */
class ExperienceStore(context: Context) {

    private val file = File(context.filesDir, "experience.json")

    private val stopwords = setOf(
        "open", "the", "on", "and", "for", "a", "to", "in", "of", "then", "use", "only",
        "do", "not", "press", "report", "用", "的", "去", "找", "给", "一下", "打开",
        "搜索", "然后", "告诉", "我", "在", "上", "找一下",
    )

    data class Experience(val keywords: MutableList<String>, var lesson: String, var hits: Int)

    private fun load(): MutableList<Experience> {
        val list = mutableListOf<Experience>()
        if (!file.exists()) return list
        return try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kws = mutableListOf<String>()
                val ka = o.getJSONArray("keywords")
                for (j in 0 until ka.length()) kws.add(ka.getString(j))
                list.add(Experience(kws, o.getString("lesson"), o.getInt("hits")))
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(list: List<Experience>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("keywords", JSONArray(e.keywords))
                    .put("lesson", e.lesson)
                    .put("hits", e.hits),
            )
        }
        file.writeText(arr.toString(2))
    }

    private fun keywordsOf(goal: String): List<String> =
        goal.lowercase()
            .split(Regex("[^\\w\\u4e00-\\u9fa5]+"))
            .filter { it.length > 1 && it !in stopwords }
            .distinct()
            .take(6)

    /** Called when a run finishes OK: distill goal + action path into a lesson. */
    fun recordSuccess(goal: String, steps: List<StepRecord>) {
        val keywords = keywordsOf(goal)
        if (keywords.isEmpty() || steps.isEmpty()) return
        val path = steps.take(16)
            .joinToString(" > ") { it.actions.joinToString("+").take(60) }
        val lesson = "$goal ==> $path".take(700)
        val list = load()
        val existing = list.firstOrNull { it.lesson.substringBefore("==>").trim() == goal }
        if (existing != null) {
            existing.lesson = lesson
            existing.hits++
        } else {
            list.add(Experience(keywords.toMutableList(), lesson, 1))
        }
        write(list.sortedByDescending { it.hits }.take(30))
    }

    /** Best-matching lessons for a new goal (keyword overlap). */
    fun recall(goal: String, max: Int = 2): List<String> {
        val keywords = keywordsOf(goal)
        if (keywords.isEmpty()) return emptyList()
        return load()
            .map { exp -> exp to exp.keywords.count { k -> keywords.any { g -> g.contains(k) || k.contains(g) } } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Experience, Int>> { it.second }.thenByDescending { it.first.hits })
            .take(max)
            .map { "- (worked ${it.first.hits}x) ${it.first.lesson}" }
    }
}
