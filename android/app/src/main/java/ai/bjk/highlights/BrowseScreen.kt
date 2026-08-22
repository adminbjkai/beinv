package ai.bjk.highlights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface Load<out T> {
    object Loading : Load<Nothing>
    data class Error(val msg: String) : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
}

private data class TeamRef(val name: String, val logo: String?)

/** A match shown in the content area with its week (label is null in week modes, where it's implicit). */
private data class Entry(val week: String?, val round: Int, val match: Match)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(onPlay: (Playlist) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember(ctx) { Prefs(ctx) }
    var leagueIdx by rememberSaveable { mutableIntStateOf(prefs.leagueIdx.coerceIn(0, LEAGUES.size - 1)) }
    val league = LEAGUES[leagueIdx]
    var mode by rememberSaveable { mutableStateOf(prefs.mode) }

    var seasons by remember { mutableStateOf<Load<List<Season>>>(Load.Loading) }
    var seasonId by rememberSaveable { mutableStateOf<Int?>(null) }
    var round by rememberSaveable { mutableStateOf<Int?>(null) }
    var seasonsRetry by remember { mutableIntStateOf(0) }

    LaunchedEffect(league, seasonsRetry) {
        seasons = Load.Loading
        seasonId = null; round = null
        seasons = try {
            val list = Api.seasons(league)
            val s = list.firstOrNull { it.id == prefs.season(league) }
                ?: list.firstOrNull { it.isCurrent == true } ?: list.firstOrNull()
            seasonId = s?.id
            val savedWeek = prefs.week(league)
            round = s?.beinSportsFixtureWeekList?.firstOrNull { it.round == savedWeek }?.round ?: defaultRound(s)
            Load.Ok(list)
        } catch (e: Exception) {
            Load.Error(e.message ?: "Failed to load")
        }
    }
    // Persist selection
    LaunchedEffect(leagueIdx, seasonId, round, mode) {
        prefs.leagueIdx = leagueIdx
        prefs.mode = mode
        val s = seasonId; val r = round
        if (s != null && r != null) prefs.saveSelection(league, s, r)
    }

    val seasonList = (seasons as? Load.Ok)?.value.orEmpty()
    val season = seasonList.firstOrNull { it.id == seasonId }
    val weeks = season?.beinSportsFixtureWeekList.orEmpty().filter { it.round != null }
    fun weekName(r: Int) = weeks.firstOrNull { it.round == r }?.weekName ?: "Week $r"

    // Week matches
    var matches by remember { mutableStateOf<Load<List<Match>>>(Load.Loading) }
    var matchesRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(league, seasonId, round, matchesRetry) {
        val sid = seasonId ?: return@LaunchedEffect
        val r = round ?: return@LaunchedEffect
        matches = Load.Loading
        matches = try {
            Load.Ok(Api.matches(league, sid, r))
        } catch (e: Exception) {
            Load.Error(e.message ?: "Failed to load")
        }
    }

    // Whole season (By team)
    var seasonData by remember { mutableStateOf<Load<List<WeekMatch>>>(Load.Loading) }
    var seasonProgress by remember { mutableIntStateOf(0) }
    var seasonRetry by remember { mutableIntStateOf(0) }
    var team by rememberSaveable { mutableStateOf(prefs.team(league)) }
    var teamGoals by rememberSaveable { mutableStateOf(prefs.teamGoals) }
    var onlyTeam by rememberSaveable { mutableStateOf(prefs.onlyTeam) }
    // Team is remembered per league (restored on relaunch); a team missing from the chosen season falls back to the list.
    LaunchedEffect(league) { team = prefs.team(league) }
    fun pickTeam(name: String) { team = name; prefs.saveTeam(league, name) }
    LaunchedEffect(league, seasonId, weeks.size, mode == Mode.ByTeam, seasonRetry) {
        if (mode != Mode.ByTeam) return@LaunchedEffect
        val sid = seasonId ?: return@LaunchedEffect
        if (weeks.isEmpty()) return@LaunchedEffect
        seasonData = Load.Loading; seasonProgress = 0
        seasonData = try {
            Load.Ok(Api.seasonMatches(league, sid, weeks.map { it.round!! }) { seasonProgress = it })
        } catch (e: Exception) {
            Load.Error(e.message ?: "Failed to load")
        }
    }

    val seasonOk = (seasonData as? Load.Ok)?.value
    val teams = remember(seasonOk) {
        seasonOk.orEmpty().flatMap { listOfNotNull(it.match.homeTeam, it.match.awayTeam) }
            .filter { !it.name.isNullOrBlank() }
            .map { TeamRef(it.name!!, it.logo) }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
    }
    val selectedTeam = teams.firstOrNull { it.name == team }

    Scaffold(
        topBar = {
            Column(Modifier.background(Background).statusBarsPadding()) {
                Text(
                    "Highlights",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                )
                Segmented(LEAGUES.map { it.name }, leagueIdx) { leagueIdx = it }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Season", color = TextGray, style = MaterialTheme.typography.labelLarge)
                    Dropdown(
                        label = season?.name ?: "Season",
                        items = seasonList.map { it.name ?: "?" },
                        modifier = Modifier.weight(1f),
                    ) { i ->
                        val s = seasonList[i]
                        seasonId = s.id
                        round = defaultRound(s)
                    }
                }
                if (mode != Mode.ByTeam) Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Week", color = TextGray, style = MaterialTheme.typography.labelLarge)
                    val wi = weeks.indexOfFirst { it.round == round }
                    IconButton(onClick = { if (wi > 0) round = weeks[wi - 1].round }, enabled = wi > 0) {
                        Icon(Icons.Default.KeyboardArrowLeft, "Previous week")
                    }
                    Dropdown(
                        label = weeks.getOrNull(wi)?.weekName ?: "Week",
                        items = weeks.map { it.weekName ?: "${it.round}" },
                        modifier = Modifier.weight(1f),
                    ) { i -> round = weeks[i].round }
                    IconButton(
                        onClick = { if (wi in 0 until weeks.size - 1) round = weeks[wi + 1].round },
                        enabled = wi in 0 until weeks.size - 1,
                    ) { Icon(Icons.Default.KeyboardArrowRight, "Next week") }
                }
                Segmented(Mode.entries.map { it.label }, mode.ordinal) { mode = Mode.entries[it] }
                if (mode == Mode.ByTeam) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Team", color = TextGray, style = MaterialTheme.typography.labelLarge)
                        Dropdown(
                            label = selectedTeam?.name ?: "Pick a team",
                            items = teams.map { it.name },
                            icons = teams.map { it.logo },
                            leadingIcon = selectedTeam?.logo,
                            modifier = Modifier.weight(1f),
                        ) { i -> pickTeam(teams[i].name) }
                    }
                    Segmented(listOf("Matches", "Goals"), if (teamGoals) 1 else 0) { teamGoals = it == 1; prefs.teamGoals = teamGoals }
                    if (teamGoals) Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Only ${selectedTeam?.name ?: "team"} goals", modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Switch(
                            checked = onlyTeam, onCheckedChange = { onlyTeam = it; prefs.onlyTeam = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Background, checkedTrackColor = Emerald,
                                uncheckedThumbColor = TextGray, uncheckedTrackColor = Surface,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = seasons) {
                Load.Loading -> SkeletonGrid()
                is Load.Error -> Centered { ErrorBox(s.msg) { seasonsRetry++ } }
                is Load.Ok -> if (mode == Mode.ByTeam) {
                    when (val d = seasonData) {
                        Load.Loading -> Centered {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Emerald)
                                Spacer(Modifier.height(12.dp))
                                Text("Loading season… $seasonProgress/${weeks.size}", color = TextGray)
                            }
                        }
                        is Load.Error -> Centered { ErrorBox(d.msg) { seasonRetry++ } }
                        is Load.Ok -> {
                            val t = selectedTeam
                            if (t == null) TeamList(teams) { pickTeam(it.name) }
                            else {
                                val mine = remember(d.value, t.name) {
                                    d.value.filter { it.match.homeTeam?.name == t.name || it.match.awayTeam?.name == t.name }
                                        .sortedByDescending { it.match.matchDate ?: "" }
                                        .map { Entry(weekName(it.round), it.round, it.match) }
                                }
                                val filter: (GoalRow) -> Boolean =
                                    if (onlyTeam) ({ it.team?.name == t.name }) else ({ true })
                                Content(mine, goalsOnly = teamGoals, onPlay = onPlay, title = t.name, goalFilter = filter,
                                    emptyMsg = "No highlights for ${t.name} this season yet.")
                            }
                        }
                    }
                } else when (val m = matches) {
                    Load.Loading -> SkeletonGrid()
                    is Load.Error -> Centered { ErrorBox(m.msg) { matchesRetry++ } }
                    is Load.Ok -> Content(
                        m.value.map { Entry(if (mode == Mode.Goals) round?.let { r -> weekName(r) } else null, round ?: 0, it) },
                        goalsOnly = mode == Mode.Goals,
                        onPlay = onPlay, title = round?.let { weekName(it) } ?: "Goals")
                }
            }
        }
    }
}

@Composable
private fun Content(
    entries: List<Entry>, goalsOnly: Boolean, title: String, onPlay: (Playlist) -> Unit,
    goalFilter: (GoalRow) -> Boolean = { true },
    emptyMsg: String = "No highlights published for this week yet.",
) {
    if (entries.isEmpty()) {
        Centered { Text(emptyMsg, color = TextGray, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)) }
        return
    }
    if (goalsOnly) GoalsView(entries, title, goalFilter, onPlay) else MatchGrid(entries, onPlay)
}

@Composable
private fun MatchGrid(entries: List<Entry>, onPlay: (Playlist) -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp > 600
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (wide) 3 else 2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { (w, _, m) -> "${w}_${m.matchId ?: m.hashCode()}" }) { (w, _, m) ->
            MatchCard(m, w) { onPlay(Playlist(m.title, m.clips())) }
        }
    }
}

/**
 * Goal clips grouped by match; [goalFilter] (e.g. "Only <Team> goals") applies to rows and Play all.
 * Play all / single-goal taps use [orderedPlaylist] (week asc → kick-off asc → minute asc) over the visible rows.
 */
@Composable
private fun GoalsView(
    entries: List<Entry>, title: String, goalFilter: (GoalRow) -> Boolean, onPlay: (Playlist) -> Unit,
) {
    val groups = entries.map { (w, r, m) -> Triple(w, m, m.goalRows(r, w).filter(goalFilter)) }.filter { it.third.isNotEmpty() }
    val ordered = orderedPlaylist(groups.flatMap { it.third })
    val all = ordered.map { it.clip() }
    if (all.isEmpty()) {
        Centered { Text("No goal clips for this selection.", color = TextGray, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)) }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Button(onClick = { onPlay(Playlist("$title · all goals", all, 0)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                Text(
                    "Play all · ${all.size} ${if (all.size == 1) "goal" else "goals"}" +
                        (ordered.first().week?.let { " · from $it" } ?: ""),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        groups.forEach { (w, m, goals) ->
            item {
                Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(m.homeTeam?.logo, m.homeTeam?.name, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(m.scoreLine, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    AsyncImage(m.awayTeam?.logo, m.awayTeam?.name, Modifier.size(20.dp))
                    if (w != null && entries.distinctBy { it.week }.size > 1)
                        Text("  $w", color = TextGray, style = MaterialTheme.typography.labelSmall)
                }
            }
            items(goals.size) { i ->
                GoalCard(goals[i]) { onPlay(Playlist("$title · all goals", all, ordered.indexOf(goals[i]).coerceAtLeast(0))) }
            }
        }
    }
}

/** Minute · scorer · scoring team (logo + name) · running score with the scoring side highlighted. */
@Composable
private fun GoalCard(g: GoalRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (g.team?.logo != null) AsyncImage(g.team.logo, g.team.name, Modifier.size(28.dp))
        else Box(Modifier.size(28.dp).clip(CircleShape).background(Background), contentAlignment = Alignment.Center) {
            Text("—", color = TextGray, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${g.event.minute ?: "?"}' ${g.event.description ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(g.team?.name ?: "—", color = if (g.team != null) Emerald else TextGray,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                withStyle(sideStyle(g.side == Side.Home)) { append(g.home.toString()) }
                append("–")
                withStyle(sideStyle(g.side == Side.Away)) { append(g.away.toString()) }
            },
            fontSize = 18.sp,
        )
    }
}

private fun sideStyle(scoring: Boolean) =
    if (scoring) SpanStyle(color = Emerald, fontWeight = FontWeight.Bold) else SpanStyle(color = TextGray)

@Composable
private fun TeamList(teams: List<TeamRef>, onPick: (TeamRef) -> Unit) {
    if (teams.isEmpty()) { Centered { Text("No matches in this season yet.", color = TextGray) }; return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Pick a team", color = TextGray, modifier = Modifier.padding(bottom = 4.dp)) }
        items(teams, key = { it.name }) { t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface)
                    .clickable { onPick(t) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(t.logo, t.name, Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text(t.name)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        labels.forEachIndexed { i, l ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Emerald,
                    activeContentColor = Background,
                    inactiveContainerColor = Surface,
                    inactiveContentColor = OnDark,
                ),
            ) { Text(l, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun defaultRound(s: Season?): Int? {
    val w = s?.beinSportsFixtureWeekList.orEmpty()
    return (w.firstOrNull { it.currentWeekForFixture == true } ?: w.lastOrNull())?.round
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SkeletonGrid() {
    val wide = LocalConfiguration.current.screenWidthDp > 600
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (wide) 3 else 2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        items(6) {
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Surface)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Background.copy(alpha = 0.6f)))
                Box(Modifier.padding(10.dp).fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Background.copy(alpha = 0.6f)))
                Box(Modifier.padding(start = 10.dp, bottom = 12.dp).fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(Background.copy(alpha = 0.6f)))
            }
        }
    }
}

@Composable
private fun ErrorBox(msg: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text("Couldn't load: $msg", color = TextGray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun Dropdown(
    label: String, items: List<String>, modifier: Modifier = Modifier,
    icons: List<String?>? = null, leadingIcon: String? = null, onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = items.isNotEmpty(),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            if (leadingIcon != null) { AsyncImage(leadingIcon, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Surface)) {
            items.forEachIndexed { i, t ->
                DropdownMenuItem(
                    text = { Text(t) },
                    leadingIcon = icons?.getOrNull(i)?.let { { AsyncImage(it, null, Modifier.size(22.dp)) } },
                    onClick = { open = false; onPick(i) },
                )
            }
        }
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("d MMM")

fun Match.shortDate(): String = runCatching {
    dateFmt.format(Instant.parse(matchDate).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

@Composable
private fun MatchCard(m: Match, weekLabel: String?, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Surface).clickable(onClick = onClick)
    ) {
        Box {
            AsyncImage(
                model = m.highlightThumbnail,
                contentDescription = m.highLightTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Background),
            )
            val goals = m.goals().size
            if (goals > 0) Text(
                "$goals ${if (goals == 1) "goal" else "goals"}",
                color = Background, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Emerald).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (weekLabel != null) Text(
                weekLabel, color = OnDark, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Background.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(m.homeTeam?.logo, m.homeTeam?.name, Modifier.size(22.dp))
            Text(
                "${m.homeTeam?.matchScore ?: "-"} : ${m.awayTeam?.matchScore ?: "-"}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            AsyncImage(m.awayTeam?.logo, m.awayTeam?.name, Modifier.size(22.dp))
        }
        Text(
            "${m.homeTeam?.name ?: ""} – ${m.awayTeam?.name ?: ""}",
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Text(
            m.shortDate(), color = TextGray, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 10.dp),
        )
    }
}
