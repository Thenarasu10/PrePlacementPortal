package placeiq;

import com.google.gson.*;
import java.sql.*;
import java.util.*;

/**
 * Adaptive assessment: start -> next (adaptive) -> answer -> submit -> result.
 * Section queue, ability state and the served-option permutations live in attempts.meta_json so a
 * reload or a server restart cannot corrupt a running attempt.
 */
public final class Assessment {

  private static final CodeRunner RUNNER = new CodeRunner.MockRunner();

  public static JsonObject start(int userId, JsonObject body, JsonObject user) throws Exception {
    JsonObject cfg = Db.config();
    String kind = Json.str(body, "kind", "diagnostic");
    String domain = Json.str(body, "domain", Json.str(user, "domain", "CS/IT"));
    String company = Json.str(body, "company", Json.str(user, "targetCompany", ""));
    int count = Json.intOf(body, "questionCount", Json.intOf(Json.child(cfg, "assessmentDefaults"), "diagnosticQuestionCount", 20));
    JsonArray sections = Json.array(body, "sections");
    if (sections.size() == 0) for (JsonElement s : Json.array(cfg, "sections")) sections.add(s.getAsJsonObject().get("id").getAsString());

    JsonObject meta = Json.obj("sections", sections, "domain", domain, "company", company,
        "questionCount", count, "asked", 0, "cursor", 0);
    JsonObject ability = new JsonObject(), diff = new JsonObject(), asked = new JsonObject();
    for (JsonElement s : sections) {
      String id = s.getAsString();
      ability.addProperty(id, Json.num(Json.child(cfg, "adaptive"), "initialAbility", 0.5));
      diff.addProperty(id, "easy");
      asked.addProperty(id, 0);
    }
    meta.add("ability", ability);
    meta.add("difficulty", diff);
    meta.add("askedPerSection", asked);
    meta.add("permutations", new JsonObject());
    meta.addProperty("perSection", Math.max(2, count / Math.max(1, sections.size())));

    if ("review".equals(kind)) {  // spaced repetition quiz built from a fixed question list
      meta.add("reviewQueue", Json.array(body, "questionIds"));
      meta.addProperty("questionCount", Json.array(body, "questionIds").size());
    }
    if ("company".equals(kind) && !company.isBlank()) meta.addProperty("companyFilter", company);

    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO attempts(user_id,kind,label,status,started_at,meta_json,violations,time_sec) VALUES(?,?,?,?,?,?,0,0)",
        Statement.RETURN_GENERATED_KEYS)) {
      ps.setInt(1, userId);
      ps.setString(2, kind);
      ps.setString(3, label(kind, company, domain));
      ps.setString(4, "in_progress");
      ps.setString(5, Time.now());
      ps.setString(6, meta.toString());
      ps.executeUpdate();
      ResultSet k = ps.getGeneratedKeys();
      k.next();
      int attemptId = k.getInt(1);
      JsonObject out = Json.obj("attemptId", attemptId, "kind", kind, "label", label(kind, company, domain),
          "sections", sections, "questionCount", Json.intOf(meta, "questionCount", count),
          "domain", domain, "company", company, "started", true);
      int limitMin = Json.intOf(body, "timeLimitMin", 0);
      if (limitMin > 0) out.addProperty("timeLimitSec", limitMin * 60L);
      JsonObject q = next(userId, attemptId);
      out.add("question", q.has("question") ? q.get("question") : null);
      out.addProperty("hasQuestion", q.has("question"));
      return out;
    }
  }

  private static String label(String kind, String company, String domain) {
    switch (kind) {
      case "company": return company.toUpperCase() + " company mock";
      case "custom": return "Custom practice (" + domain + ")";
      case "review": return "Spaced-repetition revision";
      default: return "Diagnostic assessment";
    }
  }

  private static JsonObject attemptRow(int attemptId) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM attempts WHERE id=?")) {
      ps.setInt(1, attemptId);
      ResultSet r = ps.executeQuery();
      return r.next() ? Content.attemptRow(r) : null;
    }
  }

  private static JsonObject meta(int attemptId) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT meta_json FROM attempts WHERE id=?")) {
      ps.setInt(1, attemptId);
      ResultSet r = ps.executeQuery();
      return r.next() ? Json.parse(r.getString(1)) : new JsonObject();
    }
  }

  private static void saveMeta(Connection c, int attemptId, JsonObject meta) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("UPDATE attempts SET meta_json=? WHERE id=?")) {
      ps.setString(1, meta.toString());
      ps.setInt(2, attemptId);
      ps.executeUpdate();
    }
  }

  private static Set<String> answeredIds(Connection c, int attemptId) throws SQLException {
    Set<String> ids = new HashSet<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT question_id FROM attempt_answers WHERE attempt_id=?")) {
      ps.setInt(1, attemptId);
      ResultSet r = ps.executeQuery();
      while (r.next()) ids.add(r.getString(1));
    }
    return ids;
  }

  /** Returns the next adaptive question, or {done:true} when the attempt is complete. */
  public static JsonObject next(int userId, int attemptId) throws SQLException {
    JsonObject meta = meta(attemptId);
    JsonArray sections = Json.array(meta, "sections");
    int target = Json.intOf(meta, "questionCount", 20);
    int asked = Json.intOf(meta, "asked", 0);
    if (asked >= target) return Json.obj("done", true, "asked", asked, "total", target);

    try (Connection c = Db.get()) {
      Set<String> answered = answeredIds(c, attemptId);
      JsonObject ability = Json.child(meta, "ability");
      JsonObject diff = Json.child(meta, "difficulty");
      JsonObject perSectionAsked = Json.child(meta, "askedPerSection");
      int perSectionCap = Json.intOf(meta, "perSection", 4);
      String domain = Json.str(meta, "domain", "");
      String companyFilter = Json.str(meta, "companyFilter", "");

      // review kicks: fixed queue first, then the rotating helper list above
      List<String> order = new ArrayList<>();
      for (JsonElement s : sections) order.add(s.getAsString());
      if (order.isEmpty()) order.add("aptitude");
      String cursorSection = Json.str(meta, "cursorSection", "");
      int startIdx = Math.max(0, order.indexOf(cursorSection));
      JsonObject chosen = null;
      String chosenSection = null;
      for (int i = 0; i < order.size(); i++) {
        String section = order.get((startIdx + i) % order.size());
        if (Json.intOf(perSectionAsked, section, 0) >= perSectionCap) continue;
        String want = Scoring.nextDifficulty(Json.num(ability, section, 0.5), Json.str(diff, section, "easy"), true);
        JsonObject q = pick(c, section, want, domain, companyFilter, answered);
        if (q == null) q = pick(c, section, null, domain, companyFilter, answered);   // any difficulty fallback
        if (q != null) { chosen = q; chosenSection = section; break; }
      }
      if (chosen == null) {
        // everything asked: allow extra questions from any section before declaring completion
        for (String section : order) {
          JsonObject q = pick(c, section, null, domain, companyFilter, answered);
          if (q != null) { chosen = q; chosenSection = section; break; }
        }
      }
      if (chosen == null) return Json.obj("done", true, "asked", asked, "total", target);

      JsonObject perms = Json.child(meta, "permutations");
      JsonObject served = Content.presentQuestion(chosen, perms);
      meta.add("permutations", perms);
      meta.addProperty("asked", asked + 1);
      meta.addProperty("cursorSection", chosenSection);
      asked++;
      perSectionAsked.addProperty(chosenSection, Json.intOf(perSectionAsked, chosenSection, 0) + 1);
      meta.add("askedPerSection", perSectionAsked);
      saveMeta(c, attemptId, meta);
      return Json.obj("done", false, "asked", asked, "total", target, "question", served,
          "difficulty", Json.str(chosen, "difficulty", "medium"), "section", chosenSection);
    }
  }

  private static JsonObject pick(Connection c, String section, String difficulty, String domain, String company,
                                 Set<String> exclude) {
    List<JsonObject> pool = Content.questions(section, domain, difficulty, company, exclude, 12);
    return pool.isEmpty() ? null : pool.get(0);
  }

  /** Scores an answer immediately (so the student gets instant feedback) and updates ability. */
  public static JsonObject answer(int userId, int attemptId, JsonObject body) throws Exception {
    JsonObject meta = meta(attemptId);
    String questionId = Json.str(body, "questionId", "");
    JsonObject q = Content.question(questionId);
    if (q == null) return Json.obj("error", "unknown question");
    double negative = Json.num(Json.child(Db.config(), "assessmentDefaults"), "negativeMarking", 0);

    JsonArray given = Json.array(body, "answer");
    // map displayed letters back to canonical letters using the stored permutation
    JsonObject perms = Json.child(meta, "permutations");
    JsonArray keys = perms.has(questionId) ? Json.array(perms, questionId) : new JsonArray();
    if (keys.size() > 0 && Json.str(q, "type", "mcq").matches("mcq|multi")) {
      JsonArray canonical = new JsonArray();
      for (JsonElement g : given) {
        String letter = g.getAsString().toUpperCase();
        int idx = letter.isEmpty() ? -1 : letter.charAt(0) - 'A';
        canonical.add(idx >= 0 && idx < keys.size() ? keys.get(idx).getAsString() : letter);
      }
      given = canonical;
    }
    JsonObject response = Json.obj("answer", given);
    String type = Json.str(q, "type", "mcq");
    double awarded = Scoring.award(q, response, negative);
    boolean correct = correctFlag(type, awarded);

    double timeSec = Math.max(0, Json.num(body, "timeSec", 0));
    JsonObject extra = new JsonObject();
    if ("coding".equals(type) && given.size() > 0) extra = RUNNER.run(q, given.get(0).getAsString());

    try (Connection c = Db.get()) {
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO attempt_answers(attempt_id,question_id,section,difficulty,chosen_json,awarded,max,correct,time_sec,answered_at) " +
          "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
        ps.setInt(1, attemptId);
        ps.setString(2, questionId);
        ps.setString(3, Json.str(q, "section", ""));
        ps.setString(4, Json.str(q, "difficulty", "medium"));
        ps.setString(5, given.toString());
        ps.setDouble(6, awarded);
        ps.setDouble(7, 1.0);
        ps.setInt(8, correct ? 1 : 0);
        ps.setDouble(9, timeSec);
        ps.setString(10, Time.now());
        ps.executeUpdate();
      }
      String section = Json.str(q, "section", "");
      JsonObject ability = Json.child(meta, "ability");
      double newAbility = Scoring.updateAbility(Json.num(ability, section, 0.5), Json.str(q, "difficulty", "medium"), correct);
      ability.addProperty(section, newAbility);
      JsonObject diffMap = Json.child(meta, "difficulty");
      String nextDifficulty = Scoring.nextDifficulty(newAbility, Json.str(q, "difficulty", "medium"), correct);
      diffMap.addProperty(section, nextDifficulty);
      meta.add("ability", ability);
      meta.add("difficulty", diffMap);
      saveMeta(c, attemptId, meta);

      // wrong answers feed spaced repetition
      if (!correct) Gaps.scheduleReview(userId, Json.str(q, "skillId", ""), Json.str(q, "topic", ""), questionId, false);
    }
    return Json.obj("questionId", questionId, "awarded", Math.round(awarded * 100.0) / 100.0, "correct", correct,
        "explanation", Json.str(q, "explanation", ""), "modelAnswer", Json.str(q, "modelAnswer", ""),
        "ability", Math.round(Json.num(Json.child(meta, "ability"), Json.str(q, "section", ""), 0.5) * 100.0) / 100.0,
        "runner", extra);
  }

  private static boolean correctFlag(String type, double awarded) {
    if ("coding".equals(type) || "written".equals(type)) return awarded >= 0.6;
    if ("multi".equals(type)) return awarded >= 0.999;
    return awarded >= 0.999;
  }

  /** Finalises an attempt: aggregate, readiness snapshot, PRS, XP, badges, notifications. */
  public static JsonObject submit(int userId, int attemptId, JsonObject body) throws Exception {
    JsonObject cfg = Db.config();
    try (Connection c = Db.get()) {
      List<JsonObject> answers = new ArrayList<>();
      try (PreparedStatement ps = c.prepareStatement("SELECT aa.*, q.topic, q.skill_id FROM attempt_answers aa " +
          "LEFT JOIN questions q ON q.id=aa.question_id WHERE aa.attempt_id=?")) {
        ps.setInt(1, attemptId);
        ResultSet r = ps.executeQuery();
        while (r.next()) {
          answers.add(Json.obj("section", r.getString("section"), "awarded", r.getDouble("awarded"), "max", r.getDouble("max"),
              "correct", r.getInt("correct"), "answered", r.getString("chosen_json") != null ? 1 : 0,
              "timeSec", r.getDouble("time_sec"), "topic", r.getString("topic"), "skillId", r.getString("skill_id")));
        }
      }
      JsonObject agg = Scoring.aggregate(Json.array(cfg, "sections"), answers);
      double totalAwarded = 0, totalMax = 0;
      for (JsonObject a : answers) { totalAwarded += Json.num(a, "awarded", 0); totalMax += Json.num(a, "max", 1); }
      int violations = Json.intOf(body, "violations", 0);
      double seconds = Json.num(body, "timeSec", 0);
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE attempts SET status='submitted', submitted_at=?, total_score=?, max_score=?, accuracy=?, overall_pct=?," +
          " readiness=?, section_stats_json=?, violations=?, time_sec=? WHERE id=?")) {
        ps.setString(1, Time.now());
        ps.setDouble(2, Math.round(totalAwarded * 100.0) / 100.0);
        ps.setDouble(3, totalMax);
        ps.setDouble(4, Json.num(agg, "accuracy", 0));
        ps.setDouble(5, Json.num(agg, "overallPct", 0));
        ps.setString(6, Json.str(agg, "readiness", "Beginner"));
        ps.setString(7, Json.array(agg, "sections").toString());
        ps.setInt(8, violations);
        ps.setDouble(9, seconds);
        ps.setInt(10, attemptId);
        ps.executeUpdate();
      }
      JsonObject prs = Analytics.prsComponents(userId);
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO readiness_history(user_id,attempt_id,overall_pct,prs,breakdown_json,created_at) VALUES(?,?,?,?,?,?)")) {
        ps.setInt(1, userId);
        ps.setInt(2, attemptId);
        ps.setDouble(3, Json.num(agg, "overallPct", 0));
        ps.setDouble(4, Json.num(prs, "score", 0));
        ps.setString(5, Json.array(prs, "parts").toString());
        ps.setString(6, Time.now());
      }
      Assessment.gamify(userId, attemptId, Json.num(agg, "overallPct", 0));
      if (violations > 0) Content.log(userId, "proctor", violations + " tab-switch event(s) during attempt " + attemptId);
    }
    Gaps.syncReviews(userId);
    JsonObject out = result(userId, attemptId);
    out.add("prs", Analytics.prsComponents(userId));
    out.add("recommendations", Gaps.recommendations(userId));
    out.add("gaps", Json.arr(Gaps.ranked(userId).toArray()));
    return out;
  }

  public static JsonObject result(int userId, int attemptId) throws SQLException {
    JsonObject attempt = attemptRow(attemptId);
    if (attempt == null) return Json.obj("error", "attempt not found");
    JsonArray sections = Json.array(attempt, "sections");
    JsonArray graded = new JsonArray();
    for (JsonElement e : sections) {
      JsonObject s = e.getAsJsonObject();
      JsonObject copy = s.deepCopy();
      copy.addProperty("gap", Json.num(s, "accuracy", 0) < 0.75);
      graded.add(copy);
    }
    attempt.add("sections", graded);
    attempt.add("improvement", Analytics.improvementFor(userId));
    attempt.add("topicStats", Json.arr(Content.topicStats(userId).toArray()));
    return attempt;
  }

  // ---------------- gamification (Phase 4, kept deliberately small) ----------------

  public static void gamify(int userId, int attemptId, double scorePct) throws SQLException {
    JsonObject cfg = Json.child(Db.config(), "gamification");
    if ("false".equalsIgnoreCase(Json.str(cfg, "enabled", "true"))) return;
    int xp = (int) Math.round(scorePct * 2) + 20;
    addXp(userId, xp, "assessment #" + attemptId);
    try (Connection c = Db.get()) {
      String[] badges = {"First attempt|1", "Half-century|50", "Consistent|70", "Placement ready|85"};
      double best = bestScore(userId);
      for (String b : badges) {
        String[] parts = b.split("\\|");
        if (best >= Double.parseDouble(parts[1]))
          try (PreparedStatement ps = c.prepareStatement("INSERT INTO badges(user_id,badge,earned_at) " +
              "SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM badges WHERE user_id=? AND badge=?)")) {
            ps.setInt(1, userId); ps.setString(2, parts[0]); ps.setString(3, Time.now());
            ps.setInt(4, userId); ps.setString(5, parts[0]);
            ps.executeUpdate();
          }
      }
    }
    streak(userId);
  }

  public static void addXp(int userId, int amount, String reason) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO xp_log(user_id,amount,reason,created_at) VALUES(?,?,?,?)")) {
      ps.setInt(1, userId); ps.setInt(2, amount); ps.setString(3, reason); ps.setString(4, Time.now());
      ps.executeUpdate();
    }
  }

  public static int xp(int userId) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(amount),0) x FROM xp_log WHERE user_id=?")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      return r.next() ? r.getInt("x") : 0;
    } catch (SQLException e) { return 0; }
  }

  public static double bestScore(int userId) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT COALESCE(MAX(overall_pct),0) m FROM attempts WHERE user_id=? AND status='submitted'")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      return r.next() ? r.getDouble("m") : 0;
    } catch (SQLException e) { return 0; }
  }

  private static void streak(int userId) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT DISTINCT substr(created_at,1,10) d FROM events WHERE user_id=? UNION SELECT DISTINCT substr(submitted_at,1,10) FROM attempts WHERE user_id=? AND submitted_at IS NOT NULL ORDER BY d DESC")) {
      ps.setInt(1, userId);
      ps.setInt(2, userId);
      ResultSet r = ps.executeQuery();
      int run = 0;
      String expected = Time.today();
      while (r.next()) {
        String d = r.getString(1);
        if (d == null) continue;
        if (d.equals(expected)) { run++; expected = Time.minusDays(run); }
        else break;
      }
      try (PreparedStatement up = c.prepareStatement(
          "INSERT INTO meta(k,v) VALUES('streak:'||? , ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
        up.setInt(1, userId);
        up.setString(2, String.valueOf(run));
        up.executeUpdate();
      }
    }
  }

  public static int streakOf(int userId) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT v FROM meta WHERE k=?")) {
      ps.setString(1, "streak:" + userId);
      ResultSet r = ps.executeQuery();
      return r.next() ? Integer.parseInt(r.getString(1)) : 0;
    } catch (Exception e) { return 0; }
  }

  // ---------------- demo history so graphs render on first run ----------------

  /** Generates a submitted attempt with realistic per-question outcomes (deterministic seed). */
  public static void seedDemoAttempt(int userId, int daysAgo, double talent, String label, Map<String, Double> sectionBias) throws Exception {
    JsonObject cfg = Db.config();
    JsonArray sections = Json.array(cfg, "sections");
    Random rnd = new Random(42 + daysAgo + userId);
    int attemptId;
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO attempts(user_id,kind,label,status,started_at,meta_json,violations,time_sec) VALUES(?,?,?,?,?,?,?,?)",
        Statement.RETURN_GENERATED_KEYS)) {
      ps.setInt(1, userId);
      ps.setString(2, "diagnostic");
      ps.setString(3, label);
      ps.setString(4, "submitted");
      ps.setString(5, Time.nowMinusDays(daysAgo));
      ps.setString(6, "{}");
      ps.setInt(7, 0);
      ps.setDouble(8, 1200);
      ps.executeUpdate();
      ResultSet k = ps.getGeneratedKeys();
      k.next();
      attemptId = k.getInt(1);
    }

    List<JsonObject> answers = new ArrayList<>();
    try (Connection c = Db.get()) {
      // correctness quota per attempt keeps the demo history smooth and believable
      int perSection = 5;
      double budget = 0;
      int used = 0;
      for (JsonElement se : sections) {
        String section = se.getAsJsonObject().get("id").getAsString();
        double p = Json.clamp(talent + sectionBias.getOrDefault(section, 0.0), 0.05, 0.97);
        List<JsonObject> pool = Content.questions(section, "CS/IT", null, null, new HashSet<>(), perSection);
        budget += p * Math.min(perSection, pool.size());
        int want = (int) Math.floor(budget) - used;
        used += want;
        for (int i = 0; i < pool.size() && i < perSection; i++) {
          JsonObject q = pool.get(i);
          boolean correct = i < want;
          double timeSec = Math.round(Json.num(q, "timeLimit", 90) * (0.45 + rnd.nextDouble() * 0.7));
          try (PreparedStatement ps = c.prepareStatement(
              "INSERT INTO attempt_answers(attempt_id,question_id,section,difficulty,chosen_json,awarded,max,correct,time_sec,answered_at) " +
              "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, attemptId);
            ps.setString(2, Json.str(q, "id", ""));
            ps.setString(3, section);
            ps.setString(4, Json.str(q, "difficulty", "medium"));
            ps.setString(5, correct ? "[\"A\"]" : "[\"B\"]");
            ps.setDouble(6, correct ? 1.0 : 0.0);
            ps.setDouble(7, 1.0);
            ps.setInt(8, correct ? 1 : 0);
            ps.setDouble(9, timeSec);
            ps.setString(10, Time.nowMinusDays(daysAgo));
            ps.executeUpdate();
          }
          answers.add(Json.obj("section", section, "awarded", correct ? 1.0 : 0.0, "max", 1.0, "correct", correct ? 1 : 0,
              "answered", 1, "timeSec", timeSec, "topic", Json.str(q, "topic", ""), "skillId", Json.str(q, "skillId", "")));
        }
      }
      JsonObject agg = Scoring.aggregate(sections, answers);
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE attempts SET total_score=?, max_score=?, accuracy=?, overall_pct=?, readiness=?, section_stats_json=?, submitted_at=? WHERE id=?")) {
        ps.setDouble(1, Json.num(agg, "overallPct", 0) * Json.intOf(agg, "questions", 1) / 100.0);
        ps.setDouble(2, Json.intOf(agg, "questions", 1));
        ps.setDouble(3, Json.num(agg, "accuracy", 0));
        ps.setDouble(4, Json.num(agg, "overallPct", 0));
        ps.setString(5, Json.str(agg, "readiness", "Beginner"));
        ps.setString(6, Json.array(agg, "sections").toString());
        ps.setString(7, Time.nowMinusDays(daysAgo));
        ps.setInt(8, attemptId);
        ps.executeUpdate();
      }
    }
    JsonObject prs = Analytics.prsComponents(userId);
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO readiness_history(user_id,attempt_id,overall_pct,prs,breakdown_json,created_at) VALUES(?,?,?,?,?,?)")) {
      ps.setInt(1, userId);
      ps.setInt(2, attemptId);
      ps.setDouble(3, Json.num(aggregate(attemptId), "overallPct", 0));
      ps.setDouble(4, Json.num(prs, "score", 0));
      ps.setString(5, Json.array(prs, "parts").toString());
      ps.setString(6, Time.nowMinusDays(daysAgo));
      ps.executeUpdate();
    }
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO xp_log(user_id,amount,reason,created_at) VALUES(?,?,?,?)")) {
      ps.setInt(1, userId);
      ps.setInt(2, 60 + userId);
      ps.setString(3, "seed history");
      ps.setString(4, Time.nowMinusDays(daysAgo));
      ps.executeUpdate();
    }
  }

  private static double difficultyPenalty(String difficulty) {
    switch (difficulty) { case "easy": return -0.18; case "hard": return 0.22; default: return 0.0; }
  }

  private static JsonObject aggregate(int attemptId) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT section, awarded, max, correct, time_sec, chosen_json FROM attempt_answers WHERE attempt_id=?")) {
      ps.setInt(1, attemptId);
      ResultSet r = ps.executeQuery();
      List<JsonObject> answers = new ArrayList<>();
      while (r.next())
        answers.add(Json.obj("section", r.getString("section"), "awarded", r.getDouble("awarded"), "max", r.getDouble("max"),
            "correct", r.getInt("correct"), "answered", r.getString("chosen_json") != null ? 1 : 0, "timeSec", r.getDouble("time_sec")));
      return Scoring.aggregate(Json.array(Db.config(), "sections"), answers);
    } catch (SQLException e) { return new JsonObject(); }
  }
}
