package placeiq;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Mentor and admin views: student monitoring, at-risk flags, notes, assignments, cohort analytics, data admin. */
public final class MentorAdmin {

  // ---------------- mentor: student list with risk flags ----------------

  public static JsonArray students(int mentorId, JsonObject user) {
    JsonObject risk = Json.child(Db.config(), "mentorRisk");
    double dropLimit = Json.num(risk, "decliningTrendDrop", 5);
    int inactiveDays = Json.intOf(risk, "inactiveDays", 10);
    double lowReadiness = Json.num(risk, "lowReadiness", 45);
    JsonArray out = new JsonArray();
    String sql = "SELECT * FROM users WHERE role='student'" + ("mentor".equals(Json.str(user, "role", "")) ?
        " AND (college = ? OR ? = '')" : "");
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
      if ("mentor".equals(Json.str(user, "role", ""))) {
        ps.setString(1, Json.str(user, "college", ""));
        ps.setString(2, Json.str(user, "college", ""));
      }
      ResultSet r = ps.executeQuery();
      while (r.next()) {
        int id = r.getInt("id");
        JsonObject prs = Analytics.prsComponents(id);
        List<JsonObject> attempts = Content.attempts(id, null);
        List<Double> series = new ArrayList<>();
        for (JsonObject a : attempts) series.add(Json.num(a, "overallPct", 0));
        double slope = Scoring.slope(series.size() > 4 ? series.subList(series.size() - 4, series.size()) : series);
        int daysInactive = Time.daysSince(r.getString("last_active"));
        JsonArray flags = new JsonArray();
        if (slope < -dropLimit / 10.0 && series.size() >= 2) flags.add("Declining trend (" + Math.round(slope * 10) / 10.0 + " pts/attempt)");
        if (daysInactive >= inactiveDays) flags.add("Inactive for " + daysInactive + " days");
        if (Json.num(prs, "score", 0) < lowReadiness) flags.add("Readiness below " + (int) lowReadiness);
        double completion = planCompletion(id);
        if (completion < 40 && completion >= 0) flags.add("Plan completion " + Math.round(completion) + "%");
        out.add(Json.obj(
            "id", id, "name", r.getString("name"), "email", r.getString("email"), "college", r.getString("college"),
            "branch", r.getString("branch"), "domain", r.getString("domain"), "targetCompany", r.getString("target_company"),
            "lastActive", r.getString("last_active"), "daysInactive", daysInactive,
            "assessments", attempts.size(),
            "latestPct", series.isEmpty() ? 0 : series.get(series.size() - 1),
            "firstPct", series.isEmpty() ? 0 : series.get(0),
            "improvementPoints", series.isEmpty() ? 0 : Math.round((series.get(series.size() - 1) - series.get(0)) * 10.0) / 10.0,
            "prs", Json.num(prs, "score", 0), "readiness", Json.str(prs, "readiness", "Beginner"),
            "planCompletion", completion, "flags", flags, "atRisk", flags.size() > 0));
      }
    } catch (SQLException ignored) { }
    List<JsonObject> list = new ArrayList<>();
    for (JsonElement e : out) list.add(e.getAsJsonObject());
    list.sort((a, b) -> Double.compare(Json.num(a, "prs", 0), Json.num(b, "prs", 0)));
    JsonArray sorted = new JsonArray();
    for (JsonObject o : list) sorted.add(o);
    return sorted;
  }

  private static double planCompletion(int userId) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT COUNT(*) n, SUM(CASE WHEN status='done' THEN 1 ELSE 0 END) d FROM plan_tasks WHERE user_id=?")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      if (r.next() && r.getInt("n") > 0) return (double) r.getInt("d") / r.getInt("n") * 100;
    } catch (SQLException ignored) { }
    return -1;
  }

  public static JsonObject studentDetail(int studentId) throws SQLException {
    JsonObject student = Auth.me(studentId);
    if (student == null) return Json.obj("error", "student not found");
    JsonObject out = Json.obj("student", student);
    out.add("prs", Analytics.prsComponents(studentId));
    out.add("improvement", Analytics.improvementFor(studentId));
    out.add("charts", Analytics.charts(studentId));
    out.add("gaps", Json.arr(Gaps.ranked(studentId).toArray()));
    out.add("attempts", Analytics.summaries(Content.attempts(studentId, null)));
    out.add("mocks", Tools.mockHistory(studentId));
    out.add("interviews", Tools.interviewHistory(studentId));
    out.add("plan", Planner.activePlan(studentId));
    out.add("notes", notes(studentId));
    out.add("events", events(studentId, 20));
    out.add("checklist", Tools.checklist(studentId));
    JsonObject percentile = new JsonObject();
    double mine = Json.num(Analytics.prsComponents(studentId), "score", 0);
    List<Double> others = new ArrayList<>();
    try (Connection c = Db.get(); Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT id FROM users WHERE role='student'")) {
      while (r.next()) others.add(Json.num(Analytics.prsComponents(r.getInt(1)), "score", 0));
    }
    if (!others.isEmpty()) {
      int below = 0;
      for (double v : others) if (v <= mine) below++;
      percentile = Json.obj("percentile", Math.round((double) below / others.size() * 100),
          "cohort", others.size(), "average", Math.round(others.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10.0) / 10.0);
    }
    out.add("cohortComparison", percentile);
    return out;
  }

  public static JsonArray notes(int studentId) {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT n.*, u.name mentor FROM mentor_notes n LEFT JOIN users u ON u.id=n.mentor_id WHERE n.student_id=? ORDER BY n.id DESC")) {
      ps.setInt(1, studentId);
      ResultSet r = ps.executeQuery();
      while (r.next())
        out.add(Json.obj("note", r.getString("note"), "mentor", r.getString("mentor"), "createdAt", r.getString("created_at")));
    } catch (SQLException ignored) { }
    return out;
  }

  public static JsonObject addNote(int mentorId, int studentId, String note) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO mentor_notes(mentor_id,student_id,note,created_at) VALUES(?,?,?,?)")) {
      ps.setInt(1, mentorId);
      ps.setInt(2, studentId);
      ps.setString(3, note);
      ps.setString(4, Time.now());
      ps.executeUpdate();
    }
    Content.log(mentorId, "mentor_note", "note added for student " + studentId);
    return Json.obj("notes", notes(studentId));
  }

  public static JsonObject assignTask(int mentorId, int studentId, JsonObject body) throws Exception {
    String title = Json.str(body, "title", "Mentor assignment");
    String type = Json.str(body, "type", "practice");
    String due = Json.str(body, "dueDate", Time.plusDays(Time.today(), 7));
    String section = Json.str(body, "section", "");
    JsonObject plan = Planner.activePlan(studentId);
    if (plan == null) {
      JsonObject student = Auth.me(studentId);
      Planner.create(studentId, Json.obj("targetCompany", Json.str(student, "targetCompany", "TCS"),
          "targetRole", Json.str(student, "targetRole", "Software Engineer"),
          "targetDate", Time.plusDays(Time.today(), 42)), student);
      plan = Planner.activePlan(studentId);
    }
    int planId = Json.intOf(plan, "id", 0);
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO plan_tasks(plan_id,user_id,week_no,title,type,ref_id,skill_id,hours,due_date,status,carried_over) " +
        "VALUES(?,?,?,?,?,?,?,?,?,'pending',0)")) {
      ps.setInt(1, planId);
      ps.setInt(2, studentId);
      ps.setInt(3, Json.intOf(plan, "currentWeek", 0));
      ps.setString(4, "[Mentor] " + title);
      ps.setString(5, type);
      ps.setString(6, Json.str(body, "refId", ""));
      ps.setString(7, section);
      ps.setDouble(8, Json.num(body, "hours", 2));
      ps.setString(9, due);
      ps.executeUpdate();
    }
    Content.notify(studentId, "New task from your mentor", title + " - due " + due, due);
    return Json.obj("assigned", true, "plan", Planner.activePlan(studentId));
  }

  public static JsonObject assignTest(int mentorId, int studentId, String companyId) throws Exception {
    JsonObject student = Auth.me(studentId);
    String due = Time.plusDays(Time.today(), 3);
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO plan_tasks(plan_id,user_id,week_no,title,type,ref_id,skill_id,hours,due_date,status,carried_over) " +
        "SELECT id,?,?,?, 'mock', ?, NULL, 2, ?, 'pending', 0 FROM plans WHERE user_id=? ORDER BY id DESC LIMIT 1")) {
      ps.setInt(1, studentId);
      ps.setInt(2, 0);
      ps.setString(3, "[Mentor] " + companyId.toUpperCase() + " timed mock test");
      ps.setString(4, companyId);
      ps.setString(5, due);
      ps.setInt(6, studentId);
      ps.executeUpdate();
    }
    Content.notify(studentId, "Mentor assigned a timed mock test", companyId.toUpperCase() +
        " pattern test due " + due + ". Auto-submits when the timer ends.", due);
    Content.log(mentorId, "assign_test", companyId + " -> student " + studentId);
    return Json.obj("assigned", true, "company", companyId, "dueDate", due);
  }

  public static JsonArray events(int userId, int limit) {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT * FROM events WHERE user_id=? ORDER BY id DESC LIMIT ?")) {
      ps.setInt(1, userId);
      ps.setInt(2, limit);
      ResultSet r = ps.executeQuery();
      while (r.next())
        out.add(Json.obj("type", r.getString("type"), "detail", r.getString("detail"), "createdAt", r.getString("created_at")));
    } catch (SQLException ignored) { }
    return out;
  }

  // ---------------- cohort analytics ----------------

  public static JsonObject cohort(JsonObject viewer) {
    JsonArray students = students(0, viewer);
    JsonObject out = new JsonObject();
    double avgPrs = 0, avgImprovement = 0, avgCompletion = 0;
    int atRisk = 0, withPlan = 0;
    JsonObject sectionTotals = new JsonObject();
    JsonObject sectionCounts = new JsonObject();
    Map<String, Integer> readinessBuckets = new LinkedHashMap<>();
    readinessBuckets.put("Beginner", 0);
    readinessBuckets.put("Intermediate", 0);
    readinessBuckets.put("Advanced", 0);

    for (JsonElement e : students) {
      JsonObject s = e.getAsJsonObject();
      int id = Json.intOf(s, "id", 0);
      avgPrs += Json.num(s, "prs", 0);
      avgImprovement += Json.num(s, "improvementPoints", 0);
      if (Json.num(s, "planCompletion", -1) >= 0) { avgCompletion += Json.num(s, "planCompletion", 0); withPlan++; }
      if (s.get("atRisk").getAsBoolean()) atRisk++;
      String level = Json.str(s, "readiness", "Beginner");
      readinessBuckets.merge(level, 1, Integer::sum);
      List<JsonObject> attempts = Content.attempts(id, null);
      if (!attempts.isEmpty()) {
        for (JsonElement se : Json.array(attempts.get(attempts.size() - 1), "sections")) {
          JsonObject sec = se.getAsJsonObject();
          String key = Json.str(sec, "section", "");
          sectionTotals.addProperty(key, Json.num(sectionTotals, key, 0) + Json.num(sec, "percentage", 0));
          sectionCounts.addProperty(key, Json.intOf(sectionCounts, key, 0) + 1);
        }
      }
    }
    int n = students.size();
    JsonArray sectionAverages = new JsonArray();
    for (String key : sectionTotals.keySet()) {
      double avg = Json.num(sectionTotals, key, 0) / Math.max(1, Json.intOf(sectionCounts, key, 1));
      sectionAverages.add(Json.obj("section", key, "averagePct", Math.round(avg * 10.0) / 10.0,
          "students", Json.intOf(sectionCounts, key, 0),
          "weakest", avg < 55));
    }
    JsonArray readinessDist = new JsonArray();
    readinessBuckets.forEach((k, v) -> readinessDist.add(Json.obj("level", k, "count", v)));
    out.addProperty("students", n);
    out.addProperty("averagePrs", n == 0 ? 0 : Math.round(avgPrs / n * 10.0) / 10.0);
    out.addProperty("averageImprovementPoints", n == 0 ? 0 : Math.round(avgImprovement / n * 10.0) / 10.0);
    out.addProperty("averagePlanCompletion", withPlan == 0 ? 0 : Math.round(avgCompletion / withPlan));
    out.addProperty("atRisk", atRisk);
    out.add("sectionAverages", sectionAverages);
    out.add("readinessDistribution", readinessDist);
    out.add("students", students);
    // anonymised percentile bands for the cohort view
    List<Double> prsValues = new ArrayList<>();
    for (JsonElement e : students) prsValues.add(Json.num(e.getAsJsonObject(), "prs", 0));
    Collections.sort(prsValues);
    out.add("percentileBands", Json.obj(
        "p25", prsValues.isEmpty() ? 0 : prsValues.get((int) (prsValues.size() * 0.25)),
        "p50", prsValues.isEmpty() ? 0 : prsValues.get((int) (prsValues.size() * 0.50)),
        "p75", prsValues.isEmpty() ? 0 : prsValues.get(Math.min(prsValues.size() - 1, (int) (prsValues.size() * 0.75)))));
    return out;
  }

  public static JsonArray notifications(int userId) {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT * FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 20")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      while (r.next())
        out.add(Json.obj("id", r.getInt("id"), "title", r.getString("title"), "body", r.getString("body"),
            "dueDate", r.getString("due_date"), "read", r.getInt("is_read") == 1, "createdAt", r.getString("created_at")));
    } catch (SQLException ignored) { }
    return out;
  }

  public static void markNotificationsRead(int userId) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "UPDATE notifications SET is_read=1 WHERE user_id=?")) {
      ps.setInt(1, userId);
      ps.executeUpdate();
    }
  }

  // ---------------- admin: question bank + company data + import/export ----------------

  public static JsonArray questionBank(String section, String domain) {
    JsonArray out = new JsonArray();
    StringBuilder sql = new StringBuilder("SELECT * FROM questions WHERE 1=1");
    List<String> params = new ArrayList<>();
    if (section != null && !section.isBlank()) { sql.append(" AND section=?"); params.add(section); }
    if (domain != null && !domain.isBlank()) { sql.append(" AND (domain IS NULL OR domain='' OR domain=?)"); params.add(domain); }
    sql.append(" ORDER BY section, topic, difficulty");
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
      for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
      ResultSet r = ps.executeQuery();
      while (r.next()) {
        JsonObject q = Content.questionRow(r);
        q.remove("answerRaw");
        out.add(q);
      }
    } catch (SQLException ignored) { }
    return out;
  }

  /** Validated upsert of a single question (admin form). */
  public static JsonObject saveQuestion(JsonObject q) throws Exception {
    List<String> errors = new ArrayList<>();
    String id = Json.str(q, "id", "").trim();
    String section = Json.str(q, "section", "").trim();
    String type = Json.str(q, "type", "mcq").trim();
    String prompt = Json.str(q, "prompt", "").trim();
    if (id.isEmpty()) errors.add("id is required (e.g. Q065)");
    if (section.isEmpty()) errors.add("section is required");
    if (prompt.length() < 10) errors.add("prompt must be at least 10 characters");
    if (!Arrays.asList("mcq", "multi", "short", "coding", "written").contains(type))
      errors.add("type must be one of mcq, multi, short, coding, written");
    if (("mcq".equals(type) || "multi".equals(type)) && Json.array(q, "options").size() < 2)
      errors.add("mcq/multi questions need at least 2 options");
    if (errors.size() > 0) return Json.obj("ok", false, "errors", Json.arr(errors.toArray()));
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO questions(id,section,topic,skill_id,difficulty,type,time_limit,domain,prompt,options_json,answer_json," +
        "rubric_json,starter,checks_json,tests_json,explanation,model_answer,company_tags) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
        "ON CONFLICT(id) DO UPDATE SET section=excluded.section, topic=excluded.topic, skill_id=excluded.skill_id, " +
        "difficulty=excluded.difficulty, type=excluded.type, time_limit=excluded.time_limit, domain=excluded.domain, " +
        "prompt=excluded.prompt, options_json=excluded.options_json, answer_json=excluded.answer_json, rubric_json=excluded.rubric_json, " +
        "starter=excluded.starter, checks_json=excluded.checks_json, tests_json=excluded.tests_json, explanation=excluded.explanation, " +
        "model_answer=excluded.model_answer, company_tags=excluded.company_tags")) {
      ps.setString(1, id);
      ps.setString(2, section);
      ps.setString(3, Json.str(q, "topic", "General"));
      ps.setString(4, Json.str(q, "skillId", ""));
      ps.setString(5, Json.str(q, "difficulty", "medium"));
      ps.setString(6, type);
      ps.setInt(7, Json.intOf(q, "timeLimit", 90));
      ps.setString(8, Json.str(q, "domain", ""));
      ps.setString(9, prompt);
      ps.setString(10, Json.array(q, "options").toString());
      ps.setString(11, Json.array(q, "answer").toString());
      ps.setString(12, Json.child(q, "rubric").toString());
      ps.setString(13, Json.str(q, "starter", ""));
      ps.setString(14, Json.array(q, "checks").toString());
      ps.setString(15, Json.array(q, "tests").toString());
      ps.setString(16, Json.str(q, "explanation", ""));
      ps.setString(17, Json.str(q, "modelAnswer", ""));
      ps.setString(18, Json.array(q, "companyTags").toString());
      ps.executeUpdate();
    }
    return Json.obj("ok", true, "id", id);
  }

  public static JsonObject deleteQuestion(String id) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("DELETE FROM questions WHERE id=?")) {
      ps.setString(1, id);
      return Json.obj("deleted", ps.executeUpdate() > 0);
    }
  }

  /** CSV or JSON import for the question bank and company data. Errors are reported per row. */
  public static JsonObject importData(String kind, String filename, String content) throws Exception {
    if ("companies".equalsIgnoreCase(kind) || (filename != null && filename.toLowerCase().endsWith(".json") && !"questions".equalsIgnoreCase(kind))) {
      JsonArray rows = new JsonArray();
      JsonElement parsed = JsonParser.parseString(content);
      if (parsed.isJsonArray()) rows = parsed.getAsJsonArray();
      else rows.add(parsed);
      int ok = 0;
      JsonArray errors = new JsonArray();
      for (JsonElement e : rows) {
        if (!e.isJsonObject()) { errors.add("Each company must be a JSON object"); continue; }
        JsonObject company = e.getAsJsonObject();
        if (Json.str(company, "id", "").isBlank() || Json.str(company, "name", "").isBlank()) {
          errors.add("Company missing id or name"); continue;
        }
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO companies(id,name,data_json) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name, data_json=excluded.data_json")) {
          ps.setString(1, Json.str(company, "id", ""));
          ps.setString(2, Json.str(company, "name", ""));
          ps.setString(3, company.toString());
          ps.executeUpdate();
          ok++;
        }
      }
      return Json.obj("ok", true, "imported", ok, "errors", errors, "kind", "companies");
    }
    // questions CSV
    String[] lines = content.split("\\r?\\n");
    if (lines.length < 2) return Json.obj("ok", false, "errors", Json.arr("CSV needs a header row and at least one data row"));
    String[] header = splitCsv(lines[0]);
    List<String> required = Arrays.asList("id", "section", "prompt");
    for (String req : required)
      if (!Arrays.asList(header).contains(req))
        return Json.obj("ok", false, "errors", Json.arr("Missing required column: " + req + ". Expected header: " +
            String.join(",", required) + ",topic,skillId,difficulty,type,timeLimit,optionA,optionB,optionC,optionD,answer,explanation"));
    int ok = 0;
    JsonArray errors = new JsonArray();
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) continue;
      String[] cells = splitCsv(lines[i]);
      Map<String, String> row = new HashMap<>();
      for (int j = 0; j < header.length && j < cells.length; j++) row.put(header[j].trim(), cells[j].trim());
      JsonObject q = Json.obj("id", row.getOrDefault("id", ""), "section", row.getOrDefault("section", ""),
          "topic", row.getOrDefault("topic", "General"), "skillId", row.getOrDefault("skillId", ""),
          "difficulty", row.getOrDefault("difficulty", "medium"), "type", row.getOrDefault("type", "mcq"),
          "timeLimit", parseInt(row.getOrDefault("timeLimit", "90"), 90), "prompt", row.getOrDefault("prompt", ""),
          "explanation", row.getOrDefault("explanation", ""));
      JsonArray options = new JsonArray();
      for (String letter : new String[]{"A", "B", "C", "D"}) {
        String v = row.get("option" + letter);
        if (v != null && !v.isBlank()) options.add(v);
      }
      q.add("options", options);
      String answer = row.getOrDefault("answer", "A").trim();
      JsonArray ans = new JsonArray();
      for (String part : answer.split("[|,]")) if (!part.isBlank()) ans.add(part.trim().toUpperCase());
      q.add("answer", ans);
      JsonObject res = saveQuestion(q);
      if (res.get("ok").getAsBoolean()) ok++;
      else errors.add("Row " + (i + 1) + " (" + row.getOrDefault("id", "?") + "): " + Json.array(res, "errors"));
    }
    return Json.obj("ok", true, "imported", ok, "errors", errors, "kind", "questions");
  }

  private static int parseInt(String s, int fallback) {
    try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
  }

  private static String[] splitCsv(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (char ch : line.toCharArray()) {
      if (ch == '"') quoted = !quoted;
      else if (ch == ',' && !quoted) { cells.add(cur.toString()); cur.setLength(0); }
      else cur.append(ch);
    }
    cells.add(cur.toString());
    return cells.toArray(new String[0]);
  }

  public static String exportCsv(String what, JsonObject viewer) {
    StringBuilder sb = new StringBuilder();
    if ("attempts".equals(what)) {
      sb.append("student_id,student,attempt_id,label,submitted_at,overall_pct,readiness,accuracy,violations\n");
      for (JsonElement e : students(0, viewer)) {
        int id = Json.intOf(e.getAsJsonObject(), "id", 0);
        for (JsonObject a : Content.attempts(id, null))
          sb.append(id).append(',').append(csv(Json.str(e.getAsJsonObject(), "name", ""))).append(',')
              .append(Json.intOf(a, "id", 0)).append(',').append(csv(Json.str(a, "label", ""))).append(',')
              .append(Json.str(a, "submittedAt", "")).append(',').append(Json.num(a, "overallPct", 0)).append(',')
              .append(Json.str(a, "readiness", "")).append(',').append(Json.num(a, "accuracy", 0)).append(',')
              .append(Json.intOf(a, "violations", 0)).append('\n');
      }
    } else {
      sb.append("id,name,email,college,branch,domain,target_company,target_role,prs,readiness,assessments,improvement_points,plan_completion,at_risk\n");
      for (JsonElement e : students(0, viewer)) {
        JsonObject s = e.getAsJsonObject();
        sb.append(Json.intOf(s, "id", 0)).append(',').append(csv(Json.str(s, "name", ""))).append(',')
            .append(csv(Json.str(s, "email", ""))).append(',').append(csv(Json.str(s, "college", ""))).append(',')
            .append(csv(Json.str(s, "branch", ""))).append(',').append(csv(Json.str(s, "domain", ""))).append(',')
            .append(csv(Json.str(s, "targetCompany", ""))).append(',').append(Json.num(s, "prs", 0)).append(',')
            .append(Json.str(s, "readiness", "")).append(',').append(Json.intOf(s, "assessments", 0)).append(',')
            .append(Json.num(s, "improvementPoints", 0)).append(',').append(Json.num(s, "planCompletion", 0)).append(',')
            .append(s.get("atRisk").getAsBoolean()).append('\n');
      }
    }
    return sb.toString();
  }

  private static String csv(String v) { return '"' + (v == null ? "" : v.replace("\"", "'")) + '"'; }

  // ---------------- demo population ----------------

  /** Gives a demo student mock tests, an interview, a resume report and checklist progress. */
  public static void seedBaselineProgress(int userId, int seed) throws Exception {
    double talent = 0.42 + seed * 0.07;
    Assessment.seedDemoAttempt(userId, 26 - seed, talent - 0.12, "Diagnostic assessment", new HashMap<>());
    Assessment.seedDemoAttempt(userId, 12 - seed % 4, talent, "Diagnostic assessment", new HashMap<>());
    String[] companies = {"tcs", "infosys", "zoho", "amazon", "cognizant"};
    String company = companies[seed % companies.length];
    JsonObject user = Auth.me(userId);
    // one finished mock test
    JsonObject mock = Tools.startMock(userId, company, user);
    JsonArray answers = new JsonArray();
    for (JsonElement e : Json.array(mock, "questions")) {
      JsonObject q = e.getAsJsonObject();
      JsonArray keys = Json.array(q, "optionKeys");
      String correctLetter = "";
      JsonObject full = Content.question(Json.str(q, "id", ""));
      if (full != null && Json.array(full, "answerRaw").size() > 0) {
        String canonical = Json.array(full, "answerRaw").get(0).getAsString();
        for (int i = 0; i < keys.size(); i++) if (keys.get(i).getAsString().equalsIgnoreCase(canonical)) correctLetter = String.valueOf((char) ('A' + i));
      }
      boolean right = Math.random() < talent;
      JsonArray given = new JsonArray();
      if (!correctLetter.isEmpty()) given.add(right ? correctLetter : (correctLetter.equals("A") ? "B" : "A"));
      else given.add("skipped");
      answers.add(Json.obj("questionId", Json.str(q, "id", ""), "answer", given, "timeSec", 45 + Math.random() * 40));
    }
    Tools.submitMock(userId, Json.intOf(mock, "mockId", 0), Json.obj("answers", answers));

    // one finished mock interview
    JsonObject interview = Tools.startInterview(userId, Json.obj("mode", seed % 2 == 0 ? "hr" : "technical", "company", company), user);
    int interviewId = Json.intOf(interview, "interviewId", 0);
    JsonObject question = Json.child(interview, "question");
    String answer = "Situation: our team had to ship a college project demo in one week. Task: I owned the backend while " +
        "handling the presentation. Action: I split the work into daily checkpoints, paired on the bug that blocked us, and " +
        "rehearsed twice. Result: we delivered on time and the evaluators gave us the highest score of " + (80 + seed) + "%. " +
        "The trade-off was less time for extra features, which we accepted to protect quality.";
    Tools.answerInterview(userId, Json.obj("interviewId", interviewId, "question", Json.str(question, "prompt", ""),
        "answer", answer, "mode", Json.str(interview, "mode", "hr"), "rubric", Json.child(question, "rubric")));
    Tools.finishInterview(userId, interviewId);

    // resume report
    Tools.analyzeResume(userId, "demo-resume.txt", null,
        "Aarav Sharma\n" + Json.str(user, "email", "student@placeiq.dev") + " | +91 98765 43210 | linkedin.com/in/aarav | github.com/aarav\n" +
        "Education\nB.E. Computer Science, Kongu Engineering College, CGPA 8.1, 2026\n" +
        "Skills\nJava, SQL, Data Structures, Git, REST API\n" +
        "Projects\nBuilt a placement preparation portal with Java and SQL serving 200 students\n" +
        "Automated attendance reporting and reduced manual effort by 60%\n" +
        "Experience\nDeveloped REST APIs during a 6 week internship and wrote unit tests\n" +
        "Certifications\nOracle SQL Fundamentals\n" +
        "Achievements\nWinner of the department coding contest 2025", Json.str(user, "targetRole", "Software Engineer"));

    // checklist progress
    for (String key : new String[]{"linkedin", "github", "resume_pdf", "projects"}) Tools.toggleChecklist(userId, key, true);
    Content.notify(userId, "Weekly goal", "Complete two pending plan tasks to keep your streak alive.", Time.plusDays(Time.today(), 3));
  }
}
