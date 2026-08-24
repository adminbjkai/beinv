import { test } from 'node:test'
import assert from 'node:assert/strict'
import { matchPlaylist, clipItem, scoreline, videoUrl, type Match, type Event } from './api.ts'

const ev = (id: number, minute: number, has_video = true): Event =>
  ({ id, minute, description: `g${id}`, is_goal: true, side: 'Home', thumb: `t${id}.jpg`, has_video })
const base: Match = {
  id: 7, round: 3, title: 'Beşiktaş 1-0 Eyüpspor Maç Özeti', date: '2026-08-16T18:30:00Z', thumb: 'm.jpg',
  has_highlight: true, events: [],
  home: { name: 'Beşiktaş', logo: 'h.png', score: 1 }, away: { name: 'Eyüpspor', logo: 'a.png', score: 0 },
}
/** the shape App.tsx passes: keyed by event, so it must never be called without one */
const meta = (e: Event) => ({ week: '3. Hafta', score: '1–0', title: `3. Hafta · ${e.minute}' ${e.description}` })

test('matchPlaylist handles a highlight with no events (Premier League: matchEvents null)', () => {
  const items = matchPlaylist(base, 'ingiltere-premier-ligi', 3974, meta)
  assert.equal(items.length, 1)
  assert.equal(items[0].key, 'm7')
  assert.equal(items[0].week, undefined)
  assert.equal(items[0].src, '/video/m/7?l=ingiltere-premier-ligi&s=3974&r=3')
})

test('matchPlaylist is the full highlight then every clip-backed event, in order', () => {
  const m = { ...base, events: [ev(11, 25), ev(12, 61, false), ev(13, 78)] }
  const items = matchPlaylist(m, 'super-lig', 3974, meta)
  assert.deepEqual(items.map(i => i.key), ['m7', 'e11', 'e13'])   // 12 has no clip
  assert.equal(items[0].week, '3. Hafta')                          // borrowed from the first event
  assert.equal(items[1].title, "3. Hafta · 25' g11")               // meta.title wins over the default
})

test('matchPlaylist omits the highlight when there is none, and works without meta', () => {
  const m = { ...base, has_highlight: false, events: [ev(11, 25)] }
  const items = matchPlaylist(m, 'super-lig', 3974)
  assert.deepEqual(items.map(i => i.key), ['e11'])
  assert.equal(items[0].title, "25' g11 · Beşiktaş 1-0 Eyüpspor")  // default title falls back to the scoreline
})

test('clipItem falls back to the match thumbnail, and scoreline renders a missing score as –', () => {
  const e = { ...ev(11, 25), thumb: '' }
  assert.equal(clipItem(base, e, { league: 'super-lig', season: 3974, round: 3 }).poster, 'm.jpg')
  assert.equal(videoUrl('e', 11, { league: 'super-lig', season: 3974, round: 3 }), '/video/e/11?l=super-lig&s=3974&r=3')
  assert.equal(videoUrl('m', 102249, { league: 'ispanya-la-liga', season: 3968, round: 1 }), '/video/m/102249?l=ispanya-la-liga&s=3968&r=1')
  assert.equal(scoreline({ ...base, away: { ...base.away, score: null } }), 'Beşiktaş 1-– Eyüpspor')
})
