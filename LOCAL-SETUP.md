# Run PlaceIQ on your own machine

Everything runs locally: one Java process serves both the API and the web app, and the database is
a single SQLite file. No Maven, no npm, no Docker, no internet connection needed after the clone.

---

## 1. What you need

| Requirement | Notes |
|---|---|
| **JDK 11 or newer** (`javac` + `java`) | A **JDK**, not just a JRE — the launcher compiles the backend on first run. |
| ~50 MB disk | Source, three jars in `lib/`, and the SQLite file. |
| A browser | Chrome, Edge, Firefox or Safari. |

Check what you have:

```bash
javac -version      # must print javac 11 or higher, e.g. "javac 17.0.9"
java  -version
```

If `javac` is not found, install **Eclipse Temurin JDK 17** (free):
[adoptium.net/temurin/releases](https://adoptium.net/temurin/releases/)
On Windows, tick **"Set JAVA_HOME variable"** and **"Add to PATH"** during setup, then open a **new**
terminal so the PATH change applies.

---

## 2. Get the code

```bash
git clone <your-repo-url> placeiq
cd placeiq
```

No git? Download the ZIP of the repo, or copy the `placeiq/` folder, then `cd` into it.

---

## 3. Start it

**macOS / Linux**
```bash
./run.sh
```

**Windows** (double-click `run.bat`, or from a terminal)
```bat
run.bat
```

**What you should see**
```
Compiling Java backend...
PlaceIQ running on http://localhost:8080  (db=placeiq.db)
Demo logins: student@placeiq.dev/student123 | mentor@placeiq.dev/mentor123 | admin@placeiq.dev/admin123
```

First start compiles the Java sources and **seeds the database** — 64 questions, 11 courses,
33 skills, 5 companies and three demo users with history, so every chart has data immediately.
Leave that terminal window open; the server runs until you press `Ctrl + C`.

---

## 4. Open it

Go to **<http://localhost:8080>**

| Role | Email | Password | What you land on |
|---|---|---|---|
| Student | `student@placeiq.dev` | `student123` | Onboarding → dashboard |
| Mentor | `mentor@placeiq.dev` | `mentor123` | Student monitor |
| Admin | `admin@placeiq.dev` | `admin123` | Cohort analytics |

The login screen also has one-click demo buttons, so you never have to type these.

---

## 5. A 10-minute tour

1. **Log in as the student.** A 3-step onboarding runs (goal → target company → start diagnostic) — it appears once per user.
2. **Dashboard** — readiness ring, PRS score, five section cards, upcoming tasks and the **Next best action** button. Deep-link to any section from the sidebar.
3. **Start assessment** — questions are adaptive (they get harder as you get them right). The timer slides amber at 5 minutes, red at 1 minute.
4. **Results** — count-up score, level-up milestone, per-section breakdown, then **Continue to plan**.
5. **Skill gaps** — ranked weakest-first, each with a matching course. Mark one complete for the sparkle milestone.
7. **Planner** — your weekly plan with tasks; tick them off and the weekly ring nudges up.
8. **Mock tests / Mock interview / Resume analyser** — a timed company-pattern paper with a post-test review, a scored mock interview with a rubric, and an ATS scan with a drop-zone for a PDF/DOCX.
9. **Companies** — TCS, Infosys, Zoho, Amazon, Cognizant. Open one for a fit analysis across its rounds.
10. **Sound and theme** — the speaker icon in the top bar switches sound on (off by default; it never plays in a timed test beyond timer warnings). The theme toggle switches light/dark.
11. **Log out, log in as the mentor** — sort the student table, open a student drawer, add a note, assign a task. Then as **admin** — cohort KPIs, the question bank, and CSV import/export.

---

## 6. Sign-in security (what the login page enforces)

* The page has three entry points - **Student / Mentor / Admin** - which pre-fill that demo account and
  explain what the role can see. The form itself is one secure path; which role you actually get is
  decided by the server on every request, never by the page.
* Passwords are stored as salted PBKDF2-HMAC-SHA256 (120,000 iterations) and are never logged.
  New passwords need 8+ characters with upper, lower and a digit.
* **5 wrong tries from one device locks that account for 15 minutes** (then 1 hour, then 4 hours) -
  the response is a `429` with a `Retry-After` header, and even the correct password is refused while
  the lock stands. Wrong password and unknown email give the identical message, so the form cannot be
  used to discover who has an account.
* Sessions last 24 hours, are stored server-side and are checked on every request. **Profile → Sign-in
  security → "Sign out of all devices"** revokes them all; changing your password also signs out every
  other device.
* As **admin** you get a **Sign-in security** panel: locked accounts, the recent sign-in audit trail,
  and an **Unlock** button.

To clear a lock during a demo without the admin panel: `sqlite3 placeiq.db "DELETE FROM login_attempts;"`

## 7. Useful commands

```bash
# a different port (e.g. if 8080 is taken)
PORT=3000 ./run.sh                 # macOS/Linux
set PORT=3000 && run.bat           # Windows

# wipe and re-seed the database to its demo state
./run.sh --reseed                  # or: run.bat --reseed

# keep the database somewhere else / use a separate one per demo
java -Dplaceiq.db=data/demo2.db -cp "bin:lib/*" placeiq.Main

# backend test suite (84 assertions, ~5 s)
./test.sh                          # Windows: test.bat

# optional headless UI walkthrough (needs Node + jsdom)
npm i jsdom && node tools/ui-smoke.js
```

---

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `javac: command not found` / `'javac' is not recognized` | A JRE, or no Java, is installed. Install a JDK (step 1) and open a **new** terminal. |
| `Address already in use` / port 8080 busy | Something else is on 8080. Start with another port: `PORT=3000 ./run.sh`, then use `http://localhost:3000`. On Windows find it with `netstat -ano | findstr :8080`. |
| Page loads but the layout looks broken | Hard refresh (`Ctrl + Shift + R`) — the CSS/JS are cached aggressively by the browser. |
| Login says the credentials are wrong | The email must be exact: `student@placeiq.dev` (not `.com`). Or reset everything with `./run.sh --reseed`. |
| "Too many failed attempts" | The brute-force lock is doing its job. Wait 15 minutes, clear it as admin (**Sign-in security → Unlock**), or run `sqlite3 placeiq.db "DELETE FROM login_attempts;"`. |
| Charts or data look stale after editing `data/*.json` | Content is seeded into SQLite on first run, so re-seed to pick up edits: `./run.sh --reseed`. |
| `No suitable driver found` | The `lib/` jars are missing from the working directory — run the launcher from the project root. |
| Terminal closed, site gone | The server only lives while that command runs. Start it again with `./run.sh`. |
| Want to start over completely | Delete `placeiq.db` (plus `placeiq.db-wal` / `placeiq.db-shm`) and run `./run.sh`. |

---

## 9. Where things live

```
placeiq/
├── run.sh / run.bat        start the app
├── test.sh / test.bat      run the 84-assertion backend suite
├── static/                 the web app (HTML + CSS + vanilla JS)
├── backend/src/placeiq/    the Java API
├── data/                   all content and tunables (JSON) - edit courses.json / resources.json
│                           and the change is picked up on the next start
├── lib/                    SQLite + Gson jars (so you need no Maven)
├── placeiq.db              created on first run (safe to delete)
└── DESIGN.md               design, motion and sound spec
```

`README.md` covers the architecture, API surface and what is stubbed; `DESIGN.md` covers the
visual/motion/sound system.
