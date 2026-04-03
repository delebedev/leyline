#!/usr/bin/env bun
/**
 * Build-time changelog generator for GitHub Releases.
 *
 * Reads git log (last-tag..HEAD or last 50 commits), calls OpenRouter to curate
 * into player-facing release notes, writes .changelog.md.
 *
 * Falls back to date-grouped bullets if OPENROUTER_API_KEY is missing or LLM fails.
 *
 * Usage:
 *   bun scripts/curate-changelog.ts              # auto-detect from git tags
 *   bun scripts/curate-changelog.ts --since v2026.4.1   # explicit base
 */

const API_KEY = Bun.env.OPENROUTER_API_KEY ?? Bun.env.OPEN_ROUTER_API_KEY ?? ""
const MODEL = Bun.env.LLM_MODEL ?? "google/gemini-2.5-flash"
const BASE_URL = Bun.env.LLM_BASE_URL ?? "https://openrouter.ai/api/v1"
const OUTPUT = ".changelog.md"
const COMMIT_LIMIT = 50

// --- Git log ---

function run(cmd: string[]): string {
  const proc = Bun.spawnSync(cmd, { stdout: "pipe", stderr: "pipe" })
  return proc.stdout.toString().trim()
}

function getBaseRef(): string {
  // Explicit --since flag
  const sinceIdx = Bun.argv.indexOf("--since")
  if (sinceIdx !== -1 && Bun.argv[sinceIdx + 1]) {
    return Bun.argv[sinceIdx + 1]
  }

  // Most recent tag reachable from HEAD
  const tag = run(["git", "describe", "--tags", "--abbrev=0", "HEAD^"])
  if (tag) return tag

  return ""
}

function getRawLog(): string {
  // Pre-generated .gitlog (CI can inject this)
  const gitlog = Bun.file(".gitlog")
  if (gitlog.size > 0) {
    try {
      return run(["cat", ".gitlog"]) // sync read
    } catch {}
  }

  const base = getBaseRef()
  if (base) {
    const log = run([
      "git", "log", "--format=%ad %s", "--date=short", "--no-merges",
      `${base}..HEAD`,
    ])
    if (log) return log
  }

  // Fallback: last N commits
  return run([
    "git", "log", "--format=%ad %s", "--date=short", "--no-merges",
    `-${COMMIT_LIMIT}`,
  ])
}

// --- Filtering ---

const SKIP_PREFIX = /^(\d{4}-\d{2}-\d{2})\s+(build|ci|docs|style|chore|refactor|perf|test)[\s(:]/i
const SKIP_KEYWORDS = /docker|dockerfile|ci[ /:]|\.yml|maven|gradle|deps?[ :]|bump|lint|fmt|merge/i

function filterLog(raw: string): string {
  return raw
    .split("\n")
    .filter((line) => !SKIP_PREFIX.test(line) && !SKIP_KEYWORDS.test(line))
    .join("\n")
    .trim()
}

// --- Fallback (no LLM) ---

function fallbackChangelog(raw: string): string {
  if (!raw) return "No changes in this release."
  const days = new Map<string, string[]>()
  for (const line of raw.split("\n")) {
    const match = line.match(/^(\d{4}-\d{2}-\d{2})\s+(.+)$/)
    if (!match) continue
    const [, date, msg] = match
    if (!days.has(date)) days.set(date, [])
    days.get(date)!.push(msg)
  }
  if (days.size === 0) return "No changes in this release."

  const sections: string[] = []
  for (const [date, msgs] of days) {
    sections.push(`## ${date}\n`)
    for (const msg of msgs) sections.push(`- ${msg}`)
    sections.push("")
  }
  return sections.join("\n").trim()
}

// --- LLM curation ---

const SYSTEM_PROMPT = `You are a changelog writer for Leyline — a local server that lets you play Magic: The Gathering Arena offline against AI.

Given a git log (one commit per line, newest first, prefixed with date), produce concise, player-facing release notes in markdown.

Rules:
- Group by date: use ## YYYY-MM-DD as section headers (newest first)
- Within each day, group into **New**, **Improved**, **Fixed** (skip empty groups)
- One bullet per meaningful change — merge related commits into a single bullet
- AGGRESSIVE filtering: only include changes a player would notice or care about
  - YES: new card mechanics working, gameplay fixes, new formats, UI improvements, launcher changes
  - NO: internal refactors, build/CI, protocol internals, test changes, code cleanup
- Write from the player's perspective — "You can now…", "Fixed a bug where…", "Improved…"
- Never mention technical internals: no file paths, class names, protocol details, annotation fields
- Describe fixes by what the player experienced, not what was broken internally
- Keep it short — aim for 2–5 bullets per day. Fewer is better. Empty days = skip entirely.
- No commit SHAs, no PR numbers, no jargon
- End with nothing — no footer, no sign-off`

async function curate(rawLog: string): Promise<string | null> {
  if (!API_KEY) {
    console.log("curate-changelog: no OPENROUTER_API_KEY, using fallback format")
    return null
  }

  try {
    const res = await fetch(`${BASE_URL}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${API_KEY}`,
      },
      body: JSON.stringify({
        model: MODEL,
        messages: [
          { role: "system", content: SYSTEM_PROMPT },
          { role: "user", content: rawLog },
        ],
        temperature: 0.2,
        max_tokens: 2048,
      }),
    })

    if (!res.ok) {
      console.error(`curate-changelog: LLM API error ${res.status}`)
      return null
    }

    const data = (await res.json()) as {
      choices?: { message?: { content?: string } }[]
    }
    let content = data.choices?.[0]?.message?.content?.trim()
    if (!content) {
      console.error("curate-changelog: LLM returned empty content")
      return null
    }

    // Strip markdown code fences if model wraps output
    if (content.startsWith("```")) {
      const lines = content.split("\n")
      const start = lines[0].startsWith("```") ? 1 : 0
      const end = lines.at(-1)?.trim() === "```" ? lines.length - 1 : lines.length
      content = lines.slice(start, end).join("\n").trim()
    }

    return content
  } catch (e) {
    console.error(`curate-changelog: LLM request failed: ${(e as Error).message}`)
    return null
  }
}

// --- Main ---

const raw = getRawLog()
if (!raw) {
  console.log("curate-changelog: no git log available")
  await Bun.write(OUTPUT, "No changes in this release.")
  process.exit(0)
}

const filtered = filterLog(raw)
if (!filtered) {
  console.log("curate-changelog: all commits filtered out")
  await Bun.write(OUTPUT, "No changes in this release.")
  process.exit(0)
}

const curated = await curate(filtered)
const result = curated ?? fallbackChangelog(filtered)

await Bun.write(OUTPUT, result)
console.log(
  `curate-changelog: wrote ${OUTPUT} (${result.length} chars, ${curated ? "LLM-curated" : "fallback"})`,
)
