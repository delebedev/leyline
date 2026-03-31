// src/landmarks.ts
// Named coordinates in 960×540 game-content reference space.
// (0,0) = top-left of game area (below macOS title bar).
// scaleToScreen adds the title bar offset automatically.

export const landmarks: Record<string, [number, number]> = {
  // Home screen
  "home-cta":       [866, 506],   // big Play / Start button (bottom right)

  // Top nav bar
  "nav-profile":    [108, 43],
  "nav-decks":      [157, 43],
  "nav-packs":      [207, 43],
  "nav-mastery":    [307, 43],
  "nav-quests":     [366, 43],
  "nav-back":       [51, 43],     // back arrow (Profile, Decks, etc.)

  // Play blade
  "blade-close":    [746, 79],    // X to close play blade
  "bot-match-card": [866, 336],   // Bot Match card body (opens deck selector)
  "bot-play":       [866, 520],   // Play in bot match selector
  "bot-close":      [746, 80],    // X close deck selector

  // Format tabs (Find Match / deck selector)
  "fmt-ranked":     [805, 165],   // Ranked tab
  "fmt-play":       [866, 165],   // Play tab
  "fmt-brawl":      [928, 165],   // Brawl tab

  // DeckListViewer
  "deck-new":       [96, 246],    // "+" new deck card
  "deck-edit":      [865, 518],   // Edit Deck button
  "deck-export":    [322, 516],   // Export icon
  "deck-import":    [385, 516],   // Import icon
  "deck-clone":     [445, 516],   // Clone icon
  "deck-delete":    [520, 516],   // Delete icon
  "deck-details":   [202, 516],   // Stats/details icon
  "deck-favorite":  [262, 516],   // Heart icon
  "deck-collection":[117, 518],   // Collection button

  // DeckBuilder
  "builder-done":   [867, 519],   // Done / save & exit
  "builder-search": [53, 85],     // Search collection
  "builder-format": [852, 84],    // Format dropdown (new deck)

  // In-game
  "action-btn":     [888, 490],   // universal: Pass/Next/End Turn/Resolve/All Attack
  "game-menu":      [940, 28],    // gear icon (top right)
  "game-concede":   [480, 330],   // Concede in Options overlay
  "opponent-face":  [480, 71],    // targeting opponent

  // Export dialog
  "export-ok":      [480, 326],   // dismiss export dialog

  // Dismiss / generic
  "dismiss":        [210, 468],   // generic dismiss (modals, results)
  "center":         [480, 270],   // screen center
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
