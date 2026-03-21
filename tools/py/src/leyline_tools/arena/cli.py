#!/usr/bin/env python3
"""Arena CLI — MTGA window automation."""

from __future__ import annotations

import io
import sys
import time

from .board import cmd_board
from .bot_match import cmd_bot_match as _cmd_bot_match
from .capture import cmd_capture, cmd_detect, cmd_ocr
from .common import die
from .commands.input import (
    cmd_activate,
    cmd_clear_field,
    cmd_key,
    cmd_login,
    cmd_paste,
    cmd_type,
)
from .commands.state import cmd_errors, cmd_state
from .diagnostics import cmd_health, cmd_issues
from .gameplay import cmd_attack_all, cmd_land, cmd_play
from .interaction import cmd_click, cmd_drag, cmd_move
from .nav import cmd_navigate as _cmd_navigate, cmd_scene, cmd_wait, cmd_where
from .session_log import session_log
from .shell import run
from .turn import cmd_turn


def cmd_launch(args: list[str]) -> None:
    width, height, kill, fullscreen = 1920, 1080, False, False
    it = iter(args)
    for a in it:
        if a == "--width":
            width = int(next(it))
        elif a == "--height":
            height = int(next(it))
        elif a == "--kill":
            kill = True
        elif a == "--fullscreen":
            fullscreen = True
        else:
            die(f"Unknown arg: {a}")

    # Auto-detect: fullscreen on non-retina 1080p monitors where the window overflows.
    # MTGA at 1920x1080 + title bar = 1920x1108. On 1080p that's 59px off-screen.
    # HiDPI/4K monitors that report "UI Looks like: 1920 x 1080" are fine windowed.
    if not fullscreen:
        _, sips_out, _ = run("system_profiler", "SPDisplaysDataType")
        native_1080p = (
            "1920 x 1080" in sips_out
            and "Retina" not in sips_out
            and "UI Looks like" not in sips_out
        )
        if native_1080p:
            fullscreen = True

    if kill:
        run("osascript", "-e", 'tell application "MTGA" to quit')
        time.sleep(2)

    fs_flag = "1" if fullscreen else "0"
    code, _, stderr = run(
        "open",
        "-a",
        "MTGA",
        "--args",
        "-screen-width",
        str(width),
        "-screen-height",
        str(height),
        "-screen-fullscreen",
        fs_flag,
    )
    if code != 0:
        die(f"Failed to launch MTGA: {stderr}")
    mode = "fullscreen" if fullscreen else "windowed"
    print(f"MTGA launched ({width}x{height} {mode})")


def cmd_bot_match(args: list[str]) -> None:
    _cmd_bot_match(args, COMMANDS)


def cmd_navigate(args: list[str]) -> None:
    _cmd_navigate(args, COMMANDS)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


COMMANDS = {
    "launch": cmd_launch,
    "activate": cmd_activate,
    "login": cmd_login,
    "capture": cmd_capture,
    "ocr": cmd_ocr,
    "type": cmd_type,
    "paste": cmd_paste,
    "key": cmd_key,
    "clear-field": cmd_clear_field,
    "click": cmd_click,
    "move": cmd_move,
    "drag": cmd_drag,
    "play": cmd_play,
    "land": cmd_land,
    "attack-all": cmd_attack_all,
    "bot-match": cmd_bot_match,
    "state": cmd_state,
    "errors": cmd_errors,
    "scene": cmd_scene,
    "where": cmd_where,
    "navigate": cmd_navigate,
    "wait": cmd_wait,
    "board": cmd_board,
    "detect": cmd_detect,
    "issues": cmd_issues,
    "health": cmd_health,
    "turn": cmd_turn,
}


COMMAND_HELP = {
    "launch": "Launch MTGA client (1920x1080 windowed)",
    "activate": "Activate MTGA window",
    "login": "Log in on login screen. --email <e> --password <p> (default: forge@local/forge)",
    "capture": "Screenshot MTGA window. --out <path> --resolution <px>",
    "ocr": "OCR screen text. --fmt (table) --find <text> --hand (zoomed hand strip)",
    "type": "Type text into the focused control",
    "paste": "Paste text into the focused control",
    "key": "Send a key press. <tab|enter|esc|delete|left|right|up|down|space|code:N>",
    "clear-field": "Clear focused field with Cmd-A then Delete",
    "click": "Click text or coords. <text|x,y> --retry N --double --right --exact",
    "move": "Move mouse to coords. <x,y>",
    "drag": "Drag between coords. <from> <to> [--verify <instanceId>]",
    "play": "Play card from hand by name. <name> [--to x,y]",
    "land": "Play first available land (policy: auto-pick, verified drag)",
    "attack-all": "Attack with all creatures (All Attack + confirm)",
    "bot-match": "Start bot match: lobby→deck→keep→game. [--deck <name|N>]",
    "state": "Show debug API game state (local mode only)",
    "errors": "Show client errors from Player.log",
    "scene": "Current lobby screen from Player.log",
    "where": "Detect current screen (scene + OCR)",
    "navigate": "Auto-navigate to screen. <Home|FindMatch|InGame|...>",
    "wait": "Block until condition. text=<str> scene=<str> --timeout N",
    "board": "Full board state (API + OCR). --no-ocr --no-detect",
    "detect": "Run card detection model on current screen",
    "issues": "Review session errors and failures",
    "health": "Pre-flight health check (server, client, display)",
    "turn": "Structured turn state for agent decision-making",
}


def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1] in ("--help", "-h", "help"):
        print("Usage: arena <command> [args...]")
        print()
        for cmd, desc in COMMAND_HELP.items():
            print(f"  {cmd:12s} {desc}")
        print()
        print(
            "Use 'arena <command> --help' for command-specific help (where supported)."
        )
        if len(sys.argv) >= 2 and sys.argv[1] in ("--help", "-h", "help"):
            sys.exit(0)
        sys.exit(1)

    command = sys.argv[1]
    cmd_args = sys.argv[2:]

    if command == "issues":
        cmd_issues(cmd_args)
        return

    handler = COMMANDS.get(command)
    if handler is None:
        print(f"Unknown command: {command}", file=sys.stderr)
        sys.exit(1)

    # Capture stdout/stderr for session logging
    captured_out = io.StringIO()
    captured_err = io.StringIO()

    class Tee:
        def __init__(self, original: object, capture: io.StringIO):
            self._orig = original
            self._cap = capture

        def write(self, s: str) -> int:
            self._orig.write(s)  # type: ignore
            self._cap.write(s)
            return len(s)

        def flush(self) -> None:
            self._orig.flush()  # type: ignore
            self._cap.flush()

    orig_out, orig_err = sys.stdout, sys.stderr
    sys.stdout = Tee(orig_out, captured_out)  # type: ignore
    sys.stderr = Tee(orig_err, captured_err)  # type: ignore

    start_ms = time.time()
    exit_code = 0
    try:
        handler(cmd_args)
    except SystemExit as e:
        exit_code = e.code if isinstance(e.code, int) else 1
    except Exception:
        exit_code = 1
    finally:
        sys.stdout = orig_out
        sys.stderr = orig_err
        duration_ms = int((time.time() - start_ms) * 1000)
        session_log(
            command,
            cmd_args,
            exit_code,
            captured_out.getvalue().strip(),
            captured_err.getvalue().strip(),
            duration_ms,
        )

    if exit_code != 0:
        sys.exit(exit_code)


if __name__ == "__main__":
    main()
