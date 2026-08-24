export type League = { id: string; name: string; org_id: number; sport_id: number }
export type Week = { round: number; name: string; is_current: boolean }
export type Season = { id: number; name: string; is_current: boolean; weeks: Week[] }
export type Team = { name: string; logo: string; score: number | null }
export type Event = { id: number; minute: number; description: string; is_goal: boolean; side: string | null; thumb: string; has_video: boolean }
export type Match = {
  id: number; round: number; title: string; date: string; home: Team; away: Team; thumb: string
  has_highlight: boolean; has_hd?: boolean; events: Event[]
}

async function get<T>(url: string): Promise<T> {
  const r = await fetch(url)
  if (!r.ok) throw new Error(`${r.status} ${await r.text()}`)
  return r.json()
}

export const api = {
  leagues: () => get<League[]>('/api/leagues'),
  seasons: (league: string) => get<Season[]>(`/api/leagues/${league}/seasons`),
  week: (league: string, season: number, round: number) =>
    get<Match[]>(`/api/leagues/${league}/seasons/${season}/weeks/${round}`),
  /** every match of the season (server fetches all weeks in parallel, cached 10 min) */
  seasonMatches: (league: string, season: number) =>
    get<Match[]>(`/api/leagues/${league}/seasons/${season}/matches`),
}

export type Ctx = { league: string; season: number; round: number; hd?: boolean }
export const videoUrl = (kind: 'm' | 'e', id: number, c: Ctx) =>
  `/video/${kind}/${id}?l=${c.league}&s=${c.season}&r=${c.round}${c.hd && kind === 'm' ? '&q=hd' : ''}`

/** One entry of the player's playlist: the full highlight or a single clip. */
export type PlaylistItem = {
  key: string; src: string; poster: string; title: string; match: Match; event?: Event
  /** clip-selector fields (§2b): week label, running score after the goal, scoring-team logo */
  week?: string; score?: string; logo?: string
}
export type ClipMeta = { week?: string; score?: string; logo?: string; title?: string }

/** `meta.title` overrides the default title (App passes the canonical §2b title with the week label). */
export const matchPlaylist = (m: Match, league: string, season: number, meta?: (e: Event) => ClipMeta, hd = false): PlaylistItem[] => {
  const c = { league, season, round: m.round, hd: hd && !!m.has_hd }
  const items: PlaylistItem[] = []
  // `meta` is keyed by event, so the highlight borrows the first event's week label.
  // Premier League matches arrive with `matchEvents: null` (UPSTREAM_API.md §B) — no events, no label.
  const first: Event | undefined = m.events[0]
  if (m.has_highlight) items.push({ key: `m${m.id}`, src: videoUrl('m', m.id, c), poster: m.thumb, title: m.title, match: m, week: first && meta?.(first)?.week })
  for (const e of m.events) if (e.has_video) items.push(clipItem(m, e, { ...c, hd: false }, meta?.(e)))
  return items
}

export const clipItem = (m: Match, e: Event, c: Ctx, meta: ClipMeta = {}): PlaylistItem => ({
  key: `e${e.id}`, src: videoUrl('e', e.id, c), poster: e.thumb || m.thumb,
  title: `${e.minute}' ${e.description} · ${scoreline(m)}`, match: m, event: e, ...meta,
})

export const scoreline = (m: Match) =>
  `${m.home.name} ${m.home.score ?? '–'}-${m.away.score ?? '–'} ${m.away.name}`
