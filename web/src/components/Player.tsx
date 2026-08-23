import { useCallback, useEffect, useRef, useState } from 'react'
import type { PlaylistItem } from '../api'
import ClipList from './ClipList'

type Props = {
  items: PlaylistItem[]
  index: number
  onIndex: (i: number) => void
  /** last item finished (no looping) → caller returns to the list */
  onEnd: () => void
  autoNext: boolean
  onAutoNext: (on: boolean) => void
  /** open the clip selector on mount (`?clips=1`) */
  initialClips?: boolean
}

const fmt = (t: number) => {
  if (!isFinite(t)) return '0:00'
  const m = Math.floor(t / 60), s = Math.floor(t % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}

export default function Player({ items, index, onIndex, onEnd, autoNext, onAutoNext, initialClips = false }: Props) {
  const item = items[index], next = items[index + 1], prev = items[index - 1]
  const wrap = useRef<HTMLDivElement>(null)
  const v = useRef<HTMLVideoElement>(null)
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState(0)
  const [dur, setDur] = useState(0)
  const [buffered, setBuffered] = useState(0)
  const [vol, setVol] = useState(1)
  const [muted, setMuted] = useState(false)
  const [show, setShow] = useState(true)
  const [waiting, setWaiting] = useState(false)
  const [clips, setClips] = useState(initialClips)
  const drawer = useRef<HTMLElement>(null)
  const clipsBtn = useRef<HTMLButtonElement>(null)
  const clipsRef = useRef(clips)
  useEffect(() => { clipsRef.current = clips; if (clips) setShow(true) }, [clips])
  const hideTimer = useRef<number | undefined>(undefined)
  /** best-effort resume positions per playlist key (used when coming back to the full highlight) */
  const positions = useRef(new Map<string, number>())
  const latest = useRef({ index, items, autoNext, onIndex, onEnd })
  latest.current = { index, items, autoNext, onIndex, onEnd }

  const poke = useCallback(() => {
    setShow(true)
    window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => { if (!clipsRef.current) setShow(false) }, 2200)
  }, [])

  const toggle = useCallback(() => {
    const el = v.current; if (!el) return
    if (el.paused) el.play().catch(() => {}); else el.pause()
  }, [])
  const seekBy = useCallback((d: number) => { const el = v.current; if (el) el.currentTime = Math.max(0, Math.min(el.duration || 0, el.currentTime + d)) }, [])
  const fullscreen = useCallback(() => {
    const el = wrap.current; if (!el) return
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    else el.requestFullscreen().catch(() => {})
  }, [])
  const pip = useCallback(() => { const el = v.current; if (el && 'requestPictureInPicture' in el) el.requestPictureInPicture().catch(() => {}) }, [])
  const step = useCallback((d: number) => {
    const { index, items, onIndex } = latest.current
    const n = index + d
    if (n >= 0 && n < items.length) onIndex(n)
  }, [])

  useEffect(() => {
    const el = v.current; if (!el) return
    const onTime = () => {
      setTime(el.currentTime)
      if (el.currentTime > 0) positions.current.set(item.key, el.currentTime)
      if (el.buffered.length) setBuffered(el.buffered.end(el.buffered.length - 1))
    }
    const onMeta = () => {
      setDur(el.duration)
      const t = positions.current.get(item.key)
      if (item.key.startsWith('m') && t && t < el.duration - 3) el.currentTime = t
    }
    const onPlay = () => setPlaying(true), onPause = () => setPlaying(false)
    const onWait = () => setWaiting(true), onOk = () => setWaiting(false)
    const onEnded = () => {
      const { index, items, autoNext, onIndex, onEnd } = latest.current
      positions.current.delete(item.key)
      if (index < items.length - 1) { if (autoNext) onIndex(index + 1) }
      else onEnd()
    }
    el.addEventListener('timeupdate', onTime); el.addEventListener('progress', onTime)
    el.addEventListener('loadedmetadata', onMeta); el.addEventListener('durationchange', onMeta)
    el.addEventListener('play', onPlay); el.addEventListener('pause', onPause)
    el.addEventListener('waiting', onWait); el.addEventListener('playing', onOk); el.addEventListener('canplay', onOk)
    el.addEventListener('ended', onEnded)
    return () => {
      el.removeEventListener('timeupdate', onTime); el.removeEventListener('progress', onTime)
      el.removeEventListener('loadedmetadata', onMeta); el.removeEventListener('durationchange', onMeta)
      el.removeEventListener('play', onPlay); el.removeEventListener('pause', onPause)
      el.removeEventListener('waiting', onWait); el.removeEventListener('playing', onOk); el.removeEventListener('canplay', onOk)
      el.removeEventListener('ended', onEnded)
    }
  }, [item.key])

  // new item → reset display state and start playback
  useEffect(() => {
    setTime(0); setDur(0); setBuffered(0); poke()
    const el = v.current
    if (el) el.play().catch(() => {})
  }, [item.key, poke])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null
      const tag = el?.tagName
      if (tag === 'SELECT' || tag === 'INPUT' || tag === 'TEXTAREA' || el?.isContentEditable) return
      // Space/Enter on a focused control must activate it, not toggle playback.
      if (tag === 'BUTTON' && (e.key === ' ' || e.key === 'Enter')) return
      switch (e.key) {
        case ' ': case 'k': e.preventDefault(); toggle(); break
        case 'ArrowLeft': seekBy(-10); break
        case 'ArrowRight': seekBy(10); break
        case 'f': fullscreen(); break
        case 'm': setMuted(m => !m); break
        case 'n': step(1); break
        case 'p': step(-1); break
        case 'c': setClips(c => !c); break
        case 'Escape': if (!clipsRef.current) return; e.stopPropagation(); setClips(false); break
        default: return
      }
      poke()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [toggle, seekBy, fullscreen, poke, step])
  useEffect(() => {
    if (!clips) return
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node
      // Ignore the toggle itself: closing here would let its own click re-open the drawer.
      if (!drawer.current?.contains(t) && !clipsBtn.current?.contains(t)) setClips(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [clips])

  useEffect(() => { if (v.current) { v.current.volume = vol; v.current.muted = muted } }, [vol, muted])

  const pct = dur ? (time / dur) * 100 : 0
  const bpct = dur ? (buffered / dur) * 100 : 0
  const visible = show || !playing || clips
  const btn = 'rounded-lg px-2 py-1 text-xs font-semibold transition hover:bg-white/10 disabled:opacity-30 disabled:hover:bg-transparent'

  return (
    <div ref={wrap} onMouseMove={poke} onMouseLeave={() => playing && !clips && setShow(false)}
      className="group relative aspect-video w-full overflow-hidden rounded-2xl bg-black shadow-[0_30px_80px_-20px_rgba(0,0,0,.8)] select-none">
      <video ref={v} src={item.src} poster={item.poster} autoPlay playsInline preload="metadata"
        onClick={toggle} onDoubleClick={fullscreen} className="h-full w-full" />

      {waiting && (
        <div className="pointer-events-none absolute inset-0 grid place-items-center">
          <div className="h-12 w-12 animate-spin rounded-full border-4 border-white/20 border-t-accent" />
        </div>
      )}

      {!playing && !waiting && (
        <button onClick={toggle} aria-label="Play"
          className="absolute inset-0 grid place-items-center bg-black/20 transition hover:bg-black/30">
          <span className="grid h-20 w-20 place-items-center rounded-full bg-white/90 text-black shadow-2xl transition group-hover:scale-105">
            <svg width="30" height="30" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
          </span>
        </button>
      )}

      {clips && (
        <aside ref={drawer} onMouseMove={e => e.stopPropagation()} onClick={e => e.stopPropagation()}
          className="glass absolute inset-y-0 right-0 z-10 hidden w-[35%] max-w-[35%] flex-col border-l border-white/10 shadow-2xl min-[900px]:flex">
          <div className="flex items-center gap-2 border-b border-white/10 px-3 py-2.5 text-sm font-semibold">
            <span>Clips</span><span className="text-xs font-medium text-white/50">{index + 1} of {items.length}</span>
            <button onClick={() => setClips(false)} className="ml-auto rounded-lg px-2 py-0.5 text-xs text-white/60 transition hover:bg-white/10 hover:text-white" aria-label="Close clips (c)">✕</button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto p-1.5">
            <ClipList items={items} index={index} onIndex={i => { onIndex(i); poke() }} />
          </div>
        </aside>
      )}

      <div className={`absolute inset-y-auto left-0 top-0 flex items-start ${clips ? 'right-[35%] max-[899px]:right-0' : 'right-0'} justify-between gap-4 bg-gradient-to-b from-black/70 to-transparent p-4 text-sm font-semibold text-white/90 transition-opacity ${visible ? 'opacity-100' : 'opacity-0'}`}>
        <div className="min-w-0">
          <div className="line-clamp-2 leading-snug">{item.title}</div>
          {next && <div className="truncate text-xs font-medium text-white/60">Up next: {next.title}</div>}
          {prev && <div className="truncate text-xs font-medium text-white/40">Previous: {prev.event ? `${prev.week ? prev.week + ' · ' : ''}${prev.event.minute}' ${prev.event.description}` : prev.title}</div>}
        </div>
        {items.length > 1 && <span className="shrink-0 text-xs font-medium text-white/60">Now playing · {index + 1} of {items.length}</span>}
      </div>

      <div className={`absolute bottom-0 left-0 bg-gradient-to-t ${clips ? 'right-[35%] max-[899px]:right-0' : 'right-0'} from-black/80 via-black/40 to-transparent px-4 pb-3 pt-10 transition-opacity ${visible ? 'opacity-100' : 'opacity-0'}`}>
        <div className="relative mb-2 h-1 w-full rounded-full bg-white/20">
          <div className="absolute inset-y-0 left-0 rounded-full bg-white/35" style={{ width: `${bpct}%` }} />
          <div className="absolute inset-y-0 left-0 rounded-full bg-accent" style={{ width: `${pct}%` }} />
          <input type="range" min={0} max={dur || 0} step={0.05} value={time}
            onChange={e => { if (v.current) v.current.currentTime = +e.target.value }}
            className="absolute -top-1.5 left-0 h-4 w-full cursor-pointer" aria-label="Seek" />
        </div>
        <div className="flex flex-wrap items-center gap-2 text-white md:gap-3">
          <button onClick={() => step(-1)} disabled={index === 0} className={btn} aria-label="Previous clip (p)">⏮</button>
          <button onClick={toggle} className="grid h-9 w-9 place-items-center rounded-lg transition hover:bg-white/10" aria-label={playing ? 'Pause' : 'Play'}>
            {playing
              ? <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h4v14H6zm8 0h4v14h-4z" /></svg>
              : <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>}
          </button>
          <button onClick={() => step(1)} disabled={index >= items.length - 1} className={btn} aria-label="Next clip (n)">⏭</button>
          <button onClick={() => seekBy(-10)} className={btn}>−10s</button>
          <button onClick={() => seekBy(10)} className={btn}>+10s</button>
          <div className="flex items-center gap-2">
            <button onClick={() => setMuted(m => !m)} className="grid h-9 w-9 place-items-center rounded-lg transition hover:bg-white/10" aria-label="Mute">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                {muted || vol === 0
                  ? <path d="M16.5 12A4.5 4.5 0 0 0 14 8v2.2l2.5 2.5zM19 12c0 .9-.2 1.8-.5 2.6l1.5 1.5A9 9 0 0 0 21 12a9 9 0 0 0-7-8.8v2.1A7 7 0 0 1 19 12zM4.3 3 3 4.3 7.7 9H3v6h4l5 5v-6.7l4.3 4.3c-.7.5-1.4.9-2.3 1.1v2.1c1.4-.3 2.6-1 3.7-1.8L19.7 21 21 19.7zM12 4 9.9 6.1 12 8.2z" />
                  : <path d="M3 9v6h4l5 5V4L7 9zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1A9 9 0 0 0 14 3.2z" />}
              </svg>
            </button>
            <input type="range" min={0} max={1} step={0.02} value={muted ? 0 : vol}
              onChange={e => { setVol(+e.target.value); setMuted(false) }} className="w-20" aria-label="Volume" />
          </div>
          <span className="ml-1 text-xs tabular-nums text-white/80">{fmt(time)} / {fmt(dur)}</span>
          <div className="ml-auto flex items-center gap-1">
            <button onClick={() => onAutoNext(!autoNext)} aria-pressed={autoNext} title="Autoplay next clip"
              className={`${btn} ${autoNext ? 'text-accent' : 'text-white/60'}`}>
              Autoplay {autoNext ? 'on' : 'off'}
            </button>
            {items.length > 1 && (
              <button ref={clipsBtn} onClick={() => setClips(c => !c)} aria-pressed={clips} aria-label="Clips (c)" className={`${btn} ${clips ? 'text-accent' : ''}`}>Clips</button>
            )}
            <button onClick={pip} className={btn}>PiP</button>
            <button onClick={fullscreen} className="grid h-9 w-9 place-items-center rounded-lg transition hover:bg-white/10" aria-label="Fullscreen (f)">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M7 14H5v5h5v-2H7zm-2-4h2V7h3V5H5zm12 7h-3v2h5v-5h-2zM14 5v2h3v3h2V5z" /></svg>
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
