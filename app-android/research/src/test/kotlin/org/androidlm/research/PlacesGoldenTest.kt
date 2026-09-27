package org.androidlm.research

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Reproduces fixtures/places_golden.json (scripts/make_places_golden.py, from scripts/places.py)
 * on fixtures/sample_places.db: the parse of each question, where it is looked for, and every
 * place of the list in order with its tier, score, distance and line. Skipped without fixtures.
 */
class PlacesGoldenTest {

    @Test fun normKey() {
        for ((s, key) in golden.getAsJsonObject("norm_key").entrySet()) {
            assertEquals(s, key.asString, PlacesText.normKey(s))
        }
    }

    @Test fun prompt() = assertEquals(golden.get("prompt").asString, PlacesText.PLACES_SYSTEM)

    @Test fun clip() {
        for (c in golden.getAsJsonArray("clip")) {
            val a = c.asJsonArray
            assertEquals(a[2].asString, PlacesText.clip(a[0].asString, a[1].asInt))
        }
    }

    @Test fun lead() {
        for (c in golden.getAsJsonArray("lead")) {
            val a = c.asJsonArray
            assertEquals(a[0].asString, if (a[2].isJsonNull) null else a[2].asString, PlacesText.leadText(a[0].asString, a[1].asInt))
        }
    }

    @Test fun questions() {
        val here = golden.getAsJsonArray("here").let { it[0].asDouble to it[1].asDouble }
        for (e in golden.getAsJsonArray("cases")) {
            val c = e.asJsonObject
            val q = c.get("question").asString
            val candidates = c.getAsJsonArray("candidates").map { it.asJsonArray[0].asString to it.asJsonArray[1].asBoolean }
            assertEquals(q, candidates, PlacesText.placeCandidates(q))
            val ask = places.parse(q)
            if (c.get("ask").isJsonNull) {
                assertNull(q, ask)
                continue
            }
            val want = c.getAsJsonObject("ask")
            requireNotNull(ask) { "no ask for: $q" }
            assertEquals(q, str(want, "group"), ask.group)
            assertEquals(q, str(want, "diet"), ask.diet)
            assertEquals(q, str(want, "sub"), ask.sub)
            assertEquals(q, str(want, "price"), ask.price)
            assertEquals(q, want.get("here").asBoolean, ask.here)
            assertEquals(q, want.get("restaurant").asBoolean, ask.restaurant)
            assertEquals(q, want.get("asks_hours").asBoolean, PlacesText.asksHours(ask))
            val lk = places.lookup(ask, here)
            if (!c.has("lookup")) {
                assertNull(q, lk)
                continue
            }
            val wl = c.getAsJsonObject("lookup")
            requireNotNull(lk) { "no lookup for: $q" }
            assertEquals(q, wl.get("label").asString, lk.label)
            assertEquals(q, wl.get("radius_km").asDouble, lk.radiusKm, 1e-9)
            assertEquals(q, wl.get("total").asInt, lk.total)
            assertEquals(q, wl.get("origin").asString, lk.origin)
            assertEquals(q, if (wl.get("city").isJsonNull) null else wl.get("city").asLong, lk.city?.id)
            assertEquals(q, wl.get("capped").asBoolean, lk.capped)
            assertEquals(q, wl.get("where").asString, PlacesText.whereText(lk.total, lk.radiusKm, lk.label, ask, lk.capped))
            val wp = wl.getAsJsonArray("places")
            assertEquals(q, wp.map { it.asJsonObject.get("id").asLong }, lk.places.map { it.id })
            for ((i, pe) in wp.withIndex()) {
                val w = pe.asJsonObject
                val p = lk.places[i]
                val at = "$q / ${p.name}"
                assertEquals(at, w.get("tier").asInt, p.tier)
                assertEquals(at, w.get("score").asDouble, p.score, 1e-9)
                assertEquals(at, w.get("km").asDouble, p.km, 1e-9)
                assertEquals(at, w.getAsJsonArray("why").map { it.asString }, p.why)
                assertEquals(at, w.get("line").asString, PlacesText.describe(p, i + 1, null, lk.origin))
                assertEquals(at, w.get("model_line").asString,
                    PlacesText.describe(p, i + 1, null, lk.origin, brief = true, hours = PlacesText.asksHours(ask)))
                assertEquals(at, w.get("model_line_wiki").asString,
                    PlacesText.describe(p, i + 1, null, lk.origin, brief = true, hours = PlacesText.asksHours(ask),
                        wikiText = "An article's start."))
            }
        }
    }

    private fun str(o: JsonObject, k: String): String? = o.get(k).let { if (it.isJsonNull) null else it.asString }

    companion object {
        private lateinit var db: JdbcSqlDatabase
        private lateinit var places: Places
        private lateinit var golden: JsonObject
        private var opened = false

        @JvmStatic
        @BeforeClass
        fun open() {
            val dir = File(
                System.getProperty("ANDROIDLM_FIXTURES") ?: System.getenv("ANDROIDLM_FIXTURES")
                    ?: (System.getProperty("user.home") + "/androidlm-tools/fixtures"),
            )
            val dbFile = File(dir, "sample_places.db")
            val goldenFile = File(dir, "places_golden.json")
            assumeTrue("places fixtures not found in $dir", dbFile.isFile && goldenFile.isFile)
            db = JdbcSqlDatabase(dbFile)
            places = Places(db)
            golden = JsonParser.parseString(goldenFile.readText()).asJsonObject
            opened = true
        }

        @JvmStatic
        @AfterClass
        fun close() {
            if (opened) db.close()
        }
    }
}
