package tech.handsy.phoneagent.store

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class StepRecord(
    val index: Int,
    val think: String,
    val actions: List<String>,
    var changed: Boolean? = null,
    var screenshot: String? = null,
)

data class RunRecord(
    val id: String,
    val goal: String,
    val startedAt: Long,
    var finishedAt: Long? = null,
    var status: String = "running", // running | paused | done | failed | stopped
    var result: String = "",
    var model: String = "",
    val steps: MutableList<StepRecord> = mutableListOf(),
)

/**
 * Every run is kept on the phone: run.json next to per-step JPEGs,
 * replayable in the app and exportable as a zip trace.
 */
class RunStore(context: Context) {

    private val root: File = File(context.filesDir, "runs").apply { mkdirs() }
    private val exportDir: File = File(context.cacheDir, "export").apply { mkdirs() }

    fun newRun(goal: String, model: String): RunRecord {
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val record = RunRecord(id = id, goal = goal, startedAt = System.currentTimeMillis(), model = model)
        persist(record)
        return record
    }

    fun saveScreenshot(record: RunRecord, index: Int, bitmap: Bitmap): String {
        val dir = dirFor(record)
        val name = "step-%03d.jpg".format(Locale.US, index)
        File(dir, name).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        return name
    }

    fun screenshotFile(record: RunRecord, name: String): File = File(dirFor(record), name)

    /** Newest step screenshot of a run — for the live "agent view" preview. */
    fun latestScreenshotFile(id: String): File? = try {
        dirForId(id).listFiles { f -> f.name.startsWith("step-") }?.maxByOrNull { it.name }
    } catch (e: Exception) {
        null
    }

    fun persist(record: RunRecord) {
        val steps = JSONArray()
        for (step in record.steps) {
            steps.put(
                JSONObject()
                    .put("index", step.index)
                    .put("think", step.think)
                    .put("actions", JSONArray(step.actions))
                    .put("changed", step.changed ?: JSONObject.NULL)
                    .put("screenshot", step.screenshot ?: JSONObject.NULL),
            )
        }
        val obj = JSONObject()
            .put("id", record.id)
            .put("goal", record.goal)
            .put("startedAt", record.startedAt)
            .put("finishedAt", record.finishedAt ?: JSONObject.NULL)
            .put("status", record.status)
            .put("result", record.result)
            .put("model", record.model)
            .put("steps", steps)
        dirFor(record).resolve("run.json").writeText(obj.toString(2))
    }

    fun listRuns(): List<RunRecord> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { readRecord(it) }
            ?.sortedByDescending { it.startedAt }
            ?: emptyList()

    fun load(id: String): RunRecord? = readRecord(dirForId(id))

    /** Zips run.json with all step screenshots for sharing/export. */
    fun exportZip(record: RunRecord): File {
        val out = File(exportDir, "${record.id}.zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            val dir = dirFor(record)
            dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return out
    }

    private fun dirFor(record: RunRecord): File = dirForId(record.id)

    private fun dirForId(id: String): File = File(root, id).apply { mkdirs() }

    private fun readRecord(dir: File): RunRecord? = try {
        val obj = JSONObject(dir.resolve("run.json").readText())
        val steps = mutableListOf<StepRecord>()
        val arr = obj.getJSONArray("steps")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            steps.add(
                StepRecord(
                    index = s.getInt("index"),
                    think = s.optString("think"),
                    actions = s.getJSONArray("actions").let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    },
                    changed = if (s.isNull("changed")) null else s.getBoolean("changed"),
                    screenshot = if (s.isNull("screenshot")) null else s.optString("screenshot"),
                ),
            )
        }
        RunRecord(
            id = obj.getString("id"),
            goal = obj.getString("goal"),
            startedAt = obj.getLong("startedAt"),
            finishedAt = if (obj.isNull("finishedAt")) null else obj.getLong("finishedAt"),
            status = obj.optString("status", "stopped"),
            result = obj.optString("result"),
            model = obj.optString("model"),
            steps = steps,
        )
    } catch (e: Exception) {
        null
    }
}
