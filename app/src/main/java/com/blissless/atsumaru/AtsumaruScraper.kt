package com.blissless.atsumaru

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Atsumaru (atsu.moe) scraper for the Oni manga client.
 *
 * One chapter at a time — the caller MUST specify which chapter to fetch.
 * The extension never returns a chapter list; it always fetches and returns
 * the image URLs for the single requested chapter.
 *
 * Flow:
 *   1. GET /api/search/manga?q=<manga>&query_by=title&per_page=5
 *      -> pick best match (top hit's `document.id`)
 *   2. GET /api/manga/info?mangaId=<id>
 *      -> all chapters (metadata only, used to find the requested chapter
 *         and to report totalChapters)
 *   3. Find the matching chapter via three-pass matcher (exact number →
 *      case-insensitive title → numeric equality), then fetch its pages:
 *        GET /api/read/chapter?mangaId=<id>&chapterId=<chapterId>
 *      -> `readChapter.pages[].image` (relative URLs — prepend BASE)
 *
 * Response shape (always includes totalChapters so the UI can show the
 * available range even on error):
 *
 *   Success:
 *     { "totalChapters": 402,
 *       "chapter": {
 *         "number": "1",
 *         "title": "Prologue 1",
 *         "group": "Alpha",
 *         "images": ["https://atsu.moe/static/pages/.../0.webp", ...]
 *       } }
 *
 *   Error (no manga name):
 *     { "error": "No manga name provided." }
 *
 *   Error (chapter not found — totalChapters still included):
 *     { "totalChapters": 402,
 *       "error": "Chapter '999' not found. Available range: 1–402." }
 *
 * Chapter key = chapter number (normalized: "1" instead of "1.0").
 */
object AtsumaruScraper {

    private const val BASE = "https://atsu.moe"
    private const val TAG = "Atsumaru"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * @param context    Application context (unused for HTTP; kept for parity).
     * @param mangaName  Manga title to search for.
     * @param anilistId  AniList ID (unused by atsu.moe, but available).
     * @param chapter    REQUIRED chapter identifier (as a string — supports
     *                   "1", "1.5", or even a chapter title like "Prologue 1").
     *                   The scraper finds the matching chapter via a three-pass
     *                   matcher and fetches only its image manifest.
     * @return A Map serializable to JSON. See class KDoc for shapes.
     */
    fun scrape(
        context: Context,
        mangaName: String?,
        anilistId: String?,
        chapter: String?
    ): Any {
        if (mangaName.isNullOrBlank()) {
            return mapOf("error" to "No manga name provided.")
        }
        if (chapter.isNullOrBlank()) {
            return mapOf(
                "error" to "No chapter provided. This extension requires a " +
                "chapter number (e.g. '1', '1.5') or a chapter title " +
                "(e.g. 'Prologue 1')."
            )
        }

        // 1. Search for the manga.
        val mangaId = try {
            searchManga(mangaName)
        } catch (e: Exception) {
            return mapOf("error" to "Search failed: ${e.message}")
        } ?: return mapOf("error" to "No manga found for '$mangaName'.")

        // 2. List chapters (metadata only — used to find the requested
        //    chapter and to report totalChapters in the response). Numbers
        //    are renumbered so continuation blocks (e.g. Dragon Ball Z
        //    restarting at 1 after Dragon Ball's 194) stay globally unique.
        val chapters = try {
            listChapters(mangaId)
        } catch (e: Exception) {
            return mapOf("error" to "Failed to list chapters: ${e.message}")
        }
        val resolved = resolvedChapters(chapters)
        val totalChapters = resolved.size

        // 3. Find the requested chapter and fetch its pages.
        val match = findChapter(resolved, chapter.trim())
        if (match == null) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Chapter '$chapter' not found. " +
                           "Available range: 1–$totalChapters."
            )
        }

        val chapterId = match.optString("id", "")
        if (chapterId.isBlank()) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Matched chapter has invalid id."
            )
        }

        val pages = try {
            fetchChapterPages(mangaId, chapterId)
        } catch (e: Exception) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Failed to fetch chapter $chapter pages: ${e.message}"
            )
        }

        if (pages.isEmpty()) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Chapter $chapter returned no pages."
            )
        }

        val chapterObj = JSONObject()
        chapterObj.put("number", chapter.trim())
        chapterObj.put("title", match.optString("title", ""))
        chapterObj.put("group", "")  // atsu.moe doesn't expose scanlation
                                       // group name on the chapter object
        chapterObj.put("images", JSONArray(pages))

        return mapOf(
            "totalChapters" to totalChapters,
            "chapter" to chapterObj
        )
    }

    /**
     * List all chapters for a manga (metadata only — no image URLs).
     *
     * This is the secondary function called by the Oni app's chapter selection
     * screen. It performs the same search + /api/manga/info flow as [scrape]
     * but returns the FULL chapter list instead of a single chapter's images.
     *
     * Response shape:
     *   Success:
     *     { "totalChapters": 402,
     *       "chapters": [
     *         {"number":"1","title":"Prologue 1","id":"E5PXRSUC","index":0,"pageCount":42},
     *         {"number":"2","title":"Chapter 2","id":"...","index":1,"pageCount":40},
     *         ...
     *       ] }
     *
     *   Error (no manga name):
     *     { "error": "No manga name provided." }
     *
     *   Error (manga not found):
     *     { "error": "No manga found for '...'." }
     *
     * @param context    Application context (unused for HTTP; kept for parity).
     * @param mangaName  Manga title to search for.
     * @param anilistId  AniList ID (unused by atsu.moe, but available).
     * @return A Map serializable to JSON. See class KDoc for shapes.
     */
    fun listChapters(
        context: Context,
        mangaName: String?,
        anilistId: String?
    ): Any {
        if (mangaName.isNullOrBlank()) {
            return mapOf("error" to "No manga name provided.")
        }

        // 1. Search for the manga.
        val mangaId = try {
            searchManga(mangaName)
        } catch (e: Exception) {
            return mapOf("error" to "Search failed: ${e.message}")
        } ?: return mapOf("error" to "No manga found for '$mangaName'.")

        // 2. List chapters (metadata only).
        val chapters = try {
            listChapters(mangaId)
        } catch (e: Exception) {
            return mapOf("error" to "Failed to list chapters: ${e.message}")
        }
        val totalChapters = chapters.length()

        // 3. Renumber continuation blocks (see resolvedChapters) and build a
        //    clean chapter list with normalized, globally-unique numbers.
        val resolved = resolvedChapters(chapters)
        val chapterList = resolved.map { ch ->
            mapOf(
                "number" to ch.optString("number", "?"),
                "title" to ch.optString("title", ""),
                "id" to ch.optString("id", ""),
                "index" to ch.optInt("index", 0),
                "pageCount" to ch.optInt("pageCount", 0)
            )
        }

        return mapOf(
            "totalChapters" to totalChapters,
            "mangaId" to mangaId,
            "chapters" to chapterList
        )
    }

    // ---------- Chapter matching ----------

    /**
     * Find the first chapter whose normalized number matches [requested],
     * falling back to a case-insensitive title match, then numeric equality.
     */
    private fun findChapter(chapters: List<JSONObject>, requested: String): JSONObject? {
        val requestedNorm = requested.trim()

        // Pass 1: exact match on chapter `number`.
        for (ch in chapters) {
            val numStr = ch.optString("number", "?")
            if (numStr == requestedNorm) return ch
        }

        // Pass 2: case-insensitive title match.
        for (ch in chapters) {
            val title = ch.optString("title", "")
            if (title.equals(requestedNorm, ignoreCase = true)) return ch
        }

        // Pass 3: numeric equality — "1" matches 1.0, "1.5" matches 1.5.
        val requestedNum = requestedNorm.toDoubleOrNull()
        if (requestedNum != null) {
            for (ch in chapters) {
                val num = ch.opt("number")
                if (num is Number && num.toDouble() == requestedNum) return ch
            }
        }

        return null
    }

    // ---------- API helpers ----------

    /** Search atsu.moe and return the top hit's manga ID (e.g. "CM0wz"). */
    private fun searchManga(query: String): String? {
        val url = "$BASE/api/search/manga?q=${URLEncoder.encode(query, "UTF-8")}" +
                  "&query_by=title&per_page=5"
        val body = httpGet(url)
        val data = JSONObject(body)
        val hits = data.optJSONArray("hits") ?: return null
        if (hits.length() == 0) return null
        val first = hits.optJSONObject(0) ?: return null
        val doc = first.optJSONObject("document") ?: return null
        return doc.optString("id").takeIf { it.isNotBlank() }
    }

    /** Returns the `chapters` array from /api/manga/info. */
    private fun listChapters(mangaId: String): JSONArray {
        val body = httpGet("$BASE/api/manga/info?mangaId=${URLEncoder.encode(mangaId, "UTF-8")}")
        val data = JSONObject(body)
        return data.optJSONArray("chapters") ?: JSONArray()
    }

    /** Fetches the chapter page manifest and returns absolute image URLs. */
    private fun fetchChapterPages(mangaId: String, chapterId: String): List<String> {
        val url = "$BASE/api/read/chapter?mangaId=${URLEncoder.encode(mangaId, "UTF-8")}" +
                  "&chapterId=${URLEncoder.encode(chapterId, "UTF-8")}"
        val body = httpGet(url)
        val data = JSONObject(body)
        val readChapter = data.optJSONObject("readChapter") ?: return emptyList()
        val pages = readChapter.optJSONArray("pages") ?: return emptyList()

        val out = ArrayList<String>(pages.length())
        for (i in 0 until pages.length()) {
            val page = pages.optJSONObject(i) ?: continue
            val rel = page.optString("image", "")
            if (rel.isBlank()) continue
            // API returns relative URLs like "/static/pages/CM0wz/E5PXRSUC/0.webp".
            out.add(if (rel.startsWith("http")) rel else "$BASE$rel")
        }
        return out
    }

    private fun normalizeChapterNumber(ch: JSONObject): String {
        val raw = ch.opt("number")
        return when (raw) {
            null -> ch.optString("id", "?")
            is Number -> {
                val d = raw.toDouble()
                if (d == d.toLong().toDouble()) d.toLong().toString()
                else d.toString().trimEnd('0').trimEnd('.')
            }
            else -> raw.toString()
        }
    }

    /**
     * Normalize the chapter list so every returned `number` is globally
     * unique, even when the source aggregates multiple series blocks that
     * each restart their numbering at 1 (e.g. atsu.moe's "Dragon Ball"
     * entry lists Dragon Ball 1–194 followed by Dragon Ball Z 1–325).
     *
     * Rule: whenever the numeric sequence stops increasing (current number
     * is strictly below the previous one), a new continuation block begins
     * and its numbers are offset so the run continues (DBZ 1 → 195, …).
     * Series whose chapters are a single ascending run are left untouched.
     */
    private fun resolvedChapters(raw: JSONArray): List<JSONObject> {
        val recs = ArrayList<Pair<Double?, JSONObject>>(raw.length())
        for (i in 0 until raw.length()) {
            val ch = raw.optJSONObject(i) ?: continue
            recs.add(normalizeChapterNumber(ch).toDoubleOrNull() to ch)
        }

        var base = 0.0
        var blockBase = 0.0
        var first = true
        var prevNumber: Double? = null
        val out = ArrayList<JSONObject>(recs.size)
        for ((number, ch) in recs) {
            val restarts = !first && number != null &&
                    prevNumber != null && number < prevNumber
            first = false
            if (restarts) blockBase = base

            val finalNumber = if (number == null) null else blockBase + number
            if (finalNumber != null && finalNumber > base) base = finalNumber
            prevNumber = number

            val clone = JSONObject()
            clone.put("number", finalNumber?.let { formatNumber(it) } ?: "?")
            clone.put("title", ch.optString("title", ""))
            clone.put("id", ch.optString("id", ""))
            clone.put("index", ch.optInt("index", out.size))
            clone.put("pageCount", ch.optInt("pageCount", 0))
            out.add(clone)
        }
        return out
    }

    private fun formatNumber(d: Double): String {
        if (d == d.toLong().toDouble()) return d.toLong().toString()
        return d.toString().trimEnd('0').trimEnd('.')
    }

    // ---------- HTTP ----------

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "$BASE/")
            setRequestProperty("Accept", "application/json, */*;q=0.8")
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IOException("HTTP $code for $urlStr${if (err.isNotBlank()) ": $err" else ""}")
        } finally {
            conn.disconnect()
        }
    }
}
