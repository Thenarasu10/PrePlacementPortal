package placeiq;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

/**
 * Placement tools: resume/ATS analyzer, profile checklist, mock tests and mock interviews.
 * File parsing uses only the JDK (zip for .docx, inflate for .pdf); if a file cannot be parsed the
 * student can paste the text instead, which is what the UI offers as a fallback.
 */
public final class Tools {

  // ---------------- resume analyzer ----------------

  public static JsonObject analyzeResume(int userId, String filename, byte[] bytes, String pastedText, String targetRole) throws Exception {
    JsonObject data = Json.read(Json.dataDir().resolve("role_keywords.json"));
    String text = pastedText != null && !pastedText.isBlank() ? pastedText : extract(filename, bytes);
    String lower = text.toLowerCase();

    JsonArray present = new JsonArray(), missing = new JsonArray();
    for (JsonElement e : Json.array(data, "expectedSections")) {
      String section = e.getAsString();
      boolean found = lower.contains(section.toLowerCase());
      if (found) present.add(section); else missing.add(section);
    }
    boolean hasEmail = lower.matches("(?s).*[\\w.+-]+@[\\w-]+\\.[\\w.]+.*");
    boolean hasPhone = lower.matches("(?s).*(\\+?\\d[\\d\\s-]{8,}).*");
    boolean hasLinkedIn = lower.contains("linkedin");
    boolean hasGithub = lower.contains("github");

    String role = targetRole == null || targetRole.isBlank() ? "Software Engineer" : targetRole;
    JsonObject roleKeywords = Json.child(Json.child(data, "roles"), role);
    if (roleKeywords.size() == 0) roleKeywords = Json.child(Json.child(data, "roles"), "Software Engineer");
    JsonArray matched = new JsonArray(), absent = new JsonArray();
    for (String kw : roleKeywords.keySet()) {
      if (lower.contains(kw.toLowerCase())) matched.add(kw); else absent.add(kw);
    }
    double keywordCoverage = roleKeywords.size() == 0 ? 0 : (double) matched.size() / roleKeywords.size();

    JsonArray verbs = Json.array(data, "actionVerbs");
    String[] lines = text.split("\\r?\\n");
    int bullets = 0, actionLines = 0, quantified = 0;
    for (String line : lines) {
      String l = line.trim();
      if (l.isEmpty()) continue;
      bullets++;
      String lc = l.toLowerCase();
      for (JsonElement v : verbs) if (lc.startsWith(v.getAsString())) { actionLines++; break; }
      if (l.matches("(?s).*\\d.*")) quantified++;
    }
    double actionVerbRatio = bullets == 0 ? 0 : (double) actionLines / bullets;
    int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
    int pages = Math.max(1, (int) Math.ceil(words / 550.0));

    double sectionsScore = Json.array(data, "expectedSections").size() == 0 ? 0
        : (double) present.size() / Json.array(data, "expectedSections").size() * 100;
    double contactScore = ((hasEmail ? 25 : 0) + (hasPhone ? 25 : 0) + (hasLinkedIn ? 25 : 0) + (hasGithub ? 25 : 0));
    double keywordScore = keywordCoverage * 100;
    double verbScore = Math.min(100, actionVerbRatio * 160);
    double quantifiedScore = Math.min(100, quantified * 20.0);
    double lengthScore = pages <= 1 ? 100 : pages == 2 ? 60 : 30;
    double ats = 0.25 * sectionsScore + 0.15 * contactScore + 0.25 * keywordScore
        + 0.15 * verbScore + 0.12 * quantifiedScore + 0.08 * lengthScore;

    JsonObject analysis = Json.obj("sectionsPresent", present, "missingSections", missing,
        "keywordCoverage", Math.round(keywordCoverage * 100.0) / 100.0, "matchedKeywords", matched,
        "missingKeywords", absent, "quantifiedBullets", quantified, "actionVerbRatio", Math.round(actionVerbRatio * 100.0) / 100.0,
        "words", words, "pages", pages, "email", hasEmail, "phone", hasPhone, "linkedin", hasLinkedIn, "github", hasGithub,
        "targetRole", role);
    JsonArray suggestions = Ai.provider().resumeSuggestions(analysis);
    JsonArray structural = new JsonArray();
    if (!hasEmail || !hasPhone) structural.add("Add a contact line with email and phone at the very top.");
    if (!hasLinkedIn) structural.add("Add your LinkedIn URL - 7 in 10 recruiters check it.");
    if (!hasGithub && role.toLowerCase().contains("software")) structural.add("Add a GitHub link with your project code.");
    if (missing.size() > 0) structural.add("Missing expected section(s): " + missing.toString().replace("\"", "") + ".");
    if (pages > 1) structural.add("Cut to one page - campus recruiters spend about 8 seconds on a resume.");
    for (JsonElement s : structural) suggestions.add(s);

    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO resume_reports(user_id,filename,ats_score,sections_json,keywords_json,suggestions_json,created_at) VALUES(?,?,?,?,?,?,?)",
        Statement.RETURN_GENERATED_KEYS)) {
      ps.setInt(1, userId);
      ps.setString(2, filename == null ? "pasted-text" : filename);
      ps.setDouble(3, Math.round(ats * 10.0) / 10.0);
      ps.setString(4, analysis.toString());
      ps.setString(5, Json.obj("matched", matched, "missing", absent).toString());
      ps.setString(6, suggestions.toString());
      ps.setString(7, Time.now());
      ps.executeUpdate();
      ResultSet k = ps.getGeneratedKeys();
      k.next();
      analysis.addProperty("reportId", k.getInt(1));
    }
    analysis.addProperty("atsScore", Math.round(ats * 10.0) / 10.0);
    analysis.add("suggestions", suggestions);
    analysis.add("breakdown", Json.obj("sections", Math.round(sectionsScore * 10.0) / 10.0, "contact", contactScore,
        "keywords", Math.round(keywordScore * 10.0) / 10.0, "actionVerbs", Math.round(verbScore * 10.0) / 10.0,
        "quantified", quantifiedScore, "length", lengthScore));
    return analysis;
  }

  /** Extracts plain text from .docx (zip + XML) or .pdf (inflate + text operators). JDK only. */
  public static String extract(String filename, byte[] bytes) {
    if (bytes == null || bytes.length == 0) return "";
    String name = filename == null ? "" : filename.toLowerCase();
    try {
      if (name.endsWith(".docx")) return fromDocx(bytes);
      if (name.endsWith(".pdf")) return fromPdf(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }

  private static String fromDocx(byte[] bytes) throws IOException {
    try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zin.getNextEntry()) != null) {
        if (!"word/document.xml".equals(entry.getName())) continue;
        String xml = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
        xml = xml.replaceAll("</w:p>", "\n").replaceAll("<[^>]+>", " ");
        return xml.replaceAll("[ \\t]+", " ").replaceAll("(?m)^\\s+", "").trim();
      }
    }
    return "";
  }

  private static String fromPdf(byte[] bytes) throws IOException {
    StringBuilder out = new StringBuilder();
    int idx = 0;
    while (true) {
      int streamStart = indexOf(bytes, "stream".getBytes(StandardCharsets.US_ASCII), idx);
      if (streamStart < 0) break;
      int dataStart = streamStart + 6;
      while (dataStart < bytes.length && (bytes[dataStart] == '\n' || bytes[dataStart] == '\r')) dataStart++;
      int streamEnd = indexOf(bytes, "endstream".getBytes(StandardCharsets.US_ASCII), dataStart);
      if (streamEnd < 0) break;
      byte[] slice = Arrays.copyOfRange(bytes, dataStart, streamEnd);
      String content;
      try {
        content = inflate(slice);
      } catch (Exception e) {
        content = new String(slice, StandardCharsets.ISO_8859_1);
      }
      if (content.contains("Tj") || content.contains("TJ")) out.append(pdfText(content)).append('\n');
      idx = streamEnd + 9;
    }
    String text = out.toString();
    return text.isBlank() ? new String(bytes, StandardCharsets.ISO_8859_1).replaceAll("[^\\x20-\\x7E\\n]", " ") : text;
  }

  private static String inflate(byte[] data) throws Exception {
    Inflater inf = new Inflater();
    inf.setInput(data);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    while (!inf.finished()) {
      int n = inf.inflate(buf);
      if (n == 0) break;
      bos.write(buf, 0, n);
    }
    inf.end();
    return bos.toString(StandardCharsets.ISO_8859_1);
  }

  /** Pulls the strings out of PDF text-showing operators. */
  private static String pdfText(String content) {
    StringBuilder sb = new StringBuilder();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((?:\\\\.|[^()\\\\])*\\)").matcher(content);
    while (m.find()) {
      String s = m.group().substring(1, m.group().length() - 1)
          .replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\");
      sb.append(s).append(' ');
    }
    return sb.toString();
  }

  private static int indexOf(byte[] haystack, byte[] needle, int from) {
    outer:
    for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }

  // ---------------- profile checklist ----------------

  public static JsonArray checklist(int userId) {
    JsonObject data;
    try { data = Json.read(Json.dataDir().resolve("role_keywords.json")); } catch (Exception e) { data = new JsonObject(); }
    JsonArray out = new JsonArray();
    try (Connection c = Db.get()) {
      for (JsonElement e : Json.array(data, "checklist")) {
        JsonObject item = e.getAsJsonObject();
        String key = Json.str(item, "key", "");
        boolean done = false;
        try (PreparedStatement ps = c.prepareStatement("SELECT done FROM checklist WHERE user_id=? AND item_key=?")) {
          ps.setInt(1, userId);
          ps.setString(2, key);
          ResultSet r = ps.executeQuery();
          if (r.next()) done = r.getInt("done") == 1;
        }
        out.add(Json.obj("key", key, "label", Json.str(item, "label", ""), "done", done));
      }
    } catch (SQLException ignored) { }
    return out;
  }

  public static JsonObject toggleChecklist(int userId, String key, boolean done) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO checklist(user_id,item_key,label,done,updated_at) VALUES(?,?,?,?,?) " +
        "ON CONFLICT(user_id,item_key) DO UPDATE SET done=excluded.done, updated_at=excluded.updated_at")) {
      ps.setInt(1, userId);
      ps.setString(2, key);
      ps.setString(3, key);
      ps.setInt(4, done ? 1 : 0);
      ps.setString(5, Time.now());
      ps.executeUpdate();
    }
    JsonArray items = checklist(userId);
    int doneCount = 0;
    for (JsonElement e : items) if (e.getAsJsonObject().get("done").getAsBoolean()) doneCount++;
    return Json.obj("items", items, "completionPct", items.size() == 0 ? 0 : Math.round((double) doneCount / items.size() * 100));
  }

  // ---------------- mock interviews ----------------

  public static JsonObject startInterview(int userId, JsonObject body, JsonObject user) throws Exception {
    String mode = Json.str(body, "mode", "hr");
    String company = Json.str(body, "company", Json.str(user, "targetCompany", "TCS"));
    String role = Json.str(body, "role", Json.str(user, "targetRole", "Software Engineer"));
    JsonObject generated = Ai.provider().generateInterviewQuestion(mode, company, role, user);
    int id;
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO interviews(user_id,mode,company,role,started_at) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
      ps.setInt(1, userId);
      ps.setString(2, mode);
      ps.setString(3, company);
      ps.setString(4, role);
      ps.setString(5, Time.now());
      ps.executeUpdate();
      ResultSet k = ps.getGeneratedKeys();
      k.next();
      id = k.getInt(1);
    }
    return Json.obj("interviewId", id, "mode", mode, "company", company, "role", role,
        "question", generated, "provider", Json.str(generated, "provider", "offline-rubric"));
  }

  public static JsonObject answerInterview(int userId, JsonObject body) throws Exception {
    int interviewId = Json.intOf(body, "interviewId", 0);
    String question = Json.str(body, "question", "");
    String answer = Json.str(body, "answer", "");
    String mode = Json.str(body, "mode", "hr");
    JsonObject rubric = Json.child(body, "rubric");
    JsonObject scored = Ai.provider().scoreAnswer(rubric, question, answer, mode);
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO interview_answers(interview_id,question,answer,relevance,structure,clarity,depth,feedback,model_answer,created_at) " +
        "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
      ps.setInt(1, interviewId);
      ps.setString(2, question);
      ps.setString(3, answer);
      ps.setDouble(4, Json.num(scored, "relevance", 0));
      ps.setDouble(5, Json.num(scored, "structure", 0));
      ps.setDouble(6, Json.num(scored, "clarity", 0));
      ps.setDouble(7, Json.num(scored, "depth", 0));
      ps.setString(8, Json.array(scored, "feedback").toString());
      ps.setString(9, Json.str(scored, "modelAnswer", ""));
      ps.setString(10, Time.now());
      ps.executeUpdate();
    }
    return scored;
  }

  public static JsonObject finishInterview(int userId, int interviewId) throws SQLException {
    JsonArray answers = new JsonArray();
    double total = 0;
    int n = 0;
    try (Connection c = Db.get()) {
      try (PreparedStatement ps = c.prepareStatement("SELECT * FROM interview_answers WHERE interview_id=? ORDER BY id")) {
        ps.setInt(1, interviewId);
        ResultSet r = ps.executeQuery();
        while (r.next()) {
          double overall = (r.getDouble("relevance") + r.getDouble("structure") + r.getDouble("clarity") + r.getDouble("depth")) / 4.0;
          total += overall;
          n++;
          answers.add(Json.obj("question", r.getString("question"), "answer", r.getString("answer"),
              "relevance", r.getDouble("relevance"), "structure", r.getDouble("structure"),
              "clarity", r.getDouble("clarity"), "depth", r.getDouble("depth"), "overall", Math.round(overall * 10.0) / 10.0,
              "feedback", Json.any(r.getString("feedback")), "modelAnswer", r.getString("model_answer")));
        }
      }
      double avg = n == 0 ? 0 : total / n;
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE interviews SET finished_at=?, overall_pct=?, rubric_json=?, summary=? WHERE id=? AND user_id=?")) {
        ps.setString(1, Time.now());
        ps.setDouble(2, Math.round(avg * 10.0) / 10.0);
        ps.setString(3, answers.toString());
        ps.setString(4, n == 0 ? "No answers recorded." : "Averaged " + Math.round(avg) + "% across " + n +
            " answer(s): relevance is scored against what the interviewer listens for, structure against STAR shaping.");
        ps.setInt(5, interviewId);
        ps.setInt(6, userId);
        ps.executeUpdate();
      }
      Assessment.addXp(userId, 40, "mock interview " + interviewId);
    }
    return Json.obj("interviewId", interviewId, "overallPct", n == 0 ? 0 : Math.round(total / n * 10.0) / 10.0, "answers", answers);
  }

  public static JsonArray interviewHistory(int userId) {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT * FROM interviews WHERE user_id=? ORDER BY id DESC")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      while (r.next())
        out.add(Json.obj("id", r.getInt("id"), "mode", r.getString("mode"), "company", r.getString("company"),
            "role", r.getString("role"), "startedAt", r.getString("started_at"), "finishedAt", r.getString("finished_at"),
            "overallPct", r.getDouble("overall_pct"), "summary", r.getString("summary"),
            "answers", Json.any(r.getString("rubric_json"))));
    } catch (SQLException ignored) { }
    return out;
  }

  // ---------------- timed mock tests (company patterns) ----------------

  public static JsonArray patterns() {
    JsonArray out = new JsonArray();
    for (JsonElement e : Content.companies()) {
      JsonObject company = e.getAsJsonObject();
      JsonObject skills = Json.child(company, "skills");
      int totalMin = 0;
      JsonArray sections = new JsonArray();
      for (String key : skills.keySet()) {
        double weight = skills.get(key).getAsDouble();
        int count = Math.max(2, (int) Math.round(weight * 20));
        int minutes = Math.max(3, (int) Math.round(weight * 30));
        totalMin += minutes;
        sections.add(Json.obj("section", key, "questions", count, "minutes", minutes));
      }
      out.add(Json.obj("company", Json.str(company, "id", ""), "name", Json.str(company, "name", ""),
          "difficulty", Json.str(company, "difficulty", ""), "durationMin", totalMin,
          "negativeMarking", Json.str(company, "difficulty", "").equalsIgnoreCase("Very Hard") ? 0.33 : 0.25,
          "cutoffPct", Json.str(company, "difficulty", "").equalsIgnoreCase("Easy") ? 55 : 65,
          "instructions", Json.str(company, "difficulty", "") + " pattern - auto-submits when the timer ends. " +
              "Negative marking applies to wrong MCQs, so skip what you cannot eliminate.",
          "sections", sections));
    }
    return out;
  }

  public static JsonObject startMock(int userId, String companyId, JsonObject user) throws Exception {
    JsonObject pattern = null;
    for (JsonElement e : patterns())
      if (Json.str(e.getAsJsonObject(), "company", "").equalsIgnoreCase(companyId)) pattern = e.getAsJsonObject();
    if (pattern == null) return Json.obj("error", "unknown company pattern");

    JsonObject meta = Json.obj("asked", 0, "permutations", new JsonObject(), "answers", new JsonArray());
    int mockId;
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO mock_tests(user_id,pattern,company,created_at,negative_marking,time_limit_sec,meta_json) VALUES(?,?,?,?,?,?,?)",
        Statement.RETURN_GENERATED_KEYS)) {
      ps.setInt(1, userId);
      ps.setString(2, "company:" + companyId);
      ps.setString(3, companyId);
      ps.setString(4, Time.now());
      ps.setDouble(5, Json.num(pattern, "negativeMarking", 0.25));
      ps.setInt(6, Json.intOf(pattern, "durationMin", 45) * 60);
      ps.setString(7, meta.toString());
      ps.executeUpdate();
      ResultSet k = ps.getGeneratedKeys();
      k.next();
      mockId = k.getInt(1);
    }
    JsonArray queue = new JsonArray();
    for (JsonElement se : Json.array(pattern, "sections")) {
      JsonObject s = se.getAsJsonObject();
      String section = Json.str(s, "section", "");
      List<JsonObject> pool = Content.questions(section, Json.str(user, "domain", "CS/IT"), null, null, new HashSet<>(), Json.intOf(s, "questions", 5));
      for (JsonObject q : pool) queue.add(Json.str(q, "id", ""));
    }
    meta.add("queue", queue);
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("UPDATE mock_tests SET meta_json=? WHERE id=?")) {
      ps.setString(1, meta.toString());
      ps.setInt(2, mockId);
      ps.executeUpdate();
    }
    return Json.obj("mockId", mockId, "pattern", pattern, "questionCount", queue.size(),
        "timeLimitSec", Json.intOf(pattern, "durationMin", 45) * 60, "questions", serveMockQuestions(mockId, 0, 50));
  }

  /** Serves the whole paper at once (a real timed test shows all questions). */
  public static JsonArray serveMockQuestions(int mockId, int from, int limit) throws SQLException {
    JsonArray out = new JsonArray();
    JsonObject meta = mockMeta(mockId);
    JsonArray queue = Json.array(meta, "queue");
    JsonObject perms = Json.child(meta, "permutations");
    for (int i = from; i < queue.size() && out.size() < limit; i++) {
      JsonObject q = Content.question(queue.get(i).getAsString());
      if (q == null) continue;
      out.add(Content.presentQuestion(q, perms));
    }
    meta.add("permutations", perms);
    saveMockMeta(mockId, meta);
    return out;
  }

  private static JsonObject mockMeta(int mockId) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT meta_json FROM mock_tests WHERE id=?")) {
      ps.setInt(1, mockId);
      ResultSet r = ps.executeQuery();
      return r.next() ? Json.parse(r.getString(1)) : new JsonObject();
    }
  }

  private static void saveMockMeta(int mockId, JsonObject meta) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("UPDATE mock_tests SET meta_json=? WHERE id=?")) {
      ps.setString(1, meta.toString());
      ps.setInt(2, mockId);
      ps.executeUpdate();
    }
  }

  /** body.answers = [{questionId, answer:[letters], timeSec}] */
  public static JsonObject submitMock(int userId, int mockId, JsonObject body) throws Exception {
    JsonObject meta = mockMeta(mockId);
    JsonObject perms = Json.child(meta, "permutations");
    double negative = 0.25, cutoffPct = 65;
    String companyId = "";
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM mock_tests WHERE id=? AND user_id=?")) {
      ps.setInt(1, mockId);
      ps.setInt(2, userId);
      ResultSet r = ps.executeQuery();
      if (!r.next()) return Json.obj("error", "mock not found");
      negative = r.getDouble("negative_marking");
      companyId = r.getString("company");
    }
    for (JsonElement e : patterns())
      if (Json.str(e.getAsJsonObject(), "company", "").equalsIgnoreCase(companyId)) cutoffPct = Json.num(e.getAsJsonObject(), "cutoffPct", 65);

    JsonObject cfg = Db.config();
    // a real attempt row lets timed mocks feed topic stats, company fit and analytics
    int mockAttemptId;
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO attempts(user_id,kind,label,status,started_at,meta_json,violations,time_sec) VALUES(?,'mock',?,?,?,?,0,0)",
        Statement.RETURN_GENERATED_KEYS)) {
      ps.setInt(1, userId);
      ps.setString(2, companyId.toUpperCase() + " timed mock test");
      ps.setString(3, "in_progress");
      ps.setString(4, Time.now());
      ps.setString(5, "{}");
      ps.executeUpdate();
      ResultSet k = ps.getGeneratedKeys();
      k.next();
      mockAttemptId = k.getInt(1);
    }
    List<JsonObject> rows = new ArrayList<>();
    for (JsonElement e : Json.array(body, "answers")) {
      JsonObject a = e.getAsJsonObject();
      String qid = Json.str(a, "questionId", "");
      JsonObject q = Content.question(qid);
      if (q == null) continue;
      JsonArray given = Json.array(a, "answer");
      JsonArray keys = perms.has(qid) ? Json.array(perms, qid) : new JsonArray();
      JsonArray canonical = new JsonArray();
      if (keys.size() > 0 && Json.str(q, "type", "mcq").matches("mcq|multi")) {
        for (JsonElement g : given) {
          String letter = g.getAsString().toUpperCase();
          int idx = letter.isEmpty() ? -1 : letter.charAt(0) - 'A';
          canonical.add(idx >= 0 && idx < keys.size() ? keys.get(idx).getAsString() : letter);
        }
      } else canonical = given;
      double awarded = Scoring.award(q, Json.obj("answer", canonical), negative);
      boolean correct = Json.str(q, "type", "mcq").matches("mcq|multi") ? awarded >= 0.999 : awarded >= 0.6;
      rows.add(Json.obj("questionId", qid, "section", Json.str(q, "section", ""), "chosen", canonical,
          "awarded", awarded, "max", 1.0, "correct", correct, "timeSec", Json.num(a, "timeSec", 0)));
    }
    try (Connection c = Db.get()) {
      for (JsonObject row : rows) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO mock_test_answers(mock_id,question_id,section,chosen_json,awarded,max,correct,time_sec) VALUES(?,?,?,?,?,?,?,?)")) {
          ps.setInt(1, mockId);
          ps.setString(2, Json.str(row, "questionId", ""));
          ps.setString(3, Json.str(row, "section", ""));
          ps.setString(4, Json.array(row, "chosen").toString());
          ps.setDouble(5, Json.num(row, "awarded", 0));
          ps.setDouble(6, 1.0);
          ps.setInt(7, Json.intOf(row, "correct", 0));
          ps.setDouble(8, Json.num(row, "timeSec", 0));
          ps.executeUpdate();
        }
        // timed-test answers also feed the student's topic stats
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO attempt_answers(attempt_id,question_id,section,difficulty,chosen_json,awarded,max,correct,time_sec,answered_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
          JsonObject q = Content.question(Json.str(row, "questionId", ""));
          ps.setInt(1, mockAttemptId);
          ps.setString(2, Json.str(row, "questionId", ""));
          ps.setString(3, Json.str(row, "section", ""));
          ps.setString(4, q == null ? "medium" : Json.str(q, "difficulty", "medium"));
          ps.setString(5, Json.array(row, "chosen").toString());
          ps.setDouble(6, Json.num(row, "awarded", 0));
          ps.setDouble(7, 1.0);
          ps.setInt(8, Json.intOf(row, "correct", 0));
          ps.setDouble(9, Json.num(row, "timeSec", 0));
          ps.setString(10, Time.now());
          ps.executeUpdate();
        }
      }
    }
    JsonObject agg = Scoring.aggregate(Json.array(cfg, "sections"), rows);
    double scorePct = Json.num(agg, "overallPct", 0);
    int cleared = scorePct >= cutoffPct ? 1 : 0;
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "UPDATE attempts SET status='submitted', submitted_at=?, total_score=?, max_score=?, accuracy=?, overall_pct=?, readiness=?, section_stats_json=? WHERE id=?")) {
      ps.setString(1, Time.now());
      ps.setDouble(2, scorePct);
      ps.setDouble(3, rows.size());
      ps.setDouble(4, Json.num(agg, "accuracy", 0));
      ps.setDouble(5, scorePct);
      ps.setString(6, Json.str(agg, "readiness", "Beginner"));
      ps.setString(7, Json.array(agg, "sections").toString());
      ps.setInt(8, mockAttemptId);
      ps.executeUpdate();
    }
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "UPDATE mock_tests SET submitted_at=?, total_score=?, max_score=?, cutoff_cleared=? WHERE id=?")) {
      ps.setString(1, Time.now());
      ps.setDouble(2, scorePct);
      ps.setDouble(3, rows.size());
      ps.setInt(4, cleared);
      ps.setInt(5, mockId);
      ps.executeUpdate();
    }
    Assessment.addXp(userId, 45, "mock test " + mockId);
    return Json.obj("mockId", mockId, "scorePct", scorePct, "cutoffPct", cutoffPct, "cleared", cleared == 1,
        "sections", Json.array(agg, "sections"), "accuracy", Json.num(agg, "accuracy", 0),
        "review", review(mockId, userId), "prs", Analytics.prsComponents(userId));
  }

  /** Post-test review with per-question time analysis. */
  public static JsonArray review(int mockId, int userId) {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT mta.*, q.prompt, q.topic, q.explanation FROM mock_test_answers mta " +
        "LEFT JOIN questions q ON q.id=mta.question_id JOIN mock_tests mt ON mt.id=mta.mock_id " +
        "WHERE mta.mock_id=? AND mt.user_id=? ORDER BY mta.id")) {
      ps.setInt(1, mockId);
      ps.setInt(2, userId);
      ResultSet r = ps.executeQuery();
      while (r.next())
        out.add(Json.obj("questionId", r.getString("question_id"), "section", r.getString("section"),
            "topic", r.getString("topic"), "prompt", r.getString("prompt"), "chosen", Json.any(r.getString("chosen_json")),
            "awarded", r.getDouble("awarded"), "correct", r.getInt("correct") == 1, "timeSec", r.getDouble("time_sec"),
            "explanation", r.getString("explanation")));
    } catch (SQLException ignored) { }
    return out;
  }

  public static JsonArray mockHistory(int userId) {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT * FROM mock_tests WHERE user_id=? ORDER BY id DESC")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      while (r.next())
        out.add(Json.obj("id", r.getInt("id"), "company", r.getString("company"), "createdAt", r.getString("created_at"),
            "submittedAt", r.getString("submitted_at"), "scorePct", r.getDouble("total_score"),
            "cutoffCleared", r.getInt("cutoff_cleared") == 1, "negativeMarking", r.getDouble("negative_marking"),
            "timeLimitSec", r.getInt("time_limit_sec")));
    } catch (SQLException ignored) { }
    return out;
  }
}
