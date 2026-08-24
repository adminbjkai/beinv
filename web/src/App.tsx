import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, keepPreviousData } from '@tanstack/react-query'
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
const EMPTY_WEEKS: Week[] = []
const motionBehavior = (): ScrollBehavior => window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
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
      // HD is the default for Super Lig + Premier League. URL wins; else last choice; else on.
      hd: q.has('hd') ? q.get('hd') === '1' : ls.hd ?? true,
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
    placeholderData: keepPreviousData,
  })
  const all = useQuery({
    queryKey: ['season', league, seasonId],
    queryFn: () => api.seasonMatches(league, seasonId!),
    enabled: !!seasonId && (teamMode || allWeeks),
    staleTime: 10 * 60_000,
    placeholderData: keepPreviousData,
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
    if (items.length) { setPlaying({ items, index: 0, matchId: m.id }); window.scrollTo({ top: 0, behavior: motionBehavior() }) }
  }
  // goals grouped by match with running score; By team honours "Only <Team> goals"
  const groups = useMemo(() => goalsView ? goalGroups(matches, teamMode && onlyTeam ? team : undefined) : [], [goalsView, matches, teamMode, onlyTeam, team])
  // §2b order: week asc → kick-off asc → minute asc, over exactly the visible rows
  const ordered = useMemo(() => playlistOrder(groups), [groups])
  const allGoals = () => ordered.flatMap(g => g.rows.map(r => clipItem(g.m, r.event, { league, season: seasonId!, round: g.m.round }, clipMeta(g.m)(r.event))))
  const playAll = () => { const items = allGoals(); if (items.length) { setPlaying({ items, index: 0 }); window.scrollTo({ top: 0, behavior: motionBehavior() }) } }
  const playGoal = (e: Event) => {
    const items = allGoals(); const index = Math.max(0, items.findIndex(i => i.event?.id === e.id))
    setPlaying({ items, index }); window.scrollTo({ top: 0, behavior: motionBehavior() })
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
  const weeks = season?.weeks ?? EMPTY_WEEKS
  const stepWeek = useCallback((delta: number) => {
    if (teamMode) return
    const rounds = weeks.map(w => w.round)
    if (round === 'all') {
      if (delta > 0 && rounds[0] != null) setRound(rounds[0])
      return
    }
    const i = rounds.indexOf(round as number)
    if (i < 0) return
    const j = i + delta
    if (j < 0) setRound('all')
    else if (j < rounds.length) setRound(rounds[j])
  }, [teamMode, weeks, round])
  useEffect(() => {
    if (playing) return
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null
      const tag = el?.tagName
      if (tag === 'SELECT' || tag === 'INPUT' || tag === 'TEXTAREA' || el?.isContentEditable) return
      if (e.key === '[' || e.key === ']') { e.preventDefault(); stepWeek(e.key === ']' ? 1 : -1) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [playing, stepWeek])

  const seg = (on: boolean) => `min-h-10 rounded-lg px-3 py-1.5 text-sm font-semibold transition ${on ? 'bg-accent text-black shadow-[0_8px_24px_-12px_rgba(25,195,125,.9)]' : 'text-white/65 hover:bg-white/[.06] hover:text-white'}`
  const current = playing?.items[playing.index]
  const selectedTeam = teams.find(t => t.name === team)
  const emptyMsg = teamMode
    ? 'No highlights published for this team yet.'
    : allWeeks
      ? 'No highlights published for this season yet.'
      : 'No highlights published for this week yet.'
  const goalsEmpty = league === 'ingiltere-premier-ligi' || league === 'ispanya-la-liga'
    ? 'Full-match highlights only — no per-goal clips.'
    : 'No goal clips published yet.'

  return (
    <div className="mx-auto max-w-7xl overflow-x-hidden px-4 pb-24 pt-5 md:px-8 md:pt-7">
      <header className="mb-5 space-y-4 md:mb-6">
        <div>
          <div>
            <p className="mb-1 text-[11px] font-bold uppercase tracking-[.2em] text-accent/75">Matchday, distilled</p>
            <h1 className="text-3xl font-black tracking-[-.04em] sm:text-4xl">
              <span className="bg-gradient-to-r from-accent via-emerald-300 to-accent-2 bg-clip-text text-transparent">Highlights</span>
            </h1>
          </div>
        </div>
        <div className="edge-scroll glass control-surface flex w-full max-w-full overflow-x-auto rounded-2xl p-1 [-webkit-overflow-scrolling:touch] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden" role="group" aria-label="League">
          {(leagues.data ?? []).map(l => (
            <button key={l.id} onClick={() => { setLeague(l.id); setTeam(undefined); setSeasonId(undefined); setRound('all') }}
              aria-pressed={league === l.id} aria-label={l.name}
              className={`min-h-11 shrink-0 whitespace-nowrap rounded-xl px-3 py-2 text-sm font-semibold transition sm:flex-1 sm:px-4 ${league === l.id ? 'bg-white text-black shadow-[0_10px_30px_-15px_rgba(255,255,255,.65)]' : 'text-white/60 hover:bg-white/[.06] hover:text-white'}`}>
              {l.name}
            </button>
          ))}
        </div>
        <label className="glass control-surface flex min-h-11 w-fit max-w-full items-center rounded-xl pl-3 text-sm">
          <span className="mr-2 text-white/45">Season</span>
          <select value={seasonId ?? ''} aria-label="Season"
            onChange={e => { const s = seasons.data?.find(x => x.id === +e.target.value); setSeasonId(s?.id); setRound('all') }}
            className="bg-transparent py-2 text-sm font-semibold outline-none">
            {(seasons.data ?? []).map(s => <option key={s.id} value={s.id} className="bg-panel">{s.name}</option>)}
          </select>
        </label>
      </header>

      <div className="mb-6 flex flex-wrap items-center gap-2.5 sm:gap-3">
        <div className="glass control-surface flex rounded-xl p-1" role="tablist" aria-label="View">
          {MODES.map(([m, label]) => <button key={m} role="tab" aria-selected={mode === m} onClick={() => setMode(m)} className={seg(mode === m)}>{label}</button>)}
        </div>
        {hdLeague && (
          <button role="switch" aria-checked={hd} aria-label="HD quality" onClick={() => setHd(v => !v)}
            className={`glass control-surface flex min-h-11 items-center gap-2 rounded-xl px-3 py-2 text-sm font-semibold transition hover:border-white/20 ${hd ? 'text-white' : 'text-white/55'}`}>
            <span className={`relative h-4 w-7 rounded-full transition ${hd ? 'bg-accent' : 'bg-white/20'}`}>
              <span className={`absolute top-0.5 h-3 w-3 rounded-full bg-white shadow transition ${hd ? 'left-3.5' : 'left-0.5'}`} />
            </span>
            HD
          </button>
        )}
        {teamMode && (
          <>
            <div className="glass control-surface flex min-h-11 max-w-full items-center gap-2 rounded-xl pl-3 text-sm">
              <span className="text-white/45">Team</span>
              {selectedTeam && <img src={selectedTeam.logo} alt="" className="h-6 w-6 object-contain" />}
              <select value={team ?? ''} onChange={e => setTeam(e.target.value)} disabled={!teams.length} aria-label="Team"
                className="min-w-0 max-w-[14rem] bg-transparent py-2 text-sm font-medium outline-none disabled:opacity-50">
                {!teams.length && <option className="bg-panel">{all.isLoading ? 'Loading season…' : 'No teams'}</option>}
                {teams.map(t => <option key={t.name} value={t.name} className="bg-panel">{t.name}</option>)}
              </select>
            </div>
            <div className="glass control-surface flex rounded-xl p-1" role="group" aria-label="Team content">
              <button onClick={() => setTeamGoals(false)} aria-pressed={!teamGoals} className={seg(!teamGoals)}>Matches</button>
              <button onClick={() => setTeamGoals(true)} aria-pressed={teamGoals} className={seg(teamGoals)}>Goals</button>
            </div>
            {teamGoals && (
              <button role="switch" aria-checked={onlyTeam} onClick={() => setOnlyTeam(v => !v)}
                className={`glass control-surface flex min-h-11 items-center gap-2 rounded-xl px-3 py-2 text-sm font-medium transition hover:border-white/20 ${onlyTeam ? 'text-white' : 'text-white/55'}`}>
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
        <section className="fade-up mb-10" aria-label="Player">
          <Player items={playing.items} index={playing.index} autoNext={autoNext} onAutoNext={setAutoNext} initialClips={init.clips}
            onIndex={i => setPlaying(p => p && { ...p, index: i })} onEnd={() => setPlaying(undefined)} />
          <div className="mt-3 flex items-center gap-3 text-xs text-white/55">
            <span><strong className="font-semibold text-white/80">{playing.matchId ? 'Match playlist' : 'Playing all goals'}</strong> · {playing.items.length} clips <span className="hidden sm:inline">· n / p to skip · c for the drawer</span></span>
            <button onClick={() => setPlaying(undefined)} className="ml-auto min-h-10 shrink-0 rounded-lg px-3 text-white/55 transition hover:bg-white/[.06] hover:text-white" aria-label="Close player">Close <span aria-hidden="true">✕</span></button>
          </div>
          <div className="mt-3">
            <ClipList items={playing.items} index={playing.index} onIndex={i => setPlaying(p => p && { ...p, index: i })} chips />
          </div>
        </section>
      )}

      <main className="flex items-start gap-6" aria-busy={list.isLoading}>
        {!teamMode && (
          <WeekRail weeks={weeks} round={round ?? 'all'} onPick={setRound} className="hidden md:flex" />
        )}
        <div className="min-w-0 flex-1">
          {!teamMode && (
            <WeekRail weeks={weeks} round={round ?? 'all'} onPick={setRound} className="mb-5 flex md:hidden" horizontal />
          )}
          {list.isLoading && !list.data && <LoadingCards wholeSeason={teamMode || allWeeks} weeks={weeks.length} />}
          {list.isError && (
            <div className="state-panel glass fade-up flex flex-wrap items-center gap-4 rounded-2xl p-6" role="alert">
              <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-red-400/10 text-red-300" aria-hidden="true">!</span>
              <div className="min-w-0 flex-1">
                <p className="font-semibold text-white">Could not load matches</p>
                <p className="mt-0.5 truncate text-xs text-red-200/60">{String(list.error)}</p>
              </div>
              <button onClick={() => list.refetch()} className="min-h-10 rounded-full bg-accent px-5 text-sm font-bold text-black transition hover:brightness-110">Retry</button>
            </div>
          )}
          {list.data && !goalsView && matches.length === 0 && (
            <EmptyState message={emptyMsg} detail="They usually appear a few hours after kick-off." />
          )}
          {list.data && goalsView && (
            <GoalsGrid groups={groups} label={showWeekLabels ? weekName : undefined} onPlay={playGoal}
              firstWeek={ordered[0] ? weekName(ordered[0].m) : ''} onPlayAll={playAll} empty={goalsEmpty} />
          )}
          {!goalsView && matches.length > 0 && (
            <div className="space-y-10">
              {grouped.map(([r, ms]) => (
                <section key={r} id={`week-${r}`} className="scroll-mt-20">
                  {showWeekLabels && (
                    <h2 className="mb-4 flex items-center gap-3 text-lg font-bold tracking-tight">
                      <span className="h-5 w-1 rounded-full bg-accent shadow-[0_0_18px_rgba(25,195,125,.55)]" aria-hidden="true" />
                      <span className="text-base font-bold text-white">{weekName(r)}</span>
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
      </main>

      <details className="glass control-surface mt-16 rounded-2xl text-xs text-white/45">
        <summary className="cursor-pointer select-none rounded-2xl px-4 py-3 font-semibold text-white/55 transition hover:text-white">Help &amp; shortcuts</summary>
        <div className="max-w-2xl space-y-2 px-4 pb-4 leading-relaxed">
          <p>Pick a league and season. Highlights open on every week at once — use the week list on the left (chips on a phone) to jump to one week, or All to see the season. Tap a match to play on this page.</p>
          <p><b className="text-white/60">HD</b> is on by default for Trendyol Süper Lig (official beIN SPORTS Türkiye YouTube özet) and Premier League (official NBC Sports). Goal clips stay on the standard feed. Turn HD off to use the beIN cut. New matchdays appear automatically.</p>
          <p><b className="text-white/60">İspanya La Liga</b> covers 2025/2026 and 2026/2027. Full-match highlights appear after each game; Goals mode stays empty until individual goal clips are published (same as Premier League).</p>
          <p>Keyboard: space / k play · ← → seek 10s · n / p next/prev · f fullscreen · m mute · c clips list · [ / ] previous/next week · Esc close.</p>
        </div>
      </details>
    </div>
  )
}

function WeekRail({ weeks, round, onPick, className = '', horizontal = false }: {
  weeks: Week[]; round: RoundSel; onPick: (r: RoundSel) => void; className?: string; horizontal?: boolean
}) {
  const selected = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    selected.current?.scrollIntoView({ block: 'nearest', inline: 'center', behavior: motionBehavior() })
  }, [round])
  const chip = (on: boolean) =>
    `min-h-10 shrink-0 rounded-lg px-3 py-2 text-left text-sm font-semibold transition ${on ? 'bg-accent text-black shadow-[0_8px_24px_-12px_rgba(25,195,125,.9)]' : 'text-white/65 hover:bg-white/[.06] hover:text-white'}`
  return (
    <nav aria-label="Weeks" className={`${horizontal
      ? 'edge-scroll glass control-surface w-full overflow-x-auto rounded-xl p-1 [-webkit-overflow-scrolling:touch] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden'
      : 'glass control-surface sticky top-4 flex w-44 shrink-0 flex-col gap-1 overflow-y-auto rounded-2xl p-2 max-h-[calc(100vh-6rem)]'
    } ${className}`}>
      {!horizontal && <p className="px-2 pb-1 pt-1 text-[11px] font-semibold uppercase tracking-wider text-white/40">Week</p>}
      <button type="button" ref={round === 'all' ? selected : undefined} aria-pressed={round === 'all'} onClick={() => onPick('all')} className={chip(round === 'all')}>
        All weeks
      </button>
      {weeks.map(w => (
        <button key={w.round} type="button" ref={round === w.round ? selected : undefined}
          aria-pressed={round === w.round} aria-current={w.is_current ? 'date' : undefined}
          onClick={() => onPick(w.round)} className={`${chip(round === w.round)} ${horizontal ? '' : 'flex items-center justify-between gap-2'}`}>
          <span className="flex items-center gap-2">{w.name}{w.is_current && <span className={`h-1.5 w-1.5 rounded-full ${round === w.round ? 'bg-black/50' : 'bg-accent'}`} aria-label="Current week" />}</span>
        </button>
      ))}
    </nav>
  )
}

function LoadingCards({ wholeSeason, weeks }: { wholeSeason: boolean; weeks: number }) {
  return (
    <div role="status" aria-live="polite">
      <p className="mb-4 flex items-center gap-2 text-sm font-medium text-white/60">
        <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white/15 border-t-accent" aria-hidden="true" />
        {wholeSeason ? `Loading the whole season (${weeks} weeks)…` : 'Loading highlights…'}
      </p>
      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3" aria-hidden="true">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="glass control-surface overflow-hidden rounded-2xl">
            <div className="skeleton-shimmer aspect-video bg-white/[.035]" />
            <div className="flex items-center gap-3 p-4">
              <div className="h-9 w-9 rounded-full bg-white/[.05]" />
              <div className="h-3 flex-1 rounded-full bg-white/[.05]" />
              <div className="h-7 w-12 rounded-lg bg-white/[.05]" />
              <div className="h-3 flex-1 rounded-full bg-white/[.05]" />
              <div className="h-9 w-9 rounded-full bg-white/[.05]" />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function EmptyState({ message, detail }: { message: string; detail?: string }) {
  return (
    <div className="state-panel glass fade-up rounded-2xl p-8 text-center sm:p-12" role="status">
      <span className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-accent/10 text-accent" aria-hidden="true">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="m8 2 1.5 4.5L14 8l-4.5 1.5L8 14 6.5 9.5 2 8l4.5-1.5L8 2Z"/><path d="m17 12 1 3 3 1-3 1-1 3-1-3-3-1 3-1 1-3Z"/></svg>
      </span>
      <p className="font-semibold text-white/80">{message}</p>
      {detail && <p className="mt-2 text-sm text-white/40">{detail}</p>}
    </div>
  )
}
