import { scoreline, type Event, type Match } from '../api'
import type { GoalGroup, GoalRow } from '../goals'

type Props = {
  groups: GoalGroup[]
  label?: (m: Match) => string | undefined
  onPlay: (e: Event) => void
  onPlayAll: () => void
  /** week label of the first playlist item (§2b button text) */
  firstWeek: string
  empty?: string
}

/** Goal clips grouped by match (running score + scoring team per card), plus "Play all" over the same filtered set. */
export default function GoalsGrid({ groups, label, onPlay, onPlayAll, firstWeek, empty = 'No goal clips published yet.' }: Props) {
  const total = groups.reduce((n, g) => n + g.rows.length, 0)
  if (!total) return (
    <div className="state-panel glass fade-up rounded-2xl p-8 text-center sm:p-12" role="status">
      <span className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-accent/10 text-accent" aria-hidden="true">0′</span>
      <p className="font-semibold text-white/75">{empty}</p>
    </div>
  )
  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-center gap-3">
        <span className="text-sm font-medium text-white/55">{total} goals · {groups.length} matches</span>
        <button onClick={onPlayAll} className="min-h-11 w-full rounded-full bg-accent px-5 text-sm font-bold text-black shadow-[0_14px_35px_-16px_rgba(25,195,125,.8)] transition hover:brightness-110 sm:ml-auto sm:w-auto">
          ▶ Play all · {total} {total === 1 ? 'goal' : 'goals'} · from {firstWeek}
        </button>
      </div>
      {groups.map(({ m, rows }, gi) => (
        <section key={m.id} className="fade-up rounded-2xl border border-white/[.055] bg-white/[.018] p-3 sm:p-4" style={{ animationDelay: `${Math.min(gi, 8) * 40}ms` }}>
          <h2 className="mb-3 flex min-w-0 items-center gap-2 text-sm font-semibold">
            {label?.(m) && <span className="shrink-0 rounded-md bg-accent/90 px-2 py-0.5 text-[11px] text-black">{label(m)}</span>}
            <img src={m.home.logo} alt="" className="h-5 w-5 object-contain" />
            <span className="truncate">{scoreline(m)}</span>
            <img src={m.away.logo} alt="" className="h-5 w-5 object-contain" />
          </h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {rows.map(r => <GoalCard key={r.event.id} m={m} r={r} onPlay={() => onPlay(r.event)} />)}
          </div>
        </section>
      ))}
    </div>
  )
}

function GoalCard({ m, r, onPlay }: { m: Match; r: GoalRow; onPlay: () => void }) {
  const e = r.event
  const num = (side: 'Home' | 'Away', n: number) =>
    <span className={r.side === side ? 'text-accent' : 'text-white/60'}>{n}</span>
  return (
    <button onClick={onPlay} title={`${e.minute}' ${e.description} · ${scoreline(m)}`}
      className="glass control-surface group relative overflow-hidden rounded-xl text-left transition hover:-translate-y-0.5 hover:border-accent/40 focus-visible:border-accent">
      <div className="relative aspect-video bg-black/40">
        {(e.thumb || m.thumb) && <img src={e.thumb || m.thumb} alt="" loading="lazy" className="h-full w-full object-cover transition duration-500 group-hover:scale-105" />}
        <div className="absolute inset-0 bg-gradient-to-t from-black/80 to-transparent" />
        <span className="absolute bottom-2 left-2 right-2 flex items-center gap-1 text-xs font-semibold">
          <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent" />
          <span className="tabular-nums">{e.minute}'</span>
          <span className="truncate">{e.description}</span>
        </span>
        <span className="absolute right-2 top-2 rounded bg-black/70 px-1.5 py-0.5 text-xs font-bold tabular-nums">
          {num('Home', r.home)}<span className="text-white/40">–</span>{num('Away', r.away)}
        </span>
      </div>
      <div className="flex items-center gap-2 px-2.5 py-2 text-xs">
        {r.team
          ? <><img src={r.team.logo} alt="" className="h-5 w-5 shrink-0 object-contain" /><span className="truncate font-semibold">{r.team.name}</span></>
          : <span className="text-white/50">—</span>}
      </div>
    </button>
  )
}
