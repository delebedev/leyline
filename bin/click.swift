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
import ApplicationServices

func usage() -> Never {
    fputs("""
    Usage: click <x> <y> [move|right|double|drag <x2> <y2>|scroll <delta>|hover <ms>]

    Actions:
      (default)  left click
      move       move cursor only
      right      right click
      double     double left click
      drag       drag from (x,y) to (x2,y2)
      scroll     scroll wheel at (x,y); delta>0 = up, <0 = down
      hover      move to (x,y) and hold for <ms> milliseconds

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

if !AXIsProcessTrusted() {
    fputs("WARNING: not accessibility-trusted — clicks will silently fail\n", stderr)
}

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
    // CGWarpMouseCursorPosition actually moves the visible cursor.
    // CGEvent(.mouseMoved) only posts an event — cursor stays put.
    CGWarpMouseCursorPosition(point)
    // Post a mouseMoved event too so apps track the new position.
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

case "scroll":
    guard CommandLine.arguments.count >= 5,
          let delta = Int32(CommandLine.arguments[4]) else {
        fputs("scroll requires: click <x> <y> scroll <delta>\n", stderr)
        exit(1)
    }
    CGWarpMouseCursorPosition(point)
    post(CGEvent(mouseEventSource: nil, mouseType: .mouseMoved,
                 mouseCursorPosition: point, mouseButton: .left))
    usleep(50_000)
    let scrollEvent = CGEvent(scrollWheelEvent2Source: nil,
                              units: .pixel,
                              wheelCount: 1,
                              wheel1: delta, wheel2: 0, wheel3: 0)
    post(scrollEvent)
    print("scrolled \(delta) at \(Int(x)),\(Int(y))")

case "hover":
    guard CommandLine.arguments.count >= 5,
          let ms = UInt32(CommandLine.arguments[4]) else {
        fputs("hover requires: click <x> <y> hover <milliseconds>\n", stderr)
        exit(1)
    }
    CGWarpMouseCursorPosition(point)
    post(CGEvent(mouseEventSource: nil, mouseType: .mouseMoved,
                 mouseCursorPosition: point, mouseButton: .left))
    usleep(ms * 1000)
    print("hovered \(Int(x)),\(Int(y)) for \(ms)ms")

case "drag":
    guard CommandLine.arguments.count >= 6,
          let x2 = Double(CommandLine.arguments[4]),
          let y2 = Double(CommandLine.arguments[5]) else {
        fputs("drag requires: click <x1> <y1> drag <x2> <y2>\n", stderr)
        exit(1)
    }
    let dest = CGPoint(x: x2, y: y2)
    let steps = 30
    let totalDragMs: Double = 500 // 500ms total drag time

    // 1. Pre-move cursor to source (Unity ignores events from unexpected positions)
    //    500ms settle: hand cards fan/zoom on hover — must wait for animation
    post(CGEvent(mouseEventSource: nil, mouseType: .mouseMoved,
                 mouseCursorPosition: point, mouseButton: .left))
    usleep(500_000) // 500ms settle for card hover animation

    // 2. Mouse down at source with longer hold
    post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown,
                 mouseCursorPosition: point, mouseButton: .left))
    usleep(150_000) // 150ms hold — Unity needs time to register the grab

    // 3. Ease-in/ease-out drag path (smoothstep curve)
    for i in 1...steps {
        let linear = Double(i) / Double(steps)
        // smoothstep: 3t² - 2t³ — slow start, fast middle, slow end
        let t = linear * linear * (3.0 - 2.0 * linear)
        let cx = x + (x2 - x) * t
        let cy = y + (y2 - y) * t
        let p = CGPoint(x: cx, y: cy)
        post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseDragged,
                     mouseCursorPosition: p, mouseButton: .left))
        // Variable timing: slower at start/end (ease curve applied to sleep too)
        let sleepFactor = 0.6 + 0.8 * (1.0 - abs(linear - 0.5) * 2.0)
        let baseSleep = totalDragMs / Double(steps) * 1000.0
        usleep(UInt32(baseSleep * sleepFactor))
    }

    // 4. Hold at destination before release (let Unity settle the drop zone)
    usleep(120_000) // 120ms hold at destination

    // 5. Mouse up at destination
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
