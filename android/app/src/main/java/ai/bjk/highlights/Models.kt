package ai.bjk.highlights

import kotlinx.serialization.Serializable

data class League(val id: String, val orgId: Int, val name: String)

val LEAGUES = listOf(
    League("super-lig", 18, "Trendyol Süper Lig"),
    League("ingiltere-premier-ligi", 17, "İngiltere Premier Lig"),
    League("ispanya-la-liga", 60, "İspanya La Liga"),
)

/** Public remux host — HD YouTube cuts and La Liga highlights are served from here. */
const val BEINV = "https://beinv.bjk.ai"

fun League.usesHdToggle() = id == "super-lig" || id == "ingiltere-premier-ligi"
fun League.isLaLiga() = id == "ispanya-la-liga"

fun hdVideoUrl(league: League, seasonId: Int, round: Int, matchId: Long): String =
    "$BEINV/video/m/$matchId?l=${league.id}&s=$seasonId&r=$round&q=hd"

/** Swap the full-highlight URL onto the remux proxy when HD is on (or for La Liga). Goal clips stay on beIN. */
fun Match.playable(league: League, seasonId: Int, round: Int, hd: Boolean): Match {
    val id = matchId ?: return this
    return when {
        league.isLaLiga() -> copy(highlightVideoUrl = hdVideoUrl(league, seasonId, round, id))
        hd && league.usesHdToggle() -> copy(highlightVideoUrl = hdVideoUrl(league, seasonId, round, id))
        else -> this
    }
}

enum class Mode(val label: String) { Highlights("Highlights"), Goals("Goals"), ByTeam("By team") }

@Serializable
data class OrgResponse(val Data: OrgData? = null)

@Serializable
data class OrgData(val seasons: List<Season>? = null)

@Serializable
data class Season(
    val id: Int? = null,
    val name: String? = null,
    val isCurrent: Boolean? = null,
    val beinSportsFixtureWeekList: List<Week>? = null,
)

@Serializable
data class Week(
    val round: Int? = null,
    val weekName: String? = null,
    val currentWeekForFixture: Boolean? = null,
)

@Serializable
data class EventsResponse(val Data: EventsData? = null)

@Serializable
data class EventsData(val events: List<Match>? = null)

@Serializable
data class Match(
    val matchId: Long? = null,
    val matchDate: String? = null,
    val highLightTitle: String? = null,
    val highlightThumbnail: String? = null,
    val highlightVideoUrl: String? = null,
    val homeTeam: Team? = null,
    val awayTeam: Team? = null,
    val matchEvents: List<MatchEvent>? = null,
) {
    val title get() = highLightTitle ?: "${homeTeam?.name ?: ""} - ${awayTeam?.name ?: ""}"
    val scoreLine get() =
        "${homeTeam?.name ?: ""} ${scoreText(homeTeam)}–${scoreText(awayTeam)} ${awayTeam?.name ?: ""}"
    fun goals() = matchEvents.orEmpty().filter { it.isGoal && it.playUrl != null }

    /** Full highlight (when there is one) followed by every clip of the match. */
    fun clips(): List<Clip> = buildList {
        highlightVideoUrl?.takeIf { it.isNotBlank() }
            ?.let { add(Clip("Full highlight", scoreLine, it, false, match = scoreLine)) }
        matchEvents.orEmpty().forEach { e -> if (e.playUrl != null) add(e.clip(this@Match)) }
    }
}

/** A score upstream did not report renders as an en dash on every client. */
fun scoreText(t: Team?): String = t?.matchScore?.toString() ?: "–"

@Serializable
data class Team(
    val name: String? = null,
    val logo: String? = null,
    val matchScore: Int? = null,
)

@Serializable
data class MatchEvent(
    val id: Long? = null,
    val minute: Int? = null,
    val description: String? = null,
    val type: Int? = null,
    val sourceVideoUrl: String? = null,
    val videoUrl: String? = null,
    /** "Home" / "Away" (null for unknown / own goal attribution missing). */
    val eventTeamSide: String? = null,
) {
    val isGoal get() = type == 0
    val side: Side? get() = when (eventTeamSide?.trim()?.lowercase()) {
        "home" -> Side.Home
        "away" -> Side.Away
        else -> null
    }
    val playUrl get() = sourceVideoUrl?.takeIf { it.isNotBlank() } ?: videoUrl?.takeIf { it.isNotBlank() }
    fun clip(m: Match) = Clip(
        "${minute ?: "?"}' ${description ?: ""}".trim(), m.scoreLine, playUrl!!, isGoal,
        minute = minute, scorer = description, match = m.scoreLine,
        logo = when (side) { Side.Home -> m.homeTeam?.logo; Side.Away -> m.awayTeam?.logo; null -> null },
    )
}

enum class Side { Home, Away }

/**
 * One goal with the scoring team and the running score *after* that goal.
 * Computed by walking the match's goal events in minute order and incrementing the scoring side;
 * unknown side (null) leaves the score unchanged and [team] null ("—").
 */
data class GoalRow(
    val event: MatchEvent, val match: Match, val side: Side?, val team: Team?, val home: Int, val away: Int,
    /** Week (round number + label) the match belongs to; used for playlist ordering and the clip selector. */
    val round: Int = 0, val week: String? = null,
) {
    val scoreText get() = "$home–$away"
    /** Canonical item title (§2b, same on all clients): `3. Hafta · Beşiktaş 2–1 Trabzonspor · 55' Jota Silva`. */
    val label get() = listOfNotNull(
        week, "${match.homeTeam?.name ?: ""} $scoreText ${match.awayTeam?.name ?: ""}".trim(),
        "${event.minute ?: "?"}' ${event.description ?: ""}".trim(),
    )
        .joinToString(" · ")
    fun clip() = Clip(
        label,
        "${team?.name ?: "—"} · $scoreText",
        event.playUrl!!, true,
        minute = event.minute, scorer = event.description, logo = team?.logo, score = scoreText, week = week,
        match = "${match.homeTeam?.name ?: ""} $scoreText ${match.awayTeam?.name ?: ""}".trim(),
    )
}

/**
 * Playlist order for "Play all" (§2b): week ascending → match kick-off ascending → goal minute ascending.
 * Applied to exactly the visible rows; tapping a single row opens this same list positioned at that row.
 */
fun orderedPlaylist(rows: List<GoalRow>): List<GoalRow> = rows.sortedWith(
    compareBy({ it.round }, { it.match.matchDate ?: "" }, { it.event.minute ?: 0 })
)

/** Goal rows (playable ones only) with running score; the score walk includes every goal event. */
fun Match.goalRows(round: Int = 0, week: String? = null): List<GoalRow> {
    var h = 0; var a = 0
    return matchEvents.orEmpty().filter { it.isGoal }
        .sortedBy { it.minute ?: 0 }
        .map { e ->
            val side = e.side
            when (side) { Side.Home -> h++; Side.Away -> a++; null -> {} }
            GoalRow(e, this, side, when (side) { Side.Home -> homeTeam; Side.Away -> awayTeam; null -> null }, h, a, round, week)
        }
        .filter { it.event.playUrl != null }
}

/** A match of a given week (used by the per-season team view). */
data class WeekMatch(val round: Int, val match: Match)

@Serializable
data class BeinvTeam(val name: String = "", val logo: String = "", val score: Int? = null)

@Serializable
data class BeinvMatch(
    val id: Long,
    val round: Int = 0,
    val title: String = "",
    val date: String = "",
    val home: BeinvTeam = BeinvTeam(),
    val away: BeinvTeam = BeinvTeam(),
    val thumb: String = "",
    val has_highlight: Boolean = false,
) {
    fun toMatch(league: League, seasonId: Int): Match = Match(
        matchId = id,
        matchDate = date,
        highLightTitle = title,
        highlightThumbnail = thumb,
        highlightVideoUrl = hdVideoUrl(league, seasonId, round, id),
        homeTeam = Team(home.name, home.logo, home.score),
        awayTeam = Team(away.name, away.logo, away.score),
        matchEvents = emptyList(),
    )
}

data class Clip(
    val title: String, val subtitle: String?, val url: String, val goal: Boolean,
    // Goal-only metadata for the in-player clip selector (null for full highlights / non-goal clips).
    val minute: Int? = null, val scorer: String? = null, val logo: String? = null,
    val score: String? = null, val week: String? = null,
    /** Match line shown under the scorer (`Beşiktaş 2–1 Trabzonspor`). */
    val match: String? = null,
)

data class Playlist(val title: String, val clips: List<Clip>, val start: Int = 0)
