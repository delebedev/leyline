import CoreGraphics
import Foundation

// Finds MTGA window via CGWindowList. Outputs "x,y,w,h" if on-screen, nothing otherwise.
// No accessibility permissions needed — CGWindowList is public API.

let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []

for w in windows {
    guard let owner = w[kCGWindowOwnerName as String] as? String, owner == "MTGA",
          let bounds = w[kCGWindowBounds as String] as? [String: CGFloat] else { continue }
    let x = Int(bounds["X"] ?? 0)
    let y = Int(bounds["Y"] ?? 0)
    let width = Int(bounds["Width"] ?? 0)
    let height = Int(bounds["Height"] ?? 0)
    guard width > 0, height > 0 else { continue }
    print("\(x),\(y),\(width),\(height)")
    exit(0)
}
// MTGA not visible — no output
