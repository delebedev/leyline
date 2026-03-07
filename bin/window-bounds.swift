// window-bounds.swift — Print MTGA window bounds as "x y w h" (logical points)
// Compiled: swiftc -O -o bin/window-bounds bin/window-bounds.swift

import CoreGraphics

let list = CGWindowListCopyWindowInfo(.optionAll, kCGNullWindowID) as! [[String: Any]]
var best = (0, 0, 0, 0)
var bestArea = 0
for w in list {
    let owner = w["kCGWindowOwnerName"] as? String ?? ""
    if owner.contains("MTGA") {
        let b = w["kCGWindowBounds"] as? [String: Any] ?? [:]
        let x = b["X"] as? Int ?? 0
        let y = b["Y"] as? Int ?? 0
        let ww = b["Width"] as? Int ?? 0
        let h = b["Height"] as? Int ?? 0
        if ww * h > bestArea { bestArea = ww * h; best = (x, y, ww, h) }
    }
}
if bestArea > 0 {
    print("\(best.0) \(best.1) \(best.2) \(best.3)")
} else {
    exit(1)
}
