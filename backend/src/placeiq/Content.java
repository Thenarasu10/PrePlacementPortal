package placeiq;

import com.google.gson.*;
import java.sql.*;
import java.util.*;

/** Read model for content (questions, skills, courses, companies) and for a student's own history. */
public final class Content {

  public static JsonObject config() { return Db.config(); }

  // ---------------- questions ----------------

  public static JsonObject questionRow(ResultSet r) throws SQLException {
    // canonical answers are always normalised to an array (letters for MCQ, text for short/written)
    JsonElement raw = Json.parseElement(r.getString("answer_json"));
    JsonArray canonical = raw.isJsonArray() ? raw.getAsJsonArray()
        : raw.isJsonPrimitive() && !raw.getAsString().isBlank() ? Json.arr(raw.getAsString()) : new JsonArray();
    return Json.obj(
        "id", r.getString("id"), "section", r.getString("section"), "topic", r.getString("topic"),
        "skillId", r.getString("skill_id"), "difficulty", r.getString("difficulty"), "type", r.getString("type"),
        "timeLimit", r.getInt("time_limit"), "domain", r.getString("domain"), "prompt", r.getString("prompt"),
        "options", Json.any(r.getString("options_json")),
        "starter", r.getString("starter"), "explanation", r.getString("explanation"),
        "modelAnswer", r.getString("model_answer"), "answer", canonical, "answerRaw", canonical,
        "checks", Json.any(r.getString("checks_json")),
        "tests", Json.any(r.getString("tests_json")),
        "rubric", Json.any(r.getString("rubric_json")),
        "companyTags", Json.any(r.getString("company_tags")));
  }

  public static JsonObject question(String id) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM questions WHERE id=?")) {
      ps.setString(1, id);
      ResultSet r = ps.executeQuery();
      return r.next() ? questionRow(r) : null;
    } catch (SQLException e) { return null; }
  }

  /**
   * @param section   nullable section filter
   * @param domain    nullable domain filter (domain-specific questions only apply to that branch)
   * @param difficulty nullable
   * @param exclude   question ids already answered in this attempt
   */
  public static List<JsonObject> questions(String section, String domain, String difficulty, String company,
                                           Set<String> exclude, int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM questions WHERE 1=1");
    List<String> params = new ArrayList<>();
    if (section != null && !section.isBlank()) { sql.append(" AND section=?"); params.add(section); }
    if (difficulty != null && !difficulty.isBlank()) { sql.append(" AND difficulty=?"); params.add(difficulty); }
    if (domain != null && !domain.isBlank()) {
      sql.append(" AND (domain IS NULL OR domain='' OR domain=?)"); params.add(domain);
    }
    if (company != null && !company.isBlank()) { sql.append(" AND company_tags LIKE ?"); params.add("%" + company.toLowerCase() + "%"); }
    List<JsonObject> out = new ArrayList<>();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
      for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
      ResultSet r = ps.executeQuery();
      while (r.next()) {
        JsonObject q = questionRow(r);
        if (exclude != null && exclude.contains(Json.str(q, "id", ""))) continue;
        out.add(q);
        if (limit > 0 && out.size() >= limit * 3) break;  // grab a small pool, caller picks
      }
    } catch (SQLException e) { return out; }
    Collections.shuffle(out, new Random());
    return limit > 0 && out.size() > limit ? out.subList(0, limit) : out;
  }

  public static int bankSize(String domain) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT section, COUNT(*) n FROM questions WHERE domain IS NULL OR domain='' OR domain=? GROUP BY section")) {
      ps.setString(1, domain == null ? "" : domain);
      ResultSet r = ps.executeQuery();
      int total = 0;
      while (r.next()) total += r.getInt("n");
      return total;
    } catch (SQLException e) { return 0; }
  }

  /** Shuffles the display order of options and returns the permutation so scoring uses canonical letters. */
  public static JsonObject presentQuestion(JsonObject q, JsonObject permutationStore) {
    JsonObject out = Json.obj("id", Json.str(q, "id", ""), "section", Json.str(q, "section", ""),
        "topic", Json.str(q, "topic", ""), "skillId", Json.str(q, "skillId", ""),
        "difficulty", Json.str(q, "difficulty", "medium"), "type", Json.str(q, "type", "mcq"),
        "timeLimit", Json.intOf(q, "timeLimit", 90), "prompt", Json.str(q, "prompt", ""),
        "starter", Json.str(q, "starter", ""), "companyTags", Json.array(q, "companyTags"),
        "hasOptions", Json.array(q, "options").size() > 0);
    JsonArray options = Json.array(q, "options");
    if (options.size() > 0) {
      List<Integer> order = new ArrayList<>();
      for (int i = 0; i < options.size(); i++) order.add(i);
      Collections.shuffle(order, new Random());
      JsonArray display = new JsonArray();
      JsonArray keys = new JsonArray();
      for (int i = 0; i < order.size(); i++) {
        display.add(options.get(order.get(i)));
        keys.add(String.valueOf((char) ('A' + order.get(i))));  // display index -> canonical letter
      }
      out.add("options", display);
      out.add("optionKeys", keys);
      if (permutationStore != null) permutationStore.add(Json.str(q, "id", ""), keys);
    }
    return out;
  }

  // ---------------- skills, courses, companies ----------------

  public static JsonArray skills() {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT * FROM skills ORDER BY section, name")) {
      while (r.next())
        out.add(Json.obj("id", r.getString("id"), "name", r.getString("name"), "section", r.getString("section"),
            "impact", r.getDouble("impact"), "prereq", Json.any(r.getString("prereq_json"))));
    } catch (SQLException ignored) { }
    return out;
  }

  public static JsonObject skillsById() {
    JsonObject map = new JsonObject();
    for (JsonElement e : skills()) map.add(e.getAsJsonObject().get("id").getAsString(), e);
    return map;
  }

  public static JsonArray courses() {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT * FROM courses ORDER BY id")) {
      while (r.next()) {
        JsonObject o = Json.obj("id", r.getString("id"), "title", r.getString("title"), "provider", r.getString("provider"),
            "level", r.getString("level"), "hours", r.getDouble("hours"), "url", r.getString("url"),
            "description", r.getString("description"), "skills", Json.any(r.getString("skills_json")));
        out.add(o);
      }
    } catch (SQLException ignored) { }
    return out;
  }

  public static JsonObject course(String id) {
    for (JsonElement e : courses()) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
    return null;
  }

  public static JsonArray companies() {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT data_json FROM companies ORDER BY name")) {
      while (r.next()) out.add(Json.any(r.getString(1)));
    } catch (SQLException ignored) { }
    return out;
  }

  public static JsonObject company(String idOrName) {
    if (idOrName == null) return new JsonObject();
    for (JsonElement e : companies()) {
      JsonObject c = e.getAsJsonObject();
      if (Json.str(c, "id", "").equalsIgnoreCase(idOrName) || Json.str(c, "name", "").equalsIgnoreCase(idOrName)) return c;
    }
    return new JsonObject();
  }

  // ---------------- student history ----------------

  public static List<JsonObject> attempts(int userId, String kind) {
    List<JsonObject> out = new ArrayList<>();
    // kind == null returns assessment-style attempts only; timed mock tests are listed separately
    // ordered by date so "first attempt -> latest attempt" is chronological
    String sql = "SELECT * FROM attempts WHERE user_id=? AND status='submitted'" +
        (kind == null ? " AND kind<>'mock'" : " AND kind=?") + " ORDER BY substr(submitted_at,1,10) ASC, id ASC";
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setInt(1, userId);
      if (kind != null) ps.setString(2, kind);
      ResultSet r = ps.executeQuery();
      while (r.next()) out.add(attemptRow(r));
    } catch (SQLException ignored) { }
    return out;
  }

  public static JsonObject attemptRow(ResultSet r) throws SQLException {
    return Json.obj("id", r.getInt("id"), "userId", r.getInt("user_id"), "kind", r.getString("kind"),
        "label", r.getString("label"), "status", r.getString("status"), "startedAt", r.getString("started_at"),
        "submittedAt", r.getString("submitted_at"), "totalScore", r.getDouble("total_score"),
        "maxScore", r.getDouble("max_score"), "accuracy", r.getDouble("accuracy"),
        "overallPct", r.getDouble("overall_pct"), "readiness", r.getString("readiness"),
        "sections", Json.any(r.getString("section_stats_json")),
        "violations", r.getInt("violations"), "timeSec", r.getDouble("time_sec"));
  }

  public static JsonObject attemptDetail(int attemptId, int userId) {
    JsonObject attempt = null;
    JsonArray answers = new JsonArray();
    try (Connection c = Db.get()) {
      try (PreparedStatement ps = c.prepareStatement("SELECT * FROM attempts WHERE id=? AND user_id=?")) {
        ps.setInt(1, attemptId);
        ps.setInt(2, userId);
        ResultSet r = ps.executeQuery();
        if (!r.next()) return null;
        attempt = attemptRow(r);
      }
      try (PreparedStatement ps = c.prepareStatement(
          "SELECT aa.*, q.topic, q.prompt, q.explanation, q.model_answer, q.options_json, q.answer_json " +
          "FROM attempt_answers aa LEFT JOIN questions q ON q.id=aa.question_id WHERE aa.attempt_id=? ORDER BY aa.id")) {
        ps.setInt(1, attemptId);
        ResultSet r = ps.executeQuery();
        while (r.next()) {
          JsonObject o = Json.obj("questionId", r.getString("question_id"), "section", r.getString("section"),
              "topic", r.getString("topic"), "difficulty", r.getString("difficulty"),
              "chosen", Json.any(r.getString("chosen_json")), "awarded", r.getDouble("awarded"),
              "max", r.getDouble("max"), "correct", r.getInt("correct"), "timeSec", r.getDouble("time_sec"),
              "prompt", r.getString("prompt"), "explanation", r.getString("explanation"),
              "modelAnswer", r.getString("model_answer"));
          JsonObject qRow = question(r.getString("question_id"));
          if (qRow != null) {
            o.add("options", Json.array(qRow, "options"));
            o.add("answerRaw", qRow.get("answerRaw"));
          }
          answers.add(o);
        }
      }
    } catch (SQLException e) { return null; }
    attempt.add("answers", answers);
    return attempt;
  }

  /** Topic level accuracy + speed for a student, used by the gap engine, heatmap and radar. */
  public static List<JsonObject> topicStats(int userId) {
    String sql = "SELECT q.skill_id, q.topic, q.section, COUNT(*) attempts, SUM(aa.correct) correct, " +
        "AVG(aa.time_sec) avg_time FROM attempt_answers aa JOIN questions q ON q.id=aa.question_id " +
        "JOIN attempts a ON a.id=aa.attempt_id WHERE a.user_id=? AND a.status='submitted' " +
        "GROUP BY q.skill_id, q.topic, q.section ORDER BY q.section";
    List<JsonObject> out = new ArrayList<>();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      while (r.next()) {
        int attempts = r.getInt("attempts");
        int correct = r.getInt("correct");
        out.add(Json.obj("skillId", r.getString("skill_id"), "topic", r.getString("topic"), "section", r.getString("section"),
            "attempts", attempts, "correct", correct,
            "accuracy", attempts == 0 ? 0 : Math.round((double) correct / attempts * 1000.0) / 1000.0,
            "avgTimeSec", Math.round(r.getDouble("avg_time") * 10.0) / 10.0));
      }
    } catch (SQLException ignored) { }
    return out;
  }

  /** Per-skill competency (used by company fit and the radar chart). */
  public static JsonObject competency(int userId) {
    JsonObject bySkill = new JsonObject();
    for (JsonObject t : topicStats(userId)) {
      String skillId = Json.str(t, "skillId", "");
      if (skillId.isEmpty()) continue;
      JsonObject cur = bySkill.has(skillId) ? bySkill.getAsJsonObject(skillId) : Json.obj("skillId", skillId,
          "skill", Json.str(t, "topic", ""), "section", Json.str(t, "section", ""), "attempts", 0, "correct", 0);
      int attempts = Json.intOf(cur, "attempts", 0) + Json.intOf(t, "attempts", 0);
      int correct = Json.intOf(cur, "correct", 0) + Json.intOf(t, "correct", 0);
      cur.addProperty("attempts", attempts);
      cur.addProperty("correct", correct);
      cur.addProperty("accuracy", attempts == 0 ? 0 : Math.round((double) correct / attempts * 1000.0) / 10.0);
      bySkill.add(skillId, cur);
    }
    return bySkill;
  }

  public static void log(int userId, String type, String detail) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO events(user_id,type,detail,created_at) VALUES(?,?,?,?)")) {
      ps.setInt(1, userId);
      ps.setString(2, type);
      ps.setString(3, detail == null ? "" : detail.substring(0, Math.min(500, detail.length())));
      ps.setString(4, Time.now());
      ps.executeUpdate();
    } catch (SQLException ignored) { }
  }

  public static void notify(int userId, String title, String body, String dueDate) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO notifications(user_id,title,body,due_date,is_read,created_at) VALUES(?,?,?,?,0,?)")) {
      ps.setInt(1, userId);
      ps.setString(2, title);
      ps.setString(3, body);
      ps.setString(4, dueDate);
      ps.setString(5, Time.now());
      ps.executeUpdate();
    } catch (SQLException ignored) { }
  }
}
