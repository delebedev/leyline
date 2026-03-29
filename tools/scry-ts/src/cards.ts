/**
 * Card name resolution from Arena's SQLite database.
 *
 * Finds the local Arena card DB (installed with the game client),
 * resolves grpId → card name. Caches all lookups.
 */

import { Database } from "bun:sqlite";
import { readdirSync } from "fs";
import { join } from "path";
import { homedir } from "os";

const ARENA_DB_DIR = join(
  homedir(),
  "Library/Application Support/com.wizards.mtga/Downloads/Raw"
);

const LOOKUP_SQL = `
  SELECT c.GrpId, l.Loc
  FROM Cards c
  JOIN Localizations_enUS l ON c.TitleId = l.LocId
  WHERE l.Formatted = 1 AND c.GrpId IN (`;

export function findArenaDb(): string | null {
  try {
    const files = readdirSync(ARENA_DB_DIR).filter((f) =>
      f.startsWith("Raw_CardDatabase_") && f.endsWith(".mtga")
    );
    if (files.length === 0) return null;
    files.sort();
    return join(ARENA_DB_DIR, files[files.length - 1]);
  } catch {
    return null;
  }
}

export class CardResolver {
  private db: Database;
  private cache = new Map<number, string>();

  constructor(dbPath: string) {
    this.db = new Database(dbPath, { readonly: true });
  }

  resolve(grpId: number): string | null {
    if (this.cache.has(grpId)) return this.cache.get(grpId)!;
    const result = this.resolveBatch([grpId]);
    return result.get(grpId) ?? null;
  }

  resolveBatch(grpIds: number[]): Map<number, string> {
    const uncached = grpIds.filter((id) => !this.cache.has(id));
    if (uncached.length > 0) {
      const placeholders = uncached.map(() => "?").join(",");
      const rows = this.db
        .query(`${LOOKUP_SQL}${placeholders})`)
        .all(...uncached) as { GrpId: number; Loc: string }[];
      for (const row of rows) {
        this.cache.set(row.GrpId, row.Loc);
      }
    }
    const result = new Map<number, string>();
    for (const id of grpIds) {
      const name = this.cache.get(id);
      if (name) result.set(id, name);
    }
    return result;
  }
}

export interface CardInfo {
  grpId: number;
  name: string;
  types: string[];
  subtypes: string[];
  power: string | null;
  toughness: string | null;
  isToken: boolean;
}

const RICH_SQL = `
  SELECT c.GrpId, l.Loc, c.Types, c.Subtypes, c.IsToken, c.Power, c.Toughness
  FROM Cards c
  JOIN Localizations_enUS l ON c.TitleId = l.LocId
  WHERE l.Formatted = 1 AND c.GrpId IN (`;

/** Resolve grpIds to full card info (types, subtypes, P/T). */
export function resolveCardInfo(db: Database, grpIds: number[]): Map<number, CardInfo> {
  if (grpIds.length === 0) return new Map();

  // Load type/subtype enums
  const typeMap = new Map<number, string>();
  const subtypeMap = new Map<number, string>();
  for (const row of db.query(
    "SELECT e.Value, l.Loc FROM Enums e JOIN Localizations_enUS l ON e.LocId = l.LocId WHERE e.Type = 'CardType' AND l.Formatted = 1"
  ).all() as { Value: number; Loc: string }[]) {
    typeMap.set(row.Value, row.Loc);
  }
  for (const row of db.query(
    "SELECT e.Value, l.Loc FROM Enums e JOIN Localizations_enUS l ON e.LocId = l.LocId WHERE e.Type = 'SubType' AND l.Formatted = 1"
  ).all() as { Value: number; Loc: string }[]) {
    subtypeMap.set(row.Value, row.Loc);
  }

  const placeholders = grpIds.map(() => "?").join(",");
  const rows = db.query(`${RICH_SQL}${placeholders})`).all(...grpIds) as {
    GrpId: number; Loc: string; Types: string | null; Subtypes: string | null;
    IsToken: number; Power: string | null; Toughness: string | null;
  }[];

  const result = new Map<number, CardInfo>();
  for (const row of rows) {
    const types = (row.Types ?? "").split(",").filter(Boolean).map((t) => typeMap.get(parseInt(t.trim())) ?? t.trim());
    const subtypes = (row.Subtypes ?? "").split(",").filter(Boolean).map((t) => subtypeMap.get(parseInt(t.trim())) ?? t.trim());
    result.set(row.GrpId, {
      grpId: row.GrpId,
      name: row.Loc.replace(/<[^>]+>/g, ""),
      types,
      subtypes,
      power: row.Power,
      toughness: row.Toughness,
      isToken: row.IsToken === 1,
    });
  }
  return result;
}

/** Shared singleton — null if Arena DB not found. */
let _resolver: CardResolver | null | undefined;

export function getResolver(): CardResolver | null {
  if (_resolver !== undefined) return _resolver;
  const dbPath = findArenaDb();
  _resolver = dbPath ? new CardResolver(dbPath) : null;
  return _resolver;
}
