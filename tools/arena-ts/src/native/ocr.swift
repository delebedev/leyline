#!/usr/bin/env swift
// ocr.swift — Detect text in a screenshot using macOS Vision framework
//
// Usage:
//   swift ocr.swift <image-path>              # all detected text + bounding boxes
//   swift ocr.swift <image-path> --json       # JSON output for scripting
//   swift ocr.swift <image-path> --find "Play" # find specific text, return center coords
//
// Output (default): one line per detection: "text<TAB>cx<TAB>cy<TAB>x<TAB>y<TAB>w<TAB>h<TAB>confidence"
// Coordinates are in image pixels (origin top-left).

import Foundation
import Vision
import AppKit

func usage() -> Never {
    fputs("""
    Usage: ocr <image-path> [--json] [--find "text"] [--exact] [--min-confidence 0.5]

    Detects text in an image using macOS Vision OCR.
    Coordinates are in image pixels (origin: top-left).

    Options:
      --json              JSON output
      --find <text>       Find specific text (case-insensitive substring), return center coords
      --exact             With --find: match whole text only (not substring)
      --min-confidence N  Minimum confidence threshold (0.0-1.0, default 0.3)

    """, stderr)
    exit(1)
}

// --- Parse args ---

let args = Array(CommandLine.arguments.dropFirst())
guard let imagePath = args.first, !imagePath.starts(with: "-") else { usage() }

var jsonOutput = false
var findText: String? = nil
var exactMatch = false
var minConfidence: Float = 0.3

var i = 1
while i < args.count {
    switch args[i] {
    case "--json": jsonOutput = true
    case "--exact": exactMatch = true
    case "--find":
        i += 1
        guard i < args.count else { usage() }
        findText = args[i].lowercased()
    case "--min-confidence":
        i += 1
        guard i < args.count, let v = Float(args[i]) else { usage() }
        minConfidence = v
    default: break
    }
    i += 1
}

// --- Load image ---

guard let image = NSImage(contentsOfFile: imagePath) else {
    fputs("Error: cannot load image at \(imagePath)\n", stderr)
    exit(1)
}

guard let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
    fputs("Error: cannot create CGImage\n", stderr)
    exit(1)
}

let imgW = CGFloat(cgImage.width)
let imgH = CGFloat(cgImage.height)

// --- Run OCR ---

let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = true

let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
do {
    try handler.perform([request])
} catch {
    fputs("Error: OCR failed — \(error)\n", stderr)
    exit(1)
}

guard let results = request.results else {
    fputs("No results\n", stderr)
    exit(0)
}

// --- Process results ---

struct Detection {
    let text: String
    let cx: Int      // center x (pixels, top-left origin)
    let cy: Int      // center y
    let x: Int       // bbox origin x
    let y: Int       // bbox origin y
    let w: Int       // bbox width
    let h: Int       // bbox height
    let confidence: Float
}

var detections: [Detection] = []

for observation in results {
    guard observation.confidence >= minConfidence else { continue }
    guard let candidate = observation.topCandidates(1).first else { continue }

    // Vision bbox is normalized, origin bottom-left
    let bbox = observation.boundingBox
    let px = bbox.origin.x * imgW
    let py = (1.0 - bbox.origin.y - bbox.height) * imgH  // flip to top-left origin
    let pw = bbox.width * imgW
    let ph = bbox.height * imgH

    detections.append(Detection(
        text: candidate.string,
        cx: Int(px + pw / 2),
        cy: Int(py + ph / 2),
        x: Int(px),
        y: Int(py),
        w: Int(pw),
        h: Int(ph),
        confidence: candidate.confidence
    ))
}

// Sort top-to-bottom, left-to-right
detections.sort { a, b in
    if abs(a.cy - b.cy) > 20 { return a.cy < b.cy }
    return a.cx < b.cx
}

// --- Filter if --find ---

if let needle = findText {
    if exactMatch {
        detections = detections.filter { $0.text.lowercased() == needle }
    } else {
        detections = detections.filter { $0.text.lowercased().contains(needle) }
    }
    if detections.isEmpty {
        fputs("Not found: \"\(needle)\"\n", stderr)
        exit(1)
    }
}

// --- Output ---

if jsonOutput {
    let items = detections.map { d in
        """
        {"text":"\(d.text.replacingOccurrences(of: "\"", with: "\\\""))","cx":\(d.cx),"cy":\(d.cy),"x":\(d.x),"y":\(d.y),"w":\(d.w),"h":\(d.h),"confidence":\(String(format:"%.2f", d.confidence))}
        """
    }
    print("[\(items.joined(separator: ","))]")
} else {
    for d in detections {
        print("\(d.text)\t\(d.cx)\t\(d.cy)\t\(d.x)\t\(d.y)\t\(d.w)\t\(d.h)\t\(String(format:"%.2f", d.confidence))")
    }
}
