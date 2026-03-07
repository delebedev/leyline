#!/usr/bin/env swift
// click.swift — Synthetic mouse click that works on macOS 15+ / Unity
//
// Usage:
//   swift click.swift <x> <y>           # left click at (x,y)
//   swift click.swift <x> <y> move      # move mouse to (x,y), no click
//   swift click.swift <x> <y> right     # right click
//   swift click.swift <x> <y> double    # double click
//
// The key insight: macOS Sequoia+ silently drops CGEvents with timestamp=0.
// We create events via CGEvent but set the timestamp from mach_absolute_time().
// We also post at kCGHIDEventTap (lowest level CGEvent tap) so Unity sees them.

import Foundation
import CoreGraphics

func usage() -> Never {
    fputs("""
    Usage: click <x> <y> [move|right|double|drag <x2> <y2>]

    Actions:
      (default)  left click
      move       move cursor only
      right      right click
      double     double left click
      drag       drag from (x,y) to (x2,y2)

    """, stderr)
    exit(1)
}

guard CommandLine.arguments.count >= 3,
      let x = Double(CommandLine.arguments[1]),
      let y = Double(CommandLine.arguments[2]) else {
    usage()
}

let action = CommandLine.arguments.count > 3 ? CommandLine.arguments[3] : "click"
let point = CGPoint(x: x, y: y)

func post(_ event: CGEvent?) {
    guard let event = event else {
        fputs("Failed to create CGEvent\n", stderr)
        exit(1)
    }
    event.timestamp = mach_absolute_time()
    event.post(tap: .cghidEventTap)
}

switch action {
case "move":
    post(CGEvent(mouseEventSource: nil, mouseType: .mouseMoved,
                 mouseCursorPosition: point, mouseButton: .left))
    print("moved to \(Int(x)),\(Int(y))")

case "right":
    post(CGEvent(mouseEventSource: nil, mouseType: .rightMouseDown,
                 mouseCursorPosition: point, mouseButton: .right))
    usleep(50_000) // 50ms
    post(CGEvent(mouseEventSource: nil, mouseType: .rightMouseUp,
                 mouseCursorPosition: point, mouseButton: .right))
    print("right-clicked \(Int(x)),\(Int(y))")

case "double":
    // First click
    let down1 = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown,
                        mouseCursorPosition: point, mouseButton: .left)
    down1?.setIntegerValueField(.mouseEventClickState, value: 1)
    post(down1)
    usleep(30_000)
    let up1 = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp,
                      mouseCursorPosition: point, mouseButton: .left)
    up1?.setIntegerValueField(.mouseEventClickState, value: 1)
    post(up1)
    usleep(50_000)
    // Second click
    let down2 = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown,
                        mouseCursorPosition: point, mouseButton: .left)
    down2?.setIntegerValueField(.mouseEventClickState, value: 2)
    post(down2)
    usleep(30_000)
    let up2 = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp,
                      mouseCursorPosition: point, mouseButton: .left)
    up2?.setIntegerValueField(.mouseEventClickState, value: 2)
    post(up2)
    print("double-clicked \(Int(x)),\(Int(y))")

case "drag":
    guard CommandLine.arguments.count >= 6,
          let x2 = Double(CommandLine.arguments[4]),
          let y2 = Double(CommandLine.arguments[5]) else {
        fputs("drag requires: click <x1> <y1> drag <x2> <y2>\n", stderr)
        exit(1)
    }
    let dest = CGPoint(x: x2, y: y2)
    let steps = 20
    let duration: UInt32 = 15_000 // 15ms per step (~300ms total drag)

    // Mouse down at source
    post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown,
                 mouseCursorPosition: point, mouseButton: .left))
    usleep(50_000) // hold before dragging

    // Intermediate drag events along the path
    for i in 1...steps {
        let t = Double(i) / Double(steps)
        let cx = x + (x2 - x) * t
        let cy = y + (y2 - y) * t
        let p = CGPoint(x: cx, y: cy)
        post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseDragged,
                     mouseCursorPosition: p, mouseButton: .left))
        usleep(duration)
    }

    // Mouse up at destination
    usleep(30_000)
    post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp,
                 mouseCursorPosition: dest, mouseButton: .left))
    print("dragged \(Int(x)),\(Int(y)) → \(Int(x2)),\(Int(y2))")

default: // "click" — left click
    post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown,
                 mouseCursorPosition: point, mouseButton: .left))
    usleep(50_000) // 50ms hold
    post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp,
                 mouseCursorPosition: point, mouseButton: .left))
    print("clicked \(Int(x)),\(Int(y))")
}

// Small delay to let the event propagate
usleep(10_000)
