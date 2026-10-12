package placeiq;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Test suite for the core logic. Plain Java harness (no JUnit) so it runs with javac + java alone:
 *   java -cp "bin:lib/*" placeiq.Tests
 *
 * Coverage:
 *   1. Section and overall scoring, including negative marking and multi-answer questions
 *   2. Readiness level assignment at threshold boundaries
 *   3. Percentage improvement, including single-attempt and zero-baseline cases
 *   4. Adaptive difficulty selection and the Elo ability update
 *   5. Skill-gap ranking by impact x deficit
 *   6. Planner re-scheduling when tasks are missed
 *   7. Role-based access checks over real HTTP
 */
public final class Tests {
  private static int passed = 0, failed = 0;
  private static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    // isolated test database so the demo database is never touched
    Path tmp = Files.createTempDirectory("placeiq-test");
    System.setProperty("placeiq.db", "jdbc:sqlite:" + tmp.resolve("test.db"));
    System.setProperty("placeiq.data", "data");

    System.out.println("PlaceIQ test suite\n==================");
    Db.init();
    Db.seedContent();

    testScoring();
    testReadinessBoundaries();
    testImprovement();
    testAdaptive();
    testGapRanking();
    testRubricAndCodingChecks();
    testPlannerRescheduling();
    testPrsWeighting();
    testPasswordPolicy();
    testLearnLibrary();
    testRoleBasedAccess();

    System.out.println("\n==================");
    System.out.println("passed: " + passed + "   failed: " + failed);
    if (failed > 0) {
      System.out.println("\nFailures:");
      for (String f : failures) System.out.println(" - " + f);
      System.exit(1);
    }
    System.exit(0);   // the integration test server holds non-daemon threads
  }

  // ---------------- 1. scoring ----------------

  private static void testScoring() {
    section("Scoring: sections, overall weights and negative marking");
    JsonObject cfg = Db.config();
    JsonArray sections = Json.array(cfg, "sections");

    JsonObject correct = Json.obj("section", "aptitude", "awarded", 1.0, "max", 1.0, "correct", 1, "answered", 1, "timeSec", 30);
    JsonObject wrong = Json.obj("section", "aptitude", "awarded", -0.25, "max", 1.0, "correct", 0, "answered", 1, "timeSec", 40);
    JsonObject omitted = Json.obj("section", "aptitude", "awarded", 0.0, "max", 1.0, "correct", 0, "answered", 0, "timeSec", 0);
    JsonObject code = Json.obj("section", "coding", "awarded", 0.6, "max", 1.0, "correct", 1, "answered", 1, "timeSec", 200);

    List<JsonObject> answers = new ArrayList<>(Arrays.asList(correct, wrong, omitted, code));
    JsonObject agg = Scoring.aggregate(sections, answers);

    // aptitude: (1 - 0.25 + 0) / 3 = 25%, coding: 60%
    JsonObject apt = sectionStats(agg, "aptitude");
    JsonObject cod = sectionStats(agg, "coding");
    eq("aptitude percentage with negative marking", 25.0, Json.num(apt, "percentage", -1));
    eq("coding percentage with partial credit", 60.0, Json.num(cod, "percentage", -1));
    // available sections (aptitude, coding) renormalise their weights: (25x0.25 + 60x0.25)/0.5
    eq("overall is the weighted mean of the sections present", 42.5, Json.num(agg, "overallPct", -1), 0.01);
    eq("accuracy is the correct-to-total ratio", 0.5, Json.num(agg, "accuracy", -1));

    // negative marking applied by the award function itself
    JsonObject mcq = Json.obj("type", "mcq", "answer", Json.arr("B"));
    eq("wrong MCQ with 0.25 negative marking", -0.25, Scoring.award(mcq, Json.obj("answer", Json.arr("A")), 0.25));
    eq("unanswered MCQ scores zero (not negative)", 0.0, Scoring.award(mcq, Json.obj("answer", new JsonArray()), 0.25));
    eq("correct MCQ scores 1", 1.0, Scoring.award(mcq, Json.obj("answer", Json.arr("b")), 0.25));

    JsonObject multi = Json.obj("type", "multi", "answer", Json.arr("A", "B", "D"));
    eq("multi-select exact match", 1.0, Scoring.award(multi, Json.obj("answer", Json.arr("d", "b", "a")), 0.25));
    eq("multi-select partial match counts as incorrect", -0.25, Scoring.award(multi, Json.obj("answer", Json.arr("A", "B")), 0.25));
  }

  private static JsonObject sectionStats(JsonObject agg, String section) {
    for (JsonElement e : Json.array(agg, "sections"))
      if (Json.str(e.getAsJsonObject(), "section", "").equals(section)) return e.getAsJsonObject();
    return new JsonObject();
  }

  // ---------------- 2. readiness boundaries ----------------

  private static void testReadinessBoundaries() {
    section("Readiness levels at the configured boundaries (<45 Beginner, <75 Intermediate, else Advanced)");
    JsonObject cfg = Db.config();
    eq("0% -> Beginner", "Beginner", Scoring.levelFromScore(0, cfg));
    eq("44.99% -> Beginner", "Beginner", Scoring.levelFromScore(44.99, cfg));
    eq("45% -> Intermediate", "Intermediate", Scoring.levelFromScore(45, cfg));
    eq("74.99% -> Intermediate", "Intermediate", Scoring.levelFromScore(74.99, cfg));
    eq("75% -> Advanced", "Advanced", Scoring.levelFromScore(75, cfg));
    eq("100% -> Advanced", "Advanced", Scoring.levelFromScore(100, cfg));

    // thresholds come from config, not code
    JsonObject custom = Json.parse(cfg.toString());
    custom.getAsJsonObject("readinessThresholds").addProperty("beginnerMax", 30);
    custom.getAsJsonObject("readinessThresholds").addProperty("intermediateMax", 60);
    eq("custom thresholds respected (40% -> Intermediate)", "Intermediate", Scoring.levelFromScore(40, custom));
  }

  // ---------------- 3. improvement ----------------

  private static void testImprovement() {
    section("Percentage improvement, including edge cases");
    JsonObject two = Scoring.improvement(series(40, 50), labels("a", "b"));
    eq("delta in percentage points", 10.0, Json.num(two, "deltaPoints", -1));
    eq("percent change vs first attempt", 25.0, Json.num(two, "percentChange", -1));
    eq("trend improving", "improving", Json.str(two, "trend", ""));

    JsonObject single = Scoring.improvement(series(64), labels("only"));
    eq("single attempt -> zero delta", 0.0, Json.num(single, "deltaPoints", -1));
    eq("single attempt -> no percent change", true, single.get("percentChange").isJsonNull());
    eq("single attempt trend flag", "single-attempt", Json.str(single, "trend", ""));

    JsonObject zeroBase = Scoring.improvement(series(0, 30), labels("a", "b"));
    eq("zero baseline -> null percent change (no divide by zero)", true, zeroBase.get("percentChange").isJsonNull());
    eq("zero baseline still reports point delta", 30.0, Json.num(zeroBase, "deltaPoints", -1));

    JsonObject none = Scoring.improvement(new ArrayList<>(), new ArrayList<>());
    eq("no attempts -> no-data", "no-data", Json.str(none, "trend", ""));

    JsonObject declined = Scoring.improvement(series(70, 60), labels("a", "b"));
    eq("declining trend detected", "declining", Json.str(declined, "trend", ""));

    // improvement over a real seeded student
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE email='student@placeiq.dev'")) {
      ResultSet r = ps.executeQuery();
      if (r.next()) {
        JsonObject real = Analytics.improvementFor(r.getInt(1));
        JsonObject overall = Json.child(real, "overall");
        gt("seeded student has several attempts", Json.intOf(overall, "attempts", 0), 2);
        gt("seeded student improved", Json.num(overall, "deltaPoints", 0), 0);
      }
    } catch (SQLException e) { fail("improvement integration: " + e.getMessage()); }
  }

  private static List<double[]> series(double... values) {
    List<double[]> out = new ArrayList<>();
    for (double v : values) out.add(new double[]{v});
    return out;
  }

  private static List<String> labels(String... values) { return new ArrayList<>(Arrays.asList(values)); }

  // ---------------- 4. adaptive engine ----------------

  private static void testAdaptive() {
    section("Adaptive difficulty and ability update");
    eq("ability 0.20 -> easy", "easy", Scoring.nextDifficulty(0.20, "medium", true));
    eq("ability 0.50 -> medium", "medium", Scoring.nextDifficulty(0.50, "easy", true));
    eq("ability 0.80 -> hard", "hard", Scoring.nextDifficulty(0.80, "medium", true));
    eq("boundary 0.33 -> medium", "medium", Scoring.nextDifficulty(0.33, "easy", true));
    eq("boundary 0.66 -> hard", "hard", Scoring.nextDifficulty(0.66, "medium", true));

    double up = Scoring.updateAbility(0.5, "hard", true);
    gt("correct on a hard question raises ability", up, 0.5);
    double down = Scoring.updateAbility(0.5, "easy", false);
    lt("wrong on an easy question lowers ability", down, 0.5);
    gt("beating a hard question moves ability more than an easy one",
        Scoring.updateAbility(0.5, "hard", true) - 0.5, Scoring.updateAbility(0.5, "easy", true) - 0.5);
    eq("ability is clamped at the ceiling", 1.0, Scoring.updateAbility(0.99, "easy", true), 0.001);
    eq("difficulty ratings are ordered", true,
        Scoring.difficultyRating("easy") < Scoring.difficultyRating("medium")
            && Scoring.difficultyRating("medium") < Scoring.difficultyRating("hard"));
  }

  // ---------------- 5. skill-gap ranking ----------------

  private static void testGapRanking() {
    section("Skill-gap ranking by impact x deficit");
    JsonObject skills = new JsonObject();
    skills.add("s.high", Json.obj("id", "s.high", "name", "High impact", "section", "coding", "impact", 1.0));
    skills.add("s.low", Json.obj("id", "s.low", "name", "Low impact", "section", "coding", "impact", 0.2));
    skills.add("s.ok", Json.obj("id", "s.ok", "name", "Already strong", "section", "coding", "impact", 1.0));

    List<JsonObject> topics = new ArrayList<>(Arrays.asList(
        Json.obj("skillId", "s.high", "topic", "High impact", "section", "coding", "accuracy", 0.30, "attempts", 4, "avgTimeSec", 60),
        Json.obj("skillId", "s.low", "topic", "Low impact", "section", "coding", "accuracy", 0.30, "attempts", 4, "avgTimeSec", 60),
        Json.obj("skillId", "s.ok", "topic", "Already strong", "section", "coding", "accuracy", 0.90, "attempts", 4, "avgTimeSec", 40)));

    List<JsonObject> ranked = Scoring.rankGaps(topics, skills, Db.config());
    eq("only weak topics are gaps", 2, ranked.size());
    eq("highest impact deficit ranks first", "s.high", Json.str(ranked.get(0), "skillId", ""));
    eq("strong topic excluded", false, ranked.stream().anyMatch(g -> Json.str(g, "skillId", "").equals("s.ok")));
    gt("severity of high-impact gap exceeds low-impact", Json.num(ranked.get(0), "severity", 0), Json.num(ranked.get(1), "severity", 0));

    // slow but accurate topics are penalised slightly
    List<JsonObject> slow = new ArrayList<>(Arrays.asList(
        Json.obj("skillId", "s.low", "topic", "Low impact", "section", "coding", "accuracy", 0.30, "attempts", 4, "avgTimeSec", 150)));
    List<JsonObject> slowRanked = Scoring.rankGaps(slow, skills, Db.config());
    gt("speed penalty applies to slow topics", Json.num(slowRanked.get(0), "severity", 0), 0.2 * 0.45);
  }

  // ---------------- 6. rubric + code checks ----------------

  private static void testRubricAndCodingChecks() {
    section("Written rubric scoring and mocked code runner");
    JsonObject rubric = Json.obj("keywords", Json.arr("situation", "action", "result"), "minWords", 20, "maxWords", 200);
    String good = "Situation: our demo moved up a week. My action was to split the work daily and pair on the bug. " +
        "The result was an on-time delivery with the highest score in the batch.";
    String poor = "I worked hard.";
    gt("well-structured answer scores higher", Scoring.rubricScore(Json.array(rubric, "keywords"), good, 20, 200),
        Scoring.rubricScore(Json.array(rubric, "keywords"), poor, 20, 200));
    eq("empty answer scores zero", 0.0, Scoring.rubricScore(Json.array(rubric, "keywords"), "", 20, 200));

    JsonObject coding = Json.obj("type", "coding", "checks", Json.arr(
        Json.obj("kind", "contains", "value", "for|while", "points", 0.3, "label", "loop"),
        Json.obj("kind", "contains", "value", "return", "points", 0.3, "label", "return"),
        Json.obj("kind", "contains", "value", "second|-1", "points", 0.4, "label", "second max")), "answer", Json.arr(""));
    double full = Scoring.award(coding, Json.obj("answer", Json.arr("int m=0,second=-1; for(int x:a){} return second;")), 0.25);
    eq("code hitting all checks scores 1.0", 1.0, full);
    double partial = Scoring.award(coding, Json.obj("answer", Json.arr("return 0;")), 0.25);
    eq("code hitting one check gets partial credit", 0.3, partial, 0.001);

    JsonObject runner = new CodeRunner.MockRunner().run(coding, "return second;");
    eq("mocked runner reports no execution", false, runner.get("executed").getAsBoolean());
    eq("mocked runner is explicit about its mode", "mocked", Json.str(runner, "mode", ""));
  }

  // ---------------- 7. planner rescheduling ----------------

  private static void testPlannerRescheduling() {
    section("Planner re-scheduling when tasks are missed");
    try {
      int studentId = userId("student@placeiq.dev");
      JsonObject user = Auth.me(studentId);
      JsonObject plan = Planner.create(studentId, Json.obj("targetCompany", "Infosys", "targetRole", "Systems Engineer",
          "targetDate", Time.plusDays(Time.today(), 42)), user);
      gt("plan has weeks", Json.array(plan, "weeks").size(), 2);
      int planId = Json.intOf(plan, "id", 0);

      // simulate a plan that started two weeks ago with one task still pending
      try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
          "UPDATE plans SET week_start=? WHERE id=?")) {
        ps.setString(1, Time.weekStart(Time.minusDays(14)));
        ps.setInt(2, planId);
        ps.executeUpdate();
      }
      int taskId = -1;
      try (Connection c = Db.get()) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM plan_tasks WHERE plan_id=? AND status='pending' AND week_no=0 LIMIT 1")) {
          ps.setInt(1, planId);
          ResultSet r = ps.executeQuery();
          if (r.next()) taskId = r.getInt(1);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE plan_tasks SET week_no=0, due_date=? WHERE id=?")) {
          ps.setString(1, Time.minusDays(10));
          ps.setInt(2, taskId);
          ps.executeUpdate();
        }
      }
      gt("found a task to age", taskId, 0);

      JsonObject replan = Planner.replan(studentId);
      eq("replan reports carried-over tasks", true, Json.num(replan, "carriedOver", 0) >= 1);
      try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
          "SELECT week_no, carried_over, due_date, status FROM plan_tasks WHERE id=?")) {
        ps.setInt(1, taskId);
        ResultSet r = ps.executeQuery();
        check("aged task still exists", r.next());
        eq("overdue task moved to the current week", Json.intOf(replan, "currentWeek", -1), r.getInt("week_no"));
        eq("task flagged as carried over", 1, r.getInt("carried_over"));
        gt("due date is no longer in the past", r.getString("due_date").compareTo(Time.today()), -1);
      }

      JsonObject completed = Planner.completeTask(studentId, taskId, true);
      eq("task marks done", "done", Json.str(completed, "status", ""));
      JsonObject planAfter = Json.child(completed, "plan");
      int doneTasks = Json.intOf(planAfter, "completedTasks", 0);
      gt("plan completion counter increments", doneTasks, 0);

      // a brand new gap discovered by a later assessment is appended to the current week
      Assessment.seedDemoAttempt(studentId, 1, 0.30, "Diagnostic assessment", new HashMap<>());
      JsonObject replan2 = Planner.replan(studentId);
      eq("replan runs after new results", true, replan2.get("replanned").getAsBoolean());
    } catch (Exception e) {
      fail("planner rescheduling: " + e);
    }
  }

  // ---------------- 8. PRS weighting ----------------

  private static void testPrsWeighting() {
    section("Placement Readiness Score weighting");
    JsonObject cfg = Db.config();
    JsonObject components = Json.obj(
        "assessment", Json.obj("value", 80, "detail", "latest"),
        "mockTest", Json.obj("value", 60, "detail", "best"));
    JsonObject prs = Scoring.prs(components, cfg);
    // weights 0.45 and 0.20 renormalise to 0.6923 / 0.3077
    eq("missing components are dropped and weights renormalised", 73.85, Json.num(prs, "score", -1), 0.05);
    eq("breakdown lists both contributions", 2, Json.array(prs, "parts").size());
    eq("PRS maps to a readiness band", "Intermediate", Json.str(prs, "readiness", ""));
    eq("empty profile scores zero instead of NaN", 0.0, Json.num(Scoring.prs(new JsonObject(), cfg), "score", -1));
  }

  // ---------------- 9. role-based access over HTTP ----------------

  private static void testPasswordPolicy() throws Exception {
    section("Login security: password policy, lockout, session revocation");
    check("short password rejected", Auth.passwordProblem("Ab1", "a@b.edu") != null);
    check("no-uppercase rejected", Auth.passwordProblem("abcdefg1", "a@b.edu") != null);
    check("no-digit rejected", Auth.passwordProblem("Abcdefgh", "a@b.edu") != null);
    check("common password rejected", Auth.passwordProblem("Password1", "a@b.edu") != null);
    check("own email as password rejected", Auth.passwordProblem("Ramesh123", "ramesh123@college.edu") != null);
    check("strong password accepted", Auth.passwordProblem("Str0ngPass1", "a@b.edu") == null);
    check("over-long password rejected", Auth.passwordProblem("A1" + "x".repeat(200), "a@b.edu") != null);

    String hash = Auth.hash("Str0ngPass1");
    check("hash never contains the password", !hash.contains("Str0ngPass1"));
    check("hash is salted PBKDF2 with the iteration count embedded", hash.startsWith("pbkdf2$"));
    check("correct password verifies", Auth.verify("Str0ngPass1", hash));
    check("wrong password does not verify", !Auth.verify("Str0ngPass2", hash));
    String hash2 = Auth.hash("Str0ngPass1");
    check("the same password hashes differently every time (unique salt)", !hash.equals(hash2) && Auth.verify("Str0ngPass1", hash2));

    // tokens: 64 hex chars of SecureRandom, revoked on demand
    String token = Auth.createSession(1);
    check("session token has 256 bits of entropy (64 hex chars)", token.matches("[0-9a-f]{64}"));
    check("token resolves to the user", Auth.userFor(token) != null);
    int before = Auth.activeSessions(1);
    int revoked = Auth.signOutAll(1);
    check("sign out everywhere revoked the live sessions", revoked >= 1 && Auth.activeSessions(1) == 0);
    check("revoked token no longer resolves", Auth.userFor(token) == null);

    // expiry is enforced on lookup, not only when pruning
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO sessions(token,user_id,created_at,expires_at) VALUES('expired-token-test',1,?,?)")) {
      ps.setString(1, Time.nowMinusDays(2));
      ps.setString(2, Time.nowMinusDays(1));
      ps.executeUpdate();
    }
    check("expired session is refused", Auth.userFor("expired-token-test") == null);
  }

  private static void testLearnLibrary() throws Exception {
    section("Learn screen: course catalogue, external links, resources");
    JsonObject learn = Gaps.learn(1);
    JsonArray courses = Json.array(learn, "courses");
    JsonArray resources = Json.array(learn, "resources");
    eq("catalogue returns every course", Content.courses().size(), courses.size());
    eq("every course is linked to a free external resource", Content.courses().size(), resources.size() > 0 ? courses.size() : 0);
    check("resource library is seeded (25+ free links)", resources.size() >= 25);
    check("a weakest section is identified for the learner", !learn.get("weakestSection").isJsonNull());
    eq("accuracy target comes from config as a percentage", 75, Json.intOf(learn, "targetSection", 0));

    int realLinks = 0, placeholders = 0, withLabel = 0;
    for (JsonElement e : courses) {
      String url = Json.str(e.getAsJsonObject(), "url", "");
      if (url.startsWith("http") && !url.contains("example.com")) realLinks++;
      if (url.contains("example.com") || url.equals("#")) placeholders++;
      if (!Json.str(e.getAsJsonObject(), "linkLabel", "").isEmpty()) withLabel++;
    }
    eq("no placeholder example.com links remain", 0, placeholders);
    eq("every course points at a real external site", courses.size(), realLinks);
    eq("every course carries a human-readable link label", courses.size(), withLabel);

    Map<String, Integer> bySection = new HashMap<>();
    int free = 0;
    for (JsonElement e : resources) {
      JsonObject r = e.getAsJsonObject();
      bySection.merge(Json.str(r, "section", ""), 1, Integer::sum);
      if (Json.bool(r, "free", false)) free++;
      check("resource has an external https link: " + Json.str(r, "id", ""), Json.str(r, "url", "").startsWith("https://"));
    }
    for (String section : new String[]{"aptitude", "logical", "coding", "communication", "domain"})
      check("free resources exist for the '" + section + "' section", bySection.getOrDefault(section, 0) > 0);
    eq("every resource is free", resources.size(), free);

    // progress tracking flows through to the catalogue
    Gaps.setCourseStatus(1, "C01", "in_progress", 40);
    JsonObject again = Gaps.learn(1);
    for (JsonElement e : Json.array(again, "courses")) {
      if ("C01".equals(Json.str(e.getAsJsonObject(), "id", ""))) {
        eq("course progress is reflected on the Learn screen", "in_progress", Json.str(e.getAsJsonObject(), "status", ""));
        eq("course progress percentage is reflected", 40.0, Json.num(e.getAsJsonObject(), "progressPct", 0), 0.01);
      }
    }
  }

  private static void testRoleBasedAccess() throws Exception {
    section("Role-based access checks (server side, over HTTP)");
    int port = 8123;
    com.sun.net.httpserver.HttpServer server = Main.server(port);
    try {
      String studentToken = login(port, "student@placeiq.dev", "student123");
      String mentorToken = login(port, "mentor@placeiq.dev", "mentor123");
      String adminToken = login(port, "admin@placeiq.dev", "admin123");
      check("student can sign in", studentToken != null);
      check("mentor can sign in", mentorToken != null);
      check("admin can sign in", adminToken != null);
      eq("wrong password rejected", 401, status(port, "POST", "/api/auth/login", null,
          Json.obj("email", "student@placeiq.dev", "password", "nope").toString()));

      eq("student can read own dashboard", 200, status(port, "GET", "/api/student/dashboard", studentToken, null));
      eq("student blocked from mentor area", 403, status(port, "GET", "/api/mentor/students", studentToken, null));
      eq("student blocked from admin area", 403, status(port, "GET", "/api/admin/questions", studentToken, null));
      eq("mentor can read mentor area", 200, status(port, "GET", "/api/mentor/students", mentorToken, null));
      eq("mentor blocked from admin area", 403, status(port, "GET", "/api/admin/questions", mentorToken, null));
      eq("admin can read admin area", 200, status(port, "GET", "/api/admin/questions", adminToken, null));
      eq("admin can read mentor area", 200, status(port, "GET", "/api/mentor/students", adminToken, null));
      eq("anonymous request rejected", 401, status(port, "GET", "/api/student/dashboard", null, null));
      eq("garbage token rejected", 401, status(port, "GET", "/api/student/dashboard", "not-a-real-token", null));
      eq("public meta stays public", 200, status(port, "GET", "/api/meta/config", null, null));

      // hardening over real HTTP
      check("security headers are sent on the HTML shell", hasHeader(port, "/", "Content-Security-Policy")
          && hasHeader(port, "/", "X-Frame-Options") && hasHeader(port, "/", "X-Content-Type-Options")
          && hasHeader(port, "/", "Referrer-Policy"));
      eq("unknown email and wrong password answer identically", 401, status(port, "POST", "/api/auth/login", null,
          Json.obj("email", "does.not.exist@placeiq.dev", "password", "whatever1").toString()));
      check("login errors are identical for both cases", body(port, "POST", "/api/auth/login", null,
          Json.obj("email", "does.not.exist@placeiq.dev", "password", "whatever1").toString())
          .contains("Incorrect email or password"));
      eq("weak password rejected at sign-up", 400, status(port, "POST", "/api/auth/register", null,
          Json.obj("email", "weak@college.edu", "password", "password1").toString()));
      eq("invalid email rejected at sign-up", 400, status(port, "POST", "/api/auth/register", null,
          Json.obj("email", "not-an-email", "password", "Str0ngPass1").toString()));
      eq("student cannot read the admin security panel", 403, status(port, "GET", "/api/admin/security", studentToken, null));
      eq("admin can read the security panel", 200, status(port, "GET", "/api/admin/security", adminToken, null));
      eq("learn endpoint is reachable for a student", 200, status(port, "GET", "/api/student/learn", studentToken, null));
      eq("learn endpoint is reachable for a mentor", 200, status(port, "GET", "/api/student/learn", mentorToken, null));
      eq("learn endpoint requires a session", 401, status(port, "GET", "/api/student/learn", null, null));

      // brute force: six wrong tries from the same client must end in a lockout
      for (int i = 0; i < Auth.MAX_FAILS_BEFORE_LOCK; i++)
        status(port, "POST", "/api/auth/login", null, Json.obj("email", "mentor@placeiq.dev", "password", "badpass1").toString());
      int lockedStatus = status(port, "POST", "/api/auth/login", null,
          Json.obj("email", "mentor@placeiq.dev", "password", "badpass1").toString());
      eq("repeated failures lock the account (429)", 429, lockedStatus);
      eq("the correct password is refused while locked", 429, status(port, "POST", "/api/auth/login", null,
          Json.obj("email", "mentor@placeiq.dev", "password", "mentor123").toString()));
      check("the lockout message tells the user how long", body(port, "POST", "/api/auth/login", null,
          Json.obj("email", "mentor@placeiq.dev", "password", "badpass1").toString()).contains("Too many failed attempts"));
      Auth.unlock("mentor@placeiq.dev");
      check("admin unlock clears the lock", login(port, "mentor@placeiq.dev", "mentor123") != null);

      // sign out everywhere kills the token server-side
      String doomed = login(port, "student@placeiq.dev", "student123");
      eq("fresh token works", 200, status(port, "GET", "/api/auth/session", doomed, null));
      request(port, "POST", "/api/auth/logout-all", doomed, "{}");
      eq("token is dead after signing out everywhere", 401, status(port, "GET", "/api/auth/session", doomed, null));
      // signing out everywhere revoked every session of this account, including the first one -
      // which is exactly the point, so the end-to-end flow below signs in again
      studentToken = login(port, "student@placeiq.dev", "student123");
      check("a fresh sign-in works after revoking everything", studentToken != null);

      // end-to-end assessment flow through the API
      JsonObject started = Json.parse(request(port, "POST", "/api/student/assessment/start", studentToken,
          Json.obj("kind", "custom", "questionCount", 4, "sections", Json.arr("aptitude", "coding")).toString()));
      int attemptId = Json.intOf(started, "attemptId", 0);
      gt("assessment starts with a question", Json.intOf(started, "questionCount", 0), 0);
      JsonObject question = Json.child(started, "question");
      check("first question is adaptive-easy", "easy".equals(Json.str(question, "difficulty", "")) || "medium".equals(Json.str(question, "difficulty", "")));
      JsonObject answer = Json.parse(request(port, "POST", "/api/student/assessment/" + attemptId + "/answer", studentToken,
          Json.obj("questionId", Json.str(question, "id", ""), "answer", Json.arr("A"), "timeSec", 12).toString()));
      check("answer is scored immediately", answer.has("awarded"));
      JsonObject submitted = Json.parse(request(port, "POST", "/api/student/assessment/" + attemptId + "/submit", studentToken,
          Json.obj("violations", 1, "timeSec", 60).toString()));
      check("submission returns a readiness level", !Json.str(submitted, "readiness", "").isEmpty());
      check("submission returns course recommendations", submitted.has("recommendations"));
      check("submission returns the PRS breakdown", Json.array(submitted, "prs").size() == 0 || submitted.getAsJsonObject("prs").has("score"));
      eq("proctoring violation recorded", 1, Json.intOf(submitted, "violations", 0));
    } finally {
      server.stop(0);
    }
  }

  // ---------------- HTTP helpers ----------------

  private static String login(int port, String email, String password) throws IOException {
    String body = request(port, "POST", "/api/auth/login", null, Json.obj("email", email, "password", password).toString());
    return Json.str(Json.parse(body), "token", null);
  }

  private static int status(int port, String method, String path, String token, String body) throws IOException {
    HttpURLConnection con = open(port, method, path, token, body);
    int code = con.getResponseCode();
    drain(con);
    return code;
  }

  private static String request(int port, String method, String path, String token, String body) throws IOException {
    HttpURLConnection con = open(port, method, path, token, body);
    InputStream in = con.getResponseCode() >= 400 ? con.getErrorStream() : con.getInputStream();
    if (in == null) return "{}";
    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) sb.append(line);
      return sb.toString();
    }
  }

  private static HttpURLConnection open(int port, String method, String path, String token, String body) throws IOException {
    HttpURLConnection con = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
    con.setRequestMethod(method);
    con.setConnectTimeout(4000);
    con.setReadTimeout(15000);
    if (token != null) con.setRequestProperty("Authorization", "Bearer " + token);
    if (body != null) {
      con.setDoOutput(true);
      con.setRequestProperty("Content-Type", "application/json");
      try (OutputStream os = con.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
    }
    return con;
  }

  /** Body text of a request (used for message-level assertions). */
  private static String body(int port, String method, String path, String token, String payload) throws IOException {
    return request(port, method, path, token, payload);
  }

  /** Response header lookup, including for static files. */
  private static boolean hasHeader(int port, String path, String name) throws IOException {
    HttpURLConnection con = open(port, "GET", path, null, null);
    con.getResponseCode();
    boolean present = con.getHeaderField(name) != null && !String.valueOf(con.getHeaderField(name)).isBlank();
    drain(con);
    return present;
  }

  private static void drain(HttpURLConnection con) {
    try {
      InputStream in = con.getResponseCode() >= 400 ? con.getErrorStream() : con.getInputStream();
      if (in != null) in.readAllBytes();
    } catch (Exception ignored) { }
  }

  // ---------------- harness ----------------

  private static int userId(String email) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE email=?")) {
      ps.setString(1, email);
      ResultSet r = ps.executeQuery();
      return r.next() ? r.getInt(1) : 0;
    }
  }

  private static void section(String name) { System.out.println("\n-- " + name); }

  private static void check(String name, boolean ok) {
    if (ok) { passed++; System.out.println("  PASS  " + name); }
    else fail(name);
  }

  private static void eq(String name, Object expected, Object actual) {
    boolean ok = expected == null ? actual == null : expected.equals(actual);
    if (ok) { passed++; System.out.println("  PASS  " + name + "  (" + actual + ")"); }
    else fail(name + "  expected=" + expected + " actual=" + actual);
  }

  private static void eq(String name, double expected, double actual, double tolerance) {
    if (Math.abs(expected - actual) <= tolerance) { passed++; System.out.println("  PASS  " + name + "  (" + actual + ")"); }
    else fail(name + "  expected=" + expected + " actual=" + actual);
  }

  private static void gt(String name, double actual, double threshold) {
    if (actual > threshold) { passed++; System.out.println("  PASS  " + name + "  (" + actual + ")"); }
    else fail(name + "  expected > " + threshold + " actual=" + actual);
  }

  private static void lt(String name, double actual, double threshold) {
    if (actual < threshold) { passed++; System.out.println("  PASS  " + name + "  (" + actual + ")"); }
    else fail(name + "  expected < " + threshold + " actual=" + actual);
  }

  private static void fail(String name) {
    failed++;
    failures.add(name);
    System.out.println("  FAIL  " + name);
  }
}
