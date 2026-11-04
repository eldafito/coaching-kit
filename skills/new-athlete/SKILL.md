---
name: new-athlete
description: Onboard a new athlete in the coaching workspace - pick them from the coach's intervals.icu athletes, create their profile, read their real data, and draft the plan overview for their target race. Use when the coach says they have a new athlete, wants to add an athlete, or wants to start a new plan or race for an existing athlete.
---

# New athlete / new plan

Run inside the coach's workspace (the folder with `coach-preferences.md`; if
none exists, run the `setup` skill first). Read the workspace `CLAUDE.md`
first: its rules apply. Templates are in `${CLAUDE_PLUGIN_ROOT}/templates/`.

Speak to the coach in plain coaching language; do not show IDs, file names or
JSON unless asked.

## 1. Find the athlete on intervals.icu

Call `list_athletes` and match the name the coach gave. If the athlete is not
in the list, they have not shared their intervals.icu account with the coach
yet: explain how (athlete → Settings → share with the coach) and stop until it
is done. Only if the coach insists, accept the athlete ID (`i123456`, in the
athlete's profile URL).

## 2. Read the real data first

Call `athlete_overview` (athlete ID, folder name as label). Use it to pre-fill
what intervals.icu already knows (thresholds, volume, recent load, races) and
to compare with what the coach tells you; report any mismatch.

## 3. Ask only what is missing (one message)

Do not invent data: ask for what you cannot read, in one message, and accept
material the coach provides (questionnaires, previous plans, spreadsheets).

- Workout language for athlete-facing text (default: the working language).
- Experience, injury history, goals, A/B/C races with dates (if not on the
  intervals.icu calendar).
- Constraints: available days, session times, max weekly volume, equipment
  (HR strap, power meter, smart trainer, pool access).

## 4. Create the files

Folder name: lowercase, no spaces (e.g. `anna`, `marco-r`).

```
athletes/<name>/
├── profile.md            from templates/athlete-profile-template.md
├── current-state.md      the athlete_overview result
├── race-calendar.md      A/B/C races with dates
├── tests-history.md      test results timeline (can start empty)
└── plans/                created in step 5
```

The profile must contain the line `- Athlete ID: \`i123456\`` exactly: the
other skills read the ID from it.

## 5. Plan overview

Create `athletes/<name>/plans/<YYYY-MM-DD>-<race-slug>/00-plan-overview.md` from
`templates/plan-overview-template.md`, following `coach-preferences.md` for
methodology and block structure. It must end with a `## Decisions log` section:
dated entries, newest last, recording what was decided and why. The `weekly`
skill relies on it as the plan's memory.

Present the macrocycle to the coach as a short table (blocks, dates, focus) and
wait for approval. Do **not** generate workouts here: the first week is planned
with the `weekly` skill, one week at a time. Offer to do it now.

## 6. Commit

If the workspace is a git repository: commit (`<Athlete>: onboarding + plan
<race>`) and push if a remote is configured.
