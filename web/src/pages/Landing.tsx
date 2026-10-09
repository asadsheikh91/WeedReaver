import { motion, useInView, useMotionValue, useMotionValueEvent, useReducedMotion, useScroll, useSpring, useTransform, type MotionValue } from 'framer-motion'
import {
  ArrowRight, BadgeCheck, ChevronDown, CloudOff, Droplets, FileDown, Inbox, Layers, Lock, PlaneTakeoff, ShieldCheck, Smartphone,
  Sprout, TrendingDown, Wifi,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { followUpGridOf, gridOf, zonesOf } from '../data/analysis'
import { fmt } from '../data/format'
import { fieldAcres } from '../data/geo'
import { SHOWCASE_FIELD, SHOWCASE_ROTATION } from '../data/showcase'
import type { FieldParcel, RasterGrid } from '../data/types'
import { MapView, type MapHandle } from '../map/MapView'
import { dimOutside, fieldOutline, heatLayer, zonePin, type DrawCtx } from '../map/overlays'
import { TrendChart } from '../ui/charts'
import { BrandMark } from '../ui/brand'
import { IPhone } from '../ui/iphone'
import './landing.css'

const ease = [0.2, 0, 0, 1] as const

function Reveal({ children, delay = 0, y = 28, className }: { children: ReactNode; delay?: number; y?: number; className?: string }) {
  return (
    <motion.div className={className} initial={{ opacity: 0, y }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, margin: '-80px' }} transition={{ duration: 0.8, delay, ease }}>
      {children}
    </motion.div>
  )
}

/** True while the query matches. Starts from the live value so pinned layouts do not flash unpinned first. */
function useMedia(query: string) {
  const [on, setOn] = useState(() => typeof window !== 'undefined' && window.matchMedia(query).matches)
  useEffect(() => {
    const m = window.matchMedia(query)
    const h = () => setOn(m.matches)
    h(); m.addEventListener('change', h)
    return () => m.removeEventListener('change', h)
  }, [query])
  return on
}

/** The browser window rises out of a slight backward tilt as it scrolls into view, like a laptop lid coming up. */
function Browser({ src, alt, url, className }: { src: string; alt: string; url: string; className?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'center center'] })
  const rotateX = useTransform(scrollYProgress, [0, 1], [reduce ? 0 : 9, 0])
  const scale = useTransform(scrollYProgress, [0, 1], [reduce ? 1 : 0.94, 1])
  return (
    <div className="browser-stage" ref={ref}>
      <motion.div className={`browser ${className ?? ''}`} style={{ rotateX, scale }}>
        <div className="browser__bar"><i className="r" /><i className="y" /><i className="g" /><span><Lock size={11} />{url}</span></div>
        <div className="browser__view"><img src={src} alt={alt} loading="lazy" /><i className="browser__glare" /></div>
      </motion.div>
    </div>
  )
}

/** Two phones on separate depth planes: they drift at different rates as the section crosses the viewport. */
function PhonePair() {
  const ref = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'end start'] })
  const k = reduce ? 0 : 1
  const ya = useTransform(scrollYProgress, [0, 1], [70 * k, -60 * k])
  const yb = useTransform(scrollYProgress, [0, 1], [-10 * k, 90 * k])
  const ra = useTransform(scrollYProgress, [0, 1], [-7 * k - 1, -2 * k - 1])
  const rb = useTransform(scrollYProgress, [0, 1], [6 * k, 1 * k])
  return (
    <div className="lp-phones" ref={ref}>
      <i className="lp-phones__halo" aria-hidden />
      <motion.div className="lp-phones__a" style={{ y: ya, rotate: ra }}><IPhone src="/screens/ios/route.jpg" alt="Spray route" bar="light" tone="deep" /></motion.div>
      <motion.div className="lp-phones__b" style={{ y: yb, rotate: rb }}><IPhone src="/screens/ios/navigate.jpg" alt="Navigating to a zone" bar="light" tone="silver" /></motion.div>
    </div>
  )
}

export default function Landing() {
  const chak = SHOWCASE_FIELD
  const threshold = 10
  const grid = useMemo(() => gridOf(chak, 2, threshold), [chak, threshold])
  const zones = useMemo(() => zonesOf(chak, threshold), [chak, threshold])
  const saved = 1 - grid.treatedFraction
  const [solid, setSolid] = useState(false)
  useEffect(() => {
    const on = () => setSolid(window.scrollY > 72)
    on(); window.addEventListener('scroll', on, { passive: true })
    return () => window.removeEventListener('scroll', on)
  }, [])
  useEffect(() => { document.title = 'WeedReaver · Know where control failed' }, [])

  return (
    <div className="lp">
      <header className={`lp-nav ${solid ? 'is-solid' : ''}`}>
        <div className="lp-wrap lp-nav__in">
          <a href="#top" className="lp-brand"><BrandMark size={34} bg={solid ? '#1E4A33' : '#F5F0E6'} ink={solid ? '#F5F0E6' : '#143524'} /><span>WeedReaver</span></a>
          <nav aria-label="Sections">
            <a href="#how">How it works</a><a href="#system">Two surfaces</a><a href="#app">Field app</a><a href="#dashboard">Dashboard</a><a href="#principles">Principles</a>
          </nav>
          <Link to="/app" className="lp-btn lp-btn--nav">Open the dashboard <ArrowRight size={16} /></Link>
        </div>
      </header>

      <Hero chak={chak} grid={grid} zonesCount={zones.length} saved={saved} />

      {/* ------------------------------------------------------------- proof strip */}
      <section className="lp-strip">
        <div className="lp-wrap lp-strip__grid">
          {[
            [fmt.int(grid.total), 'cells of 2 × 2 m on one 8.45 acre wheat field, each with a class and a confidence'],
            [fmt.pct(saved), 'less herbicide than a blanket spray, at equal control, on this prescription'],
            ['81 → 47%', 'control by one mode of action over three seasons, on the same field'],
            ['0 bars', 'of signal needed in the field. Every write is queued on the phone first'],
          ].map(([n, t], i) => (
            <Reveal key={n} delay={i * 0.08}><div className="lp-strip__item"><b>{n}</b><span>{t}</span></div></Reveal>
          ))}
        </div>
      </section>

      {/* ------------------------------------------------------------- problem */}
      <section className="lp-sec" id="problem">
        <div className="lp-wrap lp-problem">
          <div>
            <Reveal><div className="lp-over">The problem</div></Reveal>
            <Reveal delay={0.05}><h2 className="lp-h2">Resistance is not a spraying mistake. It is a selection process.</h2></Reveal>
            <Reveal delay={0.1}><p className="lp-lead">Littleseed canarygrass has been sprayed with the same few modes of action across Punjab wheat for two decades. Every blanket application kills the susceptible plants and leaves the survivors to seed the next season. By the time control visibly fails, the resistant patch has been growing for years.</p></Reveal>
            <ul className="lp-facts">
              {[
                [Droplets, 'Blanket spraying treats the whole field to reach a few patches.'],
                [Sprout, 'Weeds are aggregated. The patches are where the seed bank grows.'],
                [TrendingDown, 'A field average hides the one zone where control failed.'],
              ].map(([Ico, t], i) => { const I = Ico as typeof Droplets; return <Reveal key={String(t)} delay={0.15 + i * 0.07}><li><I size={18} /> {t as string}</li></Reveal> })}
            </ul>
          </div>
          <Reveal delay={0.1}>
            <div className="lp-card lp-chart">
              <div className="lp-chart__head"><div><div className="lp-over" style={{ color: 'var(--clay-ink)' }}>Chak 47 · Group 1, ACCase inhibitor</div><div className="serif" style={{ fontSize: 26, letterSpacing: '-0.02em', marginTop: 4 }}>Control fell every season it was repeated</div></div></div>
              <TrendChart values={SHOWCASE_ROTATION.map((r) => r.controlPct)} labels={SHOWCASE_ROTATION.map((r) => `Rabi ${r.season}`)} height={280} />
              <p className="caption">Worst zone per season, from the follow-up flight. The dashed line marks the level below which the agronomist treats a population as suspect.</p>
            </div>
          </Reveal>
        </div>
      </section>

      {/* ------------------------------------------------------------- how it works */}
      <section className="lp-sec lp-sec--deep" id="how">
        <div className="lp-wrap">
          <Reveal><div className="lp-over">How it works</div></Reveal>
          <Reveal delay={0.05}><h2 className="lp-h2" style={{ maxWidth: 820 }}>Survey from the air. Confirm on the ground. Measure the result.</h2></Reveal>

          <Step n="01" icon={PlaneTakeoff} title="A drone maps the field at 15 m." body="A DJI Mavic 3M flies the parcel. OpenDroneMap stitches roughly 600 images into a mosaic, and a segmentation model labels every square as crop, grass weed or broadleaf weed. Cells it cannot call are marked abstained, not guessed." tags={['15 m AGL', '0.42 cm/px', '3 classes']}>
            <SurveyVisual chak={chak} />
          </Step>
          <Step n="02" icon={Smartphone} flip title="A person confirms it, without signal." body="The operator's phone routes them from the gate to each zone, nearest first. A leaf scanner runs on the phone at 30 cm and says so when it is not sure. Slide to mark a zone treated; record the product and dose from the label." tags={['Offline first', 'Slide to confirm', 'Abstains when unsure']}>
            <PhonePair />
          </Step>
          <PinnedCompare chak={chak} />
        </div>
      </section>

      {/* ------------------------------------------------------------- two surfaces */}
      <section className="lp-sec lp-sys" id="system">
        <div className="lp-wrap">
          <Reveal><div className="lp-over" style={{ color: 'rgba(245,240,230,.55)' }}>One system, two surfaces</div></Reveal>
          <Reveal delay={0.05}><h2 className="lp-h2 lp-h2--light">The web decides.<br />The mobile does.</h2></Reveal>
          <Reveal delay={0.1}><p className="lp-lead lp-lead--light">If a screen changes what the system <i>believes</i> because a person saw something, it belongs on the phone. If it changes what the system <i>instructs</i>, it belongs on the dashboard.</p></Reveal>
          <div className="lp-sys__grid">
            <Reveal><div className="lp-side">
              <div className="lp-side__ico"><Layers /></div>
              <h3>Dashboard</h3><div className="lp-side__tag">Defines</div>
              <ul><li>Field boundaries and zone geometry</li><li>The prescription threshold</li><li>Flight upload and processing</li><li>Review of what the models declined</li><li>Exports for other tools</li></ul>
            </div></Reveal>
            <Reveal delay={0.1}><div className="lp-rule">
              <div className="lp-rule__line" />
              <div className="lp-rule__dot"><Wifi size={18} /></div>
              <p>Never last-write-wins. Each change carries a sequence number from the phone that made it.</p>
            </div></Reveal>
            <Reveal delay={0.2}><div className="lp-side lp-side--b">
              <div className="lp-side__ico"><Smartphone /></div>
              <h3>Field app</h3><div className="lp-side__tag">Observes</div>
              <ul><li>Leaf scans and their photos</li><li>Zones marked treated</li><li>Product and dose applied</li><li>Quadrat counts</li><li>Boundary walks</li></ul>
            </div></Reveal>
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------------- field app */}
      <section className="lp-sec" id="app">
        <div className="lp-wrap">
          <Reveal><div className="lp-over">The field app</div></Reveal>
          <Reveal delay={0.05}><h2 className="lp-h2" style={{ maxWidth: 780 }}>Built for a knapsack, a bund and no signal.</h2></Reveal>
        </div>
        <Carousel />
      </section>

      {/* ------------------------------------------------------------- dashboard */}
      <section className="lp-sec lp-sec--deep" id="dashboard">
        <div className="lp-wrap">
          <Reveal><div className="lp-over">The dashboard</div></Reveal>
          <Reveal delay={0.05}><h2 className="lp-h2" style={{ maxWidth: 760 }}>Where the analyst decides what gets sprayed.</h2></Reveal>
          <Reveal delay={0.1}><div className="lp-dash"><Browser src="/screens/dash-weedmap.jpg" alt="Weed map with live prescription threshold" url="weedreaver.station/app/weed-map/F-047" /></div></Reveal>
          <div className="lp-feats">
            {([
              [Layers, 'Weed map and threshold', 'Drag one slider and every figure recalculates from the survey: area, zones, abstained cells.'],
              [PlaneTakeoff, 'Flights', 'Upload a flight and watch it move through photogrammetry, segmentation and zoning.'],
              [Inbox, 'Review queue', 'Resolve what the models declined, with the photo, the location and the candidates.'],
              [TrendingDown, 'Rotation and resistance', 'Which mode of action went onto which field, and what it cost in control.'],
              [BadgeCheck, 'Verification', 'Drag between the two flights. Control is reported per zone.'],
              [FileDown, 'Exports', 'GeoJSON, Shapefile and ISO 11783-10 TASKDATA. Real files, no rates.'],
              [Smartphone, 'Devices and sync', 'Change log with sequence numbers, and who wins each kind of conflict.'],
              [ShieldCheck, 'Audit log', 'Every change to a definition, with who made it and when.'],
            ] as const).map(([I, t, d], i) => (
              <Reveal key={t} delay={(i % 4) * 0.06}><div className="lp-feat"><I size={22} /><h4>{t}</h4><p>{d}</p></div></Reveal>
            ))}
          </div>
          <Reveal><div className="lp-dash-cta"><Link to="/app" className="lp-btn lp-btn--light">Open the dashboard <ArrowRight size={17} /></Link><span>No sign-in. All data is a demonstration and lives in your browser.</span></div></Reveal>
        </div>
      </section>

      {/* ------------------------------------------------------------- principles */}
      <section className="lp-sec" id="principles">
        <div className="lp-wrap">
          <Reveal><div className="lp-over">Principles</div></Reveal>
          <Reveal delay={0.05}><h2 className="lp-h2" style={{ maxWidth: 760 }}>Four rules the interface enforces, not just describes.</h2></Reveal>
          <div className="lp-quotes">
            {[
              ['Recorded, never prescribed.', 'The system records the product and dose an operator applies. It computes nothing and publishes no application rates.', Lock],
              ['Abstains instead of guessing.', 'A confident wrong answer causes exactly the unnecessary spraying this project exists to prevent. An abstained scan or cell becomes a task for a person.', ShieldCheck],
              ['Per zone, never a field average.', 'A mean hides the zone where control failed, and that is the zone that matters.', Layers],
              ['Offline is the default.', 'Connectivity is an occasional bonus. Every write is queued on the phone first and uploaded in order.', CloudOff],
            ].map(([q, b, I], i) => { const Ico = I as typeof Lock; return (
              <Reveal key={q as string} delay={(i % 2) * 0.08}><figure className="lp-quote"><Ico size={22} /><blockquote>{q as string}</blockquote><figcaption>{b as string}</figcaption></figure></Reveal>
            ) })}
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------------- honesty */}
      <section className="lp-sec lp-sec--tight">
        <div className="lp-wrap">
          <Reveal><div className="lp-honest">
            <div>
              <div className="lp-over">Scope</div>
              <h3 className="serif" style={{ fontWeight: 400, fontSize: 30, letterSpacing: '-0.02em', lineHeight: 1.15, marginTop: 8 }}>A working prototype, stated plainly.</h3>
              <p className="body-s" style={{ marginTop: 10, maxWidth: 380 }}>Punjab wheat, Rabi 2026-27. Not a dose calculator, a diagnostic authority or a general weed detector.</p>
            </div>
            <div className="lp-honest__cols">
              <div><h5>Real arithmetic</h5><ul><li>Field areas from polygon geometry</li><li>Grid rasterisation at 1, 2 and 5 m</li><li>Zones and the nearest-first route</li><li>Threshold recalculation</li><li>GeoJSON and TASKDATA files</li></ul></div>
              <div><h5>Simulated</h5><ul><li>Aerial imagery (rendered on your device)</li><li>Segmentation and leaf inference outcomes</li><li>Photogrammetry and GNSS</li><li>Upload to a server</li></ul></div>
            </div>
          </div></Reveal>
        </div>
      </section>

      {/* ------------------------------------------------------------- CTA + footer */}
      <section className="lp-cta">
        <div className="lp-wrap">
          <Reveal><h2 className="lp-h2 lp-h2--light" style={{ maxWidth: 780, margin: '0 auto', textAlign: 'center' }}>Spray the patch, not the field.</h2></Reveal>
          <Reveal delay={0.1}><div className="lp-cta__btns"><Link to="/app" className="lp-btn lp-btn--light lp-btn--lg">Open the dashboard <ArrowRight size={18} /></Link><a href="#app" className="lp-btn lp-btn--ghost lp-btn--lg">See the field app</a></div></Reveal>
        </div>
      </section>
      <footer className="lp-foot">
        <div className="lp-wrap lp-foot__in">
          <div className="lp-brand lp-brand--foot"><BrandMark size={30} bg="#F5F0E6" ink="#143524" /><span>WeedReaver</span></div>
          <p>A final-year engineering project · Punjab wheat · Rabi 2026-27<br />On-device inference, sync and photogrammetry are simulated.</p>
          <div className="lp-foot__links"><Link to="/app">Dashboard</Link><a href="#how">How it works</a><a href="#principles">Principles</a></div>
        </div>
      </footer>
    </div>
  )
}

/* ------------------------------------------------------------------ hero */

function Hero({ chak, grid, zonesCount, saved }: { chak: FieldParcel; grid: ReturnType<typeof gridOf>; zonesCount: number; saved: number }) {
  const ref = useRef<HTMLElement>(null)
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start start', 'end start'] })
  const y = useTransform(scrollYProgress, [0, 1], [0, 90])
  const fade = useTransform(scrollYProgress, [0, 0.7], [1, 0])
  const draw = (dc: DrawCtx) => {
    dimOutside(dc, chak.boundary, 0.12)
    heatLayer(dc, grid, chak.boundary, 0.85, false)
    fieldOutline(dc, chak.boundary, { color: 'rgba(255,255,255,.9)' })
  }
  return (
    <section className="lp-hero" id="top" ref={ref}>
      <div className="lp-hero__map"><MapView field={chak} surveyed interactive={false} controls={false} scaleBar={false} drift fitPad={0} fitBounds={[-30, -20, 275, 165]} draw={draw} /></div>
      <div className="lp-hero__scrim" />
      <div className="lp-wrap lp-hero__in">
        <motion.div style={{ y, opacity: fade }} className="lp-hero__copy">
          <motion.div className="lp-hero__over" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.15, duration: 0.7, ease }}>Survey-guided spot spraying · Punjab wheat</motion.div>
          <motion.h1 initial={{ opacity: 0, y: 30 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.25, duration: 0.9, ease }}>Know where<br />control failed.</motion.h1>
          <motion.p initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.45, duration: 0.8, ease }}>A drone finds the weeds. A phone confirms them on the ground, offline. A dashboard decides what gets sprayed and measures whether it worked.</motion.p>
          <motion.div className="lp-hero__btns" initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.6, duration: 0.8, ease }}>
            <Link to="/app" className="lp-btn lp-btn--light lp-btn--lg">Open the dashboard <ArrowRight size={18} /></Link>
            <a href="#how" className="lp-btn lp-btn--glass lp-btn--lg">See how it works</a>
          </motion.div>
        </motion.div>
        <motion.aside className="lp-hero__card" initial={{ opacity: 0, x: 30 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.9, duration: 0.9, ease }}>
          <div className="lp-hero__cardhead"><span className="dot" /> Live · Chak 47 · {fmt.area(fieldAcres(chak), true)}</div>
          <div className="lp-hero__big">{fmt.sqm(grid.treatedSqm)}</div>
          <div className="lp-hero__cap">to spray, in {zonesCount} zones</div>
          <div className="lp-hero__bar"><i style={{ width: `${grid.treatedFraction * 100}%` }} /></div>
          <div className="lp-hero__row"><span>{fmt.pct(grid.treatedFraction)} of the field</span><b>{fmt.pct(saved)} less herbicide</b></div>
          <div className="lp-hero__legend"><span><i style={{ background: 'var(--heat-mid)' }} />10–30%</span><span><i style={{ background: 'var(--heat-high)' }} />over 30%</span><span><i style={{ background: 'var(--heat-abstain)' }} />abstained</span></div>
        </motion.aside>
      </div>
      <a href="#problem" className="lp-hero__down" aria-label="Scroll"><ChevronDown size={22} /></a>
    </section>
  )
}

/* ------------------------------------------------------------------ steps and visuals */

function Step({ n, icon: I, title, body, tags, children, flip, foot }: { n: string; icon: typeof PlaneTakeoff; title: string; body: string; tags: string[]; children: ReactNode; flip?: boolean; foot?: ReactNode }) {
  return (
    <div className={`lp-step ${flip ? 'is-flip' : ''}`}>
      <Reveal className="lp-step__text">
        <div className="lp-step__n">{n}</div>
        <div className="lp-step__ico"><I size={22} /></div>
        <h3>{title}</h3>
        <p>{body}</p>
        <div className="lp-tags">{tags.map((t) => <span key={t}>{t}</span>)}</div>
        {foot}
      </Reveal>
      <Reveal delay={0.1} className="lp-step__vis">{children}</Reveal>
    </div>
  )
}

function SurveyVisual({ chak }: { chak: FieldParcel }) {
  const grid = useMemo(() => gridOf(chak, 2, 10), [chak])
  const zones = useMemo(() => zonesOf(chak, 10), [chak])
  const [show, setShow] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const inView = useInView(ref, { once: true, margin: '-25%' })
  useEffect(() => { if (inView) { const t = setTimeout(() => setShow(true), 700); return () => clearTimeout(t) } }, [inView])
  const draw = (dc: DrawCtx) => {
    dimOutside(dc, chak.boundary, 0.25)
    if (show) { heatLayer(dc, grid, chak.boundary, 1, false); zones.forEach((z) => zonePin(dc, z, { size: 26 })) }
    fieldOutline(dc, chak.boundary)
  }
  return (
    <div className="lp-frame" ref={ref}>
      <MapView field={chak} surveyed interactive={false} controls={false} scaleBar fitPad={22} draw={draw} />
      <motion.div className="lp-scan" initial={{ left: '-4%' }} animate={inView ? { left: '104%' } : {}} transition={{ duration: 1.6, ease: 'easeInOut', delay: 0.2 }} />
      <div className="lp-frame__chip">{show ? `${zones.length} zones · ${fmt.int(grid.flagged)} cells` : 'Segmenting…'}</div>
    </div>
  )
}

type Chak = FieldParcel

/**
 * Step 03 is a pinned scene. On desktop the step locks to the viewport and the scroll wheel drives the
 * before/after divider, so the weeds visibly clear as the reader scrolls. On short or narrow screens there is
 * no pin: the divider follows the visual as it crosses the viewport. With reduced motion it rests near the
 * middle and is dragged by hand. Dragging always works, and the next scroll picks up from where you left it.
 */
function PinnedCompare({ chak }: { chak: Chak }) {
  const reduce = useReducedMotion()
  const roomy = useMedia('(min-width: 1101px) and (min-height: 660px)')
  const pinned = roomy && !reduce
  const pinRef = useRef<HTMLDivElement>(null)
  const frameRef = useRef<HTMLDivElement>(null)
  const pinP = useScroll({ target: pinRef, offset: ['start 68px', 'end end'] }).scrollYProgress
  const flowP = useScroll({ target: frameRef, offset: ['start end', 'end start'] }).scrollYProgress
  const progress = useMotionValue(0)
  const fromFlow = (v: number) => Math.min(1, Math.max(0, (v - 0.22) / 0.42))
  useMotionValueEvent(pinP, 'change', (v) => { if (pinned) progress.set(v) })
  useMotionValueEvent(flowP, 'change', (v) => { if (!pinned && !reduce) progress.set(fromFlow(v)) })
  useEffect(() => { progress.set(pinned ? pinP.get() : reduce ? 0.46 : fromFlow(flowP.get())) }, [pinned, reduce, progress, pinP, flowP])
  const fill = useTransform(progress, [0.06, 0.86], [0, 1])

  return (
    <div ref={pinRef} className={pinned ? 'lp-pin' : undefined}>
      <div className={pinned ? 'lp-pin__stick' : undefined}>
        <Step n="03" icon={BadgeCheck} title="Two weeks later, the same drone asks if it worked." body="The follow-up flight is compared with the pre-treatment survey zone by zone. A zone that survives label rate is flagged as a resistant patch, and the rotation history shows which mode of action put it there." tags={['Per-zone control', 'Resistance flag', '+14 d and +28 d']}
          foot={reduce ? null : (
            <div className="lp-hint" aria-hidden>
              <span className="lp-hint__mouse"><i /></span>
              <span className="lp-hint__txt">{pinned ? 'Keep scrolling. The field clears as you go.' : 'Scroll to sweep across the two flights.'}</span>
              <span className="lp-hint__track"><motion.i style={{ scaleX: fill }} /></span>
            </div>
          )}>
          <CompareVisual chak={chak} progress={progress} frameRef={frameRef} />
        </Step>
      </div>
    </div>
  )
}

function CompareVisual({ chak, progress, frameRef }: { chak: Chak; progress: MotionValue<number>; frameRef: React.RefObject<HTMLDivElement | null> }) {
  const before = useMemo(() => gridOf(chak, 2, 10), [chak])
  const post = useMemo(() => followUpGridOf(chak, 2, 10), [chak])
  const mapRef = useRef<MapHandle>(null)
  const drag = useRef(false)

  // scroll progress -> divider position, softened by a spring so the wipe glides instead of ticking
  const target = useTransform(progress, [0.06, 0.86], [0.94, 0.06])
  const split = useSpring(target, { stiffness: 150, damping: 28, mass: 0.5 })
  const splitRef = useRef(split.get())
  useMotionValueEvent(split, 'change', (v) => { splitRef.current = v; mapRef.current?.invalidate() })

  const left = useTransform(split, (v) => `${v * 100}%`)

  // exact counts: flagged cells to the left of the divider (pre-treatment) plus those to its right (follow-up)
  const cum = useMemo(() => {
    const run = (g: RasterGrid) => { const a = new Uint32Array(g.cols + 1); for (const c of g.cells) if (c.treated) a[c.col + 1]++; for (let i = 1; i < a.length; i++) a[i] += a[i - 1]; return a }
    return { b: run(before), p: run(post ?? before) }
  }, [before, post])
  const cells = useMotionValue(fmt.int(before.flagged))
  const delta = useMotionValue('0%')

  const draw = (dc: DrawCtx) => {
    dimOutside(dc, chak.boundary, 0.28)
    if (post) {
      const x = dc.w * splitRef.current, { ctx } = dc
      ctx.save(); ctx.beginPath(); ctx.rect(0, 0, x, dc.h); ctx.clip(); heatLayer(dc, before, chak.boundary, 1, false); ctx.restore()
      ctx.save(); ctx.beginPath(); ctx.rect(x, 0, dc.w - x, dc.h); ctx.clip(); heatLayer(dc, post, chak.boundary, 1, false); ctx.restore()
      const cut = Math.min(before.cols, Math.max(0, Math.round((dc.cam.wx(x) - before.originX) / before.cellMeters)))
      const n = cum.b[cut] + (cum.p[before.cols] - cum.p[cut])
      cells.set(fmt.int(n))
      delta.set(n >= before.flagged ? '0%' : `−${Math.round((1 - n / Math.max(1, before.flagged)) * 100)}%`)
    }
    fieldOutline(dc, chak.boundary)
  }

  const setFrom = (e: React.PointerEvent) => {
    const r = frameRef.current!.getBoundingClientRect()
    split.jump(Math.min(0.96, Math.max(0.04, (e.clientX - r.left) / r.width)))
  }
  const down = (e: React.PointerEvent) => {
    // a finger only grabs the handle; anywhere else it is a normal page scroll
    if (e.pointerType === 'touch' && !(e.target as HTMLElement).closest('.lp-cmp__line span')) return
    drag.current = true; e.currentTarget.setPointerCapture(e.pointerId); setFrom(e)
  }
  const move = (e: React.PointerEvent) => { if (drag.current) setFrom(e) }
  const up = () => { drag.current = false }

  return (
    <div className="lp-frame lp-frame--cmp" ref={frameRef} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}>
      <MapView ref={mapRef} field={chak} surveyed interactive={false} controls={false} scaleBar={false} fitPad={22} draw={draw} />
      <motion.div className="lp-cmp__line" style={{ left }}><span><ArrowRight size={14} style={{ transform: 'scaleX(-1)' }} /><ArrowRight size={14} /></span></motion.div>
      <div className="lp-readout">
        <span>Cells above threshold</span>
        <div><motion.b>{cells}</motion.b><motion.i>{delta}</motion.i></div>
      </div>
      <div className="lp-frame__chip lp-frame__chip--l">Before · 7 Jan</div>
      <div className="lp-frame__chip lp-frame__chip--r">After · 21 Jan</div>
    </div>
  )
}

/* ------------------------------------------------------------------ carousel */

const SLIDES = [
  ['home', 'Start where the work is', 'The next action for each field, the spray window, and one button to begin the route.'],
  ['weedmap', 'The prescription on real imagery', 'A 2 m spray grid with zones, and abstained cells shown as instructions, not holes.'],
  ['route', 'Nearest first, from the gate', 'Each leg is the shortest walk from where you are standing.'],
  ['navigate', 'Slide to mark treated', 'A stray tap in a pocket never marks a zone. Undo is one tap away.'],
  ['scan', 'Identify, or abstain', 'On-device inference at 30 cm, with candidates and a confidence ring.'],
  ['verify', 'Per zone, never a field average', 'Drag between the two flights and see where control failed.'],
] as const

/** Ink for the iOS status bar drawn over each screenshot: dark on cream headers, white on imagery. */
const BAR: Record<string, 'dark' | 'light'> = { home: 'dark', verify: 'dark', weedmap: 'light', route: 'light', navigate: 'light', scan: 'light' }

function Carousel() {
  const ref = useRef<HTMLDivElement>(null)
  const [i, setI] = useState(0)
  const go = (k: number) => {
    const el = ref.current
    if (!el) return
    const child = el.children[k] as HTMLElement
    el.scrollTo({ left: child.offsetLeft - el.clientWidth / 2 + child.clientWidth / 2, behavior: 'smooth' })
  }
  useEffect(() => {
    const el = ref.current!
    const on = () => {
      let best = 0, d = Infinity
      Array.from(el.children).forEach((c, k) => { const dd = Math.abs((c as HTMLElement).offsetLeft + (c as HTMLElement).clientWidth / 2 - (el.scrollLeft + el.clientWidth / 2)); if (dd < d) { d = dd; best = k } })
      setI(best)
    }
    el.addEventListener('scroll', on, { passive: true })
    return () => el.removeEventListener('scroll', on)
  }, [])
  return (
    <div className="lp-car">
      <div className="lp-car__track" ref={ref}>
        {SLIDES.map(([img, t, d], k) => (
          <motion.figure key={img} className={`lp-slide ${k === i ? 'is-on' : ''}`} onClick={() => go(k)} initial={{ opacity: 0, y: 40 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, margin: '-60px' }} transition={{ duration: 0.8, delay: k * 0.06, ease }}>
            <IPhone src={`/screens/ios/${img}.jpg`} alt={t} bar={BAR[img]} tilt={k === i} />
            <figcaption><b>{t}</b><span>{d}</span></figcaption>
          </motion.figure>
        ))}
      </div>
      <div className="lp-car__dots">{SLIDES.map((s, k) => <button key={s[0]} aria-label={s[1]} className={k === i ? 'is-on' : ''} onClick={() => go(k)} />)}</div>
    </div>
  )
}
