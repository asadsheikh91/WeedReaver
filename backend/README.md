# WeedReaver API

The single backend behind the **field app** (Android, `../app`) and the **web dashboard** (`../web`).
Python 3.11+, FastAPI, SQLAlchemy 2, PostgreSQL (SQLite for local work and tests), Alembic, NumPy.

It owns everything both frontends currently fake in memory: accounts and roles, fields and seasons,
flights and their processing pipeline, the spray grid and treatment zones, routes, leaf scans and
the review queue, treatment records, rotation/resistance, verification, exports, devices, offline
sync, the audit trail, and the overview the dashboard opens on.

**The web decides, the phone does.** Definitions (boundaries, the prescription threshold, flights,
review outcomes, label data, exports) belong to the dashboard; observations (scans, zones treated,
products applied, verifications) belong to the phone. Sync settles conflicts by that ownership,
never by last-write-wins, and a phone that tries to change a definition is told so.

---

## Run it

```bash
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements-dev.txt      # macOS/Linux: .venv/bin/pip
copy .env.example .env                                  # macOS/Linux: cp
```

Load the demonstration data (the same fields, flights and history the apps ship with) and start:

```bash
.venv/Scripts/python -m app.cli seed-demo
```

```bash
.venv/Scripts/uvicorn app.main:app --reload
```

- API: `http://localhost:8000/api/v1`, interactive docs at `http://localhost:8000/api/v1/docs`
- Demo accounts, password `weedreaver-demo`: `s.anjum@pindibhattian-station.pk` (analyst),
  `a.mehmood@pindibhattian-station.pk` (operator), `m.rauf@pindibhattian-station.pk` (admin),
  `trainee.0212@pindibhattian-station.pk` (trainee, North plot only)
- For the demo to look exactly like the apps, set `WR_DEMO_CLOCK_DATE=2027-01-22` in `.env`
  (the apps pin "today" to 22 Jan 2027). Leave it unset in production.

Tests (70, run on SQLite by default; set `WR_TEST_POSTGRES_URL` to run them on PostgreSQL):

```bash
.venv/Scripts/python -m pytest
```

### Production

```bash
docker compose up -d --build
```

```bash
docker compose run --rm api python -m app.cli create-admin --email you@station.pk --name "Your Name"
```

The stack is PostgreSQL, a one-shot `bootstrap` (migrations, station settings, label list, species),
the API on port 8000 (two processes) and a separate processing worker. Set `WR_SECRET_KEY` and
`POSTGRES_PASSWORD` in `.env` first; production refuses to start with the default secret. Put a
TLS-terminating reverse proxy in front and give it a body limit large enough for flight batches
(e.g. nginx `client_max_body_size 1g;`). `GET /health` is liveness, `GET /ready` checks the database.

---

## Layout

```
app/
  core/       config, clock (UTC + station zone + optional demo date), errors, security, logging
  db/         engine/session, base types (UTC timestamps on every backend)
  models/     ORM: identity, fields, surveys, zones, observations, catalog, system
  domain/     enums (the clients' exact vocabulary), geometry, noise, KML/GeoJSON/CSV parsing
  analysis/   weed surfaces, rasterising to 1/2/5 m, zones, polygons, efficacy, LRU cache
  pipeline/   engine contracts, simulated + stub engines, the job runner and worker
  services/   all business rules (routes stay thin)
  api/        dependencies and one router per area
  seed/       the demonstration dataset
  cli.py      migrate | bootstrap | seed-demo | create-admin | run-jobs
  worker.py   standalone processing worker
alembic/      migrations
tests/        API and engine tests
```

## The analysis engine

The grid, zones, abstention and routes are a port of the clients' own algorithms, vectorised with
NumPy and **bit-for-bit identical** to the dashboard's TypeScript (the test suite checks it against
reference numbers produced by running the dashboard's code): Chak 47 is 8.45 ac on all three
surfaces, its 2 m grid flags 688 cells with 43 abstained, and its zones route E → A → D → B → C from
the gate. A 1 m grid of the largest field (35k cells) rasterises in about 0.2 s and is cached.

Zone outlines are exact polygons (the union of their 1 m cells, holes included), used by the
exports and returned with every zone. Per-zone efficacy is *measured*: mean weed cover over each
zone's footprint in the follow-up surface against the pre-treatment one. On the demo data this
surfaces Zone C on Chak 47 as the control failure, as the apps describe.

## Plugging in photogrammetry and the segmentation model

Processing runs `photogrammetry -> segmentation -> zoning` as a database-backed job (it survives
restarts, retries transient failures, and several workers can run side by side). The two heavy
steps are interfaces in `app/pipeline/base.py`; choose implementations with:

```
WR_ORTHOMOSAIC_ENGINE=app.pipeline.orthomosaic:SimulatedOrthomosaicEngine
WR_SEGMENTATION_ENGINE=app.pipeline.segmentation:SimulatedSegmentationEngine
```

Until yours exist, the simulated engines make the whole loop work: uploaded flights progress
through the stages, pre-treatment flights produce weed surfaces and publish zones, and follow-up
flights respond where the phone marked zones treated and barely move where it did not.

**Orthomosaic** — `NodeODMOrthomosaicEngine` (`app/pipeline/orthomosaic.py`) stitches flights with
[OpenDroneMap](https://github.com/OpenDroneMap/ODM) through a NodeODM server, using the official
`pyodm` client. ODM runs unmodified in its own container. Its own licence (AGPL-3.0) applies to it,
and this backend only talks to it over HTTP. Run the node on the machine that holds the images:
flights are gigabytes, so they should never cross a slow internet link.

```bash
docker run -d --name nodeodm -p 3000:3000 opendronemap/nodeodm
```

```
WR_ORTHOMOSAIC_ENGINE=app.pipeline.orthomosaic:NodeODMOrthomosaicEngine
WR_NODEODM_URL=http://localhost:3000
```

(`docker compose up` already includes a `nodeodm` service for the worker.) What the engine does:

- Uploads the images in parallel, with each file retried, and sends ODM settings for a nadir,
  fixed-altitude flight over flat wheat: `fast-orthophoto`, `sfm-algorithm planar`,
  `auto-boundary`, `cog` with overviews, `skip-3dmodel`, `optimize-disk-space`. The orthophoto
  resolution defaults to the flight's own GSD.
- Override any setting with `WR_ODM_OPTIONS` (JSON). For example, split a flight too large for
  the node's RAM with `{"split": 300, "split-overlap": 60}`. Set `WR_ODM_ORTHOPHOTO_CM` to choose
  the resolution.
- Shows ODM's progress on the flight. It downloads only the orthophoto (and ODM's PDF report),
  checks the result is a georeferenced GeoTIFF (`app/pipeline/geotiff.py` reads its size, pixel
  size, origin and EPSG without GDAL), reports the real GSD, and deletes the node's copy of the
  task.
- Keeps the task id in the flight's work folder. A retry after a crash or a lost connection
  re-attaches to the running task instead of uploading again. A retry after a later step failed
  reuses the orthophoto it already has.
- If the fast run fails, restarts it once on the node with `WR_ODM_FALLBACK_SFM` (default
  `incremental`), without a new upload. Failures read as causes ("ran out of memory…",
  "too few images…"). An unreachable node is retried like any transient error.
- Cannot pass the field outline to ODM as `boundary`: NodeODM 3.6 strips the double quotes from
  option values, which breaks the GeoJSON. `boundary_geojson()` builds that outline (convex hull
  plus `WR_ODM_BOUNDARY_MARGIN_M`) for cropping on our side.

Measured on the developer laptop (i7-11800H, Docker limited to 8 GB): ODM's 77-image Aukerman
sample took 9.4 minutes end to end and peaked at 6.5 GB on the node. It produced a 266 MB
orthophoto at 2.39 cm/px covering 360 × 269 m. ODM's own guidance is roughly 16 GB of RAM for
250 images and 32 GB for 500. Size the station PC, the flight altitude or `split` accordingly.

**Segmentation** — implement `ModelSegmentationEngine.segment` in `app/pipeline/segmentation.py`.
Return a `RasterSurface`: per-pixel weed cover (0..1), class map (0 crop, 1 grass weed, 2 broadleaf
weed) and, ideally, model confidence, in the field's local frame (x east, y south, metres from the
anchor; `app.domain.geo.from_latlon` maps WGS84 into it). Everything downstream — grids,
abstention (which then uses your confidence instead of the margin heuristic), zones with stable
letters across re-zoning, routes, efficacy and exports — works on it unchanged.

## Integrating the clients

### Conventions

- Base path `/api/v1`. JSON is **camelCase** (the field names both clients already use:
  `fieldSeasonId`, `flownAt`, `infestPct`, ...). Requests accept camelCase or snake_case.
- Ids keep the clients' grammar: `F-047`, `FS-047`, `S-01`, `SC-041`, `T-0118`, `Q-04`, `X-04`, `CL-12`, `D-01`.
- Timestamps are ISO 8601 UTC (`2027-01-22T05:30:00Z`); `new Date(s)` / `Instant.parse(s)` read them.
- Geometry is in each field's local metric frame (x east, y south, metres; `lat`/`lon` anchor the
  origin), exactly as the apps' map code expects. GeoJSON endpoints and exports are WGS84.
- Errors: `{"error": {"code": "not_found", "message": "...", "details": ...}}` with the HTTP status.
  Codes worth handling: `token_expired` (refresh), `refresh_token_reused` / `account_disabled`
  (sign out), `ownership_violation` (a definition the phone may not change), `validation_error`.
- Lists that can grow return `{"items": [...], "total", "limit", "offset"}`.

### Authentication

`POST /auth/login {email, password, device?}` returns `accessToken` (15 min), `refreshToken`
(rotating, 60 days, so a phone can be offline for weeks) and the user. Send
`Authorization: Bearer <accessToken>`; on `token_expired` call `POST /auth/refresh`. Re-using an
already-rotated refresh token revokes the session family.

The field app passes `device: {installId, name, model, os, appVersion}` at login (`installId` is a
UUID generated once per install). The response's `deviceId` goes in `X-Device-ID` on later calls.

Roles: `ADMIN`, `ANALYST` (decide: the dashboard), `OPERATOR`, `TRAINEE` (observe: the phone). Users
can be scoped to some fields; everything attached to a field follows its visibility.

### Dashboard endpoints (by page)

| Page | Calls |
|---|---|
| Overview | `GET /overview` (stats, ranked `needsAttention`, field summaries, recent activity), `GET /season` |
| Fields | `GET /fields`, `GET /fields/{id}`, `POST /fields/parse-boundary` (multipart) then `POST /fields`, `PATCH`/`DELETE /fields/{id}` |
| Weed map | `GET /fields/{id}/grid?size=&threshold=` (columnar), `/grid/stats` (for the slider), `/grid/compare`, `/grid/cell?x=&y=`, `/zones`, `/zones/preview?threshold=`, `PUT /settings/threshold` (publish) |
| Flights | `GET /surveys`, `POST /surveys` (schedule), `POST /surveys/uploads` → `POST /surveys/{id}/images` (batches, zip ok) → `POST /surveys/{id}/process`; poll `GET /surveys/{id}` for `progress`/`stage` |
| Review queue | `GET /review/summary`, `GET /scans?status=NEEDS_LABEL`, `POST /scans/{id}/resolve`, `/reopen`, `GET /scans/{id}/photo`, `GET /review/cells` |
| Treatments | `GET /treatments?fieldId=&hrac=&from=&to=&season=`, `GET /treatments/{id}` |
| Rotation | `GET /fields/{id}/rotation` (trend, streak, risk, warning, alternatives) |
| Verification | `GET /fields/{id}/verification?role=PLUS_14D`, grids via `/grid?survey=PRE` and `?survey=PLUS_14D` for the swipe compare |
| Exports | `POST /exports/preview`, `POST /exports`, `GET /exports`, `GET /exports/{id}/download` |
| Devices & sync | `GET /devices`, `GET /sync/changes?deviceId=&status=&owner=`, `POST /devices/{id}/revoke` |
| Settings | `GET`/`PATCH /settings`, `PUT /settings/threshold`, `GET /users`, `POST /users/invitations`, `GET /audit`, `GET /products`, `POST`/`PATCH /products` |

The grid is columnar to stay small (a 1 m grid is ~35k cells; ~50 KB gzipped): `infestPct`,
`confidence`, `classes` (a string of `C`/`G`/`B`) and `flags` (bit 1 inside, 2 treated,
4 abstained), all row-major over `cols × rows` cells starting at (`originX`, `originY`).

### Field app endpoints (by screen)

| Screen | Calls |
|---|---|
| Welcome | `POST /auth/login` with `device`; then `GET /sync/pull` for the offline cache |
| Home | `GET /fields` (pressure, `loop`, `upNext` on detail), `GET /conditions?fieldId=` |
| Field detail, weed map | `GET /fields/{id}`, `/grid`, `/zones` |
| Spray route, navigate | `GET /fields/{id}/route?fromX=&fromY=` (resume from where you stand), `POST /fields/{id}/zones/route-all`, `POST /fields/{id}/zones/{letter}/state {state}` (TREATED, or back to ROUTED to undo) |
| Scanner | `POST /scans` (classified on the phone; idempotent `clientId`), `PUT /scans/{id}/photo`, `POST /scans/{id}/annotation` |
| Record treatment | `GET /products`, `POST /rotation/check` (the clash warning), `POST /treatments` (dose recorded verbatim, never computed) |
| Verify | `GET /fields/{id}/verification`, `POST /fields/{id}/verification` |
| Activity | `GET /activity?fieldId=&kind=&before=` |
| Sync | `POST /sync/push`, `GET /sync/pull?since=`, `POST /devices/{id}/heartbeat` |
| Settings, about | `GET /auth/me`, `PATCH /auth/me/preferences`, `GET /settings` (threshold read-only), `GET /reference` |
| Season, flights, quadrats | `GET /season`, `GET /surveys?fieldId=`, `GET`/`POST /quadrats` |

### Offline sync

The phone keeps its change log as it does today and, when it has signal:

1. `POST /sync/push` with `X-Device-ID`:

   ```json
   {"changes": [
     {"clientSeq": 213, "entity": "treatment_zone", "op": "state", "at": "2027-01-22T05:30:00Z",
      "payload": {"fieldId": "F-047", "zone": "A", "state": "TREATED"}},
     {"clientSeq": 214, "entity": "leaf_scan", "op": "create", "payload": {"clientId": "…", "fieldId": "F-047", "…": "…"}}
   ], "pendingAfter": 0}
   ```

   Each change gets `applied`, `duplicate` (already received: safe to resend after a lost
   acknowledgement) or `rejected` with a reason. Supported: `leaf_scan.create`, `treatment.create`,
   `treatment_zone.state`, `treatment_zone.route`, `verification.create`, `quadrat.create`,
   `abstention.annotate` (by `scanId` or `scanClientId`), `field.create`. Payloads are the bodies of
   the matching REST calls. Changes to definitions are rejected as `ownership_violation`.
2. `GET /sync/pull?since=<cursor>` returns everything changed since the last pull (omit `since`
   for a full snapshot) and a new `cursor`: settings (the published threshold), fields, seasons,
   surveys, zones with geometry, products, species, scans (with the analyst's resolutions),
   treatments, verifications, quadrats, and `removedFields`. Upsert by id.

## Configuration

Every setting is an environment variable prefixed `WR_`; `.env.example` lists the useful ones and
`app/core/config.py` all of them. Notable: `WR_DATABASE_URL`, `WR_SECRET_KEY`, `WR_CORS_ORIGINS`,
`WR_STORAGE_DIR`, `WR_DEMO_CLOCK_DATE`, the two engine paths, `WR_EMBEDDED_WORKER`,
`WR_WEATHER_PROVIDER` (`static` or live `open-meteo`) and `WR_MAIL_BACKEND` (`console` or `smtp`
for invitations and password resets; without mail, the invitation link is returned to the inviter).

## What is simulated, and what is real

Real: accounts, sessions and roles; every geometry, area, grid, zone, route and efficacy figure;
the review loop; treatment and rotation records; offline sync with ownership rules; the audit and
sync ledgers; GeoJSON, Shapefile and ISO 11783-10 exports; uploads and storage; the job pipeline;
photogrammetry with OpenDroneMap (NodeODMOrthomosaicEngine; the simulated engine stays the default
so demos and tests need no node).

Simulated until you plug in your model: aerial segmentation (above). Leaf
inference runs on the phone, which sends its result. Weather is static unless
`WR_WEATHER_PROVIDER=open-meteo`.
