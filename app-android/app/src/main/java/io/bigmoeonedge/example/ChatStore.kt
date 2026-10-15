package io.bigmoeonedge.example

import android.content.Context
import android.util.Log
import org.androidlm.research.Generation
import org.androidlm.research.PhaseTiming
import org.androidlm.research.ResearchPhase
import org.androidlm.research.ResearchSource
import org.androidlm.research.Route
import org.androidlm.research.RouteDecision
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** A saved conversation, as the list of chats shows it. */
data class ChatMeta(val id: String, val title: String, val updatedMs: Long)

/**
 * AndroidLM: conversations kept on the phone, one JSON file per chat in the app's own storage
 * (filesDir/chats), so they outlive the app process. A chat is its research turns, oldest first.
 * Nothing leaves the phone; deleting a chat deletes its file.
 */
object ChatStore {
    private const val DIR = "chats"
    private const val VERSION = 1
    private const val TAG = "AndroidLM"
    // writes happen off the main thread, one at a time and in order
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "chat-store") }

    fun newId(): String = System.currentTimeMillis().toString()

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR)

    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    /** The saved chats, most recently updated first. Reads every file's head: call off the main thread. */
    fun list(ctx: Context): List<ChatMeta> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                ChatMeta(o.getString("id"), o.optString("title"), o.optLong("updated", f.lastModified()))
            } catch (t: Throwable) {
                Log.w(TAG, "unreadable chat ${f.name}", t)
                null
            }
        }.sortedByDescending { it.updatedMs }

    /** A chat's turns, oldest first; null if it cannot be read. A turn saved mid-run reads as stopped. */
    fun load(ctx: Context, id: String): List<ResearchUi>? = try {
        val turns = JSONObject(file(ctx, id).readText()).getJSONArray("turns")
        (0 until turns.length()).map { i ->
            turnFrom(turns.getJSONObject(i)).let { if (it.running) it.copy(phase = ResearchPhase.CANCELLED) else it }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "chat $id not loaded", t)
        null
    }

    /** Saves [turns] as chat [id] in the background (atomically: a crash never leaves half a file). */
    fun save(ctx: Context, id: String, turns: List<ResearchUi>) {
        if (turns.isEmpty()) return
        val app = ctx.applicationContext
        val json = JSONObject()
            .put("version", VERSION)
            .put("id", id)
            .put("title", titleOf(turns))
            .put("updated", System.currentTimeMillis())
            .put("turns", JSONArray().apply { turns.forEach { put(turnJson(it)) } })
            .toString()
        writer.execute {
            try {
                val d = dir(app).apply { mkdirs() }
                val tmp = File(d, "$id.json.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file(app, id))) throw java.io.IOException("rename failed")
            } catch (t: Throwable) {
                Log.w(TAG, "chat $id not saved", t)
            }
        }
    }

    fun delete(ctx: Context, id: String) {
        val app = ctx.applicationContext
        writer.execute { file(app, id).delete() }
    }

    /** The first question, on one line, cut at about 60 characters. */
    fun titleOf(turns: List<ResearchUi>): String {
        val q = turns.firstOrNull()?.question?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return if (q.length <= 60) q else q.take(57).trimEnd() + "…"
    }

    // ── JSON ──

    private fun turnJson(r: ResearchUi): JSONObject = JSONObject().apply {
        put("question", r.question)
        put("phase", r.phase.name)
        r.titles?.let { put("titles", JSONArray(it)) }
        r.route?.let { d ->
            put("route", JSONObject().put("route", d.route.name).put("views", d.views ?: JSONObject.NULL)
                .put("travel", d.travel).put("pack", d.pack))
        }
        put("routeThreshold", r.routeThreshold)
        r.sources?.let { s -> put("sources", JSONArray().apply { s.forEach { put(sourceJson(it)) } }) }
        put("sourcesDropped", r.sourcesDropped)
        r.placesWhere?.let { put("placesWhere", it) }
        r.translatedAs?.let { put("translatedAs", it) }
        r.askedAs?.let { put("askedAs", it) }
        put("answer", r.answer)
        r.check?.let { put("check", it) }
        put("timings", JSONArray().apply { r.timings.forEach { put(timingJson(it)) } })
        r.error?.let { put("error", it) }
    }

    private fun turnFrom(o: JSONObject): ResearchUi = ResearchUi(
        question = o.getString("question"),
        phase = enumOr(o.optString("phase"), ResearchPhase.DONE),
        titles = o.optJSONArray("titles")?.let { a -> (0 until a.length()).map { a.getString(it) } },
        route = o.optJSONObject("route")?.let { d ->
            RouteDecision(enumOr(d.optString("route"), Route.ANSWER_FIRST),
                if (d.isNull("views")) null else d.optLong("views"), d.optBoolean("travel"), d.optBoolean("pack"))
        },
        routeThreshold = o.optLong("routeThreshold"),
        sources = o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { sourceFrom(a.getJSONObject(it)) } },
        sourcesDropped = o.optInt("sourcesDropped"),
        placesWhere = o.optStringOrNull("placesWhere"),
        translatedAs = o.optStringOrNull("translatedAs"),
        askedAs = o.optStringOrNull("askedAs"),
        answer = o.optString("answer"),
        check = o.optStringOrNull("check"),
        timings = o.optJSONArray("timings")?.let { a -> (0 until a.length()).mapNotNull { timingFrom(a.getJSONObject(it)) } }
            ?: emptyList(),
        error = o.optStringOrNull("error"),
    )

    private fun sourceJson(s: ResearchSource) = JSONObject()
        .put("number", s.number).put("title", s.title).put("section", s.section).put("text", s.text).put("via", s.via)
        .put("lat", s.lat ?: JSONObject.NULL).put("lon", s.lon ?: JSONObject.NULL)

    private fun sourceFrom(o: JSONObject) = ResearchSource(
        o.getInt("number"), o.optString("title"), o.optString("section"), o.optString("text"), o.optString("via"),
        if (o.isNull("lat")) null else o.optDouble("lat"), if (o.isNull("lon")) null else o.optDouble("lon"),
    )

    private fun timingJson(t: PhaseTiming) = JSONObject().apply {
        put("phase", t.phase.name)
        put("wallMs", t.wallMs)
        t.workMs?.let { put("workMs", it) }
        t.generation?.let { g ->
            put("generation", JSONObject().put("tokens", g.tokens).put("tokensPerSecond", g.tokensPerSecond)
                .put("promptTokens", g.promptTokens).put("wallSeconds", g.wallSeconds))
        }
    }

    private fun timingFrom(o: JSONObject): PhaseTiming? {
        val phase = runCatching { ResearchPhase.valueOf(o.getString("phase")) }.getOrNull() ?: return null
        return PhaseTiming(
            phase = phase,
            wallMs = o.optLong("wallMs"),
            generation = o.optJSONObject("generation")?.let { g ->
                Generation("", g.optInt("tokens"), g.optDouble("tokensPerSecond"), g.optInt("promptTokens", -1), g.optDouble("wallSeconds"))
            },
            workMs = if (o.has("workMs")) o.optLong("workMs") else null,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(default)

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}
