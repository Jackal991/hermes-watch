import json
import pytest

from hermes_runner import HermesRunner


class FakeProc:
    def __init__(self, stdout="", returncode=0):
        self.stdout = stdout
        self.returncode = returncode


class FakeRun:
    """Replaces subprocess.run with a controllable fake.

    When given a real session id, writes a usage file at the path found in the
    command so the runner can read it back (mirroring hermes --usage-file).
    """

    def __init__(self, result=None, exc=None, session_id=None):
        self._result = result
        self._exc = exc
        self._session_id = session_id
        self.calls = []

    def __call__(self, command, capture_output, text, timeout):
        self.calls.append((command, timeout))
        if self._exc is not None:
            raise self._exc
        # Emulate hermes writing the real session id to --usage-file.
        if self._session_id:
            usage_path = command[command.index("--usage-file") + 1]
            with open(usage_path, "w", encoding="utf-8") as f:
                json.dump({"session_id": self._session_id}, f)
        return self._result


def test_returns_reply_and_real_session():
    fake = FakeRun(FakeProc(stdout="the reply", returncode=0), session_id="20260827_abc")
    r = HermesRunner(run=fake)
    reply, real_session = r.run("say hello")
    assert reply == "the reply"
    assert real_session == "20260827_abc"


def test_passes_usage_file():
    fake = FakeRun(FakeProc(stdout="hi", returncode=0), session_id="20260827_xyz")
    r = HermesRunner(run=fake)
    r.run("hi")
    cmd, _ = fake.calls[0]
    assert "--usage-file" in cmd


def test_no_session_id_in_usage_returns_none():
    fake = FakeRun(FakeProc(stdout="the reply", returncode=0), session_id=None)
    r = HermesRunner(run=fake)
    _, real_session = r.run("say hello")
    assert real_session is None


def test_includes_continue_with_real_session():
    fake = FakeRun(FakeProc(stdout="the reply", returncode=0), session_id="20260827_new")
    r = HermesRunner(run=fake)
    r.run("hi", session="20260827_existing")
    cmd, _ = fake.calls[0]
    assert "--continue" in cmd
    assert cmd[cmd.index("--continue") + 1] == "20260827_existing"


def test_includes_profile_flag():
    fake = FakeRun(FakeProc(stdout="the reply", returncode=0))
    r = HermesRunner(run=fake)
    r.run("hi", profile="watch")
    cmd, _ = fake.calls[0]
    assert cmd[:2] == ["hermes", "-p"]
    assert cmd[2] == "watch"
    assert "-z" in cmd


def test_uses_custom_timeout():
    fake = FakeRun(FakeProc(stdout="x"))
    r = HermesRunner(run=fake, timeout=45)
    r.run("hi")
    assert fake.calls[0][1] == 45


def test_raises_on_nonzero_exit():
    fake = FakeRun(FakeProc(stdout="error!", returncode=2))
    r = HermesRunner(run=fake)
    with pytest.raises(RuntimeError, match="code 2"):
        r.run("boom")


def test_raises_on_timeout():
    import subprocess

    fake = FakeRun(exc=subprocess.TimeoutExpired(cmd="hermes", timeout=300))
    r = HermesRunner(run=fake)
    with pytest.raises(TimeoutError):
        r.run("slow")


def test_raises_when_hermes_missing():
    fake = FakeRun(exc=FileNotFoundError("no hermes"))
    r = HermesRunner(run=fake)
    with pytest.raises(RuntimeError, match="not found"):
        r.run("hi")
