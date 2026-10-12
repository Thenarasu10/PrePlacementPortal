# PlaceIQ - placement preparation platform

Adaptive assessment → scoring & readiness → skill-gap analysis → course recommendations → improvement
graph, plus company intelligence, placement tools (resume analyzer, timed mock tests, mock interviews),
a self-re-planning study planner, and mentor/admin views.

**Stack (your choice, as specified):** HTML/CSS/vanilla-JS frontend · Java 11 backend (JDK `HttpServer`,
no framework) · SQLite database via the xerial JDBC driver. Gson is used for JSON.
No Maven/Gradle needed: the jars are committed in `lib/`.

---

## 1. Run it

```bash
cd placeiq
./run.sh                 # compiles and starts on http://localhost:8080
```

Manual equivalent:

```bash
javac -cp "lib/*" -d bin $(find backend/src -name '*.java')
java  -cp "bin:lib/*" placeiq.Main
```

Useful flags:

```bash
java -cp "bin:lib/*" placeiq.Main --reseed          # reload data/*.json into the database
PORT=9090 java -cp "bin:lib/*" placeiq.Main         # different port
java -Dplaceiq.db=jdbc:sqlite:/tmp/x.db -cp "bin:lib/*" placeiq.Main
```

## 2. Demo logins

| Role    | Email                | Password    |
|---------|----------------------|-------------|
| Student | student@placeiq.dev  | student123  |
| Mentor  | mentor@placeiq.dev   | mentor123   |
| Admin   | admin@placeiq.dev    | admin123    |

The student account ships with sample history (6 attempts showing 35% → 68%, a finished mock test,
a scored interview, a resume report, a 6-week plan) so every chart renders on first run.
Other demo students: `nithya@`, `karthik@`, `sneha@`, `farhan@` (all `student123`).

## 3. Optional environment variables

| Variable | Effect |
|---|---|
| `PLACEIQ_AI_KEY` | Enables the hosted LLM provider for interview questions, answer scoring and resume suggestions |
| `PLACEIQ_AI_URL` | Override the endpoint (default OpenAI chat completions) |
| `PLACEIQ_AI_MODEL` | Model name (default `gpt-4o-mini`) |

With no key the app is 100% offline and uses the deterministic rubric provider.

## 4. Tests

```bash
./test.sh
```

84 assertions against a throwaway database, covering:

1. **Scoring** - section and overall percentages with weight renormalisation, negative marking, unanswered vs wrong, multi-select exactness.
2. **Readiness boundaries** - 44.99/45 and 74.99/75 edges, plus custom thresholds from config.
3. **Improvement** - delta and percent change, single attempt, zero baseline (no divide-by-zero), no-data, declining trend.
4. **Adaptive engine** - ability→difficulty cut points, Elo update direction and magnitude, clamping.
5. **Skill-gap ranking** - impact × deficit ordering, strong topics excluded, speed penalty.
6. **Rubrics and code checks** - written-answer rubric and the mocked code runner's partial credit.
7. **Planner re-scheduling** - overdue tasks carried into the current week, completion counters, re-plan after new results.
8. **PRS weighting** - missing components renormalise instead of scoring zero.
9. **Role-based access over real HTTP** - 401 anonymous/bad token, 403 student→mentor and mentor→admin, 200 for the right role, full assessment flow end to end.

### UI smoke test (optional, needs Node)

The Java suite covers logic and the API. To prove the single-page app itself runs, this walks the
real `index.html` in a DOM against the running server:

```bash
./run.sh &                    # server on http://localhost:8080
npm i jsdom                   # once
node tools/ui-smoke.js
```

51 checks over the student, mentor and admin flows: every route renders, the mock test runs and
submits, the interview scores and finishes, the resume analyser grades, the mentor drawer opens,
the admin import reports - and no screen leaks `undefined`/`NaN` or falls back to the error state.
Any uncaught error in the page fails the run.

## 5. Architecture notes

**Data model (SQLite, `data/*.json` seeded on first run)**

```
users, sessions, questions, attempts, attempt_answers, readiness_history,
courses, skills, companies, course_progress, plans, plan_tasks,
interviews, interview_answers, mock_tests, mock_test_answers,
resume_reports, checklist, srs_reviews, events, mentor_notes,
notifications, badges, xp_log, meta
```

Content is never hardcoded in components: `data/questions.json` (64 questions), `data/courses.json`
(11 courses), `data/skills.json` (33-skill graph with prerequisites), `data/companies/*.json` (5 companies),
`data/role_keywords.json` (resume keyword sets + profile checklist) and `data/config.json` (every tunable).

**Adaptive difficulty** (`Scoring.nextDifficulty` / `updateAbility`, swappable without touching anything else)

```
expected = ability / (ability + difficultyRating)      # rating: easy .3, medium .6, hard .9
ability  = clamp(ability + k * (correct - expected), 0, 1)      # k = 0.35
next     = ability < 0.33 ? easy : ability < 0.66 ? medium : hard
```

One ability value per section per attempt, persisted in `attempts.meta_json`. A `rule` model
(last answer right ⇒ harder) is one config flag away.

**Readiness levels** - `score < 45` Beginner, `< 75` Intermediate, else Advanced (config thresholds).

**Placement Readiness Score (0-100)** - weighted composite, missing components dropped and the rest
renormalised: assessment .45, best mock test .20, mock interview .15, resume ATS .10, course completion .10.
Every response carries a per-component breakdown so the UI can explain what moved the score.

**Section scoring** - each question is worth 1 mark; `sectionPct = awarded / count × 100`; overall is the
weighted mean over the sections present (aptitude .25, logical .20, coding .25, communication .15, domain .15).

## 6. File structure

```
placeiq/
├── run.sh / test.sh              one-command run and test
├── lib/                          sqlite-jdbc + gson + slf4j-api jars
├── data/                         ALL content and tunables (JSON, no code)
│   ├── config.json               thresholds, weights, adaptive params, planner knobs
│   ├── questions.json            64 questions: mcq, multi, short, coding, written
│   ├── courses.json              11 courses mapped to skill ids
│   ├── skills.json               skill graph with impact weights and prerequisites
│   ├── role_keywords.json        resume keyword sets, action verbs, profile checklist
│   └── companies/*.json          TCS, Infosys, Zoho, Amazon, Cognizant
├── backend/src/placeiq/
│   ├── Main.java                 HTTP server, routing, role checks, static files, company fit
│   ├── Db.java                   schema, JSON seeding, demo population
│   ├── Auth.java                 PBKDF2 hashing, sessions, role helpers
│   ├── Json.java / Time.java     shared helpers (JSON columns, dates)
│   ├── Scoring.java              per-question award, aggregates, readiness, improvement, PRS, adaptive model
│   ├── Assessment.java           start → next → answer → submit, proctoring counters, gamification
│   ├── Analytics.java            improvement series, radar/heatmap/scatter, PRS components, dashboard payload
│   ├── Gaps.java                 gap ranking, course recommendations, spaced repetition, course progress
│   ├── Planner.java              task generation, auto re-planning, next-best-action
│   ├── Tools.java                resume/ATS analyzer (JDK-only PDF & DOCX parsing), checklist, mock tests, interviews
│   ├── MentorAdmin.java          student monitoring, at-risk flags, notes, assignments, cohort, question bank, import/export
│   ├── Content.java              read model for content and student history
│   ├── CodeRunner.java           code-execution interface (mocked runner shipped)
│   ├── Ai.java                   AI provider interface (offline rubric + optional hosted)
│   └── Tests.java                84-assertion suite
├── tools/ui-smoke.js             optional headless DOM walkthrough of the UI (Node + jsdom)
├── DESIGN.md                     design/motion/sound spec: tokens, sound table, per-screen notes
└── static/
    ├── index.html                app shell (auth + shell markup)
    ├── css/app.css               design tokens, dark/light themes, responsive layout
    └── js/app.js                 SPA logic: routing, screens, forms, state
        js/charts.js              canvas chart library (line, radar, scatter, bars, heatmap, rings)
        js/motion.js              motion helpers: durations, stagger, count-ups, rings, milestones
        js/sound.js               opt-in synthesised sound manager (no audio files, off by default)
```

### UI layer in one paragraph

One primary action per screen, skeleton (never spinner) loading, and empty/error states everywhere.
Motion uses four durations (150/250/420/500 ms) plus 1200 ms celebrations, a 50 ms stagger capped at
8 items, and a `prefers-reduced-motion` path that drops to 1 ms fades with no confetti or count-ups.
Sound is opt-in synthesised WebAudio (no files, no autoplay) with a per-event map, and during timed
tests it plays only timer warnings and the submit confirmation - never right/wrong - with a visual
and `aria-live` equivalent for every cue. `DESIGN.md` is the full spec.

## 7. What works vs. what is stubbed

**Works end to end:** registration/login with hashed passwords and server-side role checks · adaptive
assessment with MCQ/multi/short/coding/written · shuffling + tab-switch logging · scoring, readiness and PRS ·
gap ranking and course recommendations · spaced repetition · improvement graph and skill charts · planner with
auto re-planning · company intelligence and fit analysis · resume analyzer (PDF/DOCX/text) with ATS scoring ·
timed company-pattern mocks with cut-offs, negative marking and auto-submit · mock interviews with rubric
feedback · mentor monitoring with at-risk flags, notes and assignments · admin cohort analytics, question-bank
CRUD, CSV/JSON import with row-level validation, CSV export.

**Deliberately stubbed (interfaces in place):**
- **Code execution** - `CodeRunner.MockRunner` never runs student code; it awards partial credit for static
  patterns and reports `executed:false`. Swap in a Docker/gVisor sandbox to run the real test cases. Be honest
  with students: static checks are not correctness.
- **AI provider** - deterministic offline rubric by default; the hosted path is a thin HTTP call and falls back
  silently to the offline rubric on any failure.
- **Resume parsing** - JDK-only best effort. Scanned/encrypted PDFs yield empty text, so the UI always offers
  the paste-text path.
- **Notifications** - stored and displayed in-app; no email/push infrastructure (out of scope).

## 8. Assumptions

1. Single-tenant deployment: one college per instance; mentors are scoped to their own college, admins see all.
2. Session tokens in localStorage with a 7-day expiry - fine for a prototype, not for production.
3. Domain knowledge questions are filtered by the student's branch; other sections are branch-agnostic.
4. Readiness, PRS weights, accuracy targets and thresholds are calm defaults, not psychometrically validated.
5. Interview scoring is rubric-based (keyword coverage, structure, clarity, depth) and deterministic offline.
6. The stub auth has no email verification, password reset or SSO - explicitly out of scope.
7. Content is seeded from JSON into SQLite; the JSON files remain the authoring source of truth.
8. Anti-cheating is limited to tab-switch logging, option/question shuffling and attempt logs - no webcam proctoring.
