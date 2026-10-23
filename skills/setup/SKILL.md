---
name: setup
description: Create a new coaching workspace (folder structure, coach preferences, operating rules, credentials file) for the coaching-kit plugin, or check an existing one. Use when the user wants to start using coaching-kit, set up their coaching folder, or configure their intervals.icu API key.
---

# Setup — create the coach's workspace

The workspace is the coach's **private** folder: athlete data, plans and
credentials live there, never in the plugin. Plugin files are in
`${CLAUDE_PLUGIN_ROOT}`.

## 1. Choose the location

- If the current directory already contains `coach-preferences.md`, this is an
  existing workspace: skip to step 4 and only check it.
- Otherwise ask the coach where to create it (default: the current directory if
  empty, else a new `coaching/` subfolder).

## 2. Ask the coach (one message)

1. Name (the human coach who approves every upload).
2. Working language for conversations and plans (athlete-facing language is set
   per athlete later).
3. Main sports (run, bike, swim, triathlon...).

## 3. Create the files

Copy from `${CLAUDE_PLUGIN_ROOT}/workspace/`:

| Source | Destination |
|---|---|
| `CLAUDE.md` | `CLAUDE.md` (fill in `{{COACH_NAME}}`, `{{LANGUAGE}}`, `{{SPORTS}}`) |
| `coach-preferences.md` | `coach-preferences.md` |
| `gitignore` | `.gitignore` |
| `coach-credentials.env.example` | `coach-credentials.env` |

Create an empty `athletes/` directory (with a `.gitkeep`).

Tell the coach that `coach-preferences.md` holds default methodology and
workout-style preferences, and that they should edit it to match their own
approach. Do not rewrite it for them unless asked.

## 4. API key

The coach pastes their intervals.icu API key (intervals.icu → Settings →
Developer Settings → API key) **directly into `coach-credentials.env`
themselves**, as `INTERVALS_API_KEY=...`. Do not ask them to paste it in the
chat. Alternative: export `INTERVALS_API_KEY` in their shell profile.

The coach account must have coach access to each athlete on intervals.icu
(athlete → Settings → share with coach), otherwise the API returns 403/404.

Check `.gitignore` contains `coach-credentials.env`, `*.env`,
`**/data-snapshots/` and `.claude/settings.local.json` before any commit.

## 5. Check the toolchain

1. Run `coach --help`. The first run downloads the CLI for this system (a few
   seconds). If no prebuilt binary is available it falls back to building
   from source, which needs Java 17+ (`brew install openjdk@21` on macOS).
2. If the key is configured, run `coach get /athlete/0` to check that
   authentication works (`0` = the key owner's own profile). Report only
   success or the error, never the response content in full.

## 6. Git (recommended)

Suggest `git init` and a **private** remote as backup and sync between
machines. Never suggest a public repository: it would contain athletes' health
data.

## 7. Next step

Suggest the `new-athlete` skill to onboard the first athlete.
