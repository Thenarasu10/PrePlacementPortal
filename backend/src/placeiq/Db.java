package placeiq;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * SQLite persistence. One schema for the whole product; content tables are loaded from
 * plain JSON in data/ so the question bank, courses and company data are never hardcoded.
 */
public final class Db {
  /** Accepts either a full JDBC url or a plain file path: -Dplaceiq.db=/tmp/x.db works too. */
  private static final String URL = jdbcUrl(System.getProperty("placeiq.db", "jdbc:sqlite:placeiq.db"));

  private static String jdbcUrl(String v) {
    if (v == null || v.isBlank()) return "jdbc:sqlite:placeiq.db";
    return v.startsWith("jdbc:") ? v : "jdbc:sqlite:" + v;
  }

  public static Connection get() throws SQLException {
    Connection c = DriverManager.getConnection(URL);
    try (Statement s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA busy_timeout=5000");
    }
    return c;
  }

  private static final String SCHEMA = String.join("\n",
    "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, email TEXT UNIQUE,",
    "  password_hash TEXT, role TEXT, college TEXT, branch TEXT, graduation_year INTEGER, domain TEXT,",
    "  target_role TEXT, target_company TEXT, cgpa REAL, created_at TEXT, last_active TEXT);",
    "CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, user_id INTEGER, created_at TEXT, expires_at TEXT);",
    "CREATE TABLE IF NOT EXISTS questions (id TEXT PRIMARY KEY, section TEXT, topic TEXT, skill_id TEXT, difficulty TEXT,",
    "  type TEXT, time_limit INTEGER, domain TEXT, prompt TEXT, options_json TEXT, answer_json TEXT, rubric_json TEXT,",
    "  starter TEXT, checks_json TEXT, tests_json TEXT, explanation TEXT, model_answer TEXT, company_tags TEXT);",
    "CREATE TABLE IF NOT EXISTS attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, kind TEXT, label TEXT,",
    "  status TEXT, started_at TEXT, submitted_at TEXT, total_score REAL, max_score REAL, accuracy REAL, overall_pct REAL,",
    "  readiness TEXT, section_stats_json TEXT, meta_json TEXT, violations INTEGER DEFAULT 0, time_sec REAL DEFAULT 0);",
    "CREATE TABLE IF NOT EXISTS attempt_answers (id INTEGER PRIMARY KEY AUTOINCREMENT, attempt_id INTEGER, question_id TEXT,",
    "  section TEXT, difficulty TEXT, chosen_json TEXT, awarded REAL, max REAL, correct INTEGER, time_sec REAL, answered_at TEXT);",
    "CREATE TABLE IF NOT EXISTS readiness_history (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, attempt_id INTEGER,",
    "  overall_pct REAL, prs REAL, breakdown_json TEXT, created_at TEXT);",
    "CREATE TABLE IF NOT EXISTS courses (id TEXT PRIMARY KEY, title TEXT, provider TEXT, level TEXT, hours REAL, url TEXT,",
    "  skills_json TEXT, description TEXT);",
    "CREATE TABLE IF NOT EXISTS skills (id TEXT PRIMARY KEY, name TEXT, section TEXT, impact REAL, prereq_json TEXT);",
    "CREATE TABLE IF NOT EXISTS companies (id TEXT PRIMARY KEY, name TEXT, data_json TEXT);",
    "CREATE TABLE IF NOT EXISTS course_progress (user_id INTEGER, course_id TEXT, status TEXT, progress_pct REAL, updated_at TEXT,",
    "  PRIMARY KEY (user_id, course_id));",
    "CREATE TABLE IF NOT EXISTS plans (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, title TEXT, target_company TEXT,",
    "  target_role TEXT, target_date TEXT, week_start TEXT, created_at TEXT, updated_at TEXT);",
    "CREATE TABLE IF NOT EXISTS plan_tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, plan_id INTEGER, user_id INTEGER, week_no INTEGER,",
    "  title TEXT, type TEXT, ref_id TEXT, skill_id TEXT, hours REAL, due_date TEXT, status TEXT, completed_at TEXT, carried_over INTEGER DEFAULT 0);",
    "CREATE TABLE IF NOT EXISTS interviews (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, mode TEXT, company TEXT,",
    "  role TEXT, started_at TEXT, finished_at TEXT, overall_pct REAL, rubric_json TEXT, summary TEXT);",
    "CREATE TABLE IF NOT EXISTS interview_answers (id INTEGER PRIMARY KEY AUTOINCREMENT, interview_id INTEGER, question TEXT,",
    "  answer TEXT, relevance REAL, structure REAL, clarity REAL, depth REAL, feedback TEXT, model_answer TEXT, created_at TEXT);",
    "CREATE TABLE IF NOT EXISTS mock_tests (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, pattern TEXT, company TEXT,",
    "  created_at TEXT, submitted_at TEXT, total_score REAL, max_score REAL, cutoff_cleared INTEGER, negative_marking REAL,",
    "  time_limit_sec INTEGER, meta_json TEXT);",
    "CREATE TABLE IF NOT EXISTS mock_test_answers (id INTEGER PRIMARY KEY AUTOINCREMENT, mock_id INTEGER, question_id TEXT,",
    "  section TEXT, chosen_json TEXT, awarded REAL, max REAL, correct INTEGER, time_sec REAL);",
    "CREATE TABLE IF NOT EXISTS resume_reports (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, filename TEXT, ats_score REAL,",
    "  sections_json TEXT, keywords_json TEXT, suggestions_json TEXT, created_at TEXT);",
    "CREATE TABLE IF NOT EXISTS checklist (user_id INTEGER, item_key TEXT, label TEXT, done INTEGER, updated_at TEXT,",
    "  PRIMARY KEY (user_id, item_key));",
    "CREATE TABLE IF NOT EXISTS srs_reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, skill_id TEXT, topic TEXT,",
    "  question_id TEXT, stage INTEGER, due_date TEXT, last_result TEXT, created_at TEXT);",
    "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, type TEXT, detail TEXT, created_at TEXT);",
    "CREATE TABLE IF NOT EXISTS mentor_notes (id INTEGER PRIMARY KEY AUTOINCREMENT, mentor_id INTEGER, student_id INTEGER, note TEXT,",
    "  created_at TEXT);",
    "CREATE TABLE IF NOT EXISTS notifications (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, title TEXT, body TEXT,",
    "  due_date TEXT, is_read INTEGER DEFAULT 0, created_at TEXT);",
    "CREATE TABLE IF NOT EXISTS badges (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, badge TEXT, earned_at TEXT);",
    "CREATE TABLE IF NOT EXISTS xp_log (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, amount INTEGER, reason TEXT, created_at TEXT);",
    "CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);");

  public static void init() throws Exception {
    // WAL keeps readers running while a writer is in flight; without it every concurrent page
    // load could stall for the full busy_timeout behind a single write (logins, event logging).
    try (Connection c = get(); Statement s = c.createStatement()) {
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA synchronous=NORMAL");
    }
    try (Connection c = get(); Statement s = c.createStatement()) {
      for (String stmt : SCHEMA.split(";\n")) if (!stmt.isBlank()) s.execute(stmt);
    }
    boolean force = "true".equals(System.getProperty("placeiq.reseed")) || !hasRows("questions");
    if (force) seedContent();
    if (!hasRows("users")) seedDemoUsers();
  }

  private static boolean hasRows(String table) {
    try (Connection c = get(); Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      return r.next() && r.getInt(1) > 0;
    } catch (SQLException e) { return false; }
  }

  /** Load every content table from data/*.json. Re-running is safe (idempotent upserts). */
  public static void seedContent() throws Exception {
    Path data = Json.dataDir();
    upsertConfigMeta();
    loadSkills(Json.read(data.resolve("skills.json")));
    loadCourses(Json.read(data.resolve("courses.json")));
    loadQuestions(Json.read(data.resolve("questions.json")));
    Path companies = data.resolve("companies");
    if (Files.isDirectory(companies)) {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(companies, "*.json")) {
        for (Path p : ds) loadCompany(Json.read(p));
      }
    }
  }

  private static void upsertConfigMeta() throws Exception {
    JsonObject cfg = Json.read(Json.dataDir().resolve("config.json"));
    try (Connection c = get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO meta(k,v) VALUES('config',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
      ps.setString(1, cfg.toString());
      ps.executeUpdate();
    }
  }

  private static void loadSkills(JsonObject root) throws Exception {
    try (Connection c = get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO skills(id,name,section,impact,prereq_json) VALUES(?,?,?,?,?) " +
        "ON CONFLICT(id) DO UPDATE SET name=excluded.name, section=excluded.section, impact=excluded.impact, prereq_json=excluded.prereq_json")) {
      for (JsonElement e : Json.array(root, "skills")) {
        JsonObject s = e.getAsJsonObject();
        ps.setString(1, Json.str(s, "id", ""));
        ps.setString(2, Json.str(s, "name", ""));
        ps.setString(3, Json.str(s, "section", ""));
        ps.setDouble(4, Json.num(s, "impact", 0.5));
        ps.setString(5, Json.array(s, "prereq").toString());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void loadCourses(JsonObject root) throws Exception {
    try (Connection c = get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO courses(id,title,provider,level,hours,url,skills_json,description) VALUES(?,?,?,?,?,?,?,?) " +
        "ON CONFLICT(id) DO UPDATE SET title=excluded.title, level=excluded.level, hours=excluded.hours, skills_json=excluded.skills_json, description=excluded.description")) {
      for (JsonElement e : Json.array(root, "courses")) {
        JsonObject o = e.getAsJsonObject();
        ps.setString(1, Json.str(o, "id", ""));
        ps.setString(2, Json.str(o, "title", ""));
        ps.setString(3, Json.str(o, "provider", "PlaceIQ Academy"));
        ps.setString(4, Json.str(o, "level", "Intermediate"));
        ps.setDouble(5, Json.num(o, "hours", 10));
        ps.setString(6, Json.str(o, "url", "#"));
        ps.setString(7, Json.array(o, "skills").toString());
        ps.setString(8, Json.str(o, "description", ""));
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void loadQuestions(JsonObject root) throws Exception {
    try (Connection c = get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO questions(id,section,topic,skill_id,difficulty,type,time_limit,domain,prompt,options_json,answer_json," +
        "rubric_json,starter,checks_json,tests_json,explanation,model_answer,company_tags) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
        "ON CONFLICT(id) DO UPDATE SET section=excluded.section, topic=excluded.topic, difficulty=excluded.difficulty, prompt=excluded.prompt, options_json=excluded.options_json, answer_json=excluded.answer_json")) {
      for (JsonElement e : Json.array(root, "questions")) {
        JsonObject q = e.getAsJsonObject();
        ps.setString(1, Json.str(q, "id", ""));
        ps.setString(2, Json.str(q, "section", ""));
        ps.setString(3, Json.str(q, "topic", ""));
        ps.setString(4, Json.str(q, "skillId", ""));
        ps.setString(5, Json.str(q, "difficulty", "medium"));
        ps.setString(6, Json.str(q, "type", "mcq"));
        ps.setInt(7, Json.intOf(q, "timeLimit", 90));
        ps.setString(8, Json.str(q, "domain", ""));
        ps.setString(9, Json.str(q, "prompt", ""));
        ps.setString(10, q.has("options") ? q.get("options").toString() : "[]");
        ps.setString(11, q.has("answer") ? q.get("answer").toString() : (q.has("rubric") ? q.get("rubric").toString() : "\"\""));
        ps.setString(12, q.has("rubric") ? q.get("rubric").toString() : "{}");
        ps.setString(13, Json.str(q, "starter", ""));
        ps.setString(14, q.has("checks") ? q.get("checks").toString() : "[]");
        ps.setString(15, q.has("tests") ? q.get("tests").toString() : "[]");
        ps.setString(16, Json.str(q, "explanation", ""));
        ps.setString(17, Json.str(q, "modelAnswer", ""));
        ps.setString(18, Json.array(q, "companyTags").toString());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void loadCompany(JsonObject o) throws Exception {
    try (Connection c = get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO companies(id,name,data_json) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name, data_json=excluded.data_json")) {
      ps.setString(1, Json.str(o, "id", ""));
      ps.setString(2, Json.str(o, "name", ""));
      ps.setString(3, o.toString());
      ps.executeUpdate();
    }
  }

  /** Any user data present? Used to decide whether to seed the demo history. */
  public static boolean hasStudentData() {
    try (Connection c = get(); Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT COUNT(*) FROM attempts")) {
      return r.next() && r.getInt(1) > 0;
    } catch (SQLException e) { return false; }
  }

  private static void seedDemoUsers() throws Exception {
    try (Connection c = get()) {
      int student = upsertUser(c, "Aarav Sharma", "student@placeiq.dev", "student123", "student",
          "Kongu Engineering College", "CSE", 2026, "CS/IT", "Software Engineer", "TCS", 8.1);
      int mentor = upsertUser(c, "Priya Menon", "mentor@placeiq.dev", "mentor123", "mentor",
          "Kongu Engineering College", "Placement Cell", 0, "", "Placement Mentor", "", 0);
      upsertUser(c, "Ravi Kumar", "admin@placeiq.dev", "admin123", "admin",
          "Kongu Engineering College", "Training & Placement Officer", 0, "", "TPO", "", 0);
      // extra students so mentor/admin cohort views have a population
      upsertUser(c, "Nithya R", "nithya@placeiq.dev", "student123", "student", "Kongu Engineering College", "IT", 2026, "CS/IT", "Software Engineer", "Infosys", 7.6);
      upsertUser(c, "Karthik V", "karthik@placeiq.dev", "student123", "student", "Kongu Engineering College", "ECE", 2026, "ECE", "VLSI Engineer", "Zoho", 7.0);
      upsertUser(c, "Sneha D", "sneha@placeiq.dev", "student123", "student", "SIT Tumkur", "CSE", 2026, "CS/IT", "Data Analyst", "Cognizant", 8.4);
      upsertUser(c, "Farhan A", "farhan@placeiq.dev", "student123", "student", "Kongu Engineering College", "MECH", 2026, "Mechanical", "Design Engineer", "TCS", 6.6);

      try (PreparedStatement ps = c.prepareStatement(
          "INSERT OR IGNORE INTO mentor_notes(mentor_id,student_id,note,created_at) VALUES(?,?,?,?)")) {
        ps.setInt(1, mentor);
        ps.setInt(2, student);
        ps.setString(3, "Strong in coding; needs speed work in Data Interpretation before the Infosys drive.");
        ps.setString(4, Time.nowMinusDays(3));
        ps.executeUpdate();
      }
      // growth history first, so attempt ids and dates agree and the charts tell a story
      java.util.Map<String, Double> bias = new java.util.HashMap<>();
      bias.put("coding", 0.05);
      bias.put("communication", -0.08);
      bias.put("logical", -0.04);
      Assessment.seedDemoAttempt(student, 28, 0.38, "Diagnostic assessment", new java.util.HashMap<>());
      Assessment.seedDemoAttempt(student, 21, 0.50, "Diagnostic assessment", new java.util.HashMap<>());
      Assessment.seedDemoAttempt(student, 14, 0.60, "Custom practice (CS/IT)", bias);
      Assessment.seedDemoAttempt(student, 6, 0.70, "Diagnostic assessment", bias);

      for (int uid : new int[]{student, 4, 5, 6, 7}) MentorAdmin.seedBaselineProgress(uid, 1 + uid % 5);
      Gaps.syncReviews(student);
      Planner.seedDemoPlan(student);
    }
  }

  private static int upsertUser(Connection c, String name, String email, String pw, String role, String college,
                                String branch, int gradYear, String domain, String targetRole, String targetCompany, double cgpa) throws Exception {
    try (PreparedStatement find = c.prepareStatement("SELECT id FROM users WHERE email=?")) {
      find.setString(1, email);
      ResultSet r = find.executeQuery();
      if (r.next()) return r.getInt(1);
    }
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO users(name,email,password_hash,role,college,branch,graduation_year,domain,target_role,target_company,cgpa,created_at,last_active) " +
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, name);
      ps.setString(2, email);
      ps.setString(3, Auth.hash(pw));
      ps.setString(4, role);
      ps.setString(5, college);
      ps.setString(6, branch);
      ps.setInt(7, gradYear);
      ps.setString(8, domain);
      ps.setString(9, targetRole);
      ps.setString(10, targetCompany);
      ps.setDouble(11, cgpa);
      ps.setString(12, Time.nowMinusDays(60));
      ps.setString(13, Time.now());
      ps.executeUpdate();
      ResultSet k = ps.getGeneratedKeys();
      k.next();
      return k.getInt(1);
    }
  }

  public static JsonObject config() {
    try (Connection c = get(); PreparedStatement ps = c.prepareStatement("SELECT v FROM meta WHERE k='config'")) {
      ResultSet r = ps.executeQuery();
      if (r.next()) return Json.parse(r.getString(1));
    } catch (SQLException ignored) { }
    try { return Json.read(Json.dataDir().resolve("config.json")); } catch (Exception e) { return new JsonObject(); }
  }
}
