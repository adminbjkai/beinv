import { scoreline, type Event, type Match } from '../api'
import type { GoalGroup, GoalRow } from '../goals'

type Props = {
  groups: GoalGroup[]
  label?: (m: Match) => string | undefined
  onPlay: (m: Match, e: Event) => void
  onPlayAll: () => void
  /** week label of the first playlist item (§2b button text) */
  firstWeek: string
}

/** Goal clips grouped by match (running score + scoring team per card), plus "Play all" over the same filtered set. */
export default function GoalsGrid({ groups, label, onPlay, onPlayAll, firstWeek }: Props) {
  const total = groups.reduce((n, g) => n + g.rows.length, 0)
  if (!total) return <div className="glass fade-up rounded-2xl p-12 text-center text-white/60">No goal clips published yet.</div>
  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <span className="text-sm text-white/60">{total} goals · {groups.length} matches</span>
        <button onClick={onPlayAll} className="ml-auto rounded-full bg-accent px-4 py-1.5 text-sm font-semibold text-black transition hover:brightness-110">
          ▶ Play all · {total} goals · from {firstWeek}
        </button>
      </div>
      {groups.map(({ m, rows }, gi) => (
        <section key={m.id} className="fade-up" style={{ animationDelay: `${Math.min(gi, 8) * 40}ms` }}>
          <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold">
            {label?.(m) && <span className="rounded-md bg-accent/90 px-2 py-0.5 text-[11px] text-black">{label(m)}</span>}
            <img src={m.home.logo} alt="" className="h-5 w-5 object-contain" />
            <span>{scoreline(m)}</span>
            <img src={m.away.logo} alt="" className="h-5 w-5 object-contain" />
          </h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {rows.map(r => <GoalCard key={r.event.id} m={m} r={r} onPlay={() => onPlay(m, r.event)} />)}
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
      className="glass group relative overflow-hidden rounded-xl text-left transition hover:-translate-y-0.5 hover:border-accent/40 focus-visible:border-accent">
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
