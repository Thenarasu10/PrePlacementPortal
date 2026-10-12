package placeiq;

import com.google.gson.*;
import java.sql.*;
import java.util.*;

/** Skill-gap engine: ranking, course recommendations, spaced repetition and course progress. */
public final class Gaps {

  public static List<JsonObject> ranked(int userId) {
    JsonObject config = Db.config();
    return Scoring.rankGaps(Content.topicStats(userId), Content.skillsById(), config);
  }

  /** Ranks gaps, then matches courses whose skills cover them; each recommendation names the gap it closes. */
  public static JsonArray recommendations(int userId) {
    JsonObject config = Db.config();
    List<JsonObject> gaps = ranked(userId);
    int max = Json.intOf(Json.child(config, "skillGap"), "maxRecommendations", 4);
    Map<String, Integer> gapRank = new HashMap<>();
    Map<String, JsonObject> gapBySkill = new HashMap<>();
    for (int i = 0; i < gaps.size(); i++) {
      gapRank.put(Json.str(gaps.get(i), "skillId", ""), i + 1);
      gapBySkill.put(Json.str(gaps.get(i), "skillId", ""), gaps.get(i));
    }
    JsonObject progress = courseProgressMap(userId);
    JsonArray out = new JsonArray();
    for (JsonElement e : Content.courses()) {
      JsonObject course = e.getAsJsonObject();
      double score = 0;
      String bestSkill = null;
      JsonArray closes = new JsonArray();
      for (JsonElement s : Json.array(course, "skills")) {
        String skillId = s.getAsString();
        if (!gapBySkill.containsKey(skillId)) continue;
        JsonObject gap = gapBySkill.get(skillId);
        double weight = Json.num(gap, "severity", 0) / Math.max(1, gapRank.getOrDefault(skillId, 5));
        if (weight > score) { score = weight; bestSkill = skillId; }
        closes.add(Json.obj("skillId", skillId, "skill", Json.str(gap, "skill", skillId),
            "accuracy", Json.num(gap, "accuracy", 0), "severity", Json.num(gap, "severity", 0)));
      }
      if (closes.size() == 0 || bestSkill == null) continue;
      JsonObject gap = gapBySkill.get(bestSkill);
      // difficulty match: strong students get advanced courses, weak ones get foundations
      double accuracy = Json.num(gap, "accuracy", 0);
      String level = Json.str(course, "level", "Intermediate");
      double match = "Beginner".equals(level) ? Json.clamp(100 - accuracy, 0, 100)
          : "Advanced".equals(level) ? Json.clamp(accuracy, 0, 100)
          : Json.clamp(100 - Math.abs(accuracy - 55) * 1.4, 0, 100);
      JsonObject rec = Json.obj(
          "courseId", Json.str(course, "id", ""),
          "title", Json.str(course, "title", ""),
          "provider", Json.str(course, "provider", ""),
          "level", level,
          "hours", Json.num(course, "hours", 0),
          "url", Json.str(course, "url", ""),
          "description", Json.str(course, "description", ""),
          "priority", Math.round(score * 10.0) / 10.0,
          "closesGaps", closes,
          "whyThis", "Your accuracy in " + Json.str(gap, "skill", bestSkill) + " is " + Math.round(accuracy) +
              "% against a " + Math.round(Json.num(Json.child(config, "skillGap"), "accuracyTarget", 0.75) * 100) +
              "% target, and this course covers " + closes.size() + " of your ranked gaps" +
              (Json.str(course, "level", "").equalsIgnoreCase("Beginner") ? " starting from fundamentals." : "."),
          "difficultyMatch", Math.round(match) + "%",
          "status", progress.has(Json.str(course, "id", "")) ? Json.str(progress.getAsJsonObject(Json.str(course, "id", "")), "status", "not_started") : "not_started");
      out.add(rec);
    }
    List<JsonObject> list = new ArrayList<>();
    for (JsonElement e : out) list.add(e.getAsJsonObject());
    list.sort((a, b) -> Double.compare(Json.num(b, "priority", 0), Json.num(a, "priority", 0)));
    JsonArray top = new JsonArray();
    for (int i = 0; i < Math.min(max, list.size()); i++) {
      JsonObject r = list.get(i);
      r.addProperty("rank", i + 1);
      top.add(r);
    }
    return top;
  }

  public static JsonObject courseProgressMap(int userId) {
    JsonObject map = new JsonObject();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM course_progress WHERE user_id=?")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      while (r.next())
        map.add(r.getString("course_id"), Json.obj("status", r.getString("status"), "progressPct", r.getDouble("progress_pct"),
            "updatedAt", r.getString("updated_at")));
    } catch (SQLException ignored) { }
    return map;
  }

  public static JsonObject setCourseStatus(int userId, String courseId, String status, double pct) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO course_progress(user_id,course_id,status,progress_pct,updated_at) VALUES(?,?,?,?,?) " +
        "ON CONFLICT(user_id,course_id) DO UPDATE SET status=excluded.status, progress_pct=excluded.progress_pct, updated_at=excluded.updated_at")) {
      ps.setInt(1, userId);
      ps.setString(2, courseId);
      ps.setString(3, status);
      ps.setDouble(4, pct);
      ps.setString(5, Time.now());
      ps.executeUpdate();
    }
    if ("completed".equals(status)) {
      Content.log(userId, "course_completed", courseId);
      Content.notify(userId, "Re-assess to confirm the gap closed",
          "You completed " + (Content.course(courseId) != null ? Json.str(Content.course(courseId), "title", courseId) : courseId) +
              ". Take a short review quiz to verify the skill improved.", Time.today());
      Gaps.syncReviews(userId);
    }
    return Json.obj("courseId", courseId, "status", status, "progressPct", pct);
  }

  // ---------------- spaced repetition ----------------

  public static void scheduleReview(int userId, String skillId, String topic, String questionId, boolean passed) {
    try {
      JsonObject config = Db.config();
      JsonArray intervals = Json.array(Json.child(config, "spacedRepetition"), "intervalsDays");
      int stage = 0;
      try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
          "SELECT * FROM srs_reviews WHERE user_id=? AND question_id=? ORDER BY id DESC LIMIT 1")) {
        ps.setInt(1, userId);
        ps.setString(2, questionId);
        ResultSet r = ps.executeQuery();
        if (r.next()) stage = r.getInt("stage") + (passed ? 1 : 0);
      }
      int days = intervals.size() == 0 ? 3 : intervals.get(Math.min(stage, intervals.size() - 1)).getAsInt();
      try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
          "INSERT INTO srs_reviews(user_id,skill_id,topic,question_id,stage,due_date,last_result,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
        ps.setInt(1, userId);
        ps.setString(2, skillId);
        ps.setString(3, topic);
        ps.setString(4, questionId);
        ps.setInt(5, stage);
        ps.setString(6, Time.plusDays(Time.today(), days));
        ps.setString(7, passed ? "correct" : "incorrect");
        ps.setString(8, Time.now());
        ps.executeUpdate();
      }
    } catch (SQLException ignored) { }
  }

  /** Rebuild due reviews from the topics the student got wrong; called after each submission. */
  public static void syncReviews(int userId) {
    for (JsonObject gap : ranked(userId)) {
      String topic = Json.str(gap, "topic", Json.str(gap, "skill", ""));
      List<JsonObject> candidates = Content.questions(Json.str(gap, "section", null), null, null, null, new HashSet<>(), 3);
      for (JsonObject q : candidates) {
        if (!topic.isEmpty() && !Json.str(q, "topic", "").equalsIgnoreCase(topic)) continue;
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO srs_reviews(user_id,skill_id,topic,question_id,stage,due_date,last_result,created_at) " +
            "SELECT ?,?,?,?,0,?,?,? WHERE NOT EXISTS (SELECT 1 FROM srs_reviews WHERE user_id=? AND question_id=?)")) {
          ps.setInt(1, userId);
          ps.setString(2, Json.str(gap, "skillId", ""));
          ps.setString(3, topic);
          ps.setString(4, Json.str(q, "id", ""));
          ps.setString(5, Time.plusDays(Time.today(), 2));
          ps.setString(6, "revision");
          ps.setString(7, Time.now());
          ps.setInt(8, userId);
          ps.setString(9, Json.str(q, "id", ""));
          ps.executeUpdate();
        } catch (SQLException ignored) { }
        break;
      }
    }
  }

  public static List<JsonObject> dueReviews(int userId) {
    List<JsonObject> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT * FROM srs_reviews WHERE user_id=? AND due_date <= ? ORDER BY due_date DESC")) {
      ps.setInt(1, userId);
      ps.setString(2, Time.today());
      ResultSet r = ps.executeQuery();
      while (r.next()) {
        String qid = r.getString("question_id");
        if (!seen.add(qid)) continue;                       // newest schedule per question wins
        JsonObject q = Content.question(qid);
        out.add(Json.obj("skillId", r.getString("skill_id"), "topic", r.getString("topic"), "questionId", qid,
            "stage", r.getInt("stage"), "dueDate", r.getString("due_date"),
            "section", q == null ? "" : Json.str(q, "section", ""),
            "prompt", q == null ? "" : Json.str(q, "prompt", "")));
      }
    } catch (SQLException ignored) { }
    return out;
  }

  public static JsonArray badges(int userId) {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM badges WHERE user_id=? ORDER BY id")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      while (r.next()) out.add(Json.obj("badge", r.getString("badge"), "earnedAt", r.getString("earned_at")));
    } catch (SQLException ignored) { }
    return out;
  }
}
