#!/usr/bin/env python3
"""Run a Forge install once for a shared commit-specific Maven repository."""

import fcntl
import os
from pathlib import Path
import subprocess
import sys


def main() -> int:
    if len(sys.argv) < 3:
        print(f"usage: {sys.argv[0]} <cache-dir> <command> [args...]", file=sys.stderr)
        return 2

    cache_dir = Path(sys.argv[1])
    cache_dir.mkdir(parents=True, exist_ok=True)
    marker = cache_dir / ".install-complete"

    with (cache_dir / ".install.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if marker.exists():
            print(f"Forge engine already installed in {cache_dir}")
            return 0

        result = subprocess.run(sys.argv[2:], check=False)
        if result.returncode != 0:
            return result.returncode

        temporary_marker = cache_dir / f".install-complete.{os.getpid()}"
        temporary_marker.write_text("ok\n", encoding="ascii")
        temporary_marker.replace(marker)
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
