from __future__ import annotations

import subprocess

from .paths import PROJECT_DIR


def run(*cmd: str, timeout: int = 30) -> tuple[int, str, str]:
    """Run a command, return (exit_code, stdout, stderr)."""
    try:
        p = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=PROJECT_DIR,
        )
        return p.returncode, p.stdout.strip(), p.stderr.strip()
    except subprocess.TimeoutExpired:
        return 1, "", "timeout"
    except FileNotFoundError:
        return 1, "", f"command not found: {cmd[0]}"


def peekaboo(*args: str) -> tuple[int, str, str]:
    return run("/opt/homebrew/bin/peekaboo", *args)


def sips(*args: str) -> tuple[int, str, str]:
    return run("sips", *args)
