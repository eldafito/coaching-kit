---
name: weekly
description: Weekly review of the closed (or ongoing) training week and planning of the next one, for one athlete or all active athletes. Works on any day of the week, inferring from dates what to review and what to plan. Use when the coach asks for a weekly review, next week's plan, or "how did <athlete>'s week go"; /weekly <athlete> [constraints] or /weekly all [per-athlete constraints].
---

# /weekly — review + next-week planning

Input: the athlete name (folder under `athletes/`) plus optional constraints
for the week to plan, from `$ARGUMENTS` or from the coach's request. If the name
is missing, ask for it. For all athletes ("everyone", `all`), go to **ALL mode**
at the bottom.

Work inside the coach's workspace (the folder with `coach-preferences.md`).
Read the workspace `CLAUDE.md` first: it defines the coach, the working
language and the operating model, and all of it applies, especially **no
upload without the coach's explicit OK**. Workout writing rules: the
`intervals-workouts` skill.

The coach is not necessarily technical: present reviews and plans in coaching
language and tables, never JSON, IDs or file names unless asked.

## Phase 0 — Orient (always first)

0. If `profile.md` contains `**Status: SUSPENDED**` (or the equivalent in the
   workspace language, e.g. `**Stato: SOSPESO**`), stop: tell the coach the
   athlete is suspended (date and reason from that line) and ask whether to
   reactivate. No review, no draft.
1. Read `athletes/<athlete>/plans/<active-plan>/00-plan-overview.md`:
   macrocycle, current block and the LATEST entries of the decisions log (they
   hold the real state: what was uploaded, open patterns, promises made).
2. Read `profile.md` (athlete ID, fixed constraints, days, preferences,
   workout language) and `current-state.md` (current thresholds). If
   `current-state.md` is older than a week, refresh it with `athlete_overview`.
3. From today's date determine:
   - **week to review** = the one of the latest uploaded `week-N.json`;
   - **week to plan** = the next one (Mon-Sun).
   If today is Fri/Sat/Sun the current week is not over: the review is partial
   ("check as of today") and must say so; remaining sessions of the current
   week are not touched unless asked.

## Phase 1 — Verify (real data, not memory)

1. Real activities: `get_activities` (Monday to Sunday of the week to
   review); block detail of key sessions: `get_activity_intervals`. Pair
   executed activities with planned workouts with `pair_activities` if they are
   not paired yet.
2. Wellness of the week + this morning: `get_wellness` (Monday to today:
   resting HR, HRV if available, sleep, CTL/ATL/TSB, the athlete's comments).
3. Planned workouts of the week (what was prescribed, if `week-N.json` is
   missing or edited on intervals.icu): `get_events`.
4. Compare every executed session with the prescription in `week-N.json`:
   - dose (duration/reps/km) and intensity (the athlete's **real HR zones**,
     not pace vs threshold for HR-prescribed work; the first block below zone
     is cardiac lag, not an error);
   - caps and ceilings respected? expected average respected, not just the cap?
   - key sessions delivered?
5. Note the athlete's known patterns (from the decisions log) and whether they
   repeated.

## Phase 2 — Draft next week

1. Block intent from the macrocycle + ongoing progressions (long-run ladders,
   quality progressions, tests to place) from the decisions log.
2. Apply constraints: fixed ones from the profile, those from the request, and
   those emerging from the review (warning signs, fatigue).
3. Respect the athlete's structural rules (protected key sessions, social
   slots, minimum frequency, swap rules — all in the plan overview).
4. Put corrective rules INSIDE the workouts (morning readiness checks, safety
   exits, mechanical consequences like "average above X at halfway = finish at
   Y"), not only in the conversation.
5. Write the workouts (rules in the `intervals-workouts` skill) and call
   `propose_week`. It checks them against the intervals.icu parser and saves a
   draft; nothing is uploaded. Fix every blocking problem it reports and call
   it again until the draft is saved.

## Phase 3 — Present (mandatory STOP)

Present to the coach in one message: concise review (outcomes, overshoots,
wellness) + a day / session / notes table for the proposed week + declared
trade-offs. **Stop and wait for the OK. Upload nothing before.**

If the coach asks for changes, edit the workouts, call `propose_week` again
(new draft) and present the changes.

## Phase 4 — Only after the OK

1. `upload_week` with the ID of the draft the coach approved and
   `coach_approved=true`.
2. Write `week-N.json` (same format as previous weeks of the plan) with the
   approved workouts, each carrying its `intervals_event_id` from the result.
3. Read the verification in the result. For each ⚠, propose the fix to the
   coach in one plain sentence ("Tuesday's notes contain a number the watch
   would read as a step: I'll write it in words, OK?"), push it with
   `update_workouts` after their OK and check again with `verify_event`.
4. If the workouts were uploaded hidden, tell the coach they can check them on
   intervals.icu and ask you to make them visible (`set_visibility`, after
   their OK).
5. Update the decisions log in `00-plan-overview.md`: one entry for the closed
   week's review, one for the uploaded week (what, why, event IDs, trade-offs).
6. If the workspace is a git repository: commit
   (`<Athlete>: W<x> closed ... + W<y> uploaded ...`) and push if a remote is
   configured.

## ALL mode — all active athletes

Per-athlete constraints in free form, from the request
(e.g. "anna: gym on Thursday; marco: long run Saturday").

1. **Census**: list `athletes/*/`. Check **status** first: an athlete whose
   `profile.md` has `**Status: SUSPENDED**` gets no subagent, no API call, no
   draft; they appear only in a final digest line ("Suspended: X, Y"). An
   athlete is ACTIVE if not suspended and has a plan in `plans/` with recently
   uploaded weeks (decisions log). Athletes with only a profile go in the
   digest as "to start", with no work.
2. **Data collection per active athlete** (parallelisable with one read-only
   subagent per athlete, if subagents are available): phases 0-1. Subagents do
   NOT write files and do NOT change anything on intervals.icu: they report
   facts.
3. **Drafts**: phase 2 for each active athlete with their constraints, each
   saved with `propose_week`.
4. **Single digest to the coach**, one block per athlete, ordered by urgency
   (critical warning signs and close races first): week outcome in two or three
   sentences, compact table of the proposal, trade-offs and at most one or two
   questions. Close with "who do I approve?" — **STOP, no uploads**.
5. **Per-athlete upload after OK**: the OK covers only the athletes named; a
   generic "ok" after a multi-athlete digest needs confirmation of who. For each
   approved athlete: full phase 4, one commit per athlete, in sequence (never
   from subagents). Single push at the end.
