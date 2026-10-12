package placeiq;

import com.google.gson.*;
import java.sql.*;
import java.util.*;

/**
 * Preparation planner. Tasks are derived from the ranked skill gaps, the company's emphasized
 * skills and the target date; the plan re-schedules itself whenever the student opens it and finds
 * overdue work, or when fresh assessment results arrive.
 */
public final class Planner {

  public static JsonObject create(int userId, JsonObject body, JsonObject user) throws Exception {
    JsonObject cfg = Db.config();
    JsonObject pcfg = Json.child(cfg, "planner");
    String company = Json.str(body, "targetCompany", Json.str(user, "targetCompany", "TCS"));
    String role = Json.str(body, "targetRole", Json.str(user, "targetRole", "Software Engineer"));
    String date = Json.str(body, "targetDate", Time.plusDays(Time.today(), 56));
    String weekStart = Time.weekStart(Time.today());
    int planId;
    try (Connection c = Db.get()) {
      try (PreparedStatement ps = c.prepareStatement("UPDATE plans SET updated_at=? WHERE user_id=?", Statement.RETURN_GENERATED_KEYS)) {
        ps.setString(1, Time.now());
        ps.setInt(2, userId);
        ps.executeUpdate();
      }
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO plans(user_id,title,target_company,target_role,target_date,week_start,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
          Statement.RETURN_GENERATED_KEYS)) {
        ps.setInt(1, userId);
        ps.setString(2, "Road to " + company);
        ps.setString(3, company);
        ps.setString(4, role);
        ps.setString(5, date);
        ps.setString(6, weekStart);
        ps.setString(7, Time.now());
        ps.setString(8, Time.now());
        ps.executeUpdate();
        ResultSet k = ps.getGeneratedKeys();
        k.next();
        planId = k.getInt(1);
      }
      generateTasks(c, planId, userId, company, role, date, Json.intOf(pcfg, "maxTasksPerWeek", 5));
    }
    Content.notify(userId, "Weekly plan created", "A study plan to " + date + " is ready. Start with week 1.", Time.today());
    return activePlan(userId);
  }

  /** Builds week-by-week tasks: gap-closing courses, practice sets, a mock test, a mock interview and a resume fix. */
  private static void generateTasks(Connection c, int planId, int userId, String company, String role, String targetDate, int maxPerWeek) throws Exception {
    long days = Math.max(7, Time.daysBetween(Time.today(), targetDate));
    int weeks = (int) Math.max(2, Math.ceil(days / 7.0));
    List<JsonObject> gaps = Gaps.ranked(userId);
    JsonArray recommendations = Gaps.recommendations(userId);
    JsonObject companyData = Content.company(company);
    String companyId = Json.str(companyData, "id", company.toLowerCase());
    JsonObject required = Json.child(companyData, "skills");

    // week 0: diagnostic baseline if the student has nothing yet
    if (Content.attempts(userId, null).isEmpty()) {
      insert(c, planId, userId, 0, "Take the baseline diagnostic assessment", "assessment", null, null, 2,
          Time.plusDays(Time.weekStart(Time.today()), 2));
    }
    int taskIdx = 0;
    List<String> courseQueue = new ArrayList<>();
    for (JsonElement e : recommendations) courseQueue.add(Json.str(e.getAsJsonObject(), "courseId", ""));
    for (int week = 0; week < weeks; week++) {
      String due = Time.plusDays(Time.weekStart(Time.today()), week * 7 + 6);
      int placed = 0;
      // 1. highest-impact gap first
      JsonObject gap = taskIdx < gaps.size() ? gaps.get(taskIdx) : null;
      if (gap != null && placed < maxPerWeek) {
        insert(c, planId, userId, week, "Practice " + Json.str(gap, "skill", "weak topic") + " (target " +
            Math.round(Json.num(gap, "accuracy", 0)) + "% -> 75%)", "practice", Json.str(gap, "skillId", ""),
            Json.str(gap, "section", ""), 2, due);
        taskIdx++;
        placed++;
      }
      // 2. one course module from the recommended list
      if (!courseQueue.isEmpty() && placed < maxPerWeek) {
        String courseId = courseQueue.remove(0);
        JsonObject course = Content.course(courseId);
        if (course != null) {
          insert(c, planId, userId, week, "Complete 2 modules of " + Json.str(course, "title", courseId),
              "course", courseId, null, Math.min(4, Json.num(course, "hours", 3) / 3), due);
          placed++;
        }
      }
      // 3. company-emphasized section rotation
      if (placed < maxPerWeek) {
        String section = strongestEmphasis(required, week);
        insert(c, planId, userId, week, "Timed set: " + sectionName(section) + " (" + company + " pattern)",
            "practice", null, section, 2, due);
        placed++;
      }
      // 4. milestone tasks spread over the runway
      if (week % 2 == 1 && placed < maxPerWeek) {
        insert(c, planId, userId, week, "Company-pattern mock test #" + (week / 2 + 1), "mock", companyId, null, 2, due);
        placed++;
      }
      if (week == weeks / 2 && placed < maxPerWeek) {
        insert(c, planId, userId, week, "Mock interview: " + role + " at " + company, "interview", companyId, "communication", 1, due);
        placed++;
      }
      if (week == Math.max(0, weeks - 2)) {
        insert(c, planId, userId, week, "Resume fix: rewrite bullets for " + role, "resume", companyId, null, 1, due);
      }
      if (week == weeks - 1) {
        insert(c, planId, userId, week, "Final revision: weak topics + " + company + " HR question bank", "revision", companyId, null, 3, due);
      }
    }
  }

  private static String strongestEmphasis(JsonObject required, int rotate) {
    List<Map.Entry<String, JsonElement>> list = new ArrayList<>(required.entrySet());
    list.sort((a, b) -> Double.compare(b.getValue().getAsDouble(), a.getValue().getAsDouble()));
    if (list.isEmpty()) return "aptitude";
    return list.get(rotate % list.size()).getKey();
  }

  private static String sectionName(String id) {
    for (JsonElement e : Json.array(Db.config(), "sections"))
      if (Json.str(e.getAsJsonObject(), "id", "").equals(id)) return Json.str(e.getAsJsonObject(), "name", id);
    return id;
  }

  private static void insert(Connection c, int planId, int userId, int week, String title, String type, String refId,
                             String skillId, double hours, String due) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO plan_tasks(plan_id,user_id,week_no,title,type,ref_id,skill_id,hours,due_date,status,carried_over) " +
        "VALUES(?,?,?,?,?,?,?,?,?,'pending',0)")) {
      ps.setInt(1, planId);
      ps.setInt(2, userId);
      ps.setInt(3, week);
      ps.setString(4, title);
      ps.setString(5, type);
      ps.setString(6, refId);
      ps.setString(7, skillId);
      ps.setDouble(8, hours);
      ps.setString(9, due);
      ps.executeUpdate();
    }
  }

  public static JsonObject activePlan(int userId) {
    try {
      replan(userId);
    } catch (Exception ignored) { }
    JsonObject plan = null;
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT * FROM plans WHERE user_id=? ORDER BY id DESC LIMIT 1")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      if (r.next())
        plan = Json.obj("id", r.getInt("id"), "title", r.getString("title"), "targetCompany", r.getString("target_company"),
            "targetRole", r.getString("target_role"), "targetDate", r.getString("target_date"),
            "weekStart", r.getString("week_start"), "createdAt", r.getString("created_at"));
    } catch (SQLException e) { return null; }
    if (plan == null) return null;

    JsonArray weeks = new JsonArray();
    int currentWeek = currentWeekIndex(Json.str(plan, "weekStart", Time.weekStart(Time.today())));
    Map<Integer, JsonArray> byWeek = new TreeMap<>();
    int done = 0, total = 0;
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT * FROM plan_tasks WHERE plan_id=? ORDER BY week_no, id")) {
      ps.setInt(1, Json.intOf(plan, "id", 0));
      ResultSet r = ps.executeQuery();
      while (r.next()) {
        int week = r.getInt("week_no");
        JsonObject t = Json.obj("id", r.getInt("id"), "weekNo", week, "title", r.getString("title"),
            "type", r.getString("type"), "refId", r.getString("ref_id"), "skillId", r.getString("skill_id"),
            "hours", r.getDouble("hours"), "dueDate", r.getString("due_date"), "status", r.getString("status"),
            "completedAt", r.getString("completed_at"), "carriedOver", r.getInt("carried_over") == 1,
            "overdue", "pending".equals(r.getString("status")) && r.getString("due_date").compareTo(Time.today()) < 0);
        byWeek.computeIfAbsent(week, k -> new JsonArray()).add(t);
        total++;
        if ("done".equals(r.getString("status"))) done++;
      }
    } catch (SQLException ignored) { }

    for (Map.Entry<Integer, JsonArray> e : byWeek.entrySet()) {
      int weekDone = 0, weekTotal = e.getValue().size();
      double hours = 0;
      for (JsonElement t : e.getValue()) {
        if ("done".equals(Json.str(t.getAsJsonObject(), "status", ""))) weekDone++;
        hours += Json.num(t.getAsJsonObject(), "hours", 0);
      }
      weeks.add(Json.obj("weekNo", e.getKey(), "label", weekLabel(e.getKey(), currentWeek), "current", e.getKey() == currentWeek,
          "tasks", e.getValue(), "completed", weekDone, "total", weekTotal, "plannedHours", hours,
          "completionPct", weekTotal == 0 ? 0 : Math.round((double) weekDone / weekTotal * 100)));
    }
    plan.add("weeks", weeks);
    plan.addProperty("currentWeek", currentWeek);
    plan.addProperty("completedTasks", done);
    plan.addProperty("totalTasks", total);
    plan.addProperty("completionPct", total == 0 ? 0 : Math.round((double) done / total * 100));
    plan.addProperty("daysToTarget", Time.daysBetween(Time.today(), Json.str(plan, "targetDate", Time.today())));
    return plan;
  }

  private static String weekLabel(int week, int current) {
    if (week == current) return "This week";
    return week < current ? "Week " + (week + 1) + " (past)" : "Week " + (week + 1);
  }

  private static int currentWeekIndex(String weekStart) {
    long days = Time.daysBetween(weekStart, Time.weekStart(Time.today()));
    return (int) Math.max(0, days / 7);
  }

  /**
   * Auto re-plan: overdue pending tasks are carried into the current week (marked as carried over)
   * and new gaps discovered by the latest assessment are appended as fresh tasks.
   */
  public static JsonObject replan(int userId) throws SQLException {
    JsonObject plan = currentPlanRow(userId);
    if (plan == null) return Json.obj("replanned", false);
    int planId = Json.intOf(plan, "id", 0);
    String weekStart = Json.str(plan, "weekStart", Time.weekStart(Time.today()));
    int currentWeek = currentWeekIndex(weekStart);
    int moved = 0;
    try (Connection c = Db.get()) {
      String due = Time.plusDays(Time.weekStart(Time.today()), 6);
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE plan_tasks SET week_no=?, due_date=?, carried_over=1 WHERE plan_id=? AND status='pending' " +
          "AND due_date < ? AND week_no < ?")) {
        ps.setInt(1, currentWeek);
        ps.setString(2, due);
        ps.setInt(3, planId);
        ps.setString(4, Time.today());
        ps.setInt(5, currentWeek);
        moved = ps.executeUpdate();
      }
      // refresh: add a task for any brand-new top gap that the plan does not cover yet
      List<JsonObject> gaps = Gaps.ranked(userId);
      if (!gaps.isEmpty()) {
        String topSkill = Json.str(gaps.get(0), "skillId", "");
        boolean covered = false;
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COUNT(*) n FROM plan_tasks WHERE plan_id=? AND skill_id=?")) {
          ps.setInt(1, planId);
          ps.setString(2, topSkill);
          ResultSet r = ps.executeQuery();
          if (r.next()) covered = r.getInt("n") > 0;
        }
        if (!covered && !topSkill.isBlank()) {
          insert(c, planId, userId, currentWeek, "New gap from latest assessment: " + Json.str(gaps.get(0), "skill", topSkill),
              "practice", topSkill, Json.str(gaps.get(0), "section", ""), 2, due);
          Content.notify(userId, "Plan updated", "Your latest assessment surfaced a new weak skill: " +
              Json.str(gaps.get(0), "skill", topSkill) + ". A task was added to this week.", Time.today());
        }
      }
      try (PreparedStatement ps = c.prepareStatement("UPDATE plans SET updated_at=? WHERE id=?")) {
        ps.setString(1, Time.now());
        ps.setInt(2, planId);
        ps.executeUpdate();
      }
      if (moved > 0)
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO events(user_id,type,detail,created_at) VALUES(?,?,?,?)")) {
          ps.setInt(1, userId);
          ps.setString(2, "replan");
          ps.setString(3, moved + " overdue task(s) carried into week " + (currentWeek + 1));
          ps.setString(4, Time.now());
          ps.executeUpdate();
        }
    }
    return Json.obj("replanned", true, "carriedOver", moved, "currentWeek", currentWeek);
  }

  private static JsonObject currentPlanRow(int userId) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT * FROM plans WHERE user_id=? ORDER BY id DESC LIMIT 1")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      return r.next() ? Json.obj("id", r.getInt("id"), "weekStart", r.getString("week_start"),
          "targetDate", r.getString("target_date")) : null;
    }
  }

  public static JsonObject completeTask(int userId, int taskId, boolean done) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "UPDATE plan_tasks SET status=?, completed_at=? WHERE id=? AND user_id=?")) {
      ps.setString(1, done ? "done" : "pending");
      ps.setString(2, done ? Time.now() : null);
      ps.setInt(3, taskId);
      ps.setInt(4, userId);
      ps.executeUpdate();
    }
    if (done) {
      Assessment.addXp(userId, 15, "plan task " + taskId);
      JsonObject task = taskRow(userId, taskId);
      if (task != null && "course".equals(Json.str(task, "type", ""))) {
        Gaps.setCourseStatus(userId, Json.str(task, "refId", ""), "completed", 100);
      }
    }
    return Json.obj("taskId", taskId, "status", done ? "done" : "pending", "plan", activePlan(userId));
  }

  private static JsonObject taskRow(int userId, int taskId) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM plan_tasks WHERE id=? AND user_id=?")) {
      ps.setInt(1, taskId);
      ps.setInt(2, userId);
      ResultSet r = ps.executeQuery();
      return r.next() ? Json.obj("type", r.getString("type"), "refId", r.getString("ref_id")) : null;
    } catch (SQLException e) { return null; }
  }

  /** The single card the dashboard shows: the next best thing to do today. */
  public static JsonObject nextBestAction(int userId, JsonObject user) {
    try {
      JsonObject plan = activePlan(userId);
      if (plan != null) {
        for (JsonElement we : Json.array(plan, "weeks")) {
          JsonObject week = we.getAsJsonObject();
          for (JsonElement te : Json.array(week, "tasks")) {
            JsonObject t = te.getAsJsonObject();
            if ("pending".equals(Json.str(t, "status", "")) && (Json.intOf(week, "weekNo", 0) == Json.intOf(plan, "currentWeek", 0) || Json.intOf(t, "carriedOver", 0) == 1))
              return Json.obj("type", "plan-task", "title", Json.str(t, "title", ""), "dueDate", Json.str(t, "dueDate", ""),
                  "taskId", Json.intOf(t, "id", 0), "reason", Json.num(t, "carriedOver", 0) == 1 ? "Carried over from an earlier week" : "Scheduled for this week");
          }
        }
      }
      if (Content.attempts(userId, null).isEmpty())
        return Json.obj("type", "assessment", "title", "Take the 20-minute diagnostic assessment",
            "reason", "Until you have a baseline we cannot rank your gaps.", "routes", Json.arr("assessment"));
      List<JsonObject> gaps = Gaps.ranked(userId);
      if (!gaps.isEmpty()) {
        JsonObject g = gaps.get(0);
        return Json.obj("type", "gap", "title", "Close your biggest gap: " + Json.str(g, "skill", ""),
            "reason", "Accuracy " + Math.round(Json.num(g, "accuracy", 0)) + "% against a 75% target; highest impact on your readiness score.",
            "section", Json.str(g, "section", ""));
      }
      JsonArray recs = Gaps.recommendations(userId);
      if (recs.size() > 0)
        return Json.obj("type", "course", "title", "Start " + Json.str(recs.get(0).getAsJsonObject(), "title", ""),
            "reason", Json.str(recs.get(0).getAsJsonObject(), "whyThis", ""), "courseId", Json.str(recs.get(0).getAsJsonObject(), "courseId", ""));
      return Json.obj("type", "company", "title", "Explore company patterns",
          "reason", "Compare your profile against the companies you are targeting.");
    } catch (Exception e) {
      return Json.obj("type", "assessment", "title", "Take the diagnostic assessment", "reason", "Start here.");
    }
  }

  public static JsonObject seedDemoPlan(int userId) throws Exception {
    JsonObject user = Auth.me(userId);
    JsonObject body = Json.obj("targetCompany", Json.str(user, "targetCompany", "TCS"),
        "targetRole", Json.str(user, "targetRole", "Software Engineer"),
        "targetDate", Time.plusDays(Time.today(), 42));
    create(userId, body, user);
    // mark a few early tasks done so the weekly rings are not all zero
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "UPDATE plan_tasks SET status='done', completed_at=? WHERE user_id=? AND week_no=0 AND type IN ('assessment','practice')")) {
      ps.setString(1, Time.nowMinusDays(9));
      ps.setInt(2, userId);
      ps.executeUpdate();
    }
    return activePlan(userId);
  }
}
