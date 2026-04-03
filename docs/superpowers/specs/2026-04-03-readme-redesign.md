# README Redesign Spec

## Goal

Replace the current developer-oriented README with a best-in-class open-source
landing page that serves two audiences: players (download and play) and
contributors (understand technical depth). No badges/shields fluff. Readable,
honest, forward-looking.

## Context & Research

### Positioning

- **Legal frame:** DMCA 1201(f) interoperability. "Reimplemented protocol" not
  "reverse-engineered". "Local game server for personal playtesting."
- **Naming:** "Magic: The Gathering Arena" used referentially (nominative fair
  use for describing interoperability target). Never in project name/branding.
- **Tone:** Honest, technical, indie. "Come try it. It's alpha. Growing weekly."
- **Not:** "Free Arena alternative" (reactive). "Private server" (wrong frame).

### Inspiration patterns borrowed

| Pattern | Source | How we use it |
|---------|--------|---------------|
| Recommended path first, alternatives after | OpenClaw, CodexBar | Launcher before `git clone` |
| One-line value prop | Peekaboo, Enchanted | Hero block |
| Privacy/trust as first-class section | CodexBar | "What this is not" section |
| Proactive "what we DON'T do" | CodexBar | Legal positioning |
| Honest limitations build credibility | Aspects | Alpha callout, evolving weekly |
| Specialist docs linked, not inline | CodexBar, Peekaboo | Architecture.md, catalog.yaml |
| Two audience paths | OpenClaw | Play section + Build from source |
| Visual-first for user-facing projects | Enchanted | Launcher screenshot above fold |

### Current state

- Current README: 116 lines, dev-oriented, functional but not a landing page
- Launcher exists (Tauri v2) — can be the primary entry point for players
- Card pool growing beyond FDN, evolving weekly
- No landing page/website yet — README IS the landing page

## Design

### Section order

```
1. Hero block (name + one-liner + alpha status)
2. Launcher screenshot (visual slot)
3. Play (3 steps — download, launch, play)
4. How it works (simplified mermaid diagram + module table)
5. Forge (credit upstream, explain fork relationship)
6. Build from source (2 commands + testing)
7. Design philosophy (4 bullets)
8. What this is / what this is not (legal positioning)
9. License + trademark disclaimer
10. Links
```

Target: ~200-250 lines. Medium inline diagrams (one mermaid flow, no sequence
diagram — that moves to architecture.md).

### Section details

#### 1. Hero block

```markdown
# Leyline

Open-source game server for Magic: The Gathering Arena.
Your client, open rules engine, no account required.

> **Alpha — evolving weekly.** Core game loop works. Card pool growing.
> [See what works →](docs/catalog.yaml)
```

- No badges, no shields
- One-liner does 3 things: what it is, what's different, removes friction
- Alpha callout is honest but forward-looking ("evolving weekly" not "lots broken")
- Links to catalog.yaml as the living status document

#### 2. Launcher screenshot

```markdown
<!-- screenshot: Leyline launcher (Tauri app) -->
```

- Placed between hero and Play section
- Proves "this is a real app" before they even read further
- Tauri launcher screenshot — the actual app window
- Added when available, placeholder comment for now

#### 3. Play

```markdown
## Play

Download the launcher, point it at your Arena install, hit Play.

1. **Download** — grab the latest release for your platform
   [macOS](...) · [Windows](...) · [Linux](...)
2. **Launch** — the app auto-detects your Arena install
3. **Play** — click Play, Arena connects to your local server

Requires a legally obtained copy of MTG Arena.
```

- Three steps, no terminal, no JDK
- Platform links to GitHub releases
- "Legally obtained copy" — legal language woven naturally
- This is the "holy shit it's real" moment for players

#### 4. How it works

```markdown
## How it works

Arena speaks protobuf over TLS. Leyline speaks it back —
translating between the real client and Forge's open-source rules engine.

[mermaid: Client <-> Leyline <-> Forge Engine — simple LR flow]

The key pattern: Forge's engine blocks at each decision point.
Leyline's async handler completes the future when the client responds.

[module table: app, account, frontdoor, matchdoor — one line each]

[Architecture deep-dive →](docs/architecture.md)
```

- ONE mermaid diagram (simple flow), not two
- Sequence diagram moves to architecture.md
- Module table stays — contributors scan it
- Link out for depth

#### 5. Forge

```markdown
## Forge

The heavy lifting — 20+ years of card rules, 20,000+ card
implementations, AI opponents — lives in Forge, the open-source
MTG rules engine.

Leyline uses a minimal fork that adds event hooks and controller
seams for the Arena protocol bridge. The rules engine itself
is untouched.
```

- Credits Card-Forge/forge as the hero link (upstream)
- delebedev/forge linked as "minimal fork"
- "Rules engine untouched" — signals not a hostile fork
- Sits between architecture and build — answers "where do rules come from?"
- Future-proof: when true fork migration happens, update one URL

#### 6. Build from source

```markdown
## Build from source

[2 commands: just bootstrap + just serve]

Requires: JDK 17+, just, macOS/Linux.
Arena client installed locally (reads card database at runtime).

### Testing

[3 commands: test-gate, test-one, puzzle]

[Puzzle-driven development →](docs/puzzle-driven-dev.md)
```

- Collapsed from 5 setup commands to 2 (bootstrap does everything)
- Testing section shows puzzle system — distinctive, signals quality
- Links to puzzle-driven-dev doc for depth

#### 7. Design philosophy

```markdown
## Design philosophy

**Player.log is the spec.** Real Arena logs are the conformance
baseline. Trace, diff, close gaps.

**Minimal engine changes.** Leyline plugs into Forge's existing
bridge interfaces. The fork adds event hooks and controller
seams — the rules engine stays untouched.

**Puzzles as acceptance tests.** .pzl files define exact board
states with one win path. An agent plays the game to verify
the server.

**Protocol reimplementation.** Hand-written protobuf responses
implementing a compatible wire format. No client mods, no
proxies, no distributed assets.
```

- Kept from current README but tightened
- Added puzzles — unique differentiator
- "Protocol reimplementation" is the legally correct AND impressive framing

#### 8. What this is / what this is not

```markdown
## What this is

A local game server for personal playtesting. Connects your
legally obtained Arena client to an open-source rules engine
on your own machine.

**What it is not:**
- Not a replacement for Arena or official servers
- Not a public/online server — local only
- Does not distribute card art, sounds, or game assets
- Does not support unauthorized public servers
```

- CodexBar-style "what we DON'T do" builds trust
- Legal language woven naturally, not legalese

#### 9. License + disclaimer

```markdown
## License

GPL-3.0 — inherited from Forge.
See LICENSE, LEGAL, and NOTICE.

---

[Standard trademark disclaimer — not affiliated with WotC/Hasbro.
"Magic: The Gathering" is a trademark of Wizards of the Coast LLC.]
```

- Brief. Links to LEGAL.md for depth.
- Disclaimer below horizontal rule — visually separated, always present.

#### 10. Links

```markdown
[Architecture](docs/architecture.md) ·
[What works](docs/catalog.yaml) ·
[Issues](...) ·
[Project board](...)
```

- Simple link bar. No bloat.

### What changes from current README

| Current | New |
|---------|-----|
| Dev-only entry point (git clone first) | Player path first (launcher), dev path second |
| 2 mermaid diagrams inline | 1 simplified flow diagram |
| No launcher mention | Launcher + screenshot as primary CTA |
| "Lots still broken" | "Evolving weekly" |
| No Forge credit section | Dedicated Forge section crediting upstream |
| 5 setup commands | 2 commands (bootstrap + serve) |
| No puzzle mention in quick start | Puzzle testing shown |
| No visual slot | Launcher screenshot placeholder |

### What stays

- Design philosophy section (tightened)
- Module table
- Legal disclaimer
- Links bar
- Alpha status honesty

### Files touched

- `README.md` — full rewrite
- No other files modified. Architecture.md, LEGAL.md, catalog.yaml
  already exist and are linked to, not duplicated.

### Future additions (not in this PR)

- Launcher screenshot (when ready to capture)
- Platform-specific release links (when GitHub releases exist)
- Contributing guide (CONTRIBUTING.md — separate effort)
- leyline.games landing page (separate project)
