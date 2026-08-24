package ai.bjk.highlights

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    var hd by rememberSaveable { mutableStateOf(prefs.hd) }
    var allWeeks by rememberSaveable { mutableStateOf(prefs.allWeeks) }
    // Team is remembered per league (restored on relaunch); a team missing from the chosen season falls back to the list.
    LaunchedEffect(league) { team = prefs.team(league) }
    fun pickTeam(name: String) { team = name; prefs.saveTeam(league, name) }
    LaunchedEffect(league, seasonId, weeks.size, mode == Mode.ByTeam, allWeeks, seasonRetry) {
        if (mode != Mode.ByTeam && !allWeeks) return@LaunchedEffect
        val sid = seasonId ?: return@LaunchedEffect
        if (weeks.isEmpty()) return@LaunchedEffect
        seasonData = Load.Loading; seasonProgress = 0
        seasonData = try {
            Load.Ok(Api.seasonMatches(league, sid, weeks.map { it.round!! }) { seasonProgress = it })
        } catch (e: Exception) {
            Load.Error(e.message ?: "Failed to load")
        }
    }
    LaunchedEffect(hd, allWeeks) { prefs.hd = hd; prefs.allWeeks = allWeeks }
    LaunchedEffect(league, seasonId, allWeeks, round, mode, matches, seasonData) {
        val sid = seasonId ?: return@LaunchedEffect
        if (mode == Mode.ByTeam || allWeeks) {
            if (seasonData is Load.Ok) runCatching { Api.warm(league, sid) }
        } else if (round != null && matches is Load.Ok) {
            runCatching { Api.warm(league, sid, round) }
        }
    }

    fun playMatch(e: Entry) {
        val sid = seasonId ?: return
        val m = e.match.playable(league, sid, e.round, hd)
        onPlay(Playlist(m.title, m.clips()))
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
                LeagueSelector(LEAGUES.map { it.name }, leagueIdx) { leagueIdx = it }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Season", color = TextGray, style = MaterialTheme.typography.labelLarge)
                    Dropdown(
                        label = season?.name ?: "Season",
                        description = "Season",
                        items = seasonList.map { it.name ?: "?" },
                        modifier = Modifier.weight(1f),
                    ) { i ->
                        val s = seasonList[i]
                        seasonId = s.id
                        round = defaultRound(s)
                        allWeeks = true
                    }
                }
                Segmented(Mode.entries.map { it.label }, mode.ordinal) { mode = Mode.entries[it] }
                if (league.usesHdToggle()) Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("HD", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = hd, onCheckedChange = { hd = it },
                        modifier = Modifier.semantics {
                            contentDescription = "HD"
                            stateDescription = if (hd) "On" else "Off"
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Background, checkedTrackColor = Emerald,
                            uncheckedThumbColor = TextGray, uncheckedTrackColor = Surface,
                        ),
                    )
                }
                if (mode == Mode.ByTeam) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Team", color = TextGray, style = MaterialTheme.typography.labelLarge)
                        Dropdown(
                            label = selectedTeam?.name ?: "Pick a team",
                            description = "Team",
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
                            modifier = Modifier.semantics {
                                contentDescription = "Only ${selectedTeam?.name ?: "team"} goals"
                                stateDescription = if (onlyTeam) "On" else "Off"
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Background, checkedTrackColor = Emerald,
                                uncheckedThumbColor = TextGray, uncheckedTrackColor = Surface,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
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
                                val filter: (GoalRow) -> Boolean = remember(onlyTeam, t.name) {
                                    if (onlyTeam) ({ g: GoalRow -> g.team?.name == t.name }) else ({ _: GoalRow -> true })
                                }
                                Content(mine, goalsOnly = teamGoals, onOpen = { playMatch(it) }, onPlay = onPlay,
                                    title = t.name, goalFilter = filter,
                                    groupByWeek = false,
                                    emptyMsg = "No highlights for ${t.name} this season yet.")
                            }
                        }
                    }
                } else {
                    val wide = LocalConfiguration.current.screenWidthDp > 700
                    Row(Modifier.fillMaxSize()) {
                        if (wide) WeekRail(
                            weeks = weeks, allWeeks = allWeeks, round = round,
                            onAll = { allWeeks = true },
                            onWeek = { allWeeks = false; round = it },
                            modifier = Modifier.fillMaxHeight().width(148.dp).background(Surface),
                        )
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (!wide) WeekRail(
                                weeks = weeks, allWeeks = allWeeks, round = round,
                                onAll = { allWeeks = true },
                                onWeek = { allWeeks = false; round = it },
                                horizontal = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (allWeeks) when (val d = seasonData) {
                                Load.Loading -> Centered {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = Emerald)
                                        Spacer(Modifier.height(12.dp))
                                        Text("Loading season… $seasonProgress/${weeks.size}", color = TextGray)
                                    }
                                }
                                is Load.Error -> Centered { ErrorBox(d.msg) { seasonRetry++ } }
                                is Load.Ok -> {
                                    val entries = d.value.sortedBy { it.match.matchDate ?: "" }
                                        .map { Entry(weekName(it.round), it.round, it.match) }
                                    Content(entries, goalsOnly = mode == Mode.Goals, onOpen = { playMatch(it) },
                                        onPlay = onPlay, title = "Season",
                                        groupByWeek = true,
                                        emptyMsg = "No highlights published for this season yet.")
                                }
                            } else when (val m = matches) {
                                Load.Loading -> SkeletonGrid()
                                is Load.Error -> Centered { ErrorBox(m.msg) { matchesRetry++ } }
                                is Load.Ok -> Content(
                                    m.value.map { Entry(round?.let { r -> weekName(r) }, round ?: 0, it) },
                                    goalsOnly = mode == Mode.Goals,
                                    onOpen = { playMatch(it) }, onPlay = onPlay,
                                    title = round?.let { weekName(it) } ?: "Goals")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Content(
    entries: List<Entry>, goalsOnly: Boolean, title: String,
    onOpen: (Entry) -> Unit, onPlay: (Playlist) -> Unit,
    goalFilter: (GoalRow) -> Boolean = { true },
    groupByWeek: Boolean = false,
    emptyMsg: String = "No highlights published for this week yet.",
) {
    if (entries.isEmpty()) {
        EmptyState(emptyMsg)
        return
    }
    if (goalsOnly) GoalsView(entries, title, goalFilter, onPlay) else MatchGrid(entries, groupByWeek, onOpen)
}

@Composable
private fun MatchGrid(entries: List<Entry>, groupByWeek: Boolean, onOpen: (Entry) -> Unit) {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val minCardWidth = if (screenWidth < 600) 160.dp else 220.dp
    val groups = remember(entries) { entries.groupBy { it.round }.toSortedMap() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCardWidth),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (groupByWeek) {
            groups.forEach { (round, es) ->
                item(key = "h$round", span = { GridItemSpan(maxLineSpan) }) {
                    val name = es.first().week ?: "Week $round"
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            name,
                            color = Background,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Emerald)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Text(
                            "${es.size} ${if (es.size == 1) "match" else "matches"}",
                            color = TextGray,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                items(es, key = { (w, _, m) -> "${w}_${m.matchId ?: m.hashCode()}" }) { e ->
                    MatchCard(e.match, null) { onOpen(e) }
                }
            }
        } else {
            items(entries, key = { (w, _, m) -> "${w}_${m.matchId ?: m.hashCode()}" }) { e ->
                MatchCard(e.match, e.week) { onOpen(e) }
            }
        }
    }
}

@Composable
private fun WeekRail(
    weeks: List<Week>, allWeeks: Boolean, round: Int?,
    onAll: () -> Unit, onWeek: (Int) -> Unit,
    modifier: Modifier = Modifier, horizontal: Boolean = false,
) {
    val chip: @Composable (Boolean, String, Boolean, () -> Unit) -> Unit = { on, label, current, click ->
        val shape = RoundedCornerShape(10.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .then(if (horizontal) Modifier else Modifier.fillMaxWidth())
                .clip(shape)
                .background(if (on) Emerald else Background.copy(alpha = 0.4f))
                .selectable(selected = on, role = Role.Tab, onClick = click)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                label,
                color = if (on) Background else OnDark,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (horizontal) Modifier else Modifier.weight(1f),
            )
            if (current) Box(
                Modifier.size(6.dp).clip(CircleShape)
                    .background(if (on) Background.copy(alpha = 0.55f) else Emerald)
            )
        }
    }
    if (horizontal) {
        val listState = rememberLazyListState()
        val selectedIndex = if (allWeeks) 0 else weeks.indexOfFirst { it.round == round }.takeIf { it >= 0 }?.plus(1) ?: 0
        LaunchedEffect(selectedIndex, weeks.size) {
            if (weeks.isNotEmpty()) listState.animateScrollToItem(selectedIndex)
        }
        LazyRow(
            state = listState,
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { chip(allWeeks, "All weeks", false, onAll) }
            items(weeks, key = { it.round ?: it.hashCode() }) { w ->
                val r = w.round ?: return@items
                chip(!allWeeks && round == r, w.weekName ?: "Week $r", w.currentWeekForFixture == true) { onWeek(r) }
            }
        }
    } else {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Week", color = TextGray, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
            chip(allWeeks, "All weeks", false, onAll)
            weeks.forEach { w ->
                val r = w.round ?: return@forEach
                chip(!allWeeks && round == r, w.weekName ?: "Week $r", w.currentWeekForFixture == true) { onWeek(r) }
            }
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
    // Walking every match's goals and sorting the playlist is O(nlogn) over a whole season —
    // far too much to redo on each recomposition.
    val groups = remember(entries, goalFilter) {
        entries.map { (w, r, m) -> Triple(w, m, m.goalRows(r, w).filter(goalFilter)) }.filter { it.third.isNotEmpty() }
    }
    val ordered = remember(groups) { orderedPlaylist(groups.flatMap { it.third }) }
    val all = remember(ordered) { ordered.map { it.clip() } }
    // Row → playlist position, so opening one goal does not scan the list with deep equality.
    val positionOf = remember(ordered) {
        ordered.withIndex().associate { (i, r) -> (r.match.matchId to r.event.id) to i }
    }
    val multiWeek = remember(entries) { entries.distinctBy { it.week }.size > 1 }
    if (all.isEmpty()) {
        EmptyState("No goal clips for this selection.")
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
                    TeamLogo(m.homeTeam?.name, m.homeTeam?.logo, 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(m.scoreLine, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    TeamLogo(m.awayTeam?.name, m.awayTeam?.logo, 20.dp)
                    if (w != null && multiWeek)
                        Text("  $w", color = TextGray, style = MaterialTheme.typography.labelSmall)
                }
            }
            items(goals.size) { i ->
                val g = goals[i]
                GoalCard(g) {
                    onPlay(Playlist("$title · all goals", all, positionOf[g.match.matchId to g.event.id] ?: 0))
                }
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
        if (g.team?.logo != null) TeamLogo(g.team.name, g.team.logo, 28.dp)
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
    if (teams.isEmpty()) { EmptyState("No matches in this season yet."); return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Pick a team", color = TextGray, modifier = Modifier.padding(bottom = 4.dp)) }
        items(teams, key = { it.name }) { t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface)
                    .clickable { onPick(t) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TeamLogo(t.name, t.logo, 26.dp)
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

/** Full league names stay readable on phones; larger layouts retain a compact segmented switch. */
@Composable
private fun LeagueSelector(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    if (LocalConfiguration.current.screenWidthDp >= 600) {
        Segmented(labels, selected, onSelect)
        return
    }
    val state = rememberLazyListState()
    LaunchedEffect(selected) { state.animateScrollToItem(selected) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(labels.size) { i ->
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = { Text(labels[i], maxLines = 1) },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Surface,
                    labelColor = OnDark,
                    selectedContainerColor = Emerald,
                    selectedLabelColor = Background,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = i == selected,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = Emerald,
                ),
            )
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
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val minCardWidth = if (screenWidth < 600) 160.dp else 220.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCardWidth),
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
    Surface(
        color = Surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.padding(24.dp).widthIn(max = 420.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Couldn't load", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(msg, color = TextGray, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Centered {
        Surface(
            color = Surface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.padding(24.dp).widthIn(max = 420.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Emerald.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Text("–", color = Emerald, style = MaterialTheme.typography.titleLarge) }
                Spacer(Modifier.height(12.dp))
                Text(message, color = TextGray, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Dropdown(
    label: String, description: String, items: List<String>, modifier: Modifier = Modifier,
    icons: List<String?>? = null, leadingIcon: String? = null, onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = items.isNotEmpty(),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = "$description, $label"
            },
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            if (leadingIcon != null) { TeamLogo(label, leadingIcon, 22.dp); Spacer(Modifier.width(8.dp)) }
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Surface)) {
            items.forEachIndexed { i, t ->
                DropdownMenuItem(
                    text = { Text(t) },
                    leadingIcon = icons?.getOrNull(i)?.let { { TeamLogo(t, it, 22.dp) } },
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
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button },
    ) {
        Box {
            AsyncImage(
                model = m.highlightThumbnail,
                contentDescription = null,
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
            TeamLogo(m.homeTeam?.name, m.homeTeam?.logo, 22.dp)
            Text(
                "${scoreText(m.homeTeam)}–${scoreText(m.awayTeam)}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            TeamLogo(m.awayTeam?.name, m.awayTeam?.logo, 22.dp)
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

/** A logo with a stable initial fallback, so failed/slow image requests never leave an empty target. */
@Composable
private fun TeamLogo(name: String?, logo: String?, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "–",
            color = TextGray,
            fontSize = (size.value * 0.45f).sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!logo.isNullOrBlank()) AsyncImage(
            model = logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
