---
name: weekly
description: Weekly review of the closed (or ongoing) training week and planning of the next one, for one athlete or all active athletes. Works on any day of the week, inferring from dates what to review and what to plan. Usage - /weekly <athlete> [constraints] or /weekly all [per-athlete constraints].
---

# /weekly — review + next-week planning

Arguments: `$ARGUMENTS` = athlete name (folder under `athletes/`) plus optional
constraints for the week to plan. If the name is missing, ask for it. If the
argument is `all`, go to **ALL mode** at the bottom.

Work inside the coach's workspace (the directory with `coach-preferences.md`).
The workspace `CLAUDE.md` defines the coach, the working language and the
operating model: all of it applies, especially **no upload without the coach's
explicit OK**. Workout writing rules: the `intervals-workouts` skill.

## Phase 0 — Orient (always first)

0. If `profile.md` contains `**Status: SUSPENDED**` (or the equivalent in the
   workspace language, e.g. `**Stato: SOSPESO**`), stop: tell the coach the
   athlete is suspended (date and reason from that line) and ask whether to
   reactivate. No review, no draft.
1. Read `athletes/<athlete>/plans/<active-plan>/00-plan-overview.md`:
   macrocycle, current block and the LATEST entries of the decisions log (they
   hold the real state: what was uploaded, open patterns, promises made).
2. Read `profile.md` (fixed constraints, days, preferences, workout language)
   and `current-state.md` (athlete ID, current thresholds). Run
   `coach sync --athlete <athlete>` first if `current-state.md` is stale.
3. From today's date determine:
   - **week to review** = the one of the latest uploaded `week-N.json`;
   - **week to plan** = the next one (Mon-Sun).
   If today is Fri/Sat/Sun the current week is not over: the review is partial
   ("check as of today") and must say so; remaining sessions of the current
   week are not touched unless asked.

## Phase 1 — Verify (real data, not memory)

1. Real activities: `coach get "/athlete/<id>/activities?oldest=<mon>&newest=<sun>"`;
   block detail: `coach get /activity/<activityId>/intervals`.
2. Wellness of the week + this morning:
   `coach get "/athlete/<id>/wellness?oldest=<mon>&newest=<today>"`
   (resting HR, HRV if available, sleep, CTL/ATL/TSB).
3. Compare every executed session with the prescription in `week-N.json`:
   - dose (duration/reps/km) and intensity (the athlete's **real HR zones**,
     not pace vs threshold for HR-prescribed work; the first block below zone
     is cardiac lag, not an error);
   - caps and ceilings respected? expected average respected, not just the cap?
   - key sessions delivered?
4. Note the athlete's known patterns (from the decisions log) and whether they
   repeated.

## Phase 2 — Draft next week

1. Block intent from the macrocycle + ongoing progressions (long-run ladders,
   quality progressions, tests to place) from the decisions log.
2. Apply constraints: fixed ones from the profile, those in `$ARGUMENTS`, and
   those emerging from the review (warning signs, fatigue).
3. Respect the athlete's structural rules (protected key sessions, social
   slots, minimum frequency, swap rules — all in the plan overview).
4. Put corrective rules INSIDE the workouts (morning readiness checks, safety
   exits, mechanical consequences like "average above X at halfway = finish at
   Y"), not only in the conversation.

## Phase 3 — Present (mandatory STOP)

Present to the coach in one message: concise review (outcomes, overshoots,
wellness) + a day / session / notes table for the proposed week + declared
trade-offs. **Stop and wait for the OK. Generate nothing before.**

## Phase 4 — Only after the OK

1. Write `week-N.json` (same format as previous weeks of the plan; rules in the
   `intervals-workouts` skill).
2. `coach generate --athlete <athlete> --plan <plan> --file week-N.json`, then
   copy the returned event IDs into the file as `intervals_event_id`.
3. Verify each created event via `coach get` (type, `hide_from_athlete`,
   parsed steps with zero phantom steps, `moving_time`).
4. Update the decisions log in `00-plan-overview.md`: one entry for the closed
   week's review, one for the uploaded week (what, why, event IDs, trade-offs).
5. If the workspace is a git repository: commit
   (`<Athlete>: W<x> closed ... + W<y> uploaded ...`) and push if a remote is
   configured.

## ALL mode — /weekly all [per-athlete constraints]

Text after `all` = per-athlete constraints in free form
(e.g. "anna: gym on Thursday; marco: long run Saturday").

1. **Census**: list `athletes/*/`. Check **status** first: an athlete whose
   `profile.md` has `**Status: SUSPENDED**` gets no subagent, no API call, no
   draft; they appear only in a final digest line ("Suspended: X, Y"). An
   athlete is ACTIVE if not suspended and has a plan in `plans/` with recently
   uploaded weeks (decisions log). Athletes with only a profile go in the
   digest as "to start", with no work.
2. **Data collection per active athlete** (parallelisable with one read-only
   subagent per athlete): phases 0-1. Subagents do NOT write files and do NOT
   touch intervals.icu: they report facts.
3. **Drafts**: phase 2 for each active athlete with their constraints.
4. **Single digest to the coach**, one block per athlete, ordered by urgency
   (critical warning signs and close races first): week outcome in two or three
   sentences, compact table of the proposal, trade-offs and at most one or two
   questions. Close with "who do I approve?" — **STOP, no uploads**.
5. **Per-athlete upload after OK**: the OK covers only the athletes named; a
   generic "ok" after a multi-athlete digest needs confirmation of who. For each
   approved athlete: full phase 4, one commit per athlete, in sequence (never
   from subagents). Single push at the end.
