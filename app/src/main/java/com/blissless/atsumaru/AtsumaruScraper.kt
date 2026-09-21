package com.blissless.atsumaru

import android.content.Context
import android.util.Log
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
 *         "images": ["https://cdn.atsu.moe/static/pages/.../0.webp", ...]
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
    // Page images live on the CDN host, not the API host. The API returns
    // relative paths like /static/pages/..., and hitting them on atsu.moe
    // itself returns HTTP 410 (the origin purged them there). cdn.atsu.moe
    // is the host the site's own CSP connects to for images.
    private const val IMAGE_BASE = "https://cdn.atsu.moe"
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

    /**
     * Search atsu.moe and return the manga ID whose title best matches the
     * query — NOT necessarily the top hit.
     *
     * Typesense tokenizes on spaces, so the exact-title query
     * "BLUE LOCK -EPISODE NAGI-" is scored as "blue lock" and the 2018 main
     * series returns as hits[0], with the real Episode Nagi entry missing from
     * the results entirely. To handle that we:
     *
     *   1. search with the raw query and score each hit by title coverage of
     *      the normalized query tokens (exact normalized-title match wins);
     *   2. if the best hit still misses query tokens, re-search using ONLY the
     *      uncovered tokens (e.g. "episode nagi") and prefer a better match;
     *   3. otherwise fall back to the original behavior (hits[0]).
     */
    private fun searchManga(query: String): String? {
        val queryNorm = normalizeTitle(query)
        val tokens = queryNorm.split(' ').filter { it.isNotBlank() }

        val hits = searchHits(query)
        hits.forEachIndexed { i, hit ->
            Log.d(TAG, "searchManga: hit[$i] title='${hit.title}' id=${hit.id}")
        }
        if (hits.isEmpty()) {
            Log.d(TAG, "searchManga: no hits for '$query'")
            return null
        }

        val best = pickBest(hits, queryNorm, tokens)
        Log.d(TAG, "searchManga: best candidate for '$query' -> " +
                "'${best?.title}' [${best?.id}] tokens=$tokens")

        val covered = best?.let { coveredTokens(it.title, tokens) }.orEmpty()
        val missing = tokens.filterNot { it in covered }
        if (missing.isNotEmpty() && missing.size < tokens.size) {
            val retryQuery = missing.joinToString(" ")
            Log.d(TAG, "searchManga: best hit misses tokens $missing — retrying with '$retryQuery'")
            val retry = searchHits(retryQuery)
            retry.forEachIndexed { i, hit ->
                Log.d(TAG, "searchManga: retry('$retryQuery') hit[$i] title='${hit.title}' id=${hit.id}")
            }
            if (retry.isNotEmpty()) {
                val best2 = pickBest(retry + hits, queryNorm, tokens)
                val currentScore = scoreHit(best?.title.orEmpty(), queryNorm, tokens)
                val retryScore = best2?.let { scoreHit(it.title, queryNorm, tokens) } ?: -1
                if (best2 != null && retryScore > currentScore) {
                    Log.d(TAG, "searchManga: switched to '${best2.title}' [${best2.id}] via retry")
                    return best2.id
                }
            }
        }
        Log.d(TAG, "searchManga: final choice '${best?.title}' [${best?.id}]")
        return best?.id ?: hits.first().id
    }

    private data class SearchHit(val id: String, val title: String)

    /** Runs one search and returns the mangas as (id, title) pairs. */
    private fun searchHits(query: String): List<SearchHit> {
        val url = "$BASE/api/search/manga?q=${URLEncoder.encode(query, "UTF-8")}" +
                  "&query_by=title&per_page=5"
        val body = httpGet(url)
        val data = JSONObject(body)
        val hits = data.optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<SearchHit>(hits.length())
        for (i in 0 until hits.length()) {
            val doc = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
            val id = doc.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = doc.optString("title", "")
            out.add(SearchHit(id, title))
        }
        return out
    }

    /**
     * Pick the candidate with the best title match to the query:
     * +1000 per query token present in the title, +10000 for a normalized
     * prefix/exact-size relationship, +1M for a full normalized-title match.
     * Ties keep the earlier hit.
     */
    private fun pickBest(hits: List<SearchHit>, queryNorm: String, tokens: List<String>): SearchHit? {
        var best: SearchHit? = null
        var bestScore = -1
        for (hit in hits) {
            val s = scoreHit(hit.title, queryNorm, tokens)
            if (s > bestScore) {
                bestScore = s
                best = hit
            }
        }
        return best
    }

    private fun scoreHit(title: String, queryNorm: String, tokens: List<String>): Int {
        val t = normalizeTitle(title)
        var score = tokens.count { t.contains(it) } * 1000
        if (t.isNotEmpty() && (t.startsWith(queryNorm) || queryNorm.startsWith(t))) score += 10_000
        if (t == queryNorm) score += 1_000_000
        return score
    }

    private fun coveredTokens(title: String, tokens: List<String>): Set<String> {
        val t = normalizeTitle(title)
        return tokens.filter { t.contains(it) }.toSet()
    }

    /** Lowercases and keeps only letters/digits as space-separated words. */
    private fun normalizeTitle(s: String): String {
        val sb = StringBuilder()
        var prevSpace = false
        for (c in s.lowercase()) {
            if (c.isLetterOrDigit()) {
                sb.append(c)
                prevSpace = false
            } else if (!prevSpace && sb.isNotEmpty()) {
                sb.append(' ')
                prevSpace = true
            }
        }
        return sb.toString().trim()
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
            // Full pages are only served from the CDN host — prefix with
            // IMAGE_BASE (atsu.moe itself returns 410 Gone for these).
            out.add(if (rel.startsWith("http")) rel else "$IMAGE_BASE$rel")
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
    /**
     * A continuation block is only recognized when the numbers hike back down
     * near 1 (e.g. atsu.moe's "Dragon Ball" lists DB 1–194 then DBZ starting
     * at 1 again). Out-of-order entries like decimal specials ("22.5", "25.2")
     * drop the number by a small amount but are NOT restarts — renumbering
     * them would corrupt the whole series tail (e.g. 25.2 -> 52.2).
     */
    private val RESTART_BLOCK_START_MAX = 10.0

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
                    prevNumber != null && number < prevNumber &&
                    number <= RESTART_BLOCK_START_MAX
            if (restarts) {
                Log.d(TAG, "resolvedChapters: continuation block at " +
                        "number=$number (prev=$prevNumber, base=$base) — offsetting")
            }
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
