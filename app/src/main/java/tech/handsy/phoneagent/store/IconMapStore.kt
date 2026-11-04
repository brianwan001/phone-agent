package tech.handsy.phoneagent.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 桌面图标位置记忆:app 名 → 第几页(home 相对页) + 坐标。
 * find_app 第一次扫描时学习,之后直达。
 */
class IconMapStore(context: Context) {

    private val file = File(context.filesDir, "iconmap.json")

    data class IconPos(val name: String, val page: Int, val cx: Int, val cy: Int, var hits: Int = 1)

    fun load(): MutableMap<String, IconPos> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val arr = JSONArray(file.readText())
            val map = mutableMapOf<String, IconPos>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("name").lowercase()] = IconPos(
                    o.getString("name"),
                    o.getInt("page"),
                    o.getInt("cx"),
                    o.getInt("cy"),
                    o.optInt("hits", 1),
                )
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun remember(name: String, page: Int, cx: Int, cy: Int) {
        val map = load()
        val key = name.trim().lowercase()
        val old = map[key]
        if (old != null) old.hits++ else map[key] = IconPos(name.trim(), page, cx, cy)
        val sorted = map.values.sortedByDescending { it.hits }.take(80)
        val arr = JSONArray()
        for (p in sorted) {
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("page", p.page)
                    .put("cx", p.cx)
                    .put("cy", p.cy)
                    .put("hits", p.hits),
            )
        }
        file.writeText(arr.toString(2))
    }
}
