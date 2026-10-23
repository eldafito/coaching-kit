# Coaching Kit

A Claude Code plugin for endurance coaches who use
[intervals.icu](https://intervals.icu). Claude works as your assistant coach:
it reviews each athlete's week on real data, drafts the next one, and, only
after your OK, uploads parser-safe structured workouts that sync to the
athlete's watch.

## What's inside

| Component | What it does |
|---|---|
| `/coaching-kit:setup` | Creates your private workspace (folders, preferences, operating rules, credentials file) |
| `/coaching-kit:new-athlete` | Onboards an athlete: profile, intervals.icu link, data sync, plan overview |
| `/coaching-kit:weekly <athlete>` / `weekly all` | Weekly review + next-week draft, stops for your approval, then uploads and verifies |
| `intervals-workouts` skill | Workout-writing rules learned from real parser bugs (loaded automatically) |
| `coach` CLI | `sync`, `generate`, `update`, `unhide`, `delete`, `pair`, `auto-pair`, `get`, `put` against the intervals.icu API |

## Requirements

- [Claude Code](https://code.claude.com)
- Java 17+ (`java -version`); the CLI builds itself on first use
- An intervals.icu account with coach access to your athletes, and its API key

## Install

```bash
claude plugin marketplace add eldafito/coaching-kit
claude plugin install coaching-kit@coaching-kit
```

Then, in an empty folder:

```bash
claude
> /coaching-kit:setup
```

## Your data stays yours

The plugin contains **no data**. Everything about your athletes lives in your
own workspace folder, created by `setup`:

```
my-coaching/                 ← yours, private
├── CLAUDE.md                operating rules (approval gate, language, coach name)
├── coach-preferences.md     your methodology, edit freely
├── coach-credentials.env    your API key (gitignored)
└── athletes/<name>/         profiles, plans, weekly workouts
```

- Your API key is read only by the `coach` CLI, from `coach-credentials.env` or
  the `INTERVALS_API_KEY` environment variable. It is never written into
  commands, so it does not end up in shell history or saved permissions.
- Back up the workspace to a **private** git remote. Never make it public: it
  contains your athletes' health data.
- Athlete data you work on is sent to the AI model provider as part of the
  conversation, as with any Claude Code session. Tell your athletes and get
  their consent.

## Development

```bash
claude --plugin-dir /path/to/coaching-kit     # load the plugin from source
claude plugin validate /path/to/coaching-kit  # check manifest and marketplace
```

Run the CLI directly with `bin/coach <command>` from inside a workspace (or
set `COACHING_WORKSPACE`). It rebuilds automatically when sources change.

Secret scanning runs on every push (gitleaks, see `.gitleaks.toml`). Locally:
`pre-commit install`.
