import { existsSync, readFileSync } from "fs";
import { resolve } from "path";
import { homedir } from "os";

export type GameSource = "real" | "leyline" | "puzzle" | "unknown";
export type ProvenanceConfidence = "explicit" | "inferred" | "unknown";

export interface Provenance {
  source: GameSource;
  confidence: ProvenanceConfidence;
  matchId: string | null;
  eventName?: string | null;
  puzzleRef?: string | null;
  recordedAt?: string | null;
}

interface LeylineSessionRecord {
  ts: string;
  matchId: string;
  source: "leyline" | "puzzle";
  eventName?: string | null;
  puzzleRef?: string | null;
}

const SESSION_JOURNAL = resolve(homedir(), ".scry/leyline-sessions.jsonl");
const ALL_SOURCES: GameSource[] = ["real", "leyline", "puzzle", "unknown"];
const DEFAULT_SAVED_SOURCES: GameSource[] = ["real", "unknown"];
const PROVENANCE_SUMMARY_SEPARATOR = "  ";

export function loadLeylineSessions(path: string = SESSION_JOURNAL): LeylineSessionRecord[] {
  if (!existsSync(path)) return [];
  const lines = readFileSync(path, "utf-8")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

  const sessions: LeylineSessionRecord[] = [];
  for (const line of lines) {
    try {
      const parsed = JSON.parse(line);
      if (!parsed?.matchId || (parsed.source !== "leyline" && parsed.source !== "puzzle")) continue;
      sessions.push({
        ts: parsed.ts ?? "",
        matchId: parsed.matchId,
        source: parsed.source,
        eventName: parsed.eventName ?? null,
        puzzleRef: parsed.puzzleRef ?? null,
      });
    } catch {}
  }
  return sessions;
}

export function inferProvenance(
  matchId: string | null,
  sessions: LeylineSessionRecord[],
): Provenance {
  if (matchId) {
    for (let i = sessions.length - 1; i >= 0; i--) {
      const session = sessions[i];
      if (session.matchId !== matchId) continue;
      return {
        source: session.source,
        confidence: "explicit",
        matchId,
        eventName: session.eventName ?? null,
        puzzleRef: session.puzzleRef ?? null,
        recordedAt: session.ts,
      };
    }
  }
  return {
    source: "real",
    confidence: "inferred",
    matchId,
  };
}

function parseGameSource(value: string): GameSource | null {
  return ALL_SOURCES.includes(value as GameSource) ? value as GameSource : null;
}

export function parseSavedSourceFilter(args: string[]): Set<GameSource> {
  if (args.includes("--all")) {
    return new Set(ALL_SOURCES);
  }

  const idx = args.indexOf("--source");
  if (idx === -1) {
    return new Set(DEFAULT_SAVED_SOURCES);
  }
  if (idx === args.length - 1) {
    console.error("Missing value for --source");
    process.exit(1);
  }

  const raw = args[idx + 1];
  if (!raw || raw.startsWith("-")) {
    console.error("Missing value for --source");
    process.exit(1);
  }
  if (raw === "any") {
    return new Set(ALL_SOURCES);
  }

  const parsed = raw.split(",")
    .map((part) => part.trim())
    .filter(Boolean)
    .map(parseGameSource);

  if (parsed.length === 0 || parsed.some((value) => value == null)) {
    console.error(`Invalid --source value: ${raw}`);
    console.error(`Expected one of: ${ALL_SOURCES.join(", ")}, any`);
    process.exit(1);
  }

  return new Set(parsed as GameSource[]);
}

export function sourceOf(meta: { provenance?: Provenance }): GameSource {
  return meta.provenance?.source ?? "unknown";
}

export function matchesSource(meta: { provenance?: Provenance }, allowedSources: Set<GameSource>): boolean {
  return allowedSources.has(sourceOf(meta));
}

export function formatSourceBadge(provenance?: Provenance): string {
  const source = provenance?.source ?? "unknown";
  if (source === "puzzle" && provenance?.puzzleRef) {
    return `puzzle:${provenance.puzzleRef}`;
  }
  return source;
}

export function formatProvenanceSummary(provenance?: Provenance): string {
  if (!provenance) return "unknown";
  const parts = [formatSourceBadge(provenance), provenance.confidence];
  if (provenance.eventName) parts.push(provenance.eventName);
  return parts.join(PROVENANCE_SUMMARY_SEPARATOR);
}
