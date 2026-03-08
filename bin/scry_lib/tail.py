from __future__ import annotations

import os
import time
from pathlib import Path
from typing import Iterator


def find_last_full_offset(path: Path) -> int | None:
    """Find the byte offset of the header line preceding the last GameStateType_Full GSM.

    Scans forward through the file tracking GreToClientEvent header offsets.
    When a Full GSM is found in the JSON line following a header, records that
    header's offset as the candidate. Returns the last such candidate.
    """
    last_full_header: int | None = None
    current_header_offset: int | None = None

    with open(path, "r") as f:
        while True:
            pos = f.tell()
            line = f.readline()
            if not line:
                break

            if "GreToClientEvent" in line and "[UnityCrossThreadLogger]" in line:
                current_header_offset = pos
            elif current_header_offset is not None and "GameStateType_Full" in line:
                last_full_header = current_header_offset
                current_header_offset = None
            else:
                current_header_offset = None

    return last_full_header


def tail_log(
    path: Path,
    *,
    follow: bool = False,
    start_offset: int = 0,
    poll_interval: float = 0.5,
) -> Iterator[str]:
    """Yield lines from a log file, optionally following new writes.

    Args:
        path: Log file to read.
        follow: If True, keep polling for new lines after EOF (like tail -f).
        start_offset: Byte offset to start reading from.
        poll_interval: Seconds between polls in follow mode.
    """
    with open(path, "r") as f:
        # Handle truncation: if offset > file size, reset to 0
        file_size = os.fstat(f.fileno()).st_size
        if start_offset > file_size:
            f.seek(0)
        elif start_offset > 0:
            f.seek(start_offset)

        while True:
            line = f.readline()
            if line:
                yield line
                continue

            if not follow:
                return

            # Follow mode: check for truncation, then poll
            current_size = os.path.getsize(path)
            if current_size < f.tell():
                f.seek(0)
                continue

            time.sleep(poll_interval)
