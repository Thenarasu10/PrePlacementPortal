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

163 assertions against a throwaway database, covering:

1. **Scoring** - section and overall percentages with weight renormalisation, negative marking, unanswered vs wrong, multi-select exactness.
2. **Readiness boundaries** - 44.99/45 and 74.99/75 edges, plus custom thresholds from config.
3. **Improvement** - delta and percent change, single attempt, zero baseline (no divide-by-zero), no-data, declining trend.
4. **Adaptive engine** - ability→difficulty cut points, Elo update direction and magnitude, clamping.
5. **Skill-gap ranking** - impact × deficit ordering, strong topics excluded, speed penalty.
6. **Rubrics and code checks** - written-answer rubric and the mocked code runner's partial credit.
7. **Planner re-scheduling** - overdue tasks carried into the current week, completion counters, re-plan after new results.
8. **PRS weighting** - missing components renormalise instead of scoring zero.
9. **Role-based access over real HTTP** - 401 anonymous/bad token, 403 student→mentor and mentor→admin, 200 for the right role, full assessment flow end to end.
10. **Sign-in security** - password policy (length, character mix, common-password and email-reuse rejection), salted PBKDF2 with a unique salt per hash, 256-bit session tokens, expiry and revocation on lookup, lockout after repeated failures (429 + Retry-After), identical responses for unknown email vs wrong password, and security response headers.
11. **Learning library** - every catalogue course points at a real (non-placeholder) external resource, each section has free resources, links are https, and course progress flows through to the catalogue payload.

### UI layout check (optional, needs Node)

The smoke test runs in jsdom, which has no layout engine, so it cannot catch a chart forcing a grid
track wider than the phone screen. This one drives real headless Chrome at four widths:

```bash
./run.sh &                    # server on http://localhost:8080
npm i puppeteer
node tools/ui-layout-check.js
```

24 checks - nothing wider than the viewport at 1440/1280/1024/390, the sidebar lays out as designed
(240px → 76px rail → hidden), the sidebar never overlaps the content column, nav labels appear and
collapse correctly, mobile shows the bottom tab bar, and every chart stays inside its card. Exits
non-zero on failure, so it can run in CI.

### UI smoke test (optional, needs Node)

The Java suite covers logic and the API. To prove the single-page app itself runs, this walks the
real `index.html` in a DOM against the running server:

```bash
./run.sh &                    # server on http://localhost:8080
npm i jsdom                   # once
node tools/ui-smoke.js
```

68 checks over the student, mentor and admin flows: the login role picker, every route, the Learn
screen and its external links (`target=_blank`, `rel=noopener`, no `example.com` left anywhere), the
mock test, the interview, the resume analyser, the profile security panel, the mentor drawer and the
admin security panel and import - and no screen leaks `undefined`/`NaN` or falls back to the error
state. Any uncaught error in the page fails the run.

## 5. Sign-in security

Scope-appropriate hardening for a placement-portal demo - no third-party identity provider, no
payments, nothing that needs a mail server:

| Concern | How it is handled |
|---|---|
| Password storage | PBKDF2-HMAC-SHA256, 120,000 iterations, 16-byte random salt per user. The iteration count is stored in the hash, and a hash made with older settings is transparently upgraded on the next sign-in. |
| Password policy | Minimum 8 characters with an uppercase letter, a lowercase letter and a digit; common passwords and reusing your own email address are rejected. Enforced server-side on sign-up and on password change; the UI only shows a hint meter. |
| Brute force | 5 failed attempts for one email+IP pair locks it for 15 minutes, then 1 hour, then 4 hours. Locked sign-ins get `429` with a `Retry-After` header, and the correct password is refused while the lock stands. |
| User enumeration | Unknown email and wrong password return the same status and the same message, and the unknown-email path still performs a PBKDF2 hash so the timing matches. |
| Sessions | 256-bit `SecureRandom` tokens stored server-side, expiring after 24 hours (checked on every request, not just when pruning). Only the 5 newest sessions per user are kept, expired ones are dropped, and "sign out of all devices" revokes everything immediately. |
| Transport & headers | `Content-Security-Policy` (self-only scripts, no inline JS), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, and `Strict-Transport-Security` when a proxy reports https. |
| Audit trail | Every sign-in, failure, lockout, unlock and password change is recorded and surfaced on the admin **Sign-in security** panel, where an operator can also clear a lock. |
| Authorisation | Every route declares its roles and the check happens server-side on each request - the client never decides what a user may see. |

Not included on purpose (documented rather than half-done): TLS termination, OAuth/SSO, email
verification, 2FA, and per-account rate limiting beyond sign-in. Those belong to a deployment, not
to this demo. Passwords are hashed with a JDK-only implementation so there is nothing to install.

## 6. Learning library (the Learn screen)

Students get a **Learn** section built from two seeded datasets, both plain JSON in `data/`:

* `data/courses.json` - the 11-course catalogue. Every course maps to skill ids, so the same
  entries power the gap recommendations and the catalogue. Each one links out to a real, free,
  externally hosted resource (IndiaBix, GeeksforGeeks, Khan Academy, NPTEL, NeetCode, HackerRank,
  freeCodeCamp, Coursera audit, Tech Interview Handbook, takeUforward).
* `data/resources.json` - 29 free resource links grouped by section (aptitude, logical, coding,
  core-cs, communication, domain, interview, company), each with a provider, type
  (course/video/practice/reference/tool), an hour estimate and a one-line reason to use it.

The screen ranks sections by the student's measured accuracy, flags the weakest one as **start
here**, marks recommended courses, and tracks course progress (`not_started` → `in_progress` →
`completed`). PlaceIQ never hosts or copies the material - it links out only, and every link opens
in a new tab with `rel="noopener noreferrer"`. Editing either JSON file re-seeds on the next start
(content changes are detected by fingerprint, no `--reseed` needed).

## 7. Architecture notes

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

## 8. File structure

```
placeiq/
├── run.sh / test.sh              one-command run and test
├── lib/                          sqlite-jdbc + gson + slf4j-api jars
├── data/                         ALL content and tunables (JSON, no code)
│   ├── config.json               thresholds, weights, adaptive params, planner knobs
│   ├── questions.json            64 questions: mcq, multi, short, coding, written
│   ├── courses.json              11 courses mapped to skill ids, each with a real external link
│   ├── skills.json               skill graph with impact weights and prerequisites
│   ├── role_keywords.json        resume keyword sets, action verbs, profile checklist
│   ├── resources.json            29 free external learning links, grouped by section
│   └── companies/*.json          TCS, Infosys, Zoho, Amazon, Cognizant
├── backend/src/placeiq/
│   ├── Main.java                 HTTP server, routing, role checks, static files, company fit
│   ├── Db.java                   schema, JSON seeding, demo population
│   ├── Auth.java                 PBKDF2 hashing, password policy, lockout, session TTL/revocation, audit log
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

### Layout rules that are easy to get wrong

Three rules keep the responsive shell from breaking, and they are enforced by `tools/ui-layout-check.js`:

1. **The shell grid lives on `#app` as `class="app"`** - `<section id="app" class="app hide">`. Without
   the class the sidebar and content stack instead of sitting side by side.
2. **Replaced elements set `min-width:0`** - a `<canvas>` reports its intrinsic pixel width to the
   layout engine, so on a 390px screen it would stretch its grid track to ~650px. `canvas{max-width:100%;min-width:0}`
   plus `.grid > *, .split > *, .row > *{min-width:0}` keeps every card inside the viewport.
3. **The top bar drops secondary chips on small screens** - the target chip hides under 820px and the
   role chip becomes icon-only under 640px, so the page never scrolls sideways.

### UI layer in one paragraph

One primary action per screen, skeleton (never spinner) loading, and empty/error states everywhere.
Motion uses four durations (150/250/420/500 ms) plus 1200 ms celebrations, a 50 ms stagger capped at
8 items, and a `prefers-reduced-motion` path that drops to 1 ms fades with no confetti or count-ups.
Sound is opt-in synthesised WebAudio (no files, no autoplay) with a per-event map, and during timed
tests it plays only timer warnings and the submit confirmation - never right/wrong - with a visual
and `aria-live` equivalent for every cue. `DESIGN.md` is the full spec.

## 9. What works vs. what is stubbed

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

## 10. Assumptions

1. Single-tenant deployment: one college per instance; mentors are scoped to their own college, admins see all.
2. Session tokens in localStorage with a 7-day expiry - fine for a prototype, not for production.
3. Domain knowledge questions are filtered by the student's branch; other sections are branch-agnostic.
4. Readiness, PRS weights, accuracy targets and thresholds are calm defaults, not psychometrically validated.
5. Interview scoring is rubric-based (keyword coverage, structure, clarity, depth) and deterministic offline.
6. The stub auth has no email verification, password reset or SSO - explicitly out of scope.
7. Content is seeded from JSON into SQLite; the JSON files remain the authoring source of truth.
8. Anti-cheating is limited to tab-switch logging, option/question shuffling and attempt logs - no webcam proctoring.
