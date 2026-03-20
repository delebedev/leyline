from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path


_session_start = time.time()
_log_file: Path | None = None


def _session_log_path() -> Path:
    date = datetime.now().strftime("%Y-%m-%d")
    ts = datetime.now().strftime("%H%M%S")
    p = Path(f"/tmp/arena/sessions/{date}/{ts}.jsonl")
    p.parent.mkdir(parents=True, exist_ok=True)
    return p


def _get_log_file() -> Path:
    global _log_file
    if _log_file is None:
        _log_file = _session_log_path()
    return _log_file


def session_log(
    command: str,
    args: list[str],
    exit_code: int,
    stdout: str,
    stderr: str,
    duration_ms: int,
) -> None:
    elapsed_ms = int((time.time() - _session_start) * 1000)
    entry: dict = {
        "t": elapsed_ms,
        "ts": datetime.now(timezone.utc).isoformat(),
        "cmd": command,
        "args": args,
        "exit": exit_code,
        "ms": duration_ms,
    }
    if exit_code != 0:
        entry["error"] = True
        if stderr:
            entry["stderr"] = stderr[:500]
    if stdout and len(stdout) < 200:
        entry["out"] = stdout
    try:
        with open(_get_log_file(), "a") as f:
            f.write(json.dumps(entry) + "\n")
    except OSError:
        pass
