import os
from unittest.mock import patch

from fastapi.testclient import TestClient

import main

TOKEN = "test-token-abc"


def make_client():
    os.environ["HERMES_WATCH_TOKEN"] = TOKEN
    # fresh app so the runner reads the current env
    import importlib

    importlib.reload(main)
    return TestClient(main.app)


def test_requires_auth():
    c = make_client()
    r = c.post("/api/v1/command", json={"text": "hi"})
    assert r.status_code == 401


def test_bad_token_rejected():
    c = make_client()
    r = c.post(
        "/api/v1/command",
        json={"text": "hi"},
        headers={"Authorization": "Bearer wrong"},
    )
    assert r.status_code == 401


def test_empty_text_rejected():
    c = make_client()
    r = c.post(
        "/api/v1/command",
        json={"text": "   "},
        headers={"Authorization": f"Bearer {TOKEN}"},
    )
    assert r.status_code == 400


def test_happy_path():
    c = make_client()
    with patch.object(main, "runner") as mock_runner:
        mock_runner.run.return_value = ("the reply", "20260827_real")
        r = c.post(
            "/api/v1/command",
            json={"text": "say hello"},
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["result"] == "the reply"
    mock_runner.run.assert_called_once_with("say hello", session=None, profile="watch")


def test_session_continuity_uses_real_session_id():
    c = make_client()
    with patch.object(main, "runner") as mock_runner:
        # First command: no real session yet -> continue None, then record real id.
        mock_runner.run.return_value = ("ok", "20260827_real")
        r1 = c.post(
            "/api/v1/command",
            json={"text": "remember word", "session": "watch-abc"},
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
        assert r1.status_code == 200
        # Second command, same watch session: must continue the REAL hermes id.
        mock_runner.run.return_value = ("recalled", "20260827_real")
        r2 = c.post(
            "/api/v1/command",
            json={"text": "what was it", "session": "watch-abc"},
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
        assert r2.status_code == 200
    calls = mock_runner.run.call_args_list
    assert calls[0].kwargs["session"] is None
    assert calls[1].kwargs["session"] == "20260827_real"


def test_profiles_requires_auth():
    c = make_client()
    r = c.get("/api/v1/profiles")
    assert r.status_code == 401


def test_profiles_lists_dynamic():
    c = make_client()
    r = c.get(
        "/api/v1/profiles",
        headers={"Authorization": f"Bearer {TOKEN}"},
    )
    assert r.status_code == 200
    profiles = r.json()["profiles"]
    assert isinstance(profiles, list)
    assert "default" in profiles  # default always present


def test_runner_failure_returns_502():
    c = make_client()
    with patch.object(main, "runner") as mock_runner:
        mock_runner.run.side_effect = RuntimeError("boom")
        r = c.post(
            "/api/v1/command",
            json={"text": "hi"},
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
    assert r.status_code == 502
    assert r.json()["detail"] == "boom"
