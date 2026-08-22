import { test } from 'node:test'
import assert from 'node:assert/strict'
import { goalRows, goalGroups, playlistOrder, clipTitle, scoreAt } from './goals.ts'
import type { Match } from './api'

const ev = (id: number, minute: number, side: string | null, is_goal = true, has_video = true) =>
  ({ id, minute, description: `g${id}`, is_goal, side, thumb: '', has_video })
const fix: Match = {
  id: 1, round: 1, title: 't', date: '2026-01-01', thumb: '', has_highlight: true,
  home: { name: 'Beşiktaş', logo: 'h.png', score: 2 }, away: { name: 'Galatasaray', logo: 'a.png', score: 2 },
  events: [ev(3, 80, 'Away'), ev(1, 12, 'Home'), ev(9, 50, 'Home', false), ev(2, 45, null), ev(4, 88, 'Home'), ev(5, 90, 'Away', true, false)],
}

test('running score walks goals in minute order and attributes the scoring team', () => {
  const rows = goalRows(fix)
  assert.deepEqual(rows.map(r => [r.event.id, r.team?.name ?? '—', `${r.home}-${r.away}`]), [
    [1, 'Beşiktaş', '1-0'], [2, '—', '1-0'], [3, 'Galatasaray', '1-1'], [4, 'Beşiktaş', '2-1'], [5, 'Galatasaray', '2-2'],
  ])
  assert.equal(rows[1].side, null)
})

test('goalGroups filters to the team, hides clip-less goals, keeps the full running score', () => {
  const [g] = goalGroups([fix], 'Galatasaray')
  assert.deepEqual(g.rows.map(r => [r.event.id, r.home, r.away]), [[3, 1, 1]])  // goal 5 counted (2-2) but not rendered
  assert.equal(goalGroups([fix], 'Nobody').length, 0)
  assert.equal(goalGroups([fix])[0].rows.length, 4)
})

test('playlistOrder sorts week asc, then kick-off asc, then minute asc', () => {
  const mk = (id: number, round: number, date: string, mins: number[]): Match =>
    ({ ...fix, id, round, date, events: mins.map((m, i) => ev(id * 100 + i, m, 'Home')) })
  const groups = goalGroups([mk(3, 34, '2026-05-20T19:00', [70, 10]), mk(2, 2, '2025-08-20T19:00', [5]), mk(1, 2, '2025-08-18T17:00', [90, 30])])
  const flat = playlistOrder(groups).flatMap(g => g.rows.map(r => `${g.m.round}/${g.m.id}/${r.event.minute}`))
  assert.deepEqual(flat, ['2/1/30', '2/1/90', '2/2/5', '34/3/10', '34/3/70'])
})

test('clipTitle is the canonical "week · scoreline at that moment · minute scorer"', () => {
  const [r1, , r3] = goalRows(fix)
  assert.equal(clipTitle('3. Hafta', fix, r1, r1.event), "3. Hafta · Beşiktaş 1–0 Galatasaray · 12' g1")
  assert.equal(clipTitle('3. Hafta', fix, r3, r3.event), "3. Hafta · Beşiktaş 1–1 Galatasaray · 80' g3")
  assert.deepEqual(scoreAt(fix, 60), { home: 1, away: 0 })          // non-goal clip at 60' → 12' Home counted, 45' unknown side not
  assert.deepEqual(scoreAt(fix, 88, 4), { home: 2, away: 1 })       // the goal itself counts
  assert.deepEqual(scoreAt(fix, 1), { home: 0, away: 0 })
})
