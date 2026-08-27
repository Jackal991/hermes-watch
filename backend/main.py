"""Hermes Watch backend — relays watch commands to Hermes via `hermes -z`."""

from __future__ import annotations

import os
import secrets
import json
from pathlib import Path
from typing import List, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

# Load backend.env if present (token, timeout, etc.) so the backend can run
# as a plain `uvicorn main:app` with no manual env setup.
load_dotenv(Path(__file__).parent / "backend.env")

from hermes_runner import HermesRunner

app = FastAPI(title="Hermes Watch Backend")

# Module-level runner (tests patch `main.runner`).
runner = HermesRunner(
    timeout=int(os.environ.get("HERMES_WATCH_TIMEOUT", "300"))
)

# Maps a client-supplied conversation key (the watch's `session` field, e.g.
# "watch-<uuid>") to the REAL hermes session id captured from --usage-file.
# This is what gives real cross-command continuity: the first command creates
# a hermes session and records its id; later commands --continue that id.
_hermes_session_map: dict[str, str] = {}


def _profiles_dir() -> Path:
    """The Hermes profiles directory. Respects HERMES_HOME if set."""
    home = os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))
    return Path(home) / "profiles"


def _list_profiles() -> List[str]:
    """Enumerate Hermes profiles dynamically: 'default' plus any profile
    directory that has a config.yaml. Robust for any user's install."""
    profiles = ["default"]
    pdir = _profiles_dir()
    if pdir.is_dir():
        for child in sorted(pdir.iterdir()):
            if child.is_dir() and (child / "config.yaml").is_file():
                profiles.append(child.name)
    return profiles


class CommandRequest(BaseModel):
    text: str
    session: Optional[str] = None
    profile: Optional[str] = "watch"


class CommandResponse(BaseModel):
    ok: bool
    result: Optional[str] = None
    error: Optional[str] = None


def _verify_token(authorization: Optional[str]) -> bool:
    token = os.environ.get("HERMES_WATCH_TOKEN", "")
    if not token:
        return False
    if not authorization or not authorization.startswith("Bearer "):
        return False
    supplied = authorization[len("Bearer "):].strip()
    return secrets.compare_digest(supplied, token)


@app.post("/api/v1/command", response_model=CommandResponse)
def run_command(
    req: CommandRequest,
    authorization: Optional[str] = Header(default=None),
) -> CommandResponse:
    if not _verify_token(authorization):
        raise HTTPException(status_code=401, detail="Unauthorized")

    text = (req.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="text is required")

    try:
        # If the client has an established conversation (session key), continue
        # its REAL hermes session id. First time around, map is empty -> hermes
        # creates a fresh session and we capture + record its real id.
        continue_session = None
        if req.session:
            continue_session = _hermes_session_map.get(req.session)
        result, real_session = runner.run(
            text, session=continue_session, profile=req.profile
        )
        # Record the real hermes session id for THIS conversation key so the
        # next command continues the same conversation.
        if req.session and real_session:
            _hermes_session_map[req.session] = real_session
    except TimeoutError as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    return CommandResponse(ok=True, result=result)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.get("/api/v1/profiles", response_model=dict)
def list_profiles(
    authorization: Optional[str] = Header(default=None),
) -> dict:
    if not _verify_token(authorization):
        raise HTTPException(status_code=401, detail="Unauthorized")
    return {"profiles": _list_profiles()}
