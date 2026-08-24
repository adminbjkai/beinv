import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, clipItem, matchPlaylist, type Match, type Event, type PlaylistItem, type Week } from './api'
import MatchCard from './components/MatchCard'
import GoalsGrid from './components/GoalsGrid'
import { goalGroups, playlistOrder, clipTitle, scoreAt } from './goals'
import ClipList from './components/ClipList'
import Player from './components/Player'

type Mode = 'highlights' | 'goals' | 'team'
const MODES: [Mode, string][] = [['highlights', 'Highlights'], ['goals', 'Goals'], ['team', 'By team']]
type RoundSel = number | 'all'
type Stored = { l?: string; s?: number; r?: RoundSel; mode?: Mode; t?: string; g?: boolean; og?: boolean; an?: boolean; hd?: boolean }
const LS = 'beinv.v2'
const stored = (): Stored => { try { return JSON.parse(localStorage.getItem(LS) ?? '{}') } catch { return {} } }
const isMode = (x: unknown): x is Mode => MODES.some(([m]) => m === x)
const HD_LEAGUES = new Set(['super-lig', 'ingiltere-premier-ligi'])
/** FEATURES §1: default week is all-weeks for Highlights/Goals; By team has no week. */
const parseRound = (raw: string | null | undefined): RoundSel | undefined => {
  if (raw == null || raw === '') return undefined
  if (raw === 'all') return 'all'
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : undefined
}

/** playlist + cursor; `matchId` set when it belongs to a single match (drives the clip chips + `?m=`) */
type Playing = { items: PlaylistItem[]; index: number; matchId?: number }

export default function App() {
  const leagues = useQuery({ queryKey: ['leagues'], queryFn: api.leagues })
  const init = useMemo(() => {
    const q = new URLSearchParams(window.location.search), ls = stored()
    const mode = q.get('mode') ?? ls.mode
    const r = parseRound(q.get('r')) ?? parseRound(ls.r == null ? undefined : String(ls.r)) ?? 'all'
    return {
      l: q.get('l') ?? ls.l ?? 'super-lig',
      s: Number(q.get('s')) || ls.s, r,
      mode: isMode(mode) ? mode : 'highlights' as Mode,
      t: q.get('t') ?? ls.t, g: q.has('g') ? q.get('g') === '1' : !!ls.g,
      og: q.has('og') ? q.get('og') !== '0' : ls.og ?? true,
      m: Number(q.get('m')) || undefined, an: ls.an ?? true,
      // HD is the default for Super Lig + Premier League. URL hd=0 turns it off.
      hd: q.has('hd') ? q.get('hd') === '1' : true,
      playAll: q.get('play') === 'all', clips: q.get('clips') === '1',
    }
  }, [])
  const [league, setLeague] = useState(init.l)
  const seasons = useQuery({ queryKey: ['seasons', league], queryFn: () => api.seasons(league) })
  const [seasonId, setSeasonId] = useState<number | undefined>(init.s)
  const [round, setRound] = useState<RoundSel | undefined>(init.r)
  const [mode, setMode] = useState<Mode>(init.mode)
  const [team, setTeam] = useState<string | undefined>(init.t)
  const [teamGoals, setTeamGoals] = useState(init.g)
  const [onlyTeam, setOnlyTeam] = useState(init.og)
  const [autoNext, setAutoNext] = useState(init.an)
  const [hd, setHd] = useState(init.hd)
  const [playing, setPlaying] = useState<Playing>()
  const hdLeague = HD_LEAGUES.has(league)
  const useHd = hdLeague && hd

  // defaults: current season; Highlights/Goals open on every week
  useEffect(() => {
    const known = seasons.data?.find(x => x.id === seasonId)
    if (known && round != null) return
    const s = known ?? seasons.data?.find(x => x.is_current) ?? seasons.data?.[0]
    if (!s) return
    setSeasonId(s.id)
    if (round == null) setRound('all')
  }, [seasons.data])

  const season = seasons.data?.find(s => s.id === seasonId)
  const teamMode = mode === 'team'
  const goalsView = mode === 'goals' || (teamMode && teamGoals)
  const allWeeks = !teamMode && round === 'all'
  const week = useQuery({
    queryKey: ['week', league, seasonId, round],
    queryFn: () => api.week(league, seasonId!, round as number),
    enabled: !!seasonId && typeof round === 'number' && !teamMode,
  })
  const all = useQuery({
    queryKey: ['season', league, seasonId],
    queryFn: () => api.seasonMatches(league, seasonId!),
    enabled: !!seasonId && (teamMode || allWeeks),
    staleTime: 10 * 60_000,
  })
  const list = teamMode || allWeeks ? all : week

  const teams = useMemo(() => {
    const map = new Map<string, string>()
    for (const m of all.data ?? []) { map.set(m.home.name, m.home.logo); map.set(m.away.name, m.away.logo) }
    return [...map].map(([name, logo]) => ({ name, logo })).sort((a, b) => a.name.localeCompare(b.name))
  }, [all.data])
  useEffect(() => { if (teams.length && !teams.some(t => t.name === team)) setTeam(teams[0].name) }, [teams])

  const matches = useMemo(() => {
    const src = teamMode
      ? (all.data ?? []).filter(m => m.home.name === team || m.away.name === team).sort((a, b) => b.date.localeCompare(a.date))
      : (allWeeks ? all.data ?? [] : week.data ?? [])
    return goalsView ? src.filter(m => m.events.some(e => e.is_goal)) : src.filter(m => m.has_highlight)
  }, [teamMode, allWeeks, all.data, week.data, team, goalsView])
  const weekName = (m: Match | number) => {
    const r = typeof m === 'number' ? m : m.round
    return season?.weeks.find(w => w.round === r)?.name ?? `Week ${r}`
  }
  const grouped = useMemo(() => {
    const map = new Map<number, Match[]>()
    for (const m of matches) {
      const arr = map.get(m.round) ?? []
      arr.push(m)
      map.set(m.round, arr)
    }
    return [...map.entries()].sort((a, b) => a[0] - b[0])
  }, [matches])
  const showWeekLabels = teamMode || allWeeks || grouped.length > 1

  const clipMeta = (m: Match) => (e: Event) => {
    const sc = scoreAt(m, e.minute, e.id), week = weekName(m)
    const side = e.side === 'Home' ? m.home : e.side === 'Away' ? m.away : undefined
    return { week, score: `${sc.home}–${sc.away}`, logo: e.is_goal ? side?.logo : undefined, title: clipTitle(week, m, sc, e) }
  }
  const openMatch = (m: Match) => {
    const items = matchPlaylist(m, league, seasonId!, clipMeta(m), useHd)
    if (items.length) { setPlaying({ items, index: 0, matchId: m.id }); window.scrollTo({ top: 0, behavior: 'smooth' }) }
  }
  // goals grouped by match with running score; By team honours "Only <Team> goals"
  const groups = useMemo(() => goalsView ? goalGroups(matches, teamMode && onlyTeam ? team : undefined) : [], [goalsView, matches, teamMode, onlyTeam, team])
  // §2b order: week asc → kick-off asc → minute asc, over exactly the visible rows
  const ordered = useMemo(() => playlistOrder(groups), [groups])
  const allGoals = () => ordered.flatMap(g => g.rows.map(r => clipItem(g.m, r.event, { league, season: seasonId!, round: g.m.round }, clipMeta(g.m)(r.event))))
  const playAll = () => { const items = allGoals(); if (items.length) { setPlaying({ items, index: 0 }); window.scrollTo({ top: 0, behavior: 'smooth' }) } }
  const playGoal = (e: Event) => {
    const items = allGoals(); const index = Math.max(0, items.findIndex(i => i.event?.id === e.id))
    setPlaying({ items, index }); window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  // open match from URL once data is loaded; keep URL + localStorage in sync afterwards
  const urlMatch = useRef(init.m)
  useEffect(() => {
    const id = urlMatch.current
    if (id && !playing && list.data) { urlMatch.current = undefined; const f = list.data.find(x => x.id === id); if (f) openMatch(f) }
  }, [list.data])
  // `?play=all` → open Play all once the visible goal list exists
  const urlPlayAll = useRef(init.playAll)
  useEffect(() => { if (urlPlayAll.current && groups.length) { urlPlayAll.current = false; playAll() } }, [groups])
  useEffect(() => {
    if (!seasonId || round == null) return
    const q = new URLSearchParams({ l: league, s: String(seasonId), r: round === 'all' ? 'all' : String(round) })
    if (mode !== 'highlights') q.set('mode', mode)
    if (teamMode && team) { q.set('t', team); if (teamGoals) { q.set('g', '1'); q.set('og', onlyTeam ? '1' : '0') } }
    if (playing?.matchId) q.set('m', String(playing.matchId))
    if (hdLeague) q.set('hd', hd ? '1' : '0')
    window.history.replaceState(null, '', `?${q}`)
    localStorage.setItem(LS, JSON.stringify({ l: league, s: seasonId, r: round, mode, t: team, g: teamGoals, og: onlyTeam, an: autoNext, hd } satisfies Stored))
  }, [league, seasonId, round, mode, team, teamGoals, onlyTeam, playing, autoNext, hd, hdLeague, teamMode])
  const firstRender = useRef(true)
  useEffect(() => {
    if (firstRender.current) { firstRender.current = false; return }
    setPlaying(undefined)
  }, [league, seasonId, round, mode, team, teamGoals, onlyTeam])
  // HD only swaps the full-highlight URL; keep the player open and rebuild the playlist.
  useEffect(() => {
    if (!playing?.matchId || !seasonId || !list.data) return
    const m = list.data.find(x => x.id === playing.matchId)
    if (!m) return
    const items = matchPlaylist(m, league, seasonId, clipMeta(m), useHd)
    if (items.length) setPlaying(p => p && { ...p, items, index: Math.min(p.index, items.length - 1) })
  }, [useHd])
  useEffect(() => {
    const onEsc = (e: KeyboardEvent) => e.key === 'Escape' && setPlaying(undefined)
    window.addEventListener('keydown', onEsc); return () => window.removeEventListener('keydown', onEsc)
  }, [])

  const weeks = season?.weeks ?? []
  const seg = (on: boolean) => `rounded-lg px-3 py-1.5 text-sm font-semibold transition ${on ? 'bg-accent text-black shadow' : 'text-white/70 hover:bg-white/5 hover:text-white'}`
  const current = playing?.items[playing.index]
  const selectedTeam = teams.find(t => t.name === team)
  const emptyMsg = teamMode
    ? 'No highlights published for this team yet.'
    : allWeeks
      ? 'No highlights published for this season yet.'
      : 'No highlights published for this week yet.'

  return (
    <div className="mx-auto max-w-7xl overflow-x-hidden px-4 pb-24 pt-6 md:px-8">
      <header className="mb-6 flex flex-wrap items-center gap-3">
        <h1 className="mr-auto text-2xl font-extrabold tracking-tight">
          <span className="bg-gradient-to-r from-accent to-accent-2 bg-clip-text text-transparent">Highlights</span>
        </h1>
        <div className="glass flex w-full max-w-full overflow-x-auto rounded-xl p-1 [-webkit-overflow-scrolling:touch] [scrollbar-width:none] sm:w-auto [&::-webkit-scrollbar]:hidden">
          {(leagues.data ?? []).map(l => (
            <button key={l.id} onClick={() => { setLeague(l.id); setTeam(undefined); setSeasonId(undefined); setRound('all') }}
              aria-pressed={league === l.id} aria-label={l.name}
              className={`min-h-11 shrink-0 whitespace-nowrap rounded-lg px-3 py-2 text-sm font-semibold transition sm:px-4 ${league === l.id ? 'bg-white text-black shadow' : 'text-white/70 hover:bg-white/5 hover:text-white'}`}>
              {l.name}
            </button>
          ))}
        </div>
        <label className="glass flex items-center rounded-xl pl-3 text-sm"><span className="mr-2 text-white/50">Season</span>
        <select value={seasonId ?? ''} aria-label="Season"
          onChange={e => { const s = seasons.data?.find(x => x.id === +e.target.value); setSeasonId(s?.id); setRound('all') }}
          className="bg-transparent py-2 text-sm font-medium outline-none">
          {(seasons.data ?? []).map(s => <option key={s.id} value={s.id} className="bg-panel">{s.name}</option>)}
        </select></label>
      </header>

      <div className="mb-6 flex flex-wrap items-center gap-3">
        <div className="glass flex rounded-xl p-1" role="tablist" aria-label="View">
          {MODES.map(([m, label]) => <button key={m} role="tab" aria-selected={mode === m} onClick={() => setMode(m)} className={seg(mode === m)}>{label}</button>)}
        </div>
        {hdLeague && (
          <button role="switch" aria-checked={hd} aria-label="HD quality" onClick={() => setHd(v => !v)}
            className={`glass flex min-h-11 items-center gap-2 rounded-xl px-3 py-2 text-sm font-semibold transition hover:border-white/20 ${hd ? 'text-white' : 'text-white/60'}`}>
            <span className={`relative h-4 w-7 rounded-full transition ${hd ? 'bg-accent' : 'bg-white/20'}`}>
              <span className={`absolute top-0.5 h-3 w-3 rounded-full bg-white transition ${hd ? 'left-3.5' : 'left-0.5'}`} />
            </span>
            HD
          </button>
        )}
        {teamMode && (
          <>
            <div className="glass flex items-center gap-2 rounded-xl pl-3 text-sm">
              <span className="text-white/50">Team</span>
              {selectedTeam && <img src={selectedTeam.logo} alt="" className="h-6 w-6 object-contain" />}
              <select value={team ?? ''} onChange={e => setTeam(e.target.value)} disabled={!teams.length} aria-label="Team"
                className="bg-transparent py-2 text-sm font-medium outline-none disabled:opacity-50">
                {!teams.length && <option className="bg-panel">{all.isLoading ? 'Loading season…' : 'No teams'}</option>}
                {teams.map(t => <option key={t.name} value={t.name} className="bg-panel">{t.name}</option>)}
              </select>
            </div>
            <div className="glass flex rounded-xl p-1">
              <button onClick={() => setTeamGoals(false)} className={seg(!teamGoals)}>Matches</button>
              <button onClick={() => setTeamGoals(true)} className={seg(teamGoals)}>Goals</button>
            </div>
            {teamGoals && (
              <button role="switch" aria-checked={onlyTeam} onClick={() => setOnlyTeam(v => !v)}
                className={`glass flex items-center gap-2 rounded-xl px-3 py-2 text-sm font-medium transition hover:border-white/20 ${onlyTeam ? 'text-white' : 'text-white/60'}`}>
                <span className={`relative h-4 w-7 rounded-full transition ${onlyTeam ? 'bg-accent' : 'bg-white/20'}`}>
                  <span className={`absolute top-0.5 h-3 w-3 rounded-full bg-white transition ${onlyTeam ? 'left-3.5' : 'left-0.5'}`} />
                </span>
                Only {team ?? 'team'} goals
              </button>
            )}
          </>
        )}
      </div>

      {playing && current && (
        <section className="fade-up mb-10">
          <Player items={playing.items} index={playing.index} autoNext={autoNext} onAutoNext={setAutoNext} initialClips={init.clips}
            onIndex={i => setPlaying(p => p && { ...p, index: i })} onEnd={() => setPlaying(undefined)} />
          <div className="mt-3 flex items-center gap-2 text-xs text-white/60">
            <span>{playing.matchId ? 'Match playlist' : 'Playing all goals'} · {playing.items.length} clips · n / p to skip · c for the drawer</span>
            <button onClick={() => setPlaying(undefined)} className="ml-auto rounded-lg px-2 py-1 text-white/50 transition hover:bg-white/5 hover:text-white">Close ✕</button>
          </div>
          <div className="mt-3">
            <ClipList items={playing.items} index={playing.index} onIndex={i => setPlaying(p => p && { ...p, index: i })} chips />
          </div>
        </section>
      )}

      <div className={`flex items-start gap-6 ${teamMode ? '' : ''}`}>
        {!teamMode && (
          <WeekRail weeks={weeks} round={round ?? 'all'} onPick={setRound} className="hidden md:flex" />
        )}
        <div className="min-w-0 flex-1">
          {!teamMode && (
            <WeekRail weeks={weeks} round={round ?? 'all'} onPick={setRound} className="mb-5 flex md:hidden" horizontal />
          )}
          {list.isLoading && (
            <div>
              {(teamMode || allWeeks) && <p className="mb-4 text-sm text-white/60"><span className="mr-2 inline-block h-3 w-3 animate-spin rounded-full border-2 border-white/20 border-t-accent align-middle" />Loading the whole season ({weeks.length} weeks)…</p>}
              <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
                {Array.from({ length: 6 }).map((_, i) => <div key={i} className="glass aspect-[4/3.6] animate-pulse rounded-2xl" />)}
              </div>
            </div>
          )}
          {list.isError && (
            <div className="glass fade-up flex flex-wrap items-center gap-3 rounded-2xl p-6 text-red-300">
              <span>Could not load matches. {String(list.error)}</span>
              <button onClick={() => list.refetch()} className="ml-auto rounded-full bg-accent px-4 py-1.5 text-sm font-semibold text-black transition hover:brightness-110">Retry</button>
            </div>
          )}
          {list.data && !goalsView && matches.length === 0 && (
            <div className="glass fade-up rounded-2xl p-8 text-center text-white/60 sm:p-12">
              {emptyMsg}
              <p className="mt-2 text-sm text-white/40">They usually appear a few hours after kick-off. If loading fails, tap Retry.</p>
            </div>
          )}
          {list.data && goalsView && (
            <GoalsGrid groups={groups} label={showWeekLabels ? weekName : undefined} onPlay={playGoal}
              firstWeek={ordered[0] ? weekName(ordered[0].m) : ''} onPlayAll={playAll} />
          )}
          {!goalsView && matches.length > 0 && (
            <div className="space-y-10">
              {grouped.map(([r, ms]) => (
                <section key={r} id={`week-${r}`} className="scroll-mt-20">
                  {showWeekLabels && (
                    <h2 className="mb-4 flex items-baseline gap-3 text-lg font-bold tracking-tight">
                      <span className="rounded-md bg-accent/90 px-2 py-0.5 text-sm font-semibold text-black">{weekName(r)}</span>
                      <span className="text-sm font-medium text-white/45">{ms.length} {ms.length === 1 ? 'match' : 'matches'}</span>
                    </h2>
                  )}
                  <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
                    {ms.map((m, i) => <MatchCard key={m.id} m={m} i={i} label={showWeekLabels ? weekName(m) : undefined} onOpen={() => openMatch(m)} />)}
                  </div>
                </section>
              ))}
            </div>
          )}
        </div>
      </div>

      <details className="mt-16 text-xs text-white/40">
        <summary className="cursor-pointer select-none py-2 text-white/50">Help &amp; shortcuts</summary>
        <div className="mt-2 max-w-xl space-y-2 leading-relaxed">
          <p>Pick a league and season. Highlights open on every week at once — use the week list on the left (chips on a phone) to jump to one week, or All to see the season. Tap a match to play on this page.</p>
          <p><b className="text-white/60">HD</b> is on by default for Trendyol Süper Lig (official beIN SPORTS Türkiye YouTube özet) and Premier League (official NBC Sports). Goal clips stay on the standard feed. Turn HD off to use the beIN cut. New matchdays appear automatically.</p>
          <p><b className="text-white/60">İspanya La Liga</b> covers 2025/2026 and 2026/2027. Full-match highlights appear after each game; Goals mode stays empty until individual goal clips are published (same as Premier League).</p>
          <p>Keyboard: space / k play · ← → seek 10s · n / p next/prev · f fullscreen · m mute · c clips list · Esc close.</p>
        </div>
      </details>
    </div>
  )
}

function WeekRail({ weeks, round, onPick, className = '', horizontal = false }: {
  weeks: Week[]; round: RoundSel; onPick: (r: RoundSel) => void; className?: string; horizontal?: boolean
}) {
  const chip = (on: boolean) =>
    `shrink-0 rounded-lg px-3 py-2 text-left text-sm font-semibold transition ${on ? 'bg-accent text-black shadow' : 'text-white/70 hover:bg-white/5 hover:text-white'}`
  return (
    <nav aria-label="Weeks" className={`${horizontal
      ? 'glass w-full overflow-x-auto rounded-xl p-1 [-webkit-overflow-scrolling:touch] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden'
      : 'glass sticky top-4 flex w-44 shrink-0 flex-col gap-1 overflow-y-auto rounded-2xl p-2 max-h-[calc(100vh-6rem)]'
    } ${className}`}>
      {!horizontal && <p className="px-2 pb-1 pt-1 text-[11px] font-semibold uppercase tracking-wider text-white/40">Week</p>}
      <button type="button" aria-pressed={round === 'all'} onClick={() => onPick('all')} className={chip(round === 'all')}>
        All weeks
      </button>
      {weeks.map(w => (
        <button key={w.round} type="button" aria-pressed={round === w.round} aria-current={w.is_current ? 'date' : undefined}
          onClick={() => onPick(w.round)} className={`${chip(round === w.round)} ${horizontal ? '' : 'flex items-center justify-between gap-2'}`}>
          <span className="flex items-center gap-2">{w.name}{w.is_current && <span className={`h-1.5 w-1.5 rounded-full ${round === w.round ? 'bg-black/50' : 'bg-accent'}`} aria-label="Current week" />}</span>
        </button>
      ))}
    </nav>
  )
}
