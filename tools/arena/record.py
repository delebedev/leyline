#!/usr/bin/env python3
"""Record MTGA window as a compact MP4 video for bug reproduction.

Usage:
    arena-record [--duration 10] [--out /tmp/repro.mp4] [--fps 5] [--open]

Captures frames via screencapture -R (exact window rect), resizes to
logical size, stitches with ffmpeg into H.264 MP4.
Output is GitHub-embeddable (~0.3-1.5MB per 10s).
"""
import argparse
import signal
import subprocess
import sys
import time
from pathlib import Path

FRAME_DIR = Path("/tmp/arena/_record_frames")
_stop_recording = False


def _handle_signal(signum, frame):
    """Handle SIGTERM/SIGINT — stop capturing, stitch whatever we have."""
    global _stop_recording
    _stop_recording = True
    print(f"\nRecording stopped (signal {signum})", file=sys.stderr)


def get_window_bounds():
    """Get MTGA window bounds (x, y, w, h) in logical points via osascript."""
    r = subprocess.run(
        ["osascript", "-e",
         'tell application "System Events" to tell process "MTGA" '
         'to get {position, size} of window 1'],
        capture_output=True, text=True,
    )
    if r.returncode != 0 or not r.stdout.strip():
        return None
    parts = [int(p.strip()) for p in r.stdout.strip().split(",")]
    if len(parts) != 4:
        return None
    return tuple(parts)


def check_screen_locked():
    """Check if screen saver is running (Mac is locked)."""
    r = subprocess.run(
        ["osascript", "-e",
         'tell application "System Events" to return running of screen saver preferences'],
        capture_output=True, text=True,
    )
    return r.stdout.strip() == "true"


def activate_mtga():
    subprocess.run(
        ["osascript", "-e", 'tell application "MTGA" to activate'],
        capture_output=True,
    )
    time.sleep(0.3)


def capture_frames(duration, fps, bounds):
    """Capture individual frames using screencapture -R."""
    FRAME_DIR.mkdir(parents=True, exist_ok=True)
    for f in FRAME_DIR.glob("*.png"):
        f.unlink()

    interval = 1.0 / fps
    total_frames = int(duration * fps)
    x, y, w, h = bounds

    print(f"Recording {duration}s at {fps}fps ({total_frames} frames)...", file=sys.stderr)

    for i in range(total_frames):
        if _stop_recording:
            break

        frame_path = FRAME_DIR / f"frame_{i:05d}.png"
        t_start = time.time()

        # Capture exact window rect
        subprocess.run(
            ["screencapture", "-R", f"{x},{y},{w},{h}", "-x", str(frame_path)],
            capture_output=True,
        )

        # Resize to logical (Retina captures at 2x)
        subprocess.run(
            ["sips", "--resampleWidth", str(w), str(frame_path),
             "--out", str(frame_path)],
            capture_output=True,
        )

        if (i + 1) % fps == 0:
            print(f"  {(i + 1) // fps}s / {duration}s", file=sys.stderr)

        elapsed = time.time() - t_start
        remaining = interval - elapsed
        if remaining > 0 and not _stop_recording:
            time.sleep(remaining)

    actual = len(list(FRAME_DIR.glob("*.png")))
    print(f"Captured {actual} frames", file=sys.stderr)
    return actual


def stitch(out_path, fps):
    """Stitch frames into MP4 with ffmpeg."""
    print("Encoding MP4...", file=sys.stderr)

    cmd = [
        "ffmpeg", "-y",
        "-framerate", str(fps),
        "-i", str(FRAME_DIR / "frame_%05d.png"),
        "-c:v", "libx264",
        "-preset", "fast",
        "-crf", "28",
        "-pix_fmt", "yuv420p",
        # Ensure even dimensions (libx264 requirement)
        "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
        "-movflags", "+faststart",
        out_path,
    ]

    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"Encode failed: {r.stderr[-500:]}", file=sys.stderr)
        sys.exit(1)

    for f in FRAME_DIR.glob("*.png"):
        f.unlink()

    size_kb = Path(out_path).stat().st_size / 1024
    print(f"{out_path} ({size_kb:.0f}KB)", flush=True)


def main():
    parser = argparse.ArgumentParser(description="Record MTGA window as MP4")
    parser.add_argument("--duration", "-d", type=int, default=10,
                        help="Duration in seconds (default: 10)")
    parser.add_argument("--out", "-o", default="/tmp/arena-repro.mp4",
                        help="Output path")
    parser.add_argument("--fps", type=int, default=5,
                        help="Frames per second (default: 5)")
    parser.add_argument("--open", action="store_true",
                        help="Open result in QuickTime")
    args = parser.parse_args()

    # Handle SIGTERM/SIGINT — stop capturing and stitch whatever we have
    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)

    if check_screen_locked():
        print("WARNING: Screen locked — recording will show screen saver",
              file=sys.stderr)

    bounds = get_window_bounds()
    if bounds is None:
        print("MTGA window not found", file=sys.stderr)
        sys.exit(1)

    activate_mtga()
    n = capture_frames(args.duration, args.fps, bounds)
    if n == 0:
        print("No frames captured", file=sys.stderr)
        sys.exit(1)

    stitch(args.out, args.fps)

    if args.open:
        subprocess.run(["open", args.out])


if __name__ == "__main__":
    main()
