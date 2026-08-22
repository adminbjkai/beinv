import type { Match } from '../api'

const dateFmt = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })

export default function MatchCard({ m, onOpen, i, label }: { m: Match; onOpen: () => void; i: number; label?: string }) {
  const goals = m.events.filter(e => e.is_goal).length
  return (
    <button onClick={onOpen} style={{ animationDelay: `${Math.min(i, 12) * 40}ms` }}
      className="fade-up glass group relative overflow-hidden rounded-2xl text-left transition duration-300 hover:-translate-y-1 hover:border-accent/40 hover:shadow-[0_20px_60px_-20px_rgba(25,195,125,.45)] focus-visible:border-accent">
      <div className="relative aspect-video overflow-hidden bg-black/40">
        {m.thumb && <img src={m.thumb} alt="" loading="lazy" className="h-full w-full object-cover transition duration-500 group-hover:scale-105" />}
        <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-black/10 to-transparent" />
        <div className="absolute inset-0 grid place-items-center opacity-0 transition group-hover:opacity-100 group-focus-visible:opacity-100">
          <span className="grid h-14 w-14 place-items-center rounded-full bg-white/90 text-black shadow-xl">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
          </span>
        </div>
        <span className="absolute left-3 top-3 flex gap-1.5">
          {label && <span className="rounded-md bg-accent/90 px-2 py-0.5 text-[11px] font-semibold text-black backdrop-blur">{label}</span>}
          <span className="rounded-md bg-black/60 px-2 py-0.5 text-[11px] font-medium text-white/80 backdrop-blur">
            {dateFmt.format(new Date(m.date))}
          </span>
        </span>
        {goals > 0 && (
          <span className="absolute right-3 top-3 rounded-md bg-accent px-2 py-0.5 text-[11px] font-semibold text-black">
            {goals} {goals === 1 ? 'goal' : 'goals'}
          </span>
        )}
      </div>
      <div className="flex items-center justify-between gap-3 p-4">
        <Side t={m.home} />
        <div className="shrink-0 text-center">
          <div className="text-2xl font-extrabold tabular-nums tracking-tight">
            {m.home.score ?? '–'}<span className="mx-1 text-white/30">:</span>{m.away.score ?? '–'}
          </div>
        </div>
        <Side t={m.away} right />
      </div>
    </button>
  )
}

function Side({ t, right }: { t: Match['home']; right?: boolean }) {
  return (
    <div className={`flex min-w-0 flex-1 items-center gap-2 ${right ? 'flex-row-reverse text-right' : ''}`}>
      <img src={t.logo} alt="" className="h-9 w-9 shrink-0 object-contain drop-shadow" />
      <span className="truncate text-sm font-semibold">{t.name}</span>
    </div>
  )
}
