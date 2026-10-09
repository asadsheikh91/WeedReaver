"""Every operation in the OpenAPI document answers without a server error, as analyst and as
operator, with realistic path parameters."""

from __future__ import annotations

import re

from tests.conftest import API

PARAMS = {
    "field_id": "F-047", "survey_id": "S-01", "scan_id": "SC-036", "treatment_id": "T-2025-A", "export_id": "X-03",
    "device_id": "D-01", "season_id": "FS-047", "user_id": "U-4", "product_id": "P-01", "invitation_id": "missing",
    "zone": "A",
}
BODIES = {
    ("post", "/fields/{field_id}/zones/{zone}/state"): {"state": "ROUTED"},
    ("post", "/scans/{scan_id}/annotation"): {"species": "Phalaris minor"},
    ("post", "/scans/{scan_id}/resolve"): {"species": "Phalaris minor"},
    ("post", "/devices/{device_id}/heartbeat"): {"battery": 50},
    ("post", "/devices/register"): {"installId": "sweep-install-1", "name": "Sweep"},
    ("patch", "/products/{product_id}"): {"registered": True},
    ("patch", "/users/{user_id}"): {"scopeAll": False, "fieldIds": ["F-203"]},
    ("patch", "/seasons/{season_id}"): {"variety": "Akbar-2019"},
    ("patch", "/surveys/{survey_id}"): {"altitudeM": 15},
    ("post", "/rotation/check"): {"fieldId": "F-047", "product": "Leader 75 WG"},
    ("post", "/exports/preview"): {"fieldId": "F-047", "format": "GeoJSON"},
    ("post", "/users/invitations"): {"email": "sweep@example.com", "role": "TRAINEE"},
}
SKIP = {"/auth/", "/sync/push", "/fields/parse-boundary", "/surveys/{survey_id}/images", "/scans/{scan_id}/photo"}


def test_every_operation_answers_without_server_error(client, analyst, operator):
    spec = client.get(f"{API}/openapi.json").json()
    called = 0
    for raw_path, ops in spec["paths"].items():
        path = raw_path.removeprefix(API)
        if any(s in raw_path for s in SKIP) or not raw_path.startswith(API):
            continue
        for method in ("get", "post", "patch", "put"):
            if method not in ops:
                continue
            url = API + re.sub(r"\{(\w+)\}", lambda m: PARAMS[m.group(1)], path)
            body = BODIES.get((method, path), {} if method != "get" else None)
            for headers in (analyst, operator):
                r = client.request(method.upper(), url, json=body, headers=headers)
                assert r.status_code < 500, f"{method.upper()} {url}: {r.status_code} {r.text[:300]}"
                called += 1
    assert called > 120
