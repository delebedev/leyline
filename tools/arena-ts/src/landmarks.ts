// src/landmarks.ts
// Named coordinates in 960px reference space.
// Reusable aliases for common UI targets. All coords assume 960x~568 logical.

export const landmarks: Record<string, [number, number]> = {
  // Home screen
  "home-cta":       [866, 533],   // big Play / Start button (bottom right)

  // Top nav bar
  "nav-profile":    [108, 57],
  "nav-decks":      [157, 57],
  "nav-packs":      [207, 57],
  "nav-mastery":    [307, 57],
  "nav-quests":     [366, 57],
  "nav-back":       [51, 57],     // back arrow (Profile, Decks, etc.)

  // Play blade
  "blade-close":    [746, 93],    // X to close play blade
  "bot-match-card": [866, 350],   // Bot Match card body (opens deck selector)
  "bot-play":       [866, 534],   // Play in bot match selector
  "bot-close":      [746, 94],    // X close deck selector

  // Format tabs (Find Match / deck selector)
  "fmt-ranked":     [805, 192],   // Ranked tab
  "fmt-play":       [866, 192],   // Play tab
  "fmt-brawl":      [928, 192],   // Brawl tab

  // DeckListViewer
  "deck-new":       [96, 260],    // "+" new deck card
  "deck-edit":      [865, 532],   // Edit Deck button
  "deck-export":    [322, 530],   // Export icon
  "deck-import":    [385, 530],   // Import icon
  "deck-clone":     [445, 530],   // Clone icon
  "deck-delete":    [520, 530],   // Delete icon
  "deck-details":   [202, 530],   // Stats/details icon
  "deck-favorite":  [262, 530],   // Heart icon
  "deck-collection":[117, 532],   // Collection button

  // DeckBuilder
  "builder-done":   [867, 533],   // Done / save & exit
  "builder-search": [53, 99],     // Search collection
  "builder-format": [852, 98],    // Format dropdown (new deck)

  // In-game
  "action-btn":     [888, 504],   // universal: Pass/Next/End Turn/Resolve/All Attack
  "game-menu":      [940, 42],    // gear icon (top right)
  "game-concede":   [480, 344],   // Concede in Options overlay
  "opponent-face":  [480, 85],    // targeting opponent

  // Export dialog
  "export-ok":      [480, 340],   // dismiss export dialog

  // Dismiss / generic
  "dismiss":        [210, 482],   // generic dismiss (modals, results)
  "center":         [480, 284],   // screen center
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
