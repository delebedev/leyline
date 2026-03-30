// src/landmarks.ts
// Named coordinates in 960px reference space.
// Reusable aliases for common UI targets. All coords assume 960x~568 logical.

export const landmarks: Record<string, [number, number]> = {
  // Home screen
  "home-cta":       [866, 533],   // big Play / Start button (bottom right)
  "home-play":      [866, 533],   // alias

  // Top nav bar
  "nav-profile":    [108, 57],
  "nav-decks":      [157, 57],
  "nav-packs":      [207, 57],
  "nav-mastery":    [307, 57],
  "nav-quests":     [366, 57],
  "nav-back":       [51, 57],     // back arrow (Profile, Decks, etc.)

  // Play blade
  "blade-close":    [746, 93],    // X to close play blade
  "center":         [480, 284],   // screen center

  // In-game
  "game-menu":      [940, 42],    // gear icon (top right)
  "pass-btn":       [887, 491],   // pass priority / resolve
  "pass-btn-2":     [890, 510],   // second pass click (Sparky 2-click pattern)

  // Dismiss / generic
  "dismiss":        [210, 482],   // generic dismiss click (modals, results)
};

/** Resolve a landmark name or "x,y" string to [x, y]. Returns null if unrecognized. */
export function resolve(target: string): [number, number] | null {
  // Named landmark
  if (landmarks[target]) return landmarks[target];

  // Coordinate pair: "480,300" or "480, 300"
  const match = target.match(/^(\d+)\s*,\s*(\d+)$/);
  if (match) return [parseInt(match[1]), parseInt(match[2])];

  return null;
}
