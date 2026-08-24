package ai.bjk.highlights

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object Api {
    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/126 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val seasonsCache = ConcurrentHashMap<String, List<Season>>()
    private val matchesCache = ConcurrentHashMap<String, List<Match>>()
    private val seasonMatchesCache = ConcurrentHashMap<String, List<WeekMatch>>()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    suspend fun seasons(league: League): List<Season> {
        seasonsCache[league.id]?.let { return it }
        val body = get("https://apigateway.beinsports.com.tr/api/organizations/v3/rewriteid/${league.id}")
        val list = json.decodeFromString<OrgResponse>(body).Data?.seasons.orEmpty()
            .filter { it.id != null }
        seasonsCache[league.id] = list
        return list
    }

    suspend fun matches(league: League, seasonId: Int, round: Int): List<Match> {
        val key = "${league.orgId}/$seasonId/$round"
        matchesCache[key]?.let { return it }
        val filtered = if (league.isLaLiga()) {
            val body = get("$BEINV/api/leagues/${league.id}/seasons/$seasonId/weeks/$round")
            runCatching { json.decodeFromString<List<BeinvMatch>>(body) }.getOrDefault(emptyList())
                .filter { it.has_highlight }
                .map { it.toMatch(league, seasonId) }
                .sortedBy { it.matchDate ?: "" }
        } else {
            val body = get("https://beinsports.com.tr/api/highlights/events?sp=1&o=${league.orgId}&s=$seasonId&r=$round&st=0")
            val list = if (body.trim().length < 3) emptyList() else runCatching {
                json.decodeFromString<EventsResponse>(body).Data?.events.orEmpty()
            }.getOrDefault(emptyList())
            list.filter { !it.highlightVideoUrl.isNullOrBlank() }
                .sortedBy { it.matchDate ?: "" }
        }
        matchesCache[key] = filtered
        return filtered
    }

    /**
     * Every match of a season (all weeks fetched concurrently, max 6 in flight), cached per season.
     * [onProgress] is called with the number of weeks completed so far.
     */
    suspend fun seasonMatches(
        league: League, seasonId: Int, rounds: List<Int>, onProgress: (Int) -> Unit = {},
    ): List<WeekMatch> {
        val key = "${league.orgId}/$seasonId"
        seasonMatchesCache[key]?.let { return it }
        val gate = Semaphore(6)
        val done = AtomicInteger()
        val result = coroutineScope {
            rounds.map { r ->
                async {
                    gate.withPermit {
                        val m = matches(league, seasonId, r)
                        onProgress(done.incrementAndGet())
                        m.map { WeekMatch(r, it) }
                    }
                }
            }.awaitAll().flatten()
        }
        seasonMatchesCache[key] = result
        return result
    }

    /**
     * Ask the remux host to load the same week/season the user is browsing.
     * That populates `/video` sources and starts YouTube remux warm so the
     * first HD tap is usually instant. Failures are ignored.
     */
    suspend fun warm(league: League, seasonId: Int, round: Int? = null) {
        val url = if (round == null) {
            "$BEINV/api/leagues/${league.id}/seasons/$seasonId/matches"
        } else {
            "$BEINV/api/leagues/${league.id}/seasons/$seasonId/weeks/$round"
        }
        runCatching { get(url) }
    }
}
