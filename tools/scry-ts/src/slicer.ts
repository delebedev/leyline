/**
 * Game slicer — extracts raw line ranges for each game from Player.log.
 *
 * Uses the same patterns as parser.ts but tracks line indices
 * so we can save verbatim log slices.
 */

const GRE_HEADER = /\[UnityCrossThreadLogger\](\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}:\d{2}): Match to ([0-9a-zA-Z_-]+): GreToClientEvent/;
const CONNECT_RESP = /"GREMessageType_ConnectResp"/;
const GAME_RESULT = /"ResultType_WinLoss"|"ResultType_Draw"/;

export interface GameSlice {
  startLine: number;
  endLine: number;
  startTimestamp: string | null;
  matchId: string | null;
  hasResult: boolean;
}

/**
 * Detect game boundaries and return line ranges.
 * Each game starts at the GRE header line containing ConnectResp
 * and ends at the line before the next ConnectResp or EOF.
 */
export function sliceGames(lines: string[]): GameSlice[] {
  const slices: GameSlice[] = [];
  let current: GameSlice | null = null;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Check for GRE header
    const greMatch = GRE_HEADER.exec(line);
    if (greMatch && i + 1 < lines.length) {
      const nextLine = lines[i + 1];
      if (CONNECT_RESP.test(nextLine)) {
        // New game — close previous
        if (current) {
          current.endLine = i - 1;
          slices.push(current);
        }
        current = {
          startLine: i,
          endLine: lines.length - 1,
          startTimestamp: greMatch[1],
          matchId: greMatch[2],
          hasResult: false,
        };
      }
    }

    // Track match ID from gameInfo
    if (current && greMatch) {
      current.matchId = greMatch[2];
    }

    // Detect game result
    if (current && GAME_RESULT.test(line)) {
      current.hasResult = true;
    }
  }

  // Push last game
  if (current) {
    current.endLine = lines.length - 1;
    slices.push(current);
  }

  return slices;
}
