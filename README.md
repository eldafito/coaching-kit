# Coaching Kit

A Claude plugin for endurance coaches who use
[intervals.icu](https://intervals.icu). Claude works as your assistant coach:
it reviews each athlete's week on real data, drafts the next one, and, only
after your OK, uploads parser-safe structured workouts that sync to the
athlete's watch.

Works in the **Claude desktop app (Cowork)**, no terminal needed, and in
**Claude Code**.

## What's inside

| Component | What it does |
|---|---|
| `setup` skill | Connects intervals.icu, creates your coaching folder, imports your first athletes |
| `new-athlete` skill | Onboards an athlete: picks them from your intervals.icu athletes, profile, real data, plan overview |
| `weekly` skill | Weekly review + next-week draft for one athlete or all, stops for your approval, then uploads and verifies |
| `intervals-workouts` skill | Workout-writing rules learned from real parser bugs (loaded automatically) |
| `intervals` MCP server | The tools Claude uses on intervals.icu: read activities and wellness, propose/upload/verify weeks, pair activities, update thresholds |
| `coach` CLI | The same operations from a terminal, for advanced use |

## For coaches: install in the Claude desktop app

You need the [Claude desktop app](https://claude.com/download) on **macOS**
with a plan that includes Cowork, and an intervals.icu account with coach
access to your athletes. Windows support is coming.

1. In Claude, open **Customize → Plugins**, choose **Add marketplace** and
   enter `eldafito/coaching-kit`.
2. Install **Coaching Kit**.
3. Start a Cowork task in an empty folder (for example `Documents/Coaching`)
   and write: **"Let's set up Coaching Kit"** (or "Iniziamo con Coaching Kit").

Claude asks three questions, opens a page in your browser where you paste your
intervals.icu API key (it explains where to find it), lists your athletes and
gets you to the first weekly review. A step-by-step guide in Italian:
[docs/guida-coach.md](docs/guida-coach.md).

From then on, just ask: *"weekly review for Anna"*, *"how did everyone's week
go?"*, *"Marco has a new race in April"*.

## Install in Claude Code

```bash
claude plugin marketplace add eldafito/coaching-kit
claude plugin install coaching-kit@coaching-kit
```

Then, in an empty folder, run `claude` and say "set up Coaching Kit".

## Your data stays yours

The plugin contains **no data**. Everything about your athletes lives in your
own coaching folder:

```
my-coaching/                 ← yours, private
├── CLAUDE.md                operating rules (approval gate, language, coach name)
├── coach-preferences.md     your methodology, edit freely
└── athletes/<name>/         profiles, plans, weekly workouts
```

- Your intervals.icu API key is stored on your computer (macOS Keychain; a
  file readable only by you on other systems), not in the coaching folder and
  never in the chat. Only the Coaching Kit server reads it. Existing setups
  with `coach-credentials.env` or `INTERVALS_API_KEY` keep working.
- Nothing is changed on intervals.icu without your OK: new weeks are first
  saved as drafts, checked against the intervals.icu parser and shown to you;
  only an approved draft can be uploaded, and never twice. New workouts are
  uploaded hidden from the athlete until you check them (change this in
  `coach-preferences.md`).
- Back up the folder to a **private** place. Never make it public: it
  contains your athletes' health data.
- Athlete data you work on is sent to the AI model provider as part of the
  conversation, as with any Claude session. Tell your athletes and get their
  consent: `setup` offers a ready-made message.

## Development

```bash
claude --plugin-dir /path/to/coaching-kit     # load the plugin from source
claude plugin validate /path/to/coaching-kit  # check manifest and marketplace
```

`scripts/coach` is the launcher for both the CLI and the MCP server
(`scripts/coach mcp`, started by the plugin through `.mcp.json`). From a git
checkout with Java 17+ it builds from source with Gradle and rebuilds when
sources change; run `scripts/coach --help` once before loading the plugin, so
the first build does not happen while Claude waits for the MCP server.
Installed copies download the native binary of the matching GitHub release.

The executables are kept out of a top-level `bin/` directory on purpose:
claude.ai and Cowork refuse plugins that have one.

Useful CLI commands: `scripts/coach connect` (store the API key through the
browser page), `scripts/coach sync --athlete <name>` from inside a workspace
(or set `COACHING_WORKSPACE`), `scripts/coach --help` for the rest.
`COACHING_KIT_HOME` moves the key file and drafts to another directory (used
by tests; it also bypasses the macOS Keychain).

### Testing a branch in Cowork

Cowork installs from the marketplace's default branch, and installed copies
download the binary of the version in `plugin.json`. To try a branch:

1. Push a pre-release tag that matches `plugin.json` (e.g. `v0.3.0-beta.1`):
   the `release` workflow publishes the binaries as a GitHub pre-release.
2. Zip the plugin (`git archive --format=zip -o coaching-kit.zip HEAD`) and
   upload it in **Customize → Plugins → Upload plugin**.

### Releasing

1. Bump `version` in `.claude-plugin/plugin.json`.
2. Commit, then tag and push: `git tag v<version> && git push origin v<version>`.
3. The `release` workflow builds native binaries for macOS, Linux and Windows
   with GraalVM, smoke-tests the CLI, the MCP server and the connect page, and
   publishes them with `SHA256SUMS` (tags with a suffix such as `-beta.1` are
   published as pre-releases). Users get the new version with
   `claude plugin update coaching-kit@coaching-kit`, or from Cowork's plugin
   page.

Native-image reflection config lives in
`cli/src/main/resources/META-INF/native-image/`. If a new feature fails only in
the native binary, regenerate it by running the JVM build with
`-agentlib:native-image-agent=config-merge-dir=<that dir>` on the affected
commands.

Secret scanning runs on every push (gitleaks, see `.gitleaks.toml`). Locally:
`pre-commit install`.

## License

[Apache License 2.0](LICENSE)
