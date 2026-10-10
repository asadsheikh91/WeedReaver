from __future__ import annotations

from datetime import timedelta

from app.services import users
from tests.conftest import ANALYST, API, OPERATOR, PASSWORD, TRAINEE, auth, login


def test_login_me_and_bad_password(client):
    t = login(client, ANALYST)
    assert t["user"]["role"] == "ANALYST" and t["tokenType"] == "bearer" and t["expiresIn"] > 0
    me = client.get(f"{API}/auth/me", headers=auth(t)).json()
    assert me["name"] == "Dr. S. Anjum" and me["preferences"]["language"] == "English"
    r = client.post(f"{API}/auth/login", json={"email": ANALYST, "password": "wrong-password"})
    assert r.status_code == 401 and r.json()["error"]["code"] == "invalid_credentials"
    r = client.post(f"{API}/auth/login", json={"email": "nobody@example.com", "password": "whatever1"})
    assert r.status_code == 401
    assert client.get(f"{API}/fields").status_code == 401


def test_login_rate_limited(client):
    for _ in range(10):
        client.post(f"{API}/auth/login", json={"email": OPERATOR, "password": "nope-nope"})
    r = client.post(f"{API}/auth/login", json={"email": OPERATOR, "password": PASSWORD})
    assert r.status_code == 429


def test_refresh_rotation_and_reuse_detection(client, monkeypatch):
    t = login(client, OPERATOR)
    r1 = client.post(f"{API}/auth/refresh", json={"refreshToken": t["refreshToken"]})
    assert r1.status_code == 200
    # The answer was lost and the phone retries with the old token: a retry, not a replay.
    r2 = client.post(f"{API}/auth/refresh", json={"refreshToken": t["refreshToken"]})
    assert r2.status_code == 200
    new = r2.json()
    # Outside the retry window, a rotated token coming back ends the whole family.
    monkeypatch.setattr(users, "RETRY_GRACE", timedelta(0))
    r3 = client.post(f"{API}/auth/refresh", json={"refreshToken": t["refreshToken"]})
    assert r3.status_code == 401 and r3.json()["error"]["code"] == "refresh_token_reused"
    assert client.post(f"{API}/auth/refresh", json={"refreshToken": new["refreshToken"]}).status_code == 401


def test_logout_revokes(client):
    t = login(client, OPERATOR)
    assert client.post(f"{API}/auth/logout", json={"refreshToken": t["refreshToken"]}).status_code == 200
    assert client.post(f"{API}/auth/refresh", json={"refreshToken": t["refreshToken"]}).status_code == 401


def test_device_registration_at_login(client):
    t = login(client, OPERATOR, {"installId": "new-install-12345", "name": "Spare handset", "appVersion": "0.9.1 (195)"})
    assert t["deviceId"].startswith("D-")
    devices = client.get(f"{API}/devices", headers=auth(t)).json()
    spare = next(d for d in devices if d["id"] == t["deviceId"])
    assert spare["updateAvailable"] is True and spare["operator"] == "Asad Mehmood"
    again = login(client, OPERATOR, {"installId": "new-install-12345", "name": "Spare handset"})
    assert again["deviceId"] == t["deviceId"]


def test_change_password_invalidates_tokens(client):
    t = login(client, OPERATOR)
    h = auth(t)
    r = client.post(f"{API}/auth/change-password", json={"currentPassword": PASSWORD, "newPassword": "short"}, headers=h)
    assert r.status_code == 422
    r = client.post(f"{API}/auth/change-password", json={"currentPassword": PASSWORD, "newPassword": "a-much-better-one"}, headers=h)
    assert r.status_code == 200
    assert client.post(f"{API}/auth/refresh", json={"refreshToken": t["refreshToken"]}).status_code == 401
    # The caller keeps working with the tokens it was handed back.
    assert client.get(f"{API}/auth/me", headers=auth(r.json())).status_code == 200
    assert client.post(f"{API}/auth/refresh", json={"refreshToken": r.json()["refreshToken"]}).status_code == 200
    assert client.post(f"{API}/auth/login", json={"email": OPERATOR, "password": "a-much-better-one"}).status_code == 200


def test_invite_and_accept(client, analyst, admin):
    # An analyst may invite operators, not administrators.
    r = client.post(f"{API}/users/invitations", json={"email": "boss@example.com", "role": "ADMIN"}, headers=analyst)
    assert r.status_code == 403
    r = client.post(f"{API}/users/invitations", json={"email": "New.Op@Example.com", "role": "OPERATOR", "fieldIds": ["F-112"]},
                    headers=analyst)
    assert r.status_code == 201, r.text
    inv = r.json()
    assert inv["status"] == "pending" and inv["token"] and inv["email"] == "new.op@example.com"
    r = client.post(f"{API}/auth/accept-invite", json={"token": inv["token"], "name": "New Operator", "password": "field-ready-1"})
    assert r.status_code == 200, r.text
    t = r.json()
    assert t["user"]["role"] == "OPERATOR" and t["user"]["fieldIds"] == ["F-112"] and t["user"]["operatorCode"].startswith("OP-")
    fields = client.get(f"{API}/fields", headers=auth(t)).json()
    assert [f["id"] for f in fields] == ["F-112"]
    assert client.get(f"{API}/fields/F-047", headers=auth(t)).status_code == 404
    again = client.post(f"{API}/auth/accept-invite", json={"token": inv["token"], "name": "X", "password": "field-ready-1"})
    assert again.status_code == 422


def test_password_reset(client):
    r = client.post(f"{API}/auth/password-reset/request", json={"email": OPERATOR})
    token = r.json()["devToken"]
    assert client.post(f"{API}/auth/password-reset/request", json={"email": "ghost@example.com"}).json().get("devToken") is None
    r = client.post(f"{API}/auth/password-reset/confirm", json={"token": token, "newPassword": "brand-new-pass"})
    assert r.status_code == 200
    assert client.post(f"{API}/auth/password-reset/confirm", json={"token": token, "newPassword": "brand-new-pass"}).status_code == 422
    assert client.post(f"{API}/auth/login", json={"email": OPERATOR, "password": "brand-new-pass"}).status_code == 200


def test_trainee_scope_and_preferences(client):
    t = auth(login(client, TRAINEE))
    assert [f["id"] for f in client.get(f"{API}/fields", headers=t).json()] == ["F-203"]
    r = client.patch(f"{API}/auth/me/preferences", json={"language": "Punjabi", "gridSize": 5}, headers=t)
    assert r.status_code == 200 and r.json()["language"] == "Punjabi" and r.json()["gridSize"] == 5
    assert client.patch(f"{API}/auth/me/preferences", json={"language": "Klingon"}, headers=t).status_code == 422


def test_team_management(client, admin, analyst, operator):
    users = client.get(f"{API}/users", headers=analyst).json()
    assert {u["roleLabel"] for u in users} == {"Analyst", "Field operator", "Administrator", "Trainee"}
    assert client.get(f"{API}/users", headers=operator).status_code == 403
    # Analysts can widen a trainee's scope, not change roles.
    assert client.patch(f"{API}/users/U-4", json={"role": "ANALYST"}, headers=analyst).status_code == 403
    r = client.patch(f"{API}/users/U-4", json={"fieldIds": ["F-203", "F-112"]}, headers=analyst)
    assert r.status_code == 200 and r.json()["fieldIds"] == ["F-112", "F-203"]
    # The last administrator cannot demote themselves.
    assert client.patch(f"{API}/users/U-3", json={"role": "ANALYST"}, headers=admin).status_code == 409
    r = client.patch(f"{API}/users/U-2", json={"isActive": False}, headers=admin)
    assert r.status_code == 200
    assert client.post(f"{API}/auth/login", json={"email": OPERATOR, "password": PASSWORD}).status_code == 401
