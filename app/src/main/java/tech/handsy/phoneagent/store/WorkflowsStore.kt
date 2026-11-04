package tech.handsy.phoneagent.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 用户保存的工作流:一句话目标 + 元信息,一键重跑。 */
class WorkflowsStore(context: Context) {

    private val file = File(context.filesDir, "workflows.json")

    data class Workflow(
        val id: String,
        var name: String,
        var goal: String,
        var createdAt: Long,
        var runCount: Int = 0,
        var lastStatus: String = "",
    )

    fun load(): MutableList<Workflow> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<Workflow>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Workflow(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        goal = o.getString("goal"),
                        createdAt = o.getLong("createdAt"),
                        runCount = o.optInt("runCount"),
                        lastStatus = o.optString("lastStatus"),
                    ),
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(list: List<Workflow>) {
        val arr = JSONArray()
        for (w in list) {
            arr.put(
                JSONObject()
                    .put("id", w.id)
                    .put("name", w.name)
                    .put("goal", w.goal)
                    .put("createdAt", w.createdAt)
                    .put("runCount", w.runCount)
                    .put("lastStatus", w.lastStatus),
            )
        }
        file.writeText(arr.toString(2))
    }

    fun save(name: String, goal: String): Workflow {
        val list = load()
        val existing = list.firstOrNull { it.goal == goal.trim() }
        if (existing != null) {
            existing.name = name
            write(list)
            return existing
        }
        val w = Workflow(
            id = "wf-${System.currentTimeMillis()}",
            name = name.ifBlank { goal.take(16) },
            goal = goal.trim(),
            createdAt = System.currentTimeMillis(),
        )
        list.add(0, w)
        write(list.take(50))
        return w
    }

    fun delete(id: String) = write(load().filterNot { it.id == id })

    fun persistUpdate(wf: Workflow) {
        val list = load()
        val idx = list.indexOfFirst { it.id == wf.id }
        if (idx >= 0) {
            list[idx] = wf
            write(list)
        }
    }

    fun get(id: String): Workflow? = load().firstOrNull { it.id == id }

    fun markRun(id: String, status: String) {
        val list = load()
        list.firstOrNull { it.id == id }?.let {
            it.runCount++
            it.lastStatus = status
        }
        write(list)
    }
}
