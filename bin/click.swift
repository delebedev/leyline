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
    Usage: click <x> <y> [move|right|double]
    
    Actions:
      (default)  left click
      move       move cursor only
      right      right click
      double     double left click
    
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
