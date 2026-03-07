#!/usr/bin/env swift
// detect.swift — Card detection using CoreML object detection model
//
// Usage:
//   swift detect.swift <image-path> [--threshold 0.3] [--model path/to/model.mlmodel]
//
// Output: JSON array of detections:
//   [{"label":"hand-card","x":400,"y":530,"w":120,"h":170,"cx":460,"cy":615,"confidence":0.85}]
//
// Coordinates are in image pixels (origin top-left), same space as ocr.swift.
// If the model file doesn't exist, prints [] and exits 0 (graceful degradation).

import AppKit
import CoreML
import Foundation
import Vision

// --- Parse args ---

let args = Array(CommandLine.arguments.dropFirst())
var imagePath: String?
var threshold: Float = 0.3
var modelPath: String?

var i = 0
while i < args.count {
    switch args[i] {
    case "--threshold":
        i += 1
        if i < args.count { threshold = Float(args[i]) ?? 0.3 }
    case "--model":
        i += 1
        if i < args.count { modelPath = args[i] }
    default:
        if !args[i].starts(with: "-") && imagePath == nil {
            imagePath = args[i]
        }
    }
    i += 1
}

guard let imgPath = imagePath else {
    fputs("Usage: detect <image-path> [--threshold 0.3] [--model path/to/model.mlmodel]\n", stderr)
    exit(1)
}

// --- Resolve model path ---

// Default: look next to the binary, then in bin/models/
let scriptDir = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent().path
let defaultPaths = [
    modelPath,
    "\(scriptDir)/models/card_detector.mlmodel",
    "\(scriptDir)/../data/models/card_detector_v2.mlmodel",
].compactMap { $0 }

var resolvedModel: String?
for p in defaultPaths {
    if FileManager.default.fileExists(atPath: p) {
        resolvedModel = p
        break
    }
}

guard let finalModelPath = resolvedModel else {
    // Graceful degradation: no model available, return empty
    print("[]")
    exit(0)
}

// --- Load model ---

let modelURL = URL(fileURLWithPath: finalModelPath)
let compiledURL: URL
do {
    compiledURL = try MLModel.compileModel(at: modelURL)
} catch {
    fputs("Error: cannot compile model: \(error)\n", stderr)
    print("[]")
    exit(0)
}

let mlModel: MLModel
let vnModel: VNCoreMLModel
do {
    mlModel = try MLModel(contentsOf: compiledURL)
    vnModel = try VNCoreMLModel(for: mlModel)
} catch {
    fputs("Error: cannot load model: \(error)\n", stderr)
    print("[]")
    exit(0)
}

// --- Load image ---

guard let image = NSImage(contentsOfFile: imgPath),
      let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil)
else {
    fputs("Error: cannot load image: \(imgPath)\n", stderr)
    print("[]")
    exit(1)
}

let imgW = CGFloat(cgImage.width)
let imgH = CGFloat(cgImage.height)

// --- Run detection ---

let request = VNCoreMLRequest(model: vnModel)
request.imageCropAndScaleOption = .scaleFill

let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
do {
    try handler.perform([request])
} catch {
    fputs("Error: detection failed: \(error)\n", stderr)
    print("[]")
    exit(0)
}

guard let results = request.results as? [VNRecognizedObjectObservation] else {
    print("[]")
    exit(0)
}

// --- Build output ---

var detections: [[String: Any]] = []
for obs in results {
    guard obs.confidence >= threshold else { continue }
    guard let label = obs.labels.first else { continue }

    // Vision bbox: normalized, origin bottom-left → convert to pixels, top-left
    let bbox = obs.boundingBox
    let x = Int(bbox.origin.x * imgW)
    let y = Int((1.0 - bbox.origin.y - bbox.height) * imgH)
    let w = Int(bbox.width * imgW)
    let h = Int(bbox.height * imgH)

    detections.append([
        "label": label.identifier,
        "x": x,
        "y": y,
        "w": w,
        "h": h,
        "cx": x + w / 2,
        "cy": y + h / 2,
        "confidence": round(Double(obs.confidence) * 1000) / 1000,
    ])
}

// Sort top-to-bottom, left-to-right (same as ocr.swift)
detections.sort { a, b in
    let ay = a["cy"] as! Int, by = b["cy"] as! Int
    if abs(ay - by) > 30 { return ay < by }
    return (a["cx"] as! Int) < (b["cx"] as! Int)
}

// --- Output JSON ---

if let data = try? JSONSerialization.data(withJSONObject: detections),
   let str = String(data: data, encoding: .utf8)
{
    print(str)
} else {
    print("[]")
}
