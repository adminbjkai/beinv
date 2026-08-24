import { useEffect, useRef } from 'react'
import type { PlaylistItem } from '../api'

type Props = { items: PlaylistItem[]; index: number; onIndex: (i: number) => void; /** chip layout (below player) instead of rows (drawer) */ chips?: boolean }

/** Ordered playlist grouped by week headers; current item highlighted and scrolled into view; click jumps. */
export default function ClipList({ items, index, onIndex, chips = false }: Props) {
  const root = useRef<HTMLDivElement>(null)
  useEffect(() => { root.current?.querySelector<HTMLElement>('[aria-current="true"]')?.scrollIntoView({ block: 'nearest', inline: 'nearest' }) }, [index])
  const groups: { week: string; from: number; items: PlaylistItem[] }[] = []
  items.forEach((it, i) => {
    const week = it.week ?? ''
    const g = groups[groups.length - 1]
    if (g && g.week === week) g.items.push(it); else groups.push({ week, from: i, items: [it] })
  })
  return (
    <div ref={root} className={chips ? 'space-y-3' : 'space-y-2'} aria-label="Playlist clips">
      {groups.map(g => (
        <section key={g.from}>
          {g.week && <h3 className={`mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-white/45 ${chips ? '' : 'px-2.5 pt-1'}`}>
            <span className="rounded bg-accent/80 px-1.5 py-0.5 normal-case tracking-normal text-black">{g.week}</span>
          </h3>}
          <div className={chips ? 'flex gap-2 overflow-x-auto pb-1 [-webkit-overflow-scrolling:touch] [scrollbar-width:none] sm:flex-wrap [&::-webkit-scrollbar]:hidden' : 'space-y-0.5'}>
            {g.items.map((it, j) => {
              const i = g.from + j, on = i === index
              const label = it.event ? `${it.event.minute}' ${it.event.description}` : 'Full highlight'
              return chips ? (
                <button key={it.key} onClick={() => onIndex(i)} aria-current={on} title={it.title}
                  className={`flex min-h-9 shrink-0 items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-semibold transition ${on ? 'bg-white text-black shadow-lg' : 'glass text-white/75 hover:border-white/20 hover:text-white'}`}>
                  {it.logo && <img src={it.logo} alt="" className="h-4 w-4 object-contain" />}
                  {it.event?.is_goal && !it.logo && <span className="h-1.5 w-1.5 rounded-full bg-accent" />}
                  <span className="max-w-[14rem] truncate">{label}</span>
                  {it.score && <span className={`tabular-nums ${on ? 'text-black/60' : 'text-white/50'}`}>{it.score}</span>}
                </button>
              ) : (
                <button key={it.key} onClick={() => onIndex(i)} aria-current={on} title={it.title}
                  className={`flex w-full items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-left text-xs transition ${on ? 'bg-accent/20 text-white ring-1 ring-accent/60' : 'text-white/75 hover:bg-white/10 hover:text-white'}`}>
                  <span className={`w-7 shrink-0 text-right font-semibold tabular-nums ${on ? 'text-accent' : ''}`}>{it.event ? `${it.event.minute}'` : '▶'}</span>
                  {it.logo ? <img src={it.logo} alt="" className="h-5 w-5 shrink-0 object-contain" /> : <span className="h-5 w-5 shrink-0" />}
                  <span className="min-w-0 flex-1 truncate font-semibold">{it.event?.description ?? 'Full highlight'}</span>
                  {it.score && <span className={`shrink-0 rounded bg-black/50 px-1.5 py-0.5 font-bold tabular-nums ${on ? 'text-accent' : ''}`}>{it.score}</span>}
                </button>
              )
            })}
          </div>
        </section>
      ))}
    </div>
  )
}
