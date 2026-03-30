import { Database } from "bun:sqlite";
import { findArenaDb, resolveAbility } from "../cards";

export async function abilityCommand(args: string[]) {
  if (!args[0] || args[0] === "--help" || args[0] === "-h") {
    console.log("Usage: scry ability <abilityGrpId>\n");
    console.log("Resolve an abilityGrpId to its text and source card.");
    return;
  }

  const id = parseInt(args[0], 10);
  if (isNaN(id)) {
    console.error(`Invalid abilityGrpId: ${args[0]}`);
    process.exit(1);
  }

  const dbPath = findArenaDb();
  if (!dbPath) {
    console.error("Arena card DB not found. Launch Arena at least once.");
    process.exit(1);
  }

  const db = new Database(dbPath, { readonly: true });
  const result = resolveAbility(db, id);

  if (!result) {
    console.error(`Ability ${id} not found in Arena DB`);
    process.exit(1);
  }

  if (result.cardName) {
    console.log(`${result.cardName}: ${result.text}`);
  } else {
    console.log(result.text);
  }
}
