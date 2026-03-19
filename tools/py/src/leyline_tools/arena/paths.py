from __future__ import annotations

from pathlib import Path


def _find_project_dir() -> Path:
    d = Path.cwd().resolve()
    while d != d.parent:
        if (d / "justfile").exists():
            return d
        d = d.parent
    return Path(__file__).resolve().parents[5]


PROJECT_DIR = _find_project_dir()
ARENA_TOOL_DIR = PROJECT_DIR / "tools" / "arena"
NATIVE_DIR = ARENA_TOOL_DIR / "native"
SWIFT_DIR = ARENA_TOOL_DIR / "swift"
REFERENCE_WIDTH = 960
