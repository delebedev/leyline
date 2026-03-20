from __future__ import annotations

import sys
import time

from ..macos import (
    _activate_mtga,
    _copy_to_clipboard,
    _reset_activate,
    _send_command_a,
    _send_command_v,
    _send_keycode,
    _type_text_slow,
    click_screen,
)
from ..scry_bridge import get_current_scene
from ..shell import run


def _die(msg: str) -> None:
    print(msg, file=sys.stderr)
    raise SystemExit(1)


def cmd_activate(args: list[str]) -> None:
    """Activate MTGA window. Usage: arena activate"""
    _activate_mtga()
    print("activated MTGA")


def cmd_type(args: list[str]) -> None:
    """Type text into the focused control. Usage: arena type <text> [--delay-ms N]"""
    if not args:
        _die("Usage: arena type <text> [--delay-ms N]")

    delay_ms = 120
    text_parts: list[str] = []
    i = 0
    while i < len(args):
        if args[i] == "--delay-ms":
            if i + 1 >= len(args):
                _die("Usage: arena type <text> [--delay-ms N]")
            delay_ms = int(args[i + 1])
            i += 2
            continue
        text_parts.append(args[i])
        i += 1

    if not text_parts:
        _die("Usage: arena type <text> [--delay-ms N]")

    text = " ".join(text_parts)
    _activate_mtga()
    code, err = _type_text_slow(text, delay_ms=delay_ms)
    if code != 0:
        _die(f"type failed: {err}")
    print(f"typed {text!r}")


def cmd_paste(args: list[str]) -> None:
    """Paste text into the focused control. Usage: arena paste <text> [--settle-ms N]"""
    if not args:
        _die("Usage: arena paste <text> [--settle-ms N]")

    settle_ms = 250
    text_parts: list[str] = []
    i = 0
    while i < len(args):
        if args[i] == "--settle-ms":
            if i + 1 >= len(args):
                _die("Usage: arena paste <text> [--settle-ms N]")
            settle_ms = int(args[i + 1])
            i += 2
            continue
        text_parts.append(args[i])
        i += 1

    if not text_parts:
        _die("Usage: arena paste <text> [--settle-ms N]")

    text = " ".join(text_parts)
    _activate_mtga()
    code, err = _copy_to_clipboard(text)
    if code != 0:
        _die(f"copy failed: {err}")
    time.sleep(settle_ms / 1000)
    code, _, err = _send_command_v()
    if code != 0:
        _die(f"paste failed: {err}")
    print(f"pasted {text!r}")


def cmd_key(args: list[str]) -> None:
    """Send a key press. Usage: arena key <tab|enter|esc|delete|left|right|up|down|space|code:N>"""
    if len(args) != 1:
        _die("Usage: arena key <tab|enter|esc|delete|left|right|up|down|space|code:N>")

    token = args[0].lower()
    keycodes = {
        "tab": 48,
        "enter": 36,
        "esc": 53,
        "delete": 51,
        "left": 123,
        "right": 124,
        "down": 125,
        "up": 126,
        "space": 49,
    }
    if token.startswith("code:"):
        try:
            code_num = int(token.split(":", 1)[1])
        except ValueError:
            _die("Usage: arena key <...|code:N>")
    elif token in keycodes:
        code_num = keycodes[token]
    else:
        _die("Usage: arena key <tab|enter|esc|delete|left|right|up|down|space|code:N>")

    _activate_mtga()
    code, _, err = _send_keycode(code_num)
    if code != 0:
        _die(f"key failed: {err}")
    print(f"sent key {args[0]}")


def cmd_clear_field(args: list[str]) -> None:
    """Clear focused field with Cmd-A then Delete. Usage: arena clear-field"""
    _activate_mtga()
    code, _, err = _send_command_a()
    if code != 0:
        _die(f"select-all failed: {err}")
    time.sleep(0.12)

    code, _, err = _send_keycode(51)
    if code != 0:
        _die(f"delete failed: {err}")

    for _ in range(12):
        time.sleep(0.03)
        _send_keycode(51)

    print("cleared field")


def cmd_login(args: list[str]) -> None:
    """Log in to MTGA on the login screen.

    Usage: arena login [--email <email>] [--password <pw>]
    Defaults: forge@local / forge (local dev account)
    """
    email = "forge@local"
    password = "forge"
    i = 0
    while i < len(args):
        if args[i] == "--email" and i + 1 < len(args):
            email = args[i + 1]
            i += 2
        elif args[i] == "--password" and i + 1 < len(args):
            password = args[i + 1]
            i += 2
        else:
            i += 1

    scene = get_current_scene()
    if scene != "Login":
        _die(f"Not on login screen (scene={scene})")

    email_field = (500, 355)
    password_field = (500, 385)
    login_button = (480, 470)

    def _focus_clear_paste(x: int, y: int, value: str) -> None:
        _reset_activate()
        click_screen(x, y)
        time.sleep(0.4)
        cmd_clear_field([])
        time.sleep(0.2)
        cmd_paste([value])
        time.sleep(0.6)

    def _wait_until(predicate, timeout_ms: int = 4000) -> bool:
        deadline = time.time() + timeout_ms / 1000
        while time.time() < deadline:
            if predicate():
                return True
            time.sleep(0.3)
        return False

    def _login_done() -> bool:
        code, out, _ = run("lsof", "-i", ":30010", "-sTCP:ESTABLISHED")
        return code == 0 and "ESTABLISHED" in out

    def _submit_with_password_fill() -> None:
        _focus_clear_paste(*password_field, password)
        _reset_activate()
        click_screen(*login_button)

    _focus_clear_paste(*email_field, email)
    _submit_with_password_fill()
    if _wait_until(_login_done, timeout_ms=6000):
        print(f"Login submitted ({email})")
        return

    _submit_with_password_fill()
    if _wait_until(_login_done, timeout_ms=6000):
        print(f"Login submitted ({email})")
        return
    print(f"Login submitted ({email})")
