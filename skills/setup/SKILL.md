---
name: setup
description: First-time setup of Coaching Kit for an endurance coach - connects intervals.icu, creates the coaching folder (preferences, operating rules), imports the first athletes and gets to a first weekly review. Use when the coach starts using Coaching Kit ("let's start", "set up Coaching Kit", "iniziamo"), when the folder has no coach-preferences.md, when intervals.icu is not connected, or to check an existing setup.
---

# Setup — from zero to the first weekly review

The coach is usually **not technical**. Keep every message short, friendly and
in plain language, in the coach's language (detect it from their first
message). Do not mention files, folders, JSON, tools or IDs unless they ask;
say what you are doing in coaching terms ("I'm connecting your intervals.icu
account", "I'm creating Anna's profile").

Templates are in `${CLAUDE_PLUGIN_ROOT}/workspace/` and
`${CLAUDE_PLUGIN_ROOT}/templates/`.

Target: the coach sees a first real weekly review within about 15 minutes.

## 0. Where we are

The workspace is the folder the coach is working in (in Cowork: the folder
they selected for this task). If no folder is available, ask the coach to
choose or create an empty folder for their coaching (e.g. "Coaching") and to
open it, then continue.

- If it already contains `coach-preferences.md`, this is an existing setup:
  run step 2 (connection) and report in one or two sentences what is in place
  (coach name, number of athletes). Stop there unless something is missing.
- If it is not empty and has no `coach-preferences.md`, ask before creating
  files there.

## 1. Three questions (one message)

1. Your name (you approve every workout before it reaches the athletes).
2. The language we work in (and, if different, the language your athletes
   read their workouts in).
3. Your sports (running, cycling, swimming, triathlon...).

Then create, without showing the content:

| From `${CLAUDE_PLUGIN_ROOT}/workspace/` | To |
|---|---|
| `CLAUDE.md` | `CLAUDE.md` (fill in `{{COACH_NAME}}`, `{{LANGUAGE}}`, `{{SPORTS}}`) |
| `coach-preferences.md` | `coach-preferences.md` |
| `gitignore` | `.gitignore` |

and an empty `athletes/` folder (with a `.gitkeep`).

## 2. Connect intervals.icu

1. Call `check_connection`.
2. If it is not connected (or the key is rejected), tell the coach a page is
   opening in their browser where they paste their intervals.icu key, then call
   `connect_intervals` with the conversation language (`it` or `en`). The page
   shows where to find the key.
   - **Never ask for the key in the chat.** If the coach pastes it here anyway,
     do not repeat it or write it anywhere; suggest generating a new key on
     intervals.icu later, and use the page.
   - If the browser did not open, give them the link returned by the tool.
3. When the coach says it is done, call `check_connection` again and confirm
   with the name of their intervals.icu account.

The coach's intervals.icu account must have coach access to each athlete
(athlete → Settings → share with the coach); athletes who have not done it do
not appear in the list.

## 3. Coaching method (short interview, optional)

Ask whether they want to tune the defaults now (two minutes) or later. If now,
ask in **one message**, offering the current default for each:

1. Methodology (default: polarized 80/20; or pyramidal, threshold-focused...).
2. How workouts are prescribed: HR zones, pace, power, RPE.
3. Recovery week rhythm (default: every 4th week in base, every 3rd in build).
4. Long sessions on which days (default: weekend).
5. Anything their athletes always get in the notes (e.g. RPE, feedback
   questions, fuelling).

Update the matching lines of `coach-preferences.md` with the answers; leave the
rest unchanged. Tell them they can change these any time ("from now on I want
cadence in every bike workout").

Leave the default **visibility rule** as it is: new workouts are uploaded
hidden, the coach checks them on intervals.icu and then asks to make them
visible. Mention it in one sentence; they can switch to "visible right away"
whenever they want.

## 4. First athletes

1. Call `list_athletes` and show the names (not the IDs).
2. Ask which athletes to start with (suggest one or two for the first try).
3. For each chosen athlete run the `new-athlete` skill. It reuses the ID from
   the list and asks only what intervals.icu does not know.

## 5. Consent (one sentence)

Remind the coach that athletes' training and health data is processed by an AI
assistant, and offer a short consent message they can send to their athletes,
from `${CLAUDE_PLUGIN_ROOT}/templates/athlete-consent-template.md`, translated to
the athletes' language. Write it to `athlete-consent.md` only if they want it.

## 6. First value

Offer the first weekly review for one of the onboarded athletes (the `weekly`
skill): "Shall I look at how Anna's last week went and draft the next one?"

## Backup (only if the coach asks, or is comfortable with git)

The folder holds the athletes' health data. Suggest backing it up to a
**private** place (a private git repository, or an encrypted backup). Never a
public repository or a public share link.
