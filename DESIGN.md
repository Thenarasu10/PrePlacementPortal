# PlaceIQ — design, motion and sound specification

Implemented in code: `static/css/app.css` (tokens, layout, components, motion), `static/js/motion.js`
(motion helpers), `static/js/sound.js` (sound manager), `static/js/charts.js` (animated charts),
`static/js/app.js` (screens and wiring). Everything below is live in the running app.

---

## 1. Foundations

### Colour tokens
| Token | Value | Use |
|---|---|---|
| `--indigo` | `#4F46E5` | primary actions, active nav, focus, default chart line |
| `--success` | `#16A34A` | positive deltas, completed states |
| `--warning` | `#F59E0B` | caution, "behind" banner, timer at 5 min |
| `--danger` | `#DC2626` | errors, timer under 1 min, destructive |
| neutrals | `--g50…--g900` | surfaces, borders, text |
| readiness | Beginner `--lvl-beginner` (amber), Intermediate `--lvl-intermediate` (blue), Advanced `--lvl-advanced` (green) | **one mapping, used everywhere** |

Light and dark are both defined via the same variable names (`[data-theme="dark"]`), so every component
and chart redraws correctly on theme switch (`Charts` reads colours from CSS variables at draw time).

### Type, spacing, elevation
- Inter with `system-ui` fallback (no webfont request, keeps the app offline-capable).
- Scale: 12 / 14 / 16 / 20 / 28 / 36. Bold reserved for headings and key numbers; numbers use tabular figures.
- Spacing: 8px base (`--s1`…`--s8`). Radii 8 / 12 / 16. Elevation `--shadow-1..3`.

### Motion tokens (shared by CSS and the JS layer)
| Token | Value | Applied to |
|---|---|---|
| `--dur-micro` | 150ms | hover, press, taps, chip/state changes |
| `--dur-comp` | 250ms | drawers, modals, tab underline, question slide, card state |
| `--dur-page` | 420ms | page enter, onboarding steps, radar expand |
| `--dur-chart` | 500ms | line draw, bar growth, scatter pop-in |
| `--dur-celebrate` | 1200ms | sparkle bursts and the ring's final sweep |
| `--ease-out` | `cubic-bezier(.16,1,.3,1)` | entering |
| `--ease-in` | `cubic-bezier(.4,0,1,1)` | exiting |
| `--ease-spring` | `cubic-bezier(.34,1.32,.64,1)` | cards, rings, modals (low overshoot) |
| `--stagger-step` | 50ms (cap 8) | list and grid entrances |
| `--lift` / `--press` | 3px / 0.97 | card hover lift, button press |

Nothing exceeds 600ms except celebratory moments (1.2s). Animated properties are limited to
`transform`, `opacity`, `width`/`stroke-dashoffset` where a shape must change.

**Reduced motion:** `@media (prefers-reduced-motion: reduce)` collapses all durations to 1ms, hides
sparkles and hover lifts; `M.reduced()` additionally short-circuits count-ups (values jump straight to
target), typing (text appears at once), morphs, cross-fades and confetti.

### Sound system (`static/js/sound.js`)
- **Opt-in only.** Off until enabled in onboarding or Profile. `AudioContext` is created on the first
  user gesture — nothing autoplays on load.
- Volume slider (stored), per-event mapping, all tones synthesised with oscillators (no audio files).
- **Test mode rule:** during the adaptive assessment and timed mock tests only `timerWarn` and
  `submitConfirm` are allowed. `correct` / `incorrect` are muted so the sound never reveals an answer.

| Event | Character | Length |
|---|---|---|
| `tap` | very quiet glassy tap (660 Hz sine) | 70ms |
| `correct` | short rising chime (C5→G5 triangle) | 280ms |
| `incorrect` | soft low non-punishing tone (A3→G3) | 340ms |
| `taskCheck` | light click-chime (A5→E6) | 180ms |
| `stepComplete` | two-note chime (D5→A5) | 360ms |
| `pageComplete` | two-note chime (E5→B5) | 400ms |
| `dayComplete` | brighter three-note chime | 500ms |
| `milestone` | ascending four-note flourish (C5-E5-G5-C6) | ~1.0s |
| `timerWarn` | two gentle ticks, 160ms apart (not an alarm) | 250ms |
| `notify` | single soft ping (D6) | 180ms |
| `submitConfirm` | two-note confirmation | 340ms |

Every sound has a visible equivalent (text, badge or chip), plus an `aria-live` region (`#srLive`) that
announces the same information for screen readers.

---

## 2. Global layout

- **Desktop:** 240px collapsible sidebar (collapses to a 76px rail via the ☰ button), sticky top bar with
  page title, global search, target chip, notifications and profile, and a content area capped at 1280px.
- **Tablet (≤1100px):** sidebar becomes a permanent icon rail (labels hidden).
- **Mobile (≤820px):** sidebar hidden, **bottom tab bar** with Home / Tests / Plan / Companies / Profile;
  the search field collapses away and content gets bottom padding to clear the bar.
- Every screen has a title, one primary action, and defined empty / loading / error states.
  Loading uses **skeleton shimmer** (`M.skeleton`), never a spinner, except for in-flight button actions
  (`.btn.loading`) which use a small spinner because they resolve in under two seconds.

### Reusable components and their state transitions
| Component | Default | Hover | Press | Disabled / loading | Error |
|---|---|---|---|---|---|
| Button | surface + border | border darkens + `--shadow-1` | `scale(.97)` in 150ms | 50% opacity, no transform / `.loading` swaps label for spinner | `.btn.danger` pair with inline message |
| Input | border `--line` | border `--g300` | — | `disabled` muted | `aria-invalid` + `--danger` border + shake + `.field-error` |
| Card | `--shadow-1`, radius 16 | `.lift`: −3px + `--shadow-2` | — | skeleton placeholder | inline error copy |
| Tab | muted label | text darkens | — | — | — |
| Tabs (underline) | 2px indigo bar | label darkens | — | — | — |
| Badge / level chip | tinted by readiness | — | — | — | — |
| Toast | slide-up, 3.2s | — | — | — | — |
| Modal | backdrop fade + card springs in | — | — | — | Escape / backdrop close |
| Drawer (mentor) | 250ms slide from right + backdrop | — | — | — | Escape / backdrop close |
| Score ring | track + animated value stroke | — | — | shows 0 with empty-state copy | — |
| Task row | pending | background tint | checkbox scales | done: strike-through + muted | overdue label |
| Chart card | title, filter, canvas | point tooltip scales in (140ms) | — | skeleton | no-data text + summary |

---

## 3. Student screens

### 3.1 Onboarding (3 steps)
- **Regions:** centred card; stepper (dots + connector lines) top-right; step body; primary action bottom.
- **Step 1** profile fields + **"Enable sounds"** block with *Preview* and on/off — the only place sound is
  offered before it is used. **Step 2** target role (with suggestions) and company. **Step 3** section
  weights summary + *Start diagnostic test* (primary) and *Skip for now* (ghost).
- **Responsive:** two-column form on desktop, single column under 820px; stepper shrinks but stays.
- **Motion:** each step slides in from the right (420ms `--ease-out`, 18px) and the previous step slides from
  the left when going back; connector lines fill left-to-right (420ms); dots spring when they become current
  (240ms `--ease-spring`).
- **Sound:** preview button plays `correct`; `stepComplete` on each step advance; no sound on load.

### 3.2 Dashboard
- **Regions:** full-width **Next best action** card (primary-tinted), then a row with the Placement
  Readiness **score ring** (0–100) + "what moved it" note and three KPI tiles, then **five section cards**
  (score, level badge, mini trend line, points gained), then **Upcoming tasks**, then score progression,
  readiness breakdown, radar, accuracy-vs-speed, topic heatmap, gaps, recommendations and history.
- **States:** empty (no attempts → ring at 0 with "take an assessment" copy, empty-state charts),
  loading (6 skeleton cards), error (inline card with the message).
- **Responsive:** 4-up → 2-up → 1-up; ring and KPIs stack under 820px; bottom tab bar visible.
- **Motion:** ring sweeps 0→value over 1.1s (`--ease-out`) while the number counts up in parallel; mini
  trend polylines draw with the card entrance (420ms, 50ms stagger, capped at 8); cards enter with a
  10px rise + fade.
- **Sound:** `pageComplete` **once per day**, and only after the ring lands (`firstViewToday('ring')`).

### 3.3 Assessment (adaptive, one question per screen)
- **Regions:** progress/timer meta row (question chip, section, topic, difficulty, overall countdown),
  question card, answer area (large keyboard-selectable options / textarea / code editor with check list),
  action row, feedback panel, and a **question navigator** strip (answered ✓ / current / upcoming) that sits
  to the right on desktop and below on mobile.
- **States:** unanswered (option hover lifts 1px), selected (indigo border + one-shot background flash),
  submitted (correct/partial/incorrect feedback with explanation and model answer), skipped, calm
  tab-switch banner, auto-submit at 0:00.
- **Motion:** current card slides out left (250ms `--ease-in`) and the next slides in from the right
  (250ms `--ease-out`, 18px). Timer text turns amber at 5 minutes (2s soft pulse) and red under 1 minute
  (1s pulse).
- **Sound:** `tap` on every option selection; `timerWarn` at exactly 5 minutes and 1 minute remaining.
  **No** right/wrong sounds (`Sound.setTestMode(true)` for the whole attempt); `submitConfirm` on submit.

### 3.4 Results
- **Regions:** result header with overall score + level badge, section breakdown table, "readiness after
  this attempt" breakdown, the top gap cards each with *Start this course*, and next-action buttons.
- **Motion:** overall score counts up (900ms); radar polygon **expands from the centre** (420ms); heatmap
  cells fade in row by row (50ms stagger); gap cards rise in sequence (55ms stagger).
- **Sound:** `milestone` (+ sparkle burst anchored to the score card) when the readiness level **increases**;
  otherwise `pageComplete`. First-ever diagnostic additionally triggers a milestone once per student.

### 3.5 Progress
- **Regions:** improvement KPIs (first / latest / improvement with trend chip), line chart with a
  **series toggle** (Overall + each section), readiness history table, spaced-repetition queue, all attempts.
- **Motion:** lines draw left-to-right over 500ms; the improvement badge counts up; switching series
  cross-fades the canvas (120ms out, 250ms in); hovering a point shows a tooltip that scales in (140ms).
- **Sound:** `tap` on toggle; a soft `pageComplete` is available when improvement is positive (piggybacks on
  the same count-up completion).

### 3.6 Courses
- **Regions:** skill-gap table (accuracy, deficit, impact, severity) then the course grid; each card shows
  the gap it closes, "why this", hours, difficulty-match tag and a status chip.
- **Motion:** cards lift on hover (−3px, `--shadow-2`); the status chip animates when it changes; the
  progress bar fills smoothly (250ms) when a course is marked in progress.
- **Sound:** `taskCheck` on "in progress", `milestone` (+ sparkles on the card) on completion.

### 3.7 Company explorer
- **Regions:** search field, filterable company grid, and the detail view with **Rounds / Questions /
  Skills / Tips** tabs and a **fit ring** with the top three gaps, section bars and a text summary.
- **Motion:** the grid reflows with a 45ms stagger when filtered; the clicked card **morphs** into the
  detail panel (the modal card grows out of the source card's rect over 250ms); tabs use a sliding
  underline; the fit ring and percentage count up together (900ms).
- **Sound:** none (information-dense screen).

### 3.8 Planner
- **Regions:** plan header with completion, **"You're behind" banner** with *Re-plan now*, weekly cards each
  with a completion ring, and checkable task rows grouped by due date within the week.
- **Motion:** ticking a task draws the tick, strikes the label (250ms) and nudges the weekly ring
  (320ms spring); carried-over tasks slide into their new day (`fade-up` 420ms + row highlight);
  the weekly ring animates on load from 0 to the completion value.
- **Sound:** `taskCheck` per task; a brighter `dayComplete` (+ a small sparkle burst) when every task in
  that day is done; no sound on re-planning itself.

### 3.9 Mock interview
- **Regions:** mode selector (HR / Technical / Behavioural), question card with typing target, answer box,
  and a feedback panel with four **rubric bars** (relevance, structure, clarity, depth) plus a model answer.
- **Motion:** the question types in line by line with a caret (≈55 chars/s); a three-dot pulsing "thinking"
  indicator covers the ≤600ms scoring round-trip; the four rubric bars fill one after another (120ms apart).
- **Sound:** `notify` when feedback is ready. Nothing on keystrokes.

### 3.10 Resume analyser
- **Regions:** dropzone (drag-and-drop or click) plus paste-text fallback, ATS **gauge**, score breakdown,
  fix-suggestions checklist and the profile checklist.
- **Motion:** the dropzone highlights on drag-over and scales 1.01; a progress bar shows while parsing;
  the gauge sweeps and the number counts up (900ms, coloured by band); suggestions fade in one by one (60ms).
- **Sound:** `notify` when analysis finishes; `taskCheck` (+ small sparkle) when a checklist item is ticked.

### 3.11 Timed mock test
- **Regions:** pattern cards with sections, cut-off and negative marking; in-test header with countdown and
  auto-submit notice; post-test review table with per-question time.
- **Motion:** identical question slide behaviour to the assessment; **review rows stagger in (50ms)** and the
  three slowest questions are highlighted amber; countdown turns amber/red with the same pulse as above.
- **Sound:** `timerWarn` at 5 minutes and 1 minute, `submitConfirm` on submit (manual or automatic) — nothing else.

---

## 4. Mentor screens (desktop-first)

- **Regions:** cohort KPI tiles, sortable student table (readiness, trend, last active, at-risk flags),
  section-weakness and distribution charts, and a **student detail drawer** with score history, gaps,
  notes, assignments and event log.
- **Motion:** the drawer slides in from the right (250ms `--ease-out`) over a dimmed backdrop that fades in;
  sorting re-orders rows with a 40ms stagger; the first at-risk flag pulses **once** on load.
- **Sound:** none by default (mentors often work in shared spaces).

## 5. Admin screens (desktop-first)

- **Regions:** KPI cards (cohort size, average PRS, plan completion, average improvement), section-wise
  weakness chart, readiness distribution, roster with CSV export, question-bank table, import flow.
- **Motion:** KPI numbers count up (700–900ms); charts draw in; import shows a filling progress bar;
  validation errors **shake once** (220ms, 4px amplitude) and highlight the offending rows.
- **Sound:** `stepComplete` (soft success chime) when an import finishes cleanly; **no sound on errors**.

---

## 6. Celebration policy

| Moment | Trigger | Motion | Sound |
|---|---|---|---|
| First diagnostic completed | attempt count reaches 1 | sparkles around the score card (~1.2s) | `milestone` |
| New readiness level | band increases vs last attempt | sparkles around the result card | `milestone` |
| Full day of tasks cleared | all of that day's tasks checked | small burst on the task row | `dayComplete` |
| Course completed | status set to completed | burst on the status chip | `milestone` |

All four are anchored to the relevant card (never full-screen), skippable in the sense that they never block
interaction, and disabled entirely under reduced motion or when sounds are off.

---

## 7. Notes and honest gaps

1. Sound is synthesised, not sampled — deliberately, so the app stays a single offline bundle. Swapping in
   audio files only means changing the note tables in `sound.js`.
2. The shared-element morph is a FLIP animation of the modal card from the source card's rectangle. It reads
   as a morph for grids; a true view-transition API implementation would be smoother on very large cards.
3. Chart tooltips are canvas hit-tests (14px radius) rather than DOM nodes, for 60fps on long series.
4. Inter is used when it is installed locally; otherwise the system UI font stack is used. No webfont is
   fetched, so the app still works with no network.
5. Mentor/admin screens are laid out desktop-first with responsive fallbacks, as specified — they are not
   optimised for phone-sized editing of the question bank.


## Sign-in screen and the Learn library (added after the first pass)

**Login.** Three role entry points - *Student / Mentor / Admin* - sit above the same form. Choosing one
pre-fills that demo account and swaps a one-line description of what the role gets; nothing is
submitted automatically, so the user always takes the final action. The role accent is carried into
the app as a chip in the top bar (indigo = student, green = mentor, amber = admin) so nobody is
unclear about which view they are in. Failed sign-ins shake the error line once (no confetti, no
sound spam) and the message never says whether the email exists.

**Learn.** The screen is a two-part reading layout: a personalised header (weakest section, accuracy
target, courses-completed ring) then cards. Course cards are equal-height with chips for provider,
hours, level and *free*, an SVG progress ring per course, one primary action (`Open free resource ↗`)
and quiet secondary actions. Resource items are flat bordered rows - title as the link, a type chip,
provider, hour estimate, and a `start here` chip on the resources that match the weakest section.
Everything external opens in a new tab with `rel="noopener noreferrer"`.

**Progression.** Sections are always ranked weakest-first (same ordering as the gap engine, so the
two screens never disagree). Course state moves `not_started → in_progress → completed`, and the
completion milestone reuses the existing in-card sparkle + `stepComplete` cue rather than inventing a
new celebration.
