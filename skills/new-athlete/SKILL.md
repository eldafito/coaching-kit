---
name: new-athlete
description: Onboard a new athlete in the coaching workspace - create their folder and profile, link their intervals.icu account, sync data, and draft the plan overview for their target race. Use when the coach says they have a new athlete, wants to add an athlete, or wants to start a new plan or race for an existing athlete.
---

# New athlete / new plan

Run inside the coach's workspace (the directory with `coach-preferences.md`;
if none exists, run the `setup` skill first). Templates are in
`${CLAUDE_PLUGIN_ROOT}/templates/`.

## 1. Gather information

Ask the coach (or read material they provide: questionnaires, previous plans,
spreadsheets). Do not invent missing data: list what is missing and ask.

- Folder name: lowercase, no spaces (e.g. `anna`, `marco-r`).
- intervals.icu athlete ID (`i123456`, visible in the athlete's profile URL).
- Workout language for athlete-facing text.
- Experience, injury history, goals, A/B/C races with dates.
- Constraints: available days, session times, max weekly volume, equipment
  (HR strap, power meter, smart trainer, pool access).

## 2. Create the files

```
athletes/<name>/
├── profile.md            from templates/athlete-profile-template.md
├── race-calendar.md      A/B/C races with dates
├── tests-history.md      test results timeline (can start empty)
└── plans/                created in step 4
```

The profile must contain the line `- Athlete ID: \`i123456\`` exactly: the CLI
parses it.

## 3. Sync real data

`coach sync --athlete <name>` fetches profile, 26 weeks of activities, 90 days
of wellness and upcoming events, and writes `current-state.md`. Use it to
compare declared experience with real volume, thresholds and recent load, and
report any mismatch to the coach.

## 4. Plan overview

Create `athletes/<name>/plans/<YYYY-MM-DD>-<race-slug>/00-plan-overview.md` from
`templates/plan-overview-template.md`, following `coach-preferences.md` for
methodology and block structure. It must end with a `## Decisions log` section:
dated entries, newest last, recording what was decided and why. The `weekly`
skill relies on it as the plan's memory.

Present the macrocycle to the coach and wait for approval. Do **not** generate
workouts here: the first week is planned with the `weekly` skill, one week at a
time.

## 5. Commit

If the workspace is a git repository: commit (`<Athlete>: onboarding + plan
<race>`) and push if a remote is configured.
