# WeedReaver web

The desktop companion to the WeedReaver field app. React 19, TypeScript, Vite, react-router, zustand, framer-motion.
Everything under `/app` reads and writes through the WeedReaver API in `../backend`.

Start the API first (see `../backend/README.md`; set `WR_DEMO_CLOCK_DATE=2027-01-22` in `backend/.env` so dates match the demo):

```bash
cd ../backend && .venv/Scripts/python -m app.cli seed-demo && .venv/Scripts/uvicorn app.main:app
```

Then the dashboard:

```bash
npm install
npm run dev        # http://localhost:5173, sign in as s.anjum@pindibhattian-station.pk / weedreaver-demo
npm run build      # type-check + production bundle in dist/
```

The API address comes from `VITE_API_BASE_URL` (default `http://localhost:8000/api/v1`; see `.env.example`). The API must list the dashboard's origin in `WR_CORS_ORIGINS`.

## Routes

| Path | What it is |
| --- | --- |
| `/` | Public landing page: the problem, how the system works, the two surfaces, the field app, the dashboard. Its worked example is a fixed copy of Chak 47 (`src/data/showcase.ts`), computed in the browser. |
| `/signin`, `/accept-invite`, `/reset-password` | Sign-in, and the pages the API's invitation and password-reset links open. `/app/*` needs a session. |
| `/app` | Overview: what needs a decision today. |
| `/app/fields`, `/app/fields/:id` | Fields, boundary upload (GeoJSON / KML / CSV), per-field lifecycle. |
| `/app/weed-map/:id` | The prescription: live threshold slider, 1/2/5 m grid, zones, publish to phones. |
| `/app/flights` | Upload and processing pipeline. |
| `/app/review` | Cells and scans the models declined, resolved by a person. |
| `/app/treatments` | What the phones recorded (product and dose as written on the label). |
| `/app/rotation/:id` | Mode-of-action history and control trend. |
| `/app/verification/:id` | Before/after flights, per-zone control, resistance flags. |
| `/app/exports` | GeoJSON, Shapefile, ISO 11783-10 TASKDATA (real files). |
| `/app/devices`, `/app/settings` | Sync log and conflict rules; threshold, units, models, audit log. |

## The division of labour

The web decides, the mobile does. The dashboard owns definitions (boundaries, prescription threshold, flights, review outcomes, exports). The phone owns observations (scans, zones treated, products applied). Where they disagree, the phone wins on observations and the dashboard wins on definitions.

## Where the data comes from

The API owns all state: fields, flights and their processing, grids and zones, scans, treatments, rotation, verification, exports, devices and the sync ledger, users and the audit log. The dashboard keeps a zustand store of the collections (`src/data/store.ts`, loaded on sign-in, refreshed after every change, flights in progress polled until they finish) and a small read-through cache for per-field queries such as grids, zone previews and verification (`src/data/queries.ts`). `src/api/client.ts` adds the bearer token, rotates it on `token_expired`, and turns the API's error envelope into readable messages.

The aerial imagery is still rendered procedurally in Web Workers from the parcel's `landscape` and boundary, with weed patches drawn where the published zones are. The heat map, prescription and every number come from the API.

Restoring the demonstration data is a server task: `python -m app.cli seed-demo --reset`.

## Layout

```
src/api      API client (auth, refresh, errors) and wire types
src/data     model, store, query cache, adapters from the API, landing-page showcase
src/map      canvas map, camera, worker-rendered aerial imagery, overlays
src/ui       app shell, design-system kit, charts, brand
src/pages    one file per route (+ its css)
src/styles   tokens (ported from the Android theme), base, components
public/screens   real screenshots of the Android app used on the landing page
```
