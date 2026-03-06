# Onboarding Flow Proposal (Review Draft)

This proposal updates the onboarding flow to match the latest product requirements before implementation.

## Goals

- Keep onboarding linear (no "Skip for now" branch).
- Clearly explain automatic sleep detection before users choose logging mode.
- Let users choose between **automatic** and **manual** sleep logging.
- Only ask automatic configuration questions for users who choose automatic mode.
- Include a dedicated permissions screen with a clear **Request permissions** button and per-permission rationale.
- Show sensors permission only when applicable (GrapheneOS / permission exists on device).

---

## Proposed Step-by-Step Flow

### Step 1: Welcome

- Intro to SleepTracker.
- CTA: **Get started**.

### Step 2: Health Connect required

- Validate Health Connect availability.
- If installed: show ready state and continue.
- If missing:
  - Show explanation that Health Connect is required.
  - CTA: **Install Health Connect** (launch Play Store / provider install flow).
  - CTA: **I installed it** (re-check installation).
- No skip option.

### Step 3: How automatic detection works (explanation page)

A dedicated educational page users must click through before logging mode selection:

- SleepTracker watches screen lock/unlock events.
- In automatic mode, bed time and wake time are inferred using configured windows.
- Users still confirm/edit detected sessions before save.

CTA: **Continue**.

### Step 4: Choose logging mode

Prompt:

- **How do you want to log sleep?**
  - **Automatic** (recommended)
  - **Manual**

CTA: **Continue**.

### Step 5A: Automatic setup (only if Automatic selected)

Ask these questions on a focused configuration screen:

1. **When do you usually go to sleep between?**
   - Helper text: "The last time your phone locks in this range will be logged as when you went to bed."
   - Inputs: start time + end time.

2. **What's the latest you will wake up?**
   - Helper text: "The app will look for when your phone unlocks until this point to determine when you wake up."
   - Input: time.

3. **Track fall-asleep and wake-up refinements?** (toggle)
   - If enabled, app will refine detected times using additional signals/rules.

4. **Track naps?** (toggle)
   - If enabled, show **Minimum nap duration** selector on the same page.
   - Suggested control: duration picker (e.g., 10 min increments).

CTA: **Save auto settings**.

### Step 5B: Manual setup intro (only if Manual selected)

Show explanatory page:

- "You'll first configure a daily sleep template for future days."
- "After this, we'll open template editing so you can set your usual schedule."

CTA: **Set up template**.

### Step 6B: Manual template edit (only if Manual selected)

Bring up template editor screen for future-day defaults, e.g.:

- Default bedtime.
- Default wake time.
- Default sleep quality / tags (if supported).
- Weekday/weekend variation (if supported).

CTA: **Save template**.

### Step 7: Permissions

Dedicated permission page with explicit rationale and buttoning.

- Primary button: **Request permissions**.
- Each permission row shows:
  - title
  - status (Granted / Not granted)
  - why this is needed

Permissions to request/show:

1. **Health Connect: Read Sleep**
   - Why: display sleep history.
2. **Health Connect: Write Sleep**
   - Why: save confirmed sleep sessions.
3. **Notifications** (if foreground tracking notifications are used)
   - Why: required for reliable background tracking UX.
4. **Sensors** (GrapheneOS or compatible permission model only)
   - Why: enable sensor-assisted sleep/wake refinement when available.
   - Hidden entirely when not available/applicable.

CTA enabled after required permissions granted: **Continue**.

### Step 8: Completion

- Summary of selected logging mode + key settings + permission status.
- CTA: **Finish onboarding**.

---

## What Users Will Configure

### For everyone

- Health Connect installation.
- Required permissions.

### If automatic logging selected

- Bedtime lock window (start/end).
- Latest wake-up time.
- Optional fall-asleep/wake-up refinement.
- Optional nap tracking and nap minimum duration.

### If manual logging selected

- Daily template for future entries.
- Template values edited in a dedicated template editor.

---

## UX Copy Notes

- Keep the auto-detection explanation plain-language and non-technical.
- On permission screen, keep every permission rationale to 1–2 lines.
- Avoid showing irrelevant permission rows (especially sensors when unavailable).
- Keep the flow linear and explicit; no deferred setup branch.
