"""Read models for the dashboard and the phone's home screen: the overview with what needs a
decision today, the activity timeline, the review queue of abstained grid cells, and the
season calendar."""

from __future__ import annotations

from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.analysis.raster import cell_ref
from app.core import clock
from app.domain import geo
from app.domain.enums import CLASS_CODES, SURVEY_ROLE_META, Severity, SurveyRole, SurveyStatus, ZoneState
from app.models import AuditEntry, Device, LeafScan, Survey, Treatment, TreatmentZone, User, Verification
from app.services import access, analysis, fields as field_svc, treatments, verification
from app.services.station import get_station

# ----------------------------------------------------------------------------- activity


def activity(
    db: Session, user: User, *, field_id: str | None = None, kinds: set[str] | None = None,
    before: datetime | None = None, limit: int = 50,
) -> list[dict]:
    """One timeline across sources, newest first (the phone's Activity screen)."""
    vis = {f.id: f for f in db.scalars(access.visible_fields_query(user)).all()}
    ids_ = {field_id} & set(vis) if field_id else set(vis)
    if not ids_:
        return []
    names = {u.id: u.name for u in db.scalars(select(User)).all()}
    devices = {d.id: d.name for d in db.scalars(select(Device)).all()}
    cap = before or clock.now() + timedelta(days=3650)
    want = (lambda k: True) if not kinds else (lambda k: k in kinds)
    out: list[dict] = []
    n = limit + 1

    def add(**e) -> None:
        out.append(e)

    if want("TREATMENT"):
        for t in db.scalars(select(Treatment).where(Treatment.field_id.in_(ids_), Treatment.applied_at < cap)
                            .order_by(Treatment.applied_at.desc()).limit(n)):
            earlier = clock.season_of(t.applied_at) != clock.season_of(clock.now())
            add(id=t.id, kind="TREATMENT", title=t.product,
                subtitle=f"{vis[t.field_id].name} · {'earlier season' if earlier else f'{len(t.zone_labels)} zone' + ('' if len(t.zone_labels) == 1 else 's')} · HRAC {t.hrac_group[1:]}",
                at=t.applied_at, fieldId=t.field_id, refId=t.id, tone="neutral" if earlier else "forest",
                source="phone" if t.device_id else "dashboard", actor=t.operator_name)
    if want("SCAN"):
        for s in db.scalars(select(LeafScan).where(LeafScan.field_id.in_(ids_), LeafScan.captured_at < cap)
                            .order_by(LeafScan.captured_at.desc()).limit(n)):
            title = s.resolution or s.annotation or ("Scan needs review" if s.abstained else s.species_latin)
            if s.abstained and not s.resolved and not s.annotation:
                title = "Scan needs review"
            add(id=s.id, kind="SCAN", title=title,
                subtitle=f"{vis[s.field_id].name}{' · ' + s.zone_label if s.zone_label else ''} · {round(s.confidence * 100)}% confidence",
                at=s.captured_at, fieldId=s.field_id, refId=s.id, tone="wheat" if s.abstained and not s.resolved else "slate",
                source="phone", actor=names.get(s.operator_id or "") or devices.get(s.device_id or ""))
    if want("ZONE"):
        for z in db.scalars(select(TreatmentZone).where(TreatmentZone.field_id.in_(ids_), TreatmentZone.treated_at.is_not(None),
                                                        TreatmentZone.treated_at < cap).order_by(TreatmentZone.treated_at.desc()).limit(n)):
            add(id=f"{z.field_id}-{z.code}", kind="ZONE", title=f"{z.label} treated",
                subtitle=f"{vis[z.field_id].name} · {z.area_sqm:,} m²", at=z.treated_at, fieldId=z.field_id, refId=z.id,
                tone="moss", source="phone", actor=names.get(z.treated_by or ""))
    if want("VERIFY"):
        for v in db.scalars(select(Verification).where(Verification.field_id.in_(ids_), Verification.historical.is_(False),
                                                       Verification.saved_at < cap).order_by(Verification.saved_at.desc()).limit(n)):
            add(id=v.id, kind="VERIFY", title="Treatment verified",
                subtitle=f"{vis[v.field_id].name} · worst zone {v.worst_efficacy_pct}% control", at=v.saved_at,
                fieldId=v.field_id, refId=v.id, tone="forest", source="phone" if v.device_id else "dashboard",
                actor=names.get(v.saved_by or ""))
    if want("SURVEY"):
        q = (select(Survey).join(Survey.season).where(Survey.status == SurveyStatus.READY)
             .order_by(Survey.flown_at.desc()))
        for s in db.scalars(q):
            fid = s.season.field_id
            at = s.processed_at or s.flown_at + timedelta(hours=5)
            if fid not in ids_ or at >= cap:
                continue
            add(id=s.id, kind="SURVEY", title=f"{SURVEY_ROLE_META[SurveyRole(s.role)]['label']} survey ready",
                subtitle=f"{vis[fid].name} · {s.images} images · {s.gsd_cm} cm/px", at=at, fieldId=fid, refId=s.id,
                tone="slate", source="dashboard", actor="System")
    if want("FIELD"):
        for f in vis.values():
            if f.id in ids_ and f.created_at < cap and f.capture_method != "Surveyed":
                add(id=f"FIELD-{f.id}", kind="FIELD", title=f"{f.name} added", subtitle=f"{f.capture_method} · {geo.acres(f.area_sqm):.2f} ac",
                    at=f.created_at, fieldId=f.id, refId=f.id, tone="forest", source="dashboard", actor=f.captured_by_name)
    out.sort(key=lambda e: e["at"], reverse=True)
    return out[:limit]


# ----------------------------------------------------------------------------- review: grid cells


def abstained_cells(db: Session, user: User, *, field_id: str | None = None, size: int | None = None) -> list[dict]:
    station = get_station(db)
    g_m = analysis.check_grid(size or station.default_grid_m)
    out = []
    for f in db.scalars(access.visible_fields_query(user)).all():
        if field_id and f.id != field_id:
            continue
        ctx = analysis.context(db, f)
        got = analysis.surface_for_role(db, ctx, SurveyRole.PRE)
        if got is None:
            continue
        g = analysis.grid(got[1], g_m, station.threshold_pct)
        cells = []
        rows, cols = g.abstained.shape
        for r in range(rows):
            for c in range(cols):
                if g.abstained[r, c]:
                    x = g.origin_x + (c + 0.5) * g.cell_m
                    y = g.origin_y + (r + 0.5) * g.cell_m
                    lat, lon = geo.to_latlon(f.lat, f.lon, (x, y))
                    cells.append({"ref": cell_ref(c, r), "col": c, "row": r, "x": x, "y": y, "lat": round(lat, 7), "lon": round(lon, 7),
                                  "infestPct": round(float(g.infest_pct[r, c]), 1), "confidence": round(float(g.confidence[r, c]), 2),
                                  "weedClass": CLASS_CODES[int(g.class_idx[r, c])].value})
        if cells:
            out.append({"fieldId": f.id, "fieldName": f.name, "surveyId": got[0].id, "gridSize": g_m,
                        "thresholdPct": station.threshold_pct, "count": len(cells), "cells": cells,
                        "message": f"{len(cells)} {'cell' if len(cells) == 1 else 'cells'} in {f.name} need a look"})
    return out


# ----------------------------------------------------------------------------- overview


def overview(db: Session, user: User) -> dict:
    station = get_station(db)
    now = clock.now()
    rows = db.scalars(access.visible_fields_query(user)).all()
    summaries = [field_svc.field_summary(db, f) for f in rows]
    items: list[dict] = []

    def item(priority: int, kind: str, tone: str, title: str, detail: str, link: str, field_id: str | None = None,
             ref: str | None = None) -> None:
        items.append({"priority": priority, "kind": kind, "tone": tone, "title": title, "detail": detail, "link": link,
                      "fieldId": field_id, "refId": ref})

    open_zones = 0
    open_sqm = 0
    worst_control: dict | None = None
    for f, s in zip(rows, summaries):
        ctx = analysis.context(db, f)
        zones = analysis.active_zones(db, ctx.season)
        todo = [z for z in zones if not ZoneState(z.state).done]
        open_zones += len(todo)
        open_sqm += sum(z.area_sqm for z in todo)
        follow = ctx.by_role(SurveyRole.PLUS_14D)
        if ctx.surveyed and follow is not None and follow.status == SurveyStatus.READY and zones:
            p = verification.preview(db, f, SurveyRole.PLUS_14D)
            for z in p.zones:
                if worst_control is None or z.efficacy_pct < worst_control["efficacyPct"]:
                    worst_control = {"fieldId": f.id, "fieldName": f.name, "letter": z.letter, "efficacyPct": z.efficacy_pct}
            for z in [z for z in p.zones if z.efficacy_pct < 40]:
                item(0, "control_failure", "clay", f"{z.label} barely responded on {f.name}",
                     f"{z.efficacy_pct}% control at +14 d. Likely control failure: investigate before the next application.",
                     f"/verification/{f.id}", f.id, z.letter)
            for z in [z for z in p.zones if 40 <= z.efficacy_pct < 70]:
                item(3, "weak_control", "wheat", f"{z.label} on {f.name} below acceptable control",
                     f"{z.efficacy_pct}% control at +14 d", f"/verification/{f.id}", f.id, z.letter)
            if p.saved is None:
                item(4, "verify", "forest", f"Verify treatment on {f.name}", "The +14 d flight is processed and ready to compare",
                     f"/verification/{f.id}", f.id)
        if todo:
            heavy = any(z.severity == Severity.HEAVY for z in todo)
            flagged = sum(1 for z in todo if z.state == ZoneState.FLAGGED)
            item(1 if heavy else 2, "zones_open", "clay" if heavy else "wheat",
                 f"{f.name} · {len(todo)} {'zone' if len(todo) == 1 else 'zones'} to treat",
                 f"{sum(z.area_sqm for z in todo):,} m²" + (f" · {flagged} not yet routed" if flagged else " · route published"),
                 f"/fields/{f.id}", f.id)
        rot = treatments.rotation(db, f)
        if rot.risk == "High":
            item(1, "resistance", "clay", f"Resistance risk on {f.name}", rot.warning or "Same mode of action, season after season",
                 f"/rotation/{f.id}", f.id)
        for sv in ctx.surveys:
            if sv.status in (SurveyStatus.QUEUED, SurveyStatus.PROCESSING):
                item(5, "flight_processing", "slate", f"{f.name}: {SURVEY_ROLE_META[SurveyRole(sv.role)]['short']} flight processing",
                     f"{round(sv.progress * 100)}% · {sv.stage or 'Queued'}", "/flights", f.id, sv.id)
            elif sv.status == SurveyStatus.FAILED:
                item(1, "flight_failed", "clay", f"{f.name}: flight {sv.id} failed processing", sv.error or "Re-upload the images",
                     "/flights", f.id, sv.id)
            elif sv.status == SurveyStatus.SCHEDULED and now <= sv.flown_at <= now + timedelta(days=7):
                d = (clock.local_date(sv.flown_at) - clock.today()).days
                item(6, "flight_due", "slate", f"{f.name}: {SURVEY_ROLE_META[SurveyRole(sv.role)]['label'].lower()} flight",
                     "today" if d == 0 else "tomorrow" if d == 1 else f"in {d} days", "/flights", f.id, sv.id)

    vis_ids = {f.id for f in rows}
    waiting = [s for s in db.scalars(select(LeafScan).where(LeafScan.abstained.is_(True), LeafScan.resolved.is_(False)))
               if s.field_id in vis_ids]
    if waiting:
        item(2, "review_scans", "wheat", f"{len(waiting)} {'scan' if len(waiting) == 1 else 'scans'} waiting for a label",
             "The leaf model abstained. Choose what you can see in the photo.", "/review", None)
    cells = abstained_cells(db, user)
    n_cells = sum(c["count"] for c in cells)
    if n_cells:
        item(4, "review_cells", "wheat", f"{n_cells} grid cells need a person to look",
             " · ".join(c["message"] for c in cells), "/review?tab=cells", None)
    for d in db.scalars(select(Device).where(Device.revoked.is_(False))):
        if d.pending_changes:
            item(5, "device_pending", "slate", f"{d.name}: {d.pending_changes} changes waiting to sync",
                 "They arrive when the phone next has signal", "/devices", None, d.id)

    items.sort(key=lambda i: (i["priority"], i["title"]))
    sowing = [s.season.sowing_date for s in summaries if s.season and s.season.sowing_date]
    day = (clock.today() - min(sowing)).days + 1 if sowing else None
    audit = db.scalars(select(AuditEntry).order_by(AuditEntry.at.desc()).limit(8)).all()
    return {
        "stats": {
            "fields": len(rows),
            "areaAcres": sum(geo.acres(f.area_sqm) for f in rows),
            "areaSqm": sum(f.area_sqm for f in rows),
            "zonesOpen": open_zones,
            "zonesOpenSqm": open_sqm,
            "scansAwaitingReview": len(waiting),
            "cellsAbstained": n_cells,
            "seasonDay": day,
            "seasonLengthDays": station.season_length_days,
            "worstControl": worst_control,
            "thresholdPct": station.threshold_pct,
            "season": station.season_label,
        },
        "needsAttention": items,
        "fields": [s.model_dump(mode="json", by_alias=True) for s in summaries],
        "recentActivity": [{**e, "at": e["at"].isoformat()} for e in activity(db, user, limit=12)],
        "recentAudit": [{"id": a.id, "at": a.at.isoformat(), "who": a.who, "action": a.action, "detail": a.detail} for a in audit],
    }


# ----------------------------------------------------------------------------- season calendar


def season_calendar(db: Session, user: User) -> dict:
    station = get_station(db)
    rows = db.scalars(access.visible_fields_query(user)).all()
    out = []
    for f in rows:
        ctx = analysis.context(db, f)
        out.append({
            "fieldId": f.id, "fieldName": f.name,
            "season": field_svc.season_out(ctx.season).model_dump(mode="json", by_alias=True) if ctx.season else None,
            "surveys": [{"id": s.id, "role": s.role, "status": s.status, "flownAt": s.flown_at.isoformat(), "progress": s.progress}
                        for s in ctx.surveys],
        })
    sowing = [r["season"]["sowingDate"] for r in out if r["season"] and r["season"]["sowingDate"]]
    start = min(sowing) if sowing else None
    return {"season": station.season_label, "seasonLengthDays": station.season_length_days, "startsOn": start,
            "today": clock.today().isoformat(), "fields": out}
