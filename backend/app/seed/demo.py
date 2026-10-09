"""The demonstration dataset: the same fields, flights, scans and history the field app and the
dashboard ship with (Pindi Bhattian field station, Rabi 2026-27), so all three surfaces show the
same numbers. Areas, grids and zones are computed from the geometry, never typed in.
"""

from __future__ import annotations

import uuid
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.analysis.surface import Patch, PatchSurface
from app.core import clock
from app.core.security import hash_password
from app.domain import geo
from app.domain.enums import JobStatus, Role, SurveyRole, SurveyStatus, WeedClass
from app.models import (
    AuditEntry, ChangeLogEntry, Device, ExportJob, Field, FieldSeason, LeafScan, ProcessingJob, Product, Quadrat, Species,
    StationSettings, Survey, Treatment, User, Verification,
)
from app.schemas.auth import Preferences
from app.services import analysis, ids
from app.services.station import DEFAULTS

d = clock.local
G, B = WeedClass.GRASS, WeedClass.BROADLEAF
SENSOR = "DJI Mavic 3M · 20 MP RGB"
ANALYST, OPERATOR = "Dr. S. Anjum", "Asad Mehmood"
MODEL_LEAF = "wr-leaf-yolo11n-int8 v0.3.2"
PIPELINE = "OpenDroneMap · NodeODM"
MODEL_AERIAL = "wr-seg-deeplabv3p-r50 v0.4.1"

FIELDS = [
    {
        "id": "F-047", "name": "Chak 47", "village": "Pindi Bhattian, Hafizabad",
        "boundary": [[22, 0], [243, 0], [245, 142], [1, 143], [0, 24], [22, 24]], "lat": 31.8942, "lon": 73.2711, "gate": [1, 84],
        "landscape": {"seed": 4711, "mustardBias": 0.16, "road": [{"x": -11, "y": -260}, {"x": -12, "y": 60}, {"x": -9, "y": 420}],
                      "watercourse": [{"x": -200, "y": 148}, {"x": 120, "y": 149}, {"x": 460, "y": 151}],
                      "farmstead": {"x": -58, "y": -44}, "tubewell": {"x": 11, "y": 12}},
    },
    {
        "id": "F-112", "name": "Canal side", "village": "Pindi Bhattian, Hafizabad",
        "boundary": [[0, 0], [148, 0], [172, 86], [2, 84]], "lat": 31.8901, "lon": 73.2803, "gate": [64, 1],
        "landscape": {"seed": 1123, "mustardBias": 0.08, "canal": [{"x": 122, "y": -260}, {"x": 250, "y": 300}],
                      "road": [{"x": -260, "y": -9}, {"x": 60, "y": -10}, {"x": 146, "y": -11}],
                      "watercourse": [{"x": -4, "y": -200}, {"x": -5, "y": 300}]},
    },
    {
        "id": "F-203", "name": "North plot", "village": "Jalalpur Bhattian",
        "boundary": [[0, 2], [182, 0], [183, 88], [1, 90]], "lat": 31.9034, "lon": 73.265, "gate": [92, 1],
        "landscape": {"seed": 2031, "mustardBias": 0.2, "road": [{"x": -300, "y": -28}, {"x": 480, "y": -24}],
                      "village": {"x": 70, "y": -120}, "watercourse": [{"x": 186, "y": -200}, {"x": 188, "y": 300}]},
    },
]

PATCHES = {
    "F-047": [Patch(60, 44, 22, 0.86, G, "A", 1.45), Patch(152, 50, 17, 0.62, G, "B", 1.5), Patch(204, 106, 19, 0.72, B, "C", 1.1),
              Patch(100, 108, 15, 0.46, B, "D", 1.15), Patch(28, 100, 11, 0.36, G, "E", 1.6), Patch(126, 14, 9, 0.22, G, "F", 1.8)],
    "F-112": [Patch(52, 46, 17, 0.44, G, "A", 1.5), Patch(124, 28, 13, 0.33, B, "B", 1.1), Patch(96, 68, 10, 0.19, G, "C", 1.6)],
    "F-203": [Patch(92, 46, 13, 0.16, G, "A")],
}
#: +14 d follow-up: fraction of each zone's weed cover that survived. Zone C barely moved.
EFFICACY_FACTORS = {"A": 0.18, "B": 0.34, "C": 0.86, "D": 0.26, "E": 0.30}

PRODUCTS = [
    ("Topik 15 WP", "Clodinafop-propargyl", "G1", G, "Wheat", "Wettable powder"),
    ("Puma Super 75 EW", "Fenoxaprop-P-ethyl", "G1", G, "Wheat", "Emulsion in water"),
    ("Axial 50 EC", "Pinoxaden", "G1", G, "Wheat", "Emulsifiable concentrate"),
    ("Leader 75 WG", "Sulfosulfuron", "G2", G, "Wheat", "Water-dispersible granule"),
    ("Atlantis 3.6 WG", "Mesosulfuron + iodosulfuron", "G2", G, "Wheat", "Water-dispersible granule"),
    ("Arelon 50 WP", "Isoproturon", "G5", G, "Wheat", "Wettable powder"),
    ("Sencor 70 WP", "Metribuzin", "G5", B, "Wheat", "Wettable powder"),
    ("Stomp 330 EC", "Pendimethalin", "G3", G, "Wheat", "Emulsifiable concentrate"),
    ("Sakura 85 WG", "Pyroxasulfone", "G15", G, "Wheat", "Water-dispersible granule"),
    ("Buctril Super 60 EC", "Bromoxynil + MCPA", "G4", B, "Wheat", "Emulsifiable concentrate"),
    ("Round-up 41 SL", "Glyphosate", "G9", B, "Pre-sow", "Soluble liquid"),
]

SPECIES = [
    ("Phalaris minor", "Dumbi sitti", "Littleseed canarygrass", G, "Mimics wheat at distance. The membranous ligule and absent auricles separate it."),
    ("Avena ludoviciana", "Jangli jai", "Wild oat", G, "Routinely confused with P. minor. Look for the twisted awn and hairy leaf margin."),
    ("Chenopodium album", "Bathu", "Lambsquarters", B, "Mealy white coating on young leaves. Visually distinctive."),
    ("Convolvulus arvensis", "Lehli", "Field bindweed", B, "Arrow-shaped leaves on a twining stem. Climbs the crop."),
    ("Triticum aestivum", "Kanak", "Wheat (crop)", WeedClass.CROP, "Crop plant. Clasping auricles with hairs. No action."),
]

USERS = [
    ("U-1", ANALYST, Role.ANALYST, "s.anjum@pindibhattian-station.pk", None, None),
    ("U-2", OPERATOR, Role.OPERATOR, "a.mehmood@pindibhattian-station.pk", "OP-0147", None),
    ("U-3", "M. Rauf", Role.ADMIN, "m.rauf@pindibhattian-station.pk", None, None),
    ("U-4", "Village youth account", Role.TRAINEE, "trainee.0212@pindibhattian-station.pk", "OP-0212", ["F-203"]),
]


def is_empty(db: Session) -> bool:
    return db.scalars(select(User.id)).first() is None and db.scalars(select(Field.id)).first() is None


def seed(db: Session, password: str) -> None:
    pw = hash_password(password)
    db.add(StationSettings(id=1, **DEFAULTS, threshold_published_at=d(3, 1, 2027, 9, 0), threshold_published_by=ANALYST))

    for i, (latin, local, common, cls, note) in enumerate(SPECIES):
        db.add(Species(latin=latin, local=local, common=common, cls=cls.value, note=note))
    for i, (trade, active, hrac, target, crop, form) in enumerate(PRODUCTS, start=1):
        db.add(Product(id=f"P-{i:02d}", trade=trade, active=active, hrac=hrac, target=target.value, crop=crop, formulation=form))

    fields: dict[str, Field] = {}
    for f in FIELDS:
        b = [(float(x), float(y)) for x, y in f["boundary"]]
        row = Field(id=f["id"], name=f["name"], village=f["village"], boundary=f["boundary"], lat=f["lat"], lon=f["lon"],
                    gate=f["gate"], landscape=f["landscape"], capture_method="Surveyed", captured_by_name=OPERATOR,
                    area_sqm=round(geo.area(b), 3), perimeter_m=round(geo.perimeter(b), 3),
                    created_at=d(2, 11, 2026, 10, 0), updated_at=d(2, 11, 2026, 10, 0))
        db.add(row)
        fields[f["id"]] = row
    db.flush()

    users: dict[str, User] = {}
    for uid, name, role, email, code, scope in USERS:
        u = User(id=uid, email=email, name=name, role=role.value, password_hash=pw, operator_code=code,
                 scope_all=scope is None, preferences=Preferences().model_dump(),
                 last_active_at={"U-1": d(22, 1, 2027, 8, 12), "U-2": d(22, 1, 2027, 6, 48), "U-3": d(20, 1, 2027, 15, 30),
                                 "U-4": d(19, 1, 2027, 17, 5)}[uid],
                 created_at=d(1, 11, 2026, 9, 0))
        if scope:
            u.fields = [fields[x] for x in scope]
        db.add(u)
        users[uid] = u
    db.flush()

    db.add(Device(id="D-01", install_id="demo-install-galaxy-s10", name="Asad's Galaxy S10", model="Samsung SM-G975F",
                  os="Android 12", app_version="0.9.4 (212)", user_id="U-2", battery=63, storage_mb=612, pending_changes=3,
                  last_seen_at=d(22, 1, 2027, 6, 48), last_sync_at=d(21, 1, 2027, 18, 42)))
    db.add(Device(id="D-02", install_id="demo-install-redmi-note-11", name="Trainee handset", model="Xiaomi Redmi Note 11",
                  os="Android 13", app_version="0.9.3 (204)", user_id="U-4", battery=18, storage_mb=388, pending_changes=0,
                  last_seen_at=d(19, 1, 2027, 17, 5), last_sync_at=d(19, 1, 2027, 17, 5)))

    # ---- seasons: current, and the earlier ones behind the rotation history
    for fid, sow, spacing, variety in (("F-047", (12, 11, 2026), 22, "Akbar-2019"), ("F-112", (15, 11, 2026), 22, "Dilkash-2020"),
                                       ("F-203", (18, 11, 2026), 20, "Galaxy-2013")):
        db.add(FieldSeason(id=f"FS-{fid[2:]}", field_id=fid, crop="Wheat", season="Rabi 2026-27",
                           sowing_date=clock.local_date(d(*sow)), row_spacing_cm=spacing, variety=variety))
    for sid, fid, label, sow in (("FS-047-2526", "F-047", "Rabi 2025-26", (14, 11, 2025)), ("FS-047-2425", "F-047", "Rabi 2024-25", (16, 11, 2024)),
                                 ("FS-047-2324", "F-047", "Rabi 2023-24", (20, 11, 2023)), ("FS-112-2526", "F-112", "Rabi 2025-26", (17, 11, 2025))):
        db.add(FieldSeason(id=sid, field_id=fid, crop="Wheat", season=label, sowing_date=clock.local_date(d(*sow)),
                           row_spacing_cm=22, variety="Akbar-2019", harvest_date=clock.local_date(d(25, 4, int(label[-7:-3]) + 1))))
    db.flush()

    # ---- flights and their segmentation surfaces
    flights = [
        ("S-01", "FS-047", (7, 1, 2027, 9, 12), SurveyRole.PRE, 0.42, SurveyStatus.READY, 612, 1.0),
        ("S-02", "FS-047", (21, 1, 2027, 8, 50), SurveyRole.PLUS_14D, 0.43, SurveyStatus.READY, 598, 1.0),
        ("S-03", "FS-047", (4, 2, 2027, 9, 0), SurveyRole.PLUS_28D, 0.42, SurveyStatus.SCHEDULED, 0, 0.0),
        ("S-04", "FS-112", (8, 1, 2027, 10, 20), SurveyRole.PRE, 0.44, SurveyStatus.READY, 287, 1.0),
        ("S-06", "FS-112", (21, 1, 2027, 10, 5), SurveyRole.PLUS_14D, 0.44, SurveyStatus.PROCESSING, 281, 0.41),
        ("S-05", "FS-203", (26, 1, 2027, 9, 30), SurveyRole.PRE, 0.0, SurveyStatus.SCHEDULED, 0, 0.0),
    ]
    for sid, season, when, role, gsd, status, images, progress in flights:
        flown = d(*when)
        ready = status == SurveyStatus.READY
        db.add(Survey(id=sid, field_season_id=season, role=role.value, status=status.value, flown_at=flown, altitude_m=15,
                      sensor=SENSOR, gsd_cm=gsd, images=images, image_bytes=images * 7_600_000, progress=progress,
                      stage="Ready" if ready else ("Photogrammetry" if status == SurveyStatus.PROCESSING else None),
                      source=f"{images} images" if images else None, pipeline=PIPELINE if ready else None,
                      model_version=MODEL_AERIAL if ready else None,
                      processed_at=flown + timedelta(hours=5) if ready else None, created_by="U-1"))
    db.flush()

    def surface(fid: str) -> PatchSurface:
        f = fields[fid]
        return PatchSurface([(float(x), float(y)) for x, y in f.boundary], PATCHES[fid], f.landscape["seed"])

    # S-05 and S-06 are not processed yet; their surfaces are what the simulated model will report
    # once they are, so the demonstration matches the field app and dashboard exactly.
    for sid, fid, follow in (("S-01", "F-047", False), ("S-02", "F-047", True), ("S-04", "F-112", False), ("S-06", "F-112", True),
                             ("S-05", "F-203", False)):
        s = surface(fid)
        analysis.store_surface(db, db.get(Survey, sid), s.scaled(EFFICACY_FACTORS, 0.5) if follow else s, MODEL_AERIAL)
    # Canal side's +14 d flight is mid-photogrammetry; the worker picks it up from 41%.
    db.add(ProcessingJob(id=str(uuid.uuid4()), survey_id="S-06", status=JobStatus.QUEUED.value, stage="Photogrammetry",
                         progress=0.41, attempts=0, started_at=d(21, 1, 2027, 11, 2)))
    db.flush()

    for fid in ("F-047", "F-112"):
        analysis.publish_zones(db, fields[fid], actor="System", threshold_pct=DEFAULTS["threshold_pct"], quiet=True)

    # ---- leaf scans uploaded by the phone (two of them abstained)
    outcomes = [
        (0, 0.91, False, "ARUCO-12", 11, [["Avena ludoviciana", 0.06], ["Triticum aestivum", 0.02]], 142, "A", (21, 1, 2027, 14, 20), (1.3, -0.9)),
        (1, 0.61, True, None, 23, [["Phalaris minor", 0.34], ["Triticum aestivum", 0.04]], 156, "B", (21, 1, 2027, 14, 34), (2.6, -1.8)),
        (2, 0.95, False, "ARUCO-07", 31, [["Convolvulus arvensis", 0.03], ["Phalaris minor", 0.01]], 138, "C", (21, 1, 2027, 14, 51), (-1.4, 0.7)),
        (0, 0.78, False, None, 47, [["Triticum aestivum", 0.14], ["Avena ludoviciana", 0.06]], 149, "A", (21, 1, 2027, 15, 12), (3.9, 2.7)),
        (3, 0.57, True, "ARUCO-19", 53, [["Chenopodium album", 0.29], ["Phalaris minor", 0.09]], 161, "C", (21, 1, 2027, 15, 30), (2.2, 1.9)),
        (4, 0.93, False, None, 67, [["Phalaris minor", 0.05], ["Avena ludoviciana", 0.01]], 133, None, (21, 1, 2027, 15, 48), (0.0, 0.0)),
    ]
    centres = {p.label: (p.cx, p.cy) for p in PATCHES["F-047"]}
    f47 = fields["F-047"]
    for i, (sp, conf, abstain, frame, seed, runner, ms, zone, when, jit) in enumerate(outcomes):
        latin, local, _, cls, _ = SPECIES[sp]
        cx, cy = centres[zone] if zone else (120.0, 78.0)
        lat, lon = geo.to_latlon(f47.lat, f47.lon, (cx + jit[0], cy + jit[1]))
        at = d(*when)
        db.add(LeafScan(id=f"SC-{35 + i:03d}", client_id=f"demo-scan-{35 + i}", device_id="D-01", operator_id="U-2", captured_at=at,
                        field_id="F-047", zone_label=f"Zone {zone}" if zone else None, frame_id=frame, species_latin=latin,
                        species_local=local, weed_class=cls.value, confidence=conf, abstained=abstain, runner_up=runner,
                        inference_ms=ms, model_version=MODEL_LEAF, leaf_seed=seed, lat=lat, lon=lon,
                        gnss_accuracy_m=round(3.4 + (i % 3) * 0.6, 1), created_at=d(21, 1, 2027, 18, 42), updated_at=d(21, 1, 2027, 18, 42)))

    # ---- earlier seasons: the same mode of action three years running on Chak 47
    prior = [
        ("T-2025-A", "FS-047-2526", "F-047", ["Zone A", "Zone B", "Zone E"], (12, 12, 2025, 10, 30), 0, "100", "g / acre", 2.4),
        ("T-2024-A", "FS-047-2425", "F-047", ["Zone A", "Zone C"], (19, 12, 2024, 11, 0), 1, "500", "mL / acre", 3.1),
        ("T-2023-A", "FS-047-2324", "F-047", ["Zone A"], (8, 1, 2024, 9, 45), 0, "100", "g / acre", 2.8),
        ("T-2025-C", "FS-112-2526", "F-112", ["Zone A"], (16, 12, 2025, 10, 10), 3, "13", "g / acre", 0.9),
    ]
    for tid, season, fid, zones_, when, pi, dose, unit, acres in prior:
        trade, active, hrac, *_ = PRODUCTS[pi]
        db.add(Treatment(id=tid, field_season_id=season, field_id=fid, zone_labels=zones_, applied_at=d(*when), product=trade,
                         active_ingredient=active, hrac_group=hrac, dose_recorded=dose, dose_unit=unit, application_mode="Knapsack",
                         growth_stage="Tillering (GS 21-25)", operator_id="U-2", operator_name=OPERATOR, area_acres=acres,
                         water_litres="100", notes="", created_at=d(*when), updated_at=d(*when)))
    # Worst-zone control measured each season with Group 1: 81% -> 64% -> 47%.
    for vid, season, when, worst in (("V-001", "FS-047-2324", (5, 2, 2024, 11, 0), 81), ("V-002", "FS-047-2425", (9, 1, 2025, 11, 0), 64),
                                      ("V-003", "FS-047-2526", (2, 1, 2026, 11, 0), 47)):
        db.add(Verification(id=vid, field_season_id=season, field_id="F-047", role=SurveyRole.PLUS_14D.value, saved_at=d(*when),
                            saved_by="U-1", results=[{"letter": "A", "label": "Zone A", "efficacyPct": worst}],
                            worst_efficacy_pct=worst, historical=True))

    for qid, frame, when, zone, counts, seed in (
        ("Q-01", "ARUCO-12", (7, 1, 2027, 11, 40), "Zone A", [["Phalaris minor", 34], ["Avena ludoviciana", 11], ["Chenopodium album", 3]], 12),
        ("Q-02", "ARUCO-07", (7, 1, 2027, 12, 5), "Zone C", [["Chenopodium album", 21], ["Convolvulus arvensis", 8]], 7),
        ("Q-03", "ARUCO-19", (21, 1, 2027, 10, 15), "Zone C", [["Convolvulus arvensis", 14], ["Phalaris minor", 12]], 19),
    ):
        db.add(Quadrat(id=qid, frame_id=frame, recorded_at=d(*when), field_id="F-047", zone_label=zone, species_counts=counts,
                       verified_by="Dr. S. Anjum, station agronomist", recorded_by="U-2", seed=seed))

    # ---- the sync ledger: the phone's last push, 21 Jan 18:41
    for i, (entity, summary, when, size) in enumerate((
        ("field", "Chak 47 boundary confirmed", (21, 1, 2027, 18, 41), 2400),
        ("leaf_scan", "Scan SC-035 · Phalaris minor", (21, 1, 2027, 18, 41), 184000),
        ("leaf_scan", "Scan SC-036 sent to review queue", (21, 1, 2027, 18, 41), 179000),
        ("leaf_scan", "Scan SC-037 · Chenopodium album", (21, 1, 2027, 18, 41), 186000),
        ("leaf_scan", "Scan SC-038 · Phalaris minor", (21, 1, 2027, 18, 42), 181000),
        ("leaf_scan", "Scan SC-039 sent to review queue", (21, 1, 2027, 18, 42), 183000),
        ("leaf_scan", "Scan SC-040 · Triticum aestivum", (21, 1, 2027, 18, 42), 178000),
        ("quadrat", "Quadrat Q-03 · ARUCO-19 counted", (21, 1, 2027, 18, 42), 1200),
    )):
        db.add(ChangeLogEntry(id=f"CL-{i + 1}", seq=212 + i, device_id="D-01", client_seq=212 + i, user_id="U-2", entity=entity,
                              op="create", summary=summary, at=d(*when), received_at=d(*when), owned_by_mobile=True, bytes=size))

    for aid, when, who, action, detail in (
        ("A-3", (3, 1, 2027, 9, 0), ANALYST, "Threshold set", "Prescription threshold 12% → 10%"),
        ("A-4", (7, 1, 2027, 15, 10), "System", "Zones published to phones", "Chak 47 · 5 zones at 10% threshold"),
        ("A-5", (8, 1, 2027, 10, 4), ANALYST, "Export generated", "GeoJSON · Chak 47"),
        ("A-6", (9, 1, 2027, 11, 20), ANALYST, "Export generated", "TASKDATA · Canal side"),
        ("A-7", (21, 1, 2027, 9, 30), "System", "Photogrammetry finished", "S-02 · Chak 47 · orthomosaic ready"),
        ("A-8", (21, 1, 2027, 11, 2), ANALYST, "Flight uploaded", "S-06 · Canal side · +14 d · 281 images"),
        ("A-9", (21, 1, 2027, 18, 43), "System", "Phone sync received", "8 changes from Asad's Galaxy S10"),
    ):
        db.add(AuditEntry(id=aid, at=d(*when), user_id="U-1" if who == ANALYST else None, who=who, action=action, detail=detail))

    db.add(ExportJob(id="X-03", field_id="F-047", format="GeoJSON", grid_size=2, threshold_pct=10, include={"boundary": True, "zones": True, "cells": True, "abstained": False},
                     size_bytes=412 * 1024, cells=analysis.grid(surface("F-047"), 2, 10).flagged, zones=5, filename="chak-47_prescription_2m_t10.geojson", created_by="U-1", at=d(8, 1, 2027, 10, 4)))
    db.add(ExportJob(id="X-02", field_id="F-112", format="TASKDATA", grid_size=2, threshold_pct=10, include={"boundary": True, "zones": True, "cells": False, "abstained": False},
                     size_bytes=96 * 1024, cells=0, zones=2, filename="canal-side_prescription_2m_t10_TASKDATA.xml", created_by="U-1", at=d(9, 1, 2027, 11, 20)))
    db.flush()

    ids.ensure_counters(db)
    db.flush()
