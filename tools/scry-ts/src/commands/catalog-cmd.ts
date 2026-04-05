/**
 * `scry catalog` — generate protocol reference from saved game corpus.
 *
 * Orchestrates corpus collection, profile building, card enrichment,
 * dictionary extraction, and file output to docs/protocol/.
 */

import { resolve } from "path";
import {
  collectCorpus,
  buildAnnotationProfile,
  buildPromptProfile,
  buildActionProfile,
  enrichCards,
} from "../catalog-builder";
import {
  writeAnnotations,
  writePrompts,
  writeActions,
  writeDictionaries,
  writeIndex,
  buildDictionaries,
  collectDictionaryValues,
} from "../catalog-writer";
import { getResolver } from "../cards";
import { parseSavedSourceFilter } from "../provenance";

/** Repo root: up 4 levels from src/commands/ */
const REPO_ROOT = resolve(import.meta.dir, "../../../..");
const DEFAULT_OUT = resolve(REPO_ROOT, "docs/protocol");

export async function catalogCommand(args: string[]) {
  if (args[0] === "--help" || args[0] === "-h") {
    console.log("Usage: scry catalog [--out DIR] [--dry-run] [--source SRC] [--markdown]\n");
    console.log("Generate protocol reference from saved game corpus.\n");
    console.log("Options:");
    console.log("  --out DIR       Output directory (default: docs/protocol/)");
    console.log("  --dry-run       Preview stats without writing files");
    console.log("  --source SRC    Game sources (default: real,unknown)");
    console.log("  --markdown      Markdown output (not yet implemented)");
    return;
  }

  // Parse flags
  const dryRun = args.includes("--dry-run");
  const markdown = args.includes("--markdown");
  const outDir = parseFlag(args, "--out") ?? DEFAULT_OUT;

  if (markdown) {
    console.log("--markdown is not yet implemented.");
    return;
  }

  // Source filter — inject --source into args for parseSavedSourceFilter
  const sourceRaw = parseFlag(args, "--source");
  const sourceArgs = sourceRaw ? ["--source", sourceRaw] : [];
  const sourceFilter = parseSavedSourceFilter(sourceArgs);

  // 1. Collect corpus
  console.log("Collecting corpus...");
  const corpus = await collectCorpus(sourceFilter);
  console.log(`  ${corpus.gameCount} games, ${corpus.gsmCount} GSMs`);
  console.log(`  ${corpus.annotations.size} annotation types, ${corpus.prompts.size} prompt types, ${corpus.actions.size} action types`);

  // 2. Build profiles
  const annProfiles = new Map(
    [...corpus.annotations].map(([type, insts]) => [type, buildAnnotationProfile(type, insts)]),
  );
  const promptProfiles = new Map(
    [...corpus.prompts].map(([type, insts]) => [type, buildPromptProfile(type, insts)]),
  );
  const actionProfiles = new Map(
    [...corpus.actions].map(([type, insts]) => [type, buildActionProfile(type, insts)]),
  );

  // 3. Enrich cards (if resolver available)
  const resolver = getResolver();
  if (resolver) {
    enrichCards(annProfiles, corpus.annotations, resolver);
    console.log("  Card enrichment: done");
  } else {
    console.log("  Card enrichment: skipped (no card DB)");
  }

  // 4. Build dictionaries
  const observedValues = collectDictionaryValues(corpus.annotations);
  const dictionaries = buildDictionaries(observedValues);

  // 5. Dry-run: print stats and exit
  if (dryRun) {
    console.log("\n--- Dry run (no files written) ---");
    console.log(`Annotations: ${annProfiles.size} types`);
    console.log(`Prompts:     ${promptProfiles.size} types`);
    console.log(`Actions:     ${actionProfiles.size} types`);
    console.log(`Dictionaries: ${Object.keys(dictionaries).length} dictionaries`);
    console.log(`Output would go to: ${outDir}`);
    return;
  }

  // 6. Write files
  console.log(`\nWriting to ${outDir}...`);
  const annResult = writeAnnotations(annProfiles, outDir);
  const promptResult = writePrompts(promptProfiles, outDir);
  const actionResult = writeActions(actionProfiles, outDir);
  writeDictionaries(dictionaries, outDir);
  writeIndex(annProfiles, promptProfiles, actionProfiles, corpus.gameCount, corpus.gsmCount, outDir);

  // 7. Summary
  const totalWritten = annResult.written.length + promptResult.written.length + actionResult.written.length;
  const totalUnchanged = annResult.unchanged.length + promptResult.unchanged.length + actionResult.unchanged.length;
  console.log(`  Written: ${totalWritten} files, unchanged: ${totalUnchanged} files`);
  console.log("Done.");
}

function parseFlag(args: string[], flag: string): string | null {
  for (let i = 0; i < args.length; i++) {
    if (args[i] === flag && i + 1 < args.length) return args[i + 1];
  }
  return null;
}
