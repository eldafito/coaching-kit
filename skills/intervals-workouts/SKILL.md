---
name: intervals-workouts
description: Rules for writing, uploading and verifying structured workouts on intervals.icu (week-N.json files, workout descriptions, step syntax, parser pitfalls, Garmin export). Use whenever creating or editing a workout JSON file, a workout description, or before running `coach generate` / `coach update`.
---

# Writing workouts for intervals.icu

These rules come from real production bugs. Treat them as hard rules, not style.
The coach's own style preferences (methodology, RPE, feedback sections, run and
swim structure) live in `coach-preferences.md` at the workspace root: read it
too.

## Workout file format (`plans/<plan>/workouts/week-N.json`)

A JSON array of workouts:

```json
[
  {
    "start_date_local": "2026-10-27T00:00:00",
    "name": "Easy run 8 km",
    "type": "Run",
    "hide_from_athlete": false,
    "moving_time": 2880,
    "description": "- 1km Z1 HR\n- 7km Z2 HR\n\n---\n\n## Notes\n\n...\n\n## Feedback\n\n1. Average HR\n2. How did the legs feel?\n"
  }
]
```

- `type`: `Run`, `Ride`, `VirtualRide`, `Swim`, `WeightTraining`, `Other`.
- `start_date_local`: always `T00:00:00` unless the coach says otherwise.
- `hide_from_athlete`: follow the visibility rule in `coach-preferences.md`.
- `moving_time` in seconds; it must match the sum of the structured steps
  (see "Verification").
- Omit `icu_training_load` unless the server estimate is clearly wrong.
- After `coach generate`, copy each created event ID into the workout as
  `"intervals_event_id": "<id>"`. `coach update` only touches workouts that
  carry it.

## Description structure

1. **Top = ONLY structured steps**, one per line:
   `- [cue] [duration|distance] [target] [optional cadence]`.
   - Bike: `- Warmup 10m Z2 HR 90rpm`
   - Run: `- 5km Z2 HR`, `- TEST 30m 100% LTHR`
   - Swim: `- Freestyle 400mtr 1:55/100m Pace`
   - Repeats: blank line, `5x` (or `Main Set 5x`), the steps, blank line.
2. Then a line with `---`.
3. Then prose (`## Notes`, `## Feedback`), in the athlete's language from
   `profile.md`.

## Parser rules (absolute)

intervals.icu parses **every line** of the description, prose included.

1. **Never write `<number><unit>` in prose**: `60'`, `45'`, `30s`, `5m30s`,
   `4x80m`, `1km`, `200m`, `500mtr`, `Drink 500 ml/h`. Spell numbers out
   ("forty-five minutes", "two hundred metres", "half a litre per hour").
   `200m` in prose becomes a 200-*minute* phantom step. Bold labels and
   numbered lists do NOT protect a line.
2. **Never start a prose line with `- ` or `* `**: the parser turns it into an
   empty phantom step. Use numbered lists (`1.`), an em dash (`— `), or plain
   paragraphs. Markdown headings and `**Label**: prose` are safe.
3. **Metres in steps are `mtr`** (`m` means minutes). In prose, metres are
   spelled out.
4. **HR steps use zones** (`Z2 HR`, `95% LTHR`), never bpm ranges
   (`148-155` is not parsed). Exact bpm belong in prose only.
5. Every step needs a target, otherwise the chart does not render.

## Prescription rules

- **One workout = one target domain** (all HR or all Pace/Power). The chart has
  one axis, and the athlete's watch gets one target per step. A **cap** in the
  other domain is legitimate ("slow down if above X"); an **expected average**
  belongs only to the prescribed domain. Judge execution only on the
  prescribed domain.
- Documented per-athlete exceptions (e.g. pace strides after an HR run) live
  in that athlete's plan overview; respect them.
- **Athletes execute the watch workout, not the notes.** A cap that matters
  must be the target of a step. Optional work and strides must be structured
  steps too: in notes they will not be done.
- **Short efforts (< ~2 min) and their immediate recovery: target Pace/Power or
  RPE, never HR.** HR lags 30-60 s.
- Mixed HR + Pace steps leave one set of steps uncoloured on the chart. It is
  a rendering limitation: correct targets beat chart aesthetics.
- **Swim**: the parser recomputes `moving_time` from steps with a generic
  pace; align the local `moving_time` to the server value after upload.

## Upload and verification (mandatory)

Never upload without the coach's explicit OK (see the workspace `CLAUDE.md`).

```bash
coach generate --athlete <name> --plan <plan> --file week-N.json --dry-run   # inspect payloads
coach generate --athlete <name> --plan <plan> --file week-N.json             # after OK
coach update   --athlete <name> --plan <plan> --file week-N.json             # push edits to existing events
coach get /athlete/<athleteId>/events/<eventId>                              # verify one event
```

Check each created or updated event:

- `type` and `hide_from_athlete` as intended;
- `workout_doc.steps`: expected count, every `text` is one of your cues;
- `moving_time` close to the planned value.

Red flags and fix:

- `moving_time` 10-20x too large, or a single non-repeat step over 10000 s:
  the parser ate a prose duration such as `200m`.
- A step `text` that is a prose fragment: a `- ` line or a `<number><unit>`
  pattern below the `---`.
- Fix: spell out the offending prose, `coach update`, verify again.

## API access

Use only the `coach` CLI. It reads the API key itself.

- **Never** write the API key in a command, a file you create, or a `curl`
  call. Commands with a literal key get saved in tool permissions and shell
  history.
- Reads: `coach get <path>`. Examples:
  `/athlete/<id>/activities?oldest=YYYY-MM-DD&newest=YYYY-MM-DD`,
  `/athlete/<id>/wellness?oldest=...&newest=...`, `/activity/<activityId>/intervals`.
- One-off writes (e.g. thresholds after a test): write the JSON body to a temp
  file, then `coach put /athlete/<id>/sport-settings/<settingsId> --body-file <file>`.
- **Pairing**: intervals.icu does not auto-pair executed activities with
  planned events. Use `coach auto-pair --athlete <name>` (date + type match)
  or `coach pair --athlete <name> --activity-id <a> --event-id <e>`.
