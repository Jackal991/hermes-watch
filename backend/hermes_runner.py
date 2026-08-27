"""Runs a Hermes one-shot command via `hermes -z "<text>"`."""

from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path


class HermesRunner:
    """Invokes `hermes -z "<text>"` and returns (reply, real_session_id).

    Hermes auto-generates its own session id (e.g. `20260827_023302_6dc059`)
    on every `-z` cold boot. Passing `--continue <arbitrary-name>` does NOT
    create a session with that name — hermes silently ignores unknown names
    and starts a fresh session, which is why naive `--continue watch-<uuid>`
    lost all context between commands.

    To get real continuity we let hermes create/continue its own session and
    read the ACTUAL session id back from `--usage-file`. Callers store that id
    keyed by their own conversation key, then pass it as `--continue` on the
    next call so hermes resumes the SAME session.
    """

    def __init__(
        self,
        run=subprocess.run,
        timeout: int = 300,
        binary: str = "hermes",
    ) -> None:
        self._run = run
        self._timeout = timeout
        self._binary = binary

    def run(
        self,
        text: str,
        session: str | None = None,
        profile: str | None = None,
    ) -> tuple[str, str | None]:
        # Append brevity guidance so replies are short and TTS-friendly on the
        # watch — nobody wants to listen to paragraphs.
        guided = (
            f"{text}\n\n"
            "Keep your reply to 1-3 short sentences, plain text, no markdown, "
            "no bullets, suitable for text-to-speech."
        )
        # --continue <session> resumes an EXISTING hermes session (a real id
        # captured from a previous --usage-file). --profile <name> runs under an
        # ISOLATED Hermes profile so the watch cannot touch the main agent.
        command = [self._binary]
        if profile:
            command += ["-p", profile]
        if session:
            command += ["--continue", session]
        command += ["-z", guided]

        # Write usage to a temp file so we can read the real session id back.
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
            usage_path = f.name
        command += ["--usage-file", usage_path]

        try:
            result = self._run(
                command,
                capture_output=True,
                text=True,
                timeout=self._timeout,
            )
        except subprocess.TimeoutExpired as exc:
            try:
                Path(usage_path).unlink(missing_ok=True)
            except Exception:
                pass
            raise TimeoutError(
                f"hermes command timed out after {self._timeout}s"
            ) from exc
        except FileNotFoundError as exc:
            try:
                Path(usage_path).unlink(missing_ok=True)
            except Exception:
                pass
            raise RuntimeError(
                f"{self._binary} executable not found on PATH"
            ) from exc

        if result.returncode != 0:
            raise RuntimeError(
                f"{self._binary} exited with code {result.returncode}: "
                f"{result.stdout.strip() or result.stderr.strip()}"
            )

        # Extract the real session id hermes used.
        real_session = None
        try:
            with open(usage_path, "r", encoding="utf-8") as f:
                usage = json.load(f)
            real_session = usage.get("session_id")
        except Exception:
            real_session = None
        finally:
            try:
                Path(usage_path).unlink(missing_ok=True)
            except Exception:
                pass

        return result.stdout.strip(), real_session
