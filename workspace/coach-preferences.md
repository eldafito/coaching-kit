# Coach Preferences

Global methodology and workout-style preferences used across all athletes.
Athlete-specific overrides live in `athletes/<name>/profile.md` and in each
plan overview. **Edit this file to match your own coaching approach**: these are
starting defaults.

Parser-safety rules for intervals.icu are not here: they are fixed and live in
the plugin's `intervals-workouts` skill.

## Methodology defaults

- Default approach: **Polarized 80/20**
- Adapt methodology based on athlete volume, experience, and goals
- Prioritize sustainability and enjoyment over peak performance unless the
  athlete explicitly requests otherwise
- **Progression bias**: avoid over-conservative ramping. When initial
  constraints (injury history, low base) are validated by 2-3 clean weeks,
  downgrade them and accelerate progression. Athletes notice slow progress and
  lose motivation faster than they get injured from a moderately ambitious
  plan. Per-athlete overrides in `profile.md`.
- Recovery week every 4 weeks (Base) / every 3 weeks (Build) — **not
  automatic**: skip deload when current load is genuinely manageable and the
  athlete is progressing
- Standard 2-week taper before A-races
- Long sessions on weekend (Sat/Sun) by default

## Workout conventions

- Zones: `% FTP`, `% CSS`, HR zones (`Z1`–`Z5`), or pace zones
- Prefer relative zones (% of threshold) over absolute values, so workouts
  remain valid after re-tests
- Always include **expected RPE** (1–10)
- Always close the description with a **Feedback** section of 2-4 questions
- **No name prefix** — descriptive names only (e.g. `Bike Endurance + Tempo 75'`)
- **Visibility**: `hide_from_athlete: false` (visible) unless you prefer to
  upload hidden and unlock weekly with `coach unhide`
- **Athlete-facing text**: plain ASCII (no accented letters) in workout names
  and descriptions
- **Easy and long runs**: explicit HR ceiling ("hard ceiling X bpm") in prose;
  duration caps written "on the watch". For athletes who tend to run too fast,
  also state the **expected average**, in the prescribed domain only
- **Start time**: always `T00:00:00` in `start_date_local`. The athlete
  chooses when to train within the day; never ask or propose a time of day.
  When updating an event to reflect actual execution, leave the time untouched

### Run structure

- **Warmup/cooldown**: easy running in Z1 (e.g. `- 2km Z1 HR`). No walking or
  stretching steps in the structured workout.
- **Z2 steady runs**: no cooldown step. Warmup (if any) + Z2 main block.
- **Quality runs** (intervals, tempo, tests): warmup + main work + short Z1
  cooldown.
- **Prefer distance (km) over time** for run steps, so steps line up with the
  watch's 1 km auto-lap (rule of thumb ~10 min ≈ 2 km at easy pace). Keep the
  HR or pace target on the distance step (`- 12km Z2 HR`).
- **Exception**: strides, hill sprints and their recoveries stay time-based
  (`- 15s ... Pace`, `- 60s ... Pace`).

### Swim structure

- **Stroke naming**: "Freestyle" (or the athlete-language equivalent), never
  "Crawl".
- **Rest between reps** is a fixed pause at the wall, as a time step
  (`- Rest 20s`), after every rep:

  | Rep intensity | Rest |
  |---|---|
  | Easy / technique (Z1, drills) | `15s` |
  | Aerobic (Z2, ~95% Pace) | `20s` |
  | Threshold (CSS, 100% Pace) | `30s` |
  | Above threshold (105%+ Pace, sprints) | `45s` |

  Override when the workout demands it (e.g. the easy swim block between the
  efforts of a CSS test is an active block, not a rest).
- **Backstroke is a stroke, not a recovery**: label it as the stroke it is.
- **Drills**: always name the concrete exercise (`Kick with board`,
  `Pull buoy freestyle`, `Catch-up`, `Fingertip drag`, `Single arm`), never a
  generic `Drill`.
- Vary structure and drills week to week (never two identical weeks).

## Default test protocols

| Discipline | With instrumentation | Without |
|---|---|---|
| Bike | 20' FTP test (power) | 30' LTHR all-out test (HR) |
| Run | 30' threshold all-out (LTHR + threshold pace) | same |
| Swim | 400 + 200 CSS test | same |

## Planning structure (default)

| Block | Default duration | Focus |
|---|---|---|
| Base 1 | 4 weeks | Aerobic Z2, technique, low-intensity volume |
| Base 2 | 4 weeks | Volume growth, sweet-spot, tempo |
| Build 1 | 3 weeks | Threshold, brick, race-pace introduction |
| Recovery | 1 week | Deload + re-tests |
| Build 2 | 3 weeks | Peak volume, race-specific |
| Peak | 2–3 weeks | Race simulations, specific intensity |
| Taper | 2 weeks | Volume −40/−60%, maintain intensity |
| Race week | 1 week | Activations, fueling, race |

Loading pattern: 3 load + 1 deload (Base), 2 load + 1 deload (Build).

## Review cadence

- Weekly review with `/coaching-kit:weekly`, recorded in the plan's decisions log
- Mesocycle summary at the end of each block
- On-demand modifications anytime
