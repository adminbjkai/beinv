package ai.bjk.highlights

import android.content.Context

/** Remembers league / season / week / mode across launches (SharedPreferences). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("highlights", Context.MODE_PRIVATE)

    var leagueIdx: Int
        get() = sp.getInt("league", 0)
        set(v) = sp.edit().putInt("league", v).apply()

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", null) ?: "") }.getOrDefault(Mode.Highlights)
        set(v) = sp.edit().putString("mode", v.name).apply()

    fun season(league: League): Int? = sp.getInt("season_${league.id}", -1).takeIf { it > 0 }
    fun week(league: League): Int? = sp.getInt("week_${league.id}", -1).takeIf { it > 0 }
    fun saveSelection(league: League, seasonId: Int, round: Int) =
        sp.edit().putInt("season_${league.id}", seasonId).putInt("week_${league.id}", round).apply()

    /** Last team picked in By team, per league (null = none). */
    fun team(league: League): String? = sp.getString("team_${league.id}", null)
    fun saveTeam(league: League, name: String?) = sp.edit().putString("team_${league.id}", name).apply()

    /** By team sub-switch (`Matches` | `Goals`) and the `Only <Team> goals` toggle. */
    var teamGoals: Boolean
        get() = sp.getBoolean("teamGoals", false)
        set(v) = sp.edit().putBoolean("teamGoals", v).apply()
    var onlyTeam: Boolean
        get() = sp.getBoolean("onlyTeam", true)
        set(v) = sp.edit().putBoolean("onlyTeam", v).apply()

    /** HD remux for Super Lig / Premier League (default ON). */
    var hd: Boolean
        get() = sp.getBoolean("hd", true)
        set(v) = sp.edit().putBoolean("hd", v).apply()

    /** Highlights/Goals show every week until the user picks one. */
    var allWeeks: Boolean
        get() = sp.getBoolean("allWeeks", true)
        set(v) = sp.edit().putBoolean("allWeeks", v).apply()
}
