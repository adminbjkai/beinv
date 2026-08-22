import type { Event, Match, Team } from './api'

export type Side = 'Home' | 'Away'
/** One goal with its attribution and the scoreline right after it. */
export type GoalRow = { event: Event; side: Side | null; team: Team | null; home: number; away: number }
export type GoalGroup = { m: Match; rows: GoalRow[] }

const asSide = (s: string | null): Side | null => (s === 'Home' || s === 'Away' ? s : null)

/** Walk ALL of a match's goals (with or without a clip) in minute order, incrementing the scoring side (unknown side: no increment, team "—"). */
export function goalRows(m: Match): GoalRow[] {
  const goals = m.events.filter(e => e.is_goal).slice().sort((a, b) => a.minute - b.minute)
  let home = 0, away = 0
  return goals.map(event => {
    const side = asSide(event.side)
    if (side === 'Home') home++
    else if (side === 'Away') away++
    return { event, side, team: side === 'Home' ? m.home : side === 'Away' ? m.away : null, home, away }
  })
}

/** Playable goals grouped by match; `onlyTeam` keeps goals scored by that team. The running score always counts every goal, clip or not. */
export function goalGroups(matches: Match[], onlyTeam?: string): GoalGroup[] {
  return matches
    .map(m => ({ m, rows: goalRows(m).filter(r => r.event.has_video && (!onlyTeam || r.team?.name === onlyTeam)) }))
    .filter(g => g.rows.length)
}

/** Playlist order (§2b): week asc → kick-off asc → minute asc (rows are already minute-sorted). Pure; does not mutate. */
export function playlistOrder(groups: GoalGroup[]): GoalGroup[] {
  return groups.slice().sort((a, b) => a.m.round - b.m.round || a.m.date.localeCompare(b.m.date) || a.m.id - b.m.id)
}

/** Scoreline after the goals scored strictly before `minute` plus any goal at that minute with the same event id (i.e. "at that moment"). */
export function scoreAt(m: Match, minute: number, eventId?: number): { home: number; away: number } {
  const rows = goalRows(m)
  let last = { home: 0, away: 0 }
  for (const r of rows) { if (r.event.minute < minute || r.event.id === eventId) last = { home: r.home, away: r.away }; else if (r.event.minute > minute) break }
  return last
}

/** Canonical item title (§2b, identical on all clients): `3. Hafta · Beşiktaş 2–1 Trabzonspor · 55' Jota Silva`. */
export function clipTitle(week: string, m: Match, score: { home: number; away: number }, e: Event): string {
  return `${week} · ${m.home.name} ${score.home}–${score.away} ${m.away.name} · ${e.minute}' ${e.description}`
}
