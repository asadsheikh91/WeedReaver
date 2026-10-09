"""The clients test their decoders against fixtures captured from this API:

- app/src/test/resources/sync-pull-full.json  (Android SyncContractTest)
- web/src/data/__fixtures__/f047-grid-5m.json (web infestation.test.ts)

These tests fail when the API's shape drifts from those fixtures, so a breaking change is caught here,
not on a phone in the field. If a change is intended, regenerate the fixtures (see their tests).
"""

from __future__ import annotations

import json
from pathlib import Path

from tests.conftest import API, OPERATOR, auth, login

REPO = Path(__file__).resolve().parents[2]


def shape(v):
    """Keys of every object, recursively; lists by their first element."""
    if isinstance(v, dict):
        return {k: shape(x) for k, x in v.items()}
    if isinstance(v, list):
        return [shape(v[0])] if v and isinstance(v[0], (dict, list)) else []
    return None


def missing(expected, actual, path="") -> list[str]:
    out: list[str] = []
    if isinstance(expected, dict):
        for k, v in expected.items():
            if not isinstance(actual, dict) or k not in actual:
                out.append(f"{path}.{k}")
            else:
                out += missing(v, actual[k], f"{path}.{k}")
    elif isinstance(expected, list) and expected and isinstance(actual, list) and actual:
        out += missing(expected[0], actual[0], f"{path}[]")
    return out


def test_sync_pull_still_has_every_key_the_phone_fixture_has(client):
    fixture = json.loads((REPO / "app/src/test/resources/sync-pull-full.json").read_text(encoding="utf-8"))
    t = login(client, OPERATOR, {"installId": "demo-install-galaxy-s10", "name": "Asad's Galaxy S10"})
    live = client.get(f"{API}/sync/pull", headers=auth(t, device=True)).json()
    assert missing(shape(fixture), shape(live)) == []
    assert {k: len(v) for k, v in live.items() if isinstance(v, list)} == {k: len(v) for k, v in fixture.items() if isinstance(v, list)}


def test_grid_response_matches_the_web_fixture(client):
    fixture = json.loads((REPO / "web/src/data/__fixtures__/f047-grid-5m.json").read_text(encoding="utf-8"))
    t = login(client, OPERATOR)
    live = client.get(f"{API}/fields/F-047/grid?size=5", headers=auth(t)).json()
    assert live == fixture
