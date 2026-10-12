package placeiq;

import com.google.gson.*;
import java.sql.*;
import java.util.*;

/** Improvement analytics, skill profile charts and the Placement Readiness Score. */
public final class Analytics {

  /** Series of overall + per-section percentages across a student's submitted attempts. */
  public static JsonObject improvementFor(int userId) {
    List<JsonObject> attempts = Content.attempts(userId, null);
    List<String> labels = new ArrayList<>();
    List<Double> overall = new ArrayList<>();
    Map<String, List<Double>> perSection = new LinkedHashMap<>();
    for (JsonElement se : Json.array(Db.config(), "sections")) perSection.put(se.getAsJsonObject().get("id").getAsString(), new ArrayList<>());
    for (JsonObject a : attempts) {
      labels.add(Json.str(a, "label", "Attempt"));
      overall.add(Json.num(a, "overallPct", 0));
      Map<String, Double> bySection = new HashMap<>();
      for (JsonElement se : Json.array(a, "sections")) {
        JsonObject s = se.getAsJsonObject();
        bySection.put(Json.str(s, "section", ""), Json.num(s, "percentage", 0));
      }
      for (String section : perSection.keySet()) perSection.get(section).add(bySection.getOrDefault(section, 0.0));
    }
    JsonObject out = new JsonObject();
    out.add("overall", Scoring.improvement(wrap(overall), labels));
    JsonArray sections = new JsonArray();
    for (Map.Entry<String, List<Double>> e : perSection.entrySet()) {
      JsonObject s = Scoring.improvement(wrap(e.getValue()), labels);
      s.addProperty("section", e.getKey());
      s.addProperty("name", sectionName(e.getKey()));
      sections.add(s);
    }
    out.add("sections", sections);
    out.add("labels", Json.arr(labels.toArray()));
    out.add("overallSeries", Json.arr(overall.toArray()));
    // trend indicator from a least-squares slope over the last 4 attempts
    List<Double> recent = overall.size() > 4 ? overall.subList(overall.size() - 4, overall.size()) : overall;
    double slope = Scoring.slope(recent);
    out.addProperty("trend", overall.size() < 2 ? "no-data" : slope > 0.8 ? "improving" : slope < -0.8 ? "declining" : "flat");
    return out;
  }

  private static List<double[]> wrap(List<Double> values) {
    List<double[]> out = new ArrayList<>();
    for (Double v : values) out.add(new double[]{v});
    return out;
  }

  private static String sectionName(String id) {
    for (JsonElement e : Json.array(Db.config(), "sections")) {
      JsonObject s = e.getAsJsonObject();
      if (Json.str(s, "id", "").equals(id)) return Json.str(s, "name", id);
    }
    return id;
  }

  /** Radar (current skill profile), heatmap (accuracy by topic) and accuracy-vs-speed scatter. */
  public static JsonObject charts(int userId) {
    JsonObject competency = Content.competency(userId);
    List<JsonObject> topics = Content.topicStats(userId);

    JsonArray radar = new JsonArray();
    JsonObject bySection = new JsonObject();
    for (JsonElement e : Json.array(Db.config(), "sections")) {
      String id = e.getAsJsonObject().get("id").getAsString();
      bySection.add(id, Json.obj("attempts", 0, "correct", 0, "sum", 0.0));
    }
    for (JsonObject t : topics) {
      String section = Json.str(t, "section", "");
      if (!bySection.has(section)) continue;
      JsonObject acc = bySection.getAsJsonObject(section);
      acc.addProperty("attempts", Json.intOf(acc, "attempts", 0) + Json.intOf(t, "attempts", 0));
      acc.addProperty("correct", Json.intOf(acc, "correct", 0) + Json.intOf(t, "correct", 0));
    }
    for (String section : bySection.keySet()) {
      JsonObject acc = bySection.getAsJsonObject(section);
      int attempts = Json.intOf(acc, "attempts", 0);
      double pct = attempts == 0 ? 0 : (double) Json.intOf(acc, "correct", 0) / attempts * 100;
      radar.add(Json.obj("axis", sectionName(section), "section", section,
          "value", Math.round(pct * 10.0) / 10.0, "attempts", attempts));
    }

    JsonArray heatmap = new JsonArray();
    for (JsonObject t : topics)
      heatmap.add(Json.obj("topic", Json.str(t, "topic", ""), "section", Json.str(t, "section", ""),
          "skillId", Json.str(t, "skillId", ""), "accuracy", Json.num(t, "accuracy", 0) * 100,
          "attempts", Json.intOf(t, "attempts", 0)));

    JsonArray scatter = new JsonArray();
    for (JsonObject t : topics)
      scatter.add(Json.obj("topic", Json.str(t, "topic", ""), "section", Json.str(t, "section", ""),
          "accuracy", Math.round(Json.num(t, "accuracy", 0) * 1000.0) / 10.0,
          "avgTimeSec", Json.num(t, "avgTimeSec", 0)));
    return Json.obj("radar", radar, "heatmap", heatmap, "scatter", scatter);
  }

  /** Weighted Placement Readiness Score with a breakdown of what contributed. */
  public static JsonObject prsComponents(int userId) {
    JsonObject cfg = Db.config();
    JsonObject components = new JsonObject();

    List<JsonObject> attempts = Content.attempts(userId, null);
    if (!attempts.isEmpty()) {
      JsonObject latest = attempts.get(attempts.size() - 1);
      double value = Json.num(latest, "overallPct", 0);
      double prev = attempts.size() > 1 ? Json.num(attempts.get(attempts.size() - 2), "overallPct", 0) : value;
      double delta = value - prev;
      components.add("assessment", Json.obj("value", value, "detail",
          (delta >= 1 ? "up " : delta <= -1 ? "down " : "steady ") + Math.abs(Math.round(delta)) + " pts vs previous assessment"));
    }
    double bestMock = 0;
    String mockDetail = "no timed mock yet";
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT MAX(total_score) m, MAX(submitted_at) s FROM mock_tests WHERE user_id=? AND submitted_at IS NOT NULL")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      if (r.next() && r.getObject("m") != null) {
        bestMock = r.getDouble("m");
        mockDetail = "best company-pattern mock on " + r.getString("s");
      }
    } catch (SQLException ignored) { }
    if (bestMock > 0) components.add("mockTest", Json.obj("value", bestMock, "detail", mockDetail));

    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT AVG(overall_pct) a, COUNT(*) n FROM interviews WHERE user_id=? AND finished_at IS NOT NULL")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      if (r.next() && r.getInt("n") > 0)
        components.add("mockInterview", Json.obj("value", r.getDouble("a"), "detail", r.getInt("n") + " mock interview(s) scored"));
    } catch (SQLException ignored) { }

    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT ats_score FROM resume_reports WHERE user_id=? ORDER BY id DESC LIMIT 1")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      if (r.next()) components.add("resume", Json.obj("value", r.getDouble("ats_score"), "detail", "latest ATS scan"));
    } catch (SQLException ignored) { }

    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT AVG(CASE status WHEN 'completed' THEN 100 WHEN 'in_progress' THEN 50 ELSE 0 END) p, COUNT(*) n " +
        "FROM course_progress WHERE user_id=?")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      if (r.next() && r.getInt("n") > 0)
        components.add("courseCompletion", Json.obj("value", r.getDouble("p"), "detail", r.getInt("n") + " recommended course(s) tracked"));
    } catch (SQLException ignored) { }

    return Scoring.prs(components, cfg);
  }

  /** Readiness history for the student dashboard sparkline. */
  public static JsonArray readinessHistory(int userId) {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT rh.*, a.label FROM readiness_history rh LEFT JOIN attempts a ON a.id=rh.attempt_id " +
        "WHERE rh.user_id=? ORDER BY rh.id")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      while (r.next())
        out.add(Json.obj("overallPct", r.getDouble("overall_pct"), "prs", r.getDouble("prs"),
            "label", r.getString("label"), "createdAt", r.getString("created_at")));
    } catch (SQLException ignored) { }
    return out;
  }

  /** Everything the student dashboard needs in one round trip. */
  public static JsonObject dashboard(int userId, JsonObject user) throws SQLException {
    JsonObject out = new JsonObject();
    out.add("improvement", improvementFor(userId));
    out.add("charts", charts(userId));
    out.add("prs", prsComponents(userId));
    out.add("readinessHistory", readinessHistory(userId));
    out.add("gaps", Json.arr(Gaps.ranked(userId).toArray()));
    out.add("recommendations", Gaps.recommendations(userId));
    out.add("xp", Json.obj("total", Assessment.xp(userId), "streak", Assessment.streakOf(userId),
        "badges", Gaps.badges(userId)));
    out.add("dueReviews", Json.arr(Gaps.dueReviews(userId).toArray()));
    out.add("plan", Planner.activePlan(userId));
    out.add("notifications", MentorAdmin.notifications(userId));
    out.add("attempts", summaries(Content.attempts(userId, null)));
    out.add("nextBestAction", Planner.nextBestAction(userId, user));
    return out;
  }

  public static JsonArray summaries(List<JsonObject> attempts) {
    JsonArray out = new JsonArray();
    for (JsonObject a : attempts)
      out.add(Json.obj("id", Json.intOf(a, "id", 0), "label", Json.str(a, "label", ""), "kind", Json.str(a, "kind", ""),
          "submittedAt", Json.str(a, "submittedAt", ""), "overallPct", Json.num(a, "overallPct", 0),
          "readiness", Json.str(a, "readiness", ""), "accuracy", Json.num(a, "accuracy", 0),
          "violations", Json.intOf(a, "violations", 0)));
    return out;
  }
}
