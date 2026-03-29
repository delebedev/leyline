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

/** Shared singleton — null if Arena DB not found. */
let _resolver: CardResolver | null | undefined;

export function getResolver(): CardResolver | null {
  if (_resolver !== undefined) return _resolver;
  const dbPath = findArenaDb();
  _resolver = dbPath ? new CardResolver(dbPath) : null;
  return _resolver;
}
