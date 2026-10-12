package placeiq;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.*;

/**
 * PlaceIQ HTTP server: JDK http server + SQLite. No framework so the project runs with javac alone.
 *
 * Route groups are authorization-checked here (server side, not in the browser):
 *   /api/auth/**    public
 *   /api/meta/**    public read of content
 *   /api/student/** requires role student
 *   /api/mentor/**  requires role mentor or admin
 *   /api/admin/**   requires role admin
 */
public final class Main {
  private static final Pattern ROUTE = Pattern.compile("\\{([a-zA-Z]+)}");

  interface Handler { Object handle(Ctx ctx) throws Exception; }

  /** Thrown by handlers that need a specific HTTP status instead of 200. */
  static final class HttpError extends RuntimeException {
    final int status;
    HttpError(int status, String message) { super(message); this.status = status; }
  }

  static final class Ctx {
    final HttpExchange ex;
    final JsonObject body;
    final JsonObject query = new JsonObject();
    final Map<String, String> params = new LinkedHashMap<>();
    JsonObject user;

    Ctx(HttpExchange ex, JsonObject body, String rawQuery) {
      this.ex = ex;
      this.body = body;
      if (rawQuery != null)
        for (String pair : rawQuery.split("&")) {
          if (pair.isBlank()) continue;
          int eq = pair.indexOf('=');
          String k = eq < 0 ? pair : pair.substring(0, eq);
          String v = eq < 0 ? "" : urlDecode(pair.substring(eq + 1));
          query.addProperty(k, v);
        }
    }

    String param(String key) { return params.getOrDefault(key, ""); }

    int intParam(String key) { try { return Integer.parseInt(params.getOrDefault(key, "0")); } catch (Exception e) { return 0; } }

    String q(String key, String fallback) { return query.has(key) ? query.get(key).getAsString() : fallback; }

    int qInt(String key, int fallback) {
      try { return query.has(key) ? Integer.parseInt(query.get(key).getAsString()) : fallback; } catch (Exception e) { return fallback; }
    }

    int userId() { return Json.intOf(user, "id", 0); }

    String role() { return Json.str(user, "role", ""); }
  }

  private static final List<Object[]> ROUTES = new ArrayList<>();  // {method, pattern, roles, handler}

  private static void route(String method, String path, String roles, Handler handler) {
    ROUTES.add(new Object[]{method, Pattern.compile("^" + ROUTE.matcher(path).replaceAll("([^/]+)") + "$"), roles, handler, path});
  }

  static {
    // ---------------- public ----------------
    route("POST", "/api/auth/login", "public", ctx -> {
      JsonObject b = ctx.body;
      int uid = Auth.login(Json.str(b, "email", ""), Json.str(b, "password", ""));
      if (uid == -1) throw new HttpError(401, "No account with that email.");
      if (uid == -2) throw new HttpError(401, "Incorrect password.");
      String token = Auth.createSession(uid);
      Content.log(uid, "login", "login from api");
      return Json.obj("token", token, "user", Auth.me(uid));
    });
    route("POST", "/api/auth/register", "public", ctx -> {
      JsonObject user = Auth.register(ctx.body);
      if (user == null) return Json.obj("error", "Email already registered or password shorter than 6 characters.");
      String token = Auth.createSession(Json.intOf(user, "id", 0));
      Content.notify(Json.intOf(user, "id", 0), "Welcome to PlaceIQ",
          "Start with the diagnostic assessment - it takes about 20 minutes and unlocks your gap analysis.", Time.today());
      return Json.obj("token", token, "user", user);
    });
    route("GET", "/api/health", "public", ctx -> Json.obj("ok", true, "time", Time.now()));
    route("GET", "/api/meta/config", "public", ctx -> Json.obj(
        "sections", Json.array(Db.config(), "sections"), "domains", Json.array(Db.config(), "domains"),
        "questionBankSize", Content.bankSize(null), "companies", companySummary(), "thresholads", Json.child(Db.config(), "readinessThresholds"),
        "demoLogins", Json.arr(Json.obj("role", "student", "email", "student@placeiq.dev", "password", "student123"),
            Json.obj("role", "mentor", "email", "mentor@placeiq.dev", "password", "mentor123"),
            Json.obj("role", "admin", "email", "admin@placeiq.dev", "password", "admin123"))));
    route("GET", "/api/meta/companies", "public", ctx -> companySummary());
    route("GET", "/api/meta/companies/{id}", "public", ctx -> Content.company(ctx.param("id")));
    route("GET", "/api/meta/courses", "public", ctx -> Content.courses());
    route("GET", "/api/meta/skills", "public", ctx -> Content.skills());

    // ---------------- student ----------------
    route("GET", "/api/me", "student,mentor,admin", ctx -> Auth.me(ctx.userId()));
    route("PUT", "/api/me", "student,mentor,admin", ctx -> {
      Auth.updateProfile(ctx.userId(), ctx.body);
      return Auth.me(ctx.userId());
    });
    route("GET", "/api/student/dashboard", "student,admin", ctx -> Analytics.dashboard(ctx.userId(), ctx.user));
    route("POST", "/api/student/assessment/start", "student,admin", ctx -> Assessment.start(ctx.userId(), ctx.body, ctx.user));
    route("GET", "/api/student/assessment/{id}/next", "student,admin", ctx -> Assessment.next(ctx.userId(), ctx.intParam("id")));
    route("POST", "/api/student/assessment/{id}/answer", "student,admin", ctx -> Assessment.answer(ctx.userId(), ctx.intParam("id"), ctx.body));
    route("POST", "/api/student/assessment/{id}/submit", "student,admin", ctx -> Assessment.submit(ctx.userId(), ctx.intParam("id"), ctx.body));
    route("GET", "/api/student/assessment/{id}/result", "student,admin", ctx -> Assessment.result(ctx.userId(), ctx.intParam("id")));
    route("GET", "/api/student/attempts", "student,admin", ctx -> Analytics.summaries(Content.attempts(ctx.userId(), ctx.q("kind", null))));
    route("GET", "/api/student/attempts/{id}", "student,admin", ctx -> {
      JsonObject a = Content.attemptDetail(ctx.intParam("id"), ctx.userId());
      return a == null ? Json.obj("error", "not found") : a;
    });
    route("GET", "/api/student/analytics", "student,admin", ctx -> Json.obj(
        "improvement", Analytics.improvementFor(ctx.userId()), "charts", Analytics.charts(ctx.userId()),
        "prs", Analytics.prsComponents(ctx.userId()), "readinessHistory", Analytics.readinessHistory(ctx.userId())));
    route("GET", "/api/student/gaps", "student,admin", ctx -> Json.obj("gaps", Json.arr(Gaps.ranked(ctx.userId()).toArray()),
        "recommendations", Gaps.recommendations(ctx.userId())));
    route("GET", "/api/student/recommendations", "student,admin", ctx -> Gaps.recommendations(ctx.userId()));
    route("POST", "/api/student/courses/{id}/status", "student,admin", ctx ->
        Gaps.setCourseStatus(ctx.userId(), ctx.param("id"), Json.str(ctx.body, "status", "in_progress"), Json.num(ctx.body, "progressPct", 50)));
    route("GET", "/api/student/reviews", "student,admin", ctx -> Json.arr(Gaps.dueReviews(ctx.userId()).toArray()));
    route("GET", "/api/student/planner", "student,admin", ctx -> Planner.activePlan(ctx.userId()));
    route("POST", "/api/student/planner", "student,admin", ctx -> Planner.create(ctx.userId(), ctx.body, ctx.user));
    route("POST", "/api/student/planner/task/{id}", "student,admin", ctx ->
        Planner.completeTask(ctx.userId(), ctx.intParam("id"), Json.str(ctx.body, "status", "done").equals("done")));
    route("POST", "/api/student/resume/analyze", "student,admin", ctx -> {
      byte[] bytes = null;
      String content = Json.str(ctx.body, "contentBase64", "");
      if (!content.isBlank()) bytes = Base64.getDecoder().decode(content);
      return Tools.analyzeResume(ctx.userId(), Json.str(ctx.body, "filename", "pasted"), bytes,
          Json.str(ctx.body, "text", ""), Json.str(ctx.body, "targetRole", Json.str(ctx.user, "targetRole", "")));
    });
    route("GET", "/api/student/checklist", "student,admin", ctx -> Json.obj("items", Tools.checklist(ctx.userId()), "plan",
        Json.obj()));
    route("POST", "/api/student/checklist", "student,admin", ctx ->
        Tools.toggleChecklist(ctx.userId(), Json.str(ctx.body, "key", ""), Json.bool(ctx.body, "done")));
    route("GET", "/api/student/mock/patterns", "student,admin", ctx -> Tools.patterns());
    route("POST", "/api/student/mock/start", "student,admin", ctx -> Tools.startMock(ctx.userId(), Json.str(ctx.body, "company", ""), ctx.user));
    route("POST", "/api/student/mock/{id}/submit", "student,admin", ctx -> Tools.submitMock(ctx.userId(), ctx.intParam("id"), ctx.body));
    route("GET", "/api/student/mock/history", "student,admin", ctx -> Tools.mockHistory(ctx.userId()));
    route("GET", "/api/student/mock/{id}/review", "student,admin", ctx -> Tools.review(ctx.intParam("id"), ctx.userId()));
    route("POST", "/api/student/interview/start", "student,admin", ctx -> Tools.startInterview(ctx.userId(), ctx.body, ctx.user));
    route("POST", "/api/student/interview/answer", "student,admin", ctx -> Tools.answerInterview(ctx.userId(), ctx.body));
    route("POST", "/api/student/interview/{id}/finish", "student,admin", ctx -> Tools.finishInterview(ctx.userId(), ctx.intParam("id")));
    route("GET", "/api/student/interviews", "student,admin", ctx -> Tools.interviewHistory(ctx.userId()));
    route("GET", "/api/student/company-fit/{id}", "student,admin", ctx -> companyFit(ctx.userId(), ctx.param("id"), ctx.q("role", "")));
    route("POST", "/api/student/events", "student,admin", ctx -> {
      String type = Json.str(ctx.body, "type", "unknown");
      String detail = Json.str(ctx.body, "detail", "");
      Content.log(ctx.userId(), type, detail);
      if ("tab_switch".equals(type)) {
        try (java.sql.Connection c = Db.get(); java.sql.PreparedStatement ps = c.prepareStatement(
            "UPDATE attempts SET violations = violations + 1 WHERE id=? AND user_id=?")) {
          ps.setInt(1, Json.intOf(ctx.body, "attemptId", 0));
          ps.setInt(2, ctx.userId());
          ps.executeUpdate();
        }
      }
      return Json.obj("logged", true);
    });
    route("GET", "/api/student/notifications", "student,mentor,admin", ctx -> MentorAdmin.notifications(ctx.userId()));
    route("POST", "/api/student/notifications/read", "student,mentor,admin", ctx -> {
      MentorAdmin.markNotificationsRead(ctx.userId());
      return Json.obj("ok", true);
    });

    // ---------------- mentor ----------------
    route("GET", "/api/mentor/students", "mentor,admin", ctx -> MentorAdmin.students(0, ctx.user));
    route("GET", "/api/mentor/students/{id}", "mentor,admin", ctx -> MentorAdmin.studentDetail(ctx.intParam("id")));
    route("POST", "/api/mentor/students/{id}/note", "mentor,admin", ctx ->
        MentorAdmin.addNote(ctx.userId(), ctx.intParam("id"), Json.str(ctx.body, "note", "")));
    route("POST", "/api/mentor/students/{id}/assign-task", "mentor,admin", ctx ->
        MentorAdmin.assignTask(ctx.userId(), ctx.intParam("id"), ctx.body));
    route("POST", "/api/mentor/students/{id}/assign-test", "mentor,admin", ctx ->
        MentorAdmin.assignTest(ctx.userId(), ctx.intParam("id"), Json.str(ctx.body, "company", "tcs")));
    route("GET", "/api/mentor/cohort", "mentor,admin", ctx -> MentorAdmin.cohort(ctx.user));

    // ---------------- admin ----------------
    route("GET", "/api/admin/cohort", "admin", ctx -> MentorAdmin.cohort(ctx.user));
    route("GET", "/api/admin/questions", "admin", ctx -> MentorAdmin.questionBank(ctx.q("section", null), ctx.q("domain", null)));
    route("POST", "/api/admin/questions", "admin", ctx -> MentorAdmin.saveQuestion(ctx.body));
    route("DELETE", "/api/admin/questions/{id}", "admin", ctx -> MentorAdmin.deleteQuestion(ctx.param("id")));
    route("POST", "/api/admin/import", "admin", ctx -> MentorAdmin.importData(Json.str(ctx.body, "kind", "questions"),
        Json.str(ctx.body, "filename", ""), Json.str(ctx.body, "content", "")));
    route("GET", "/api/admin/export/{what}", "admin", ctx -> {
      String csv = MentorAdmin.exportCsv(ctx.param("what"), ctx.user);
      byte[] out = csv.getBytes(StandardCharsets.UTF_8);
      ctx.ex.getResponseHeaders().add("Content-Type", "text/csv; charset=utf-8");
      ctx.ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=placeiq-" + ctx.param("what") + ".csv");
      ctx.ex.sendResponseHeaders(200, out.length);
      ctx.ex.getResponseBody().write(out);
      ctx.ex.close();
      return null;
    });
  }

  // ---------------- company fit ----------------

  static JsonObject companyFit(int userId, String companyId, String role) {
    JsonObject company = Content.company(companyId);
    if (company.size() == 0) return Json.obj("error", "unknown company");
    JsonObject required = Json.child(company, "skills");
    JsonObject competency = Content.competency(userId);
    JsonObject skillMeta = Content.skillsById();
    double weighted = 0, weightTotal = 0;
    List<JsonObject> gaps = new ArrayList<>();
    for (String section : required.keySet()) {
      double weight = required.get(section).getAsDouble();
      double sum = 0;
      int n = 0;
      for (String skillId : skillMeta.keySet()) {
        JsonObject skill = skillMeta.getAsJsonObject(skillId);
        if (!Json.str(skill, "section", "").equals(section)) continue;
        JsonObject c = competency.has(skillId) ? competency.getAsJsonObject(skillId) : null;
        sum += c == null ? 0 : Json.num(c, "accuracy", 0);
        n++;
      }
      double avg = n == 0 ? 0 : sum / n;
      weighted += avg * weight;
      weightTotal += weight;
      gaps.add(Json.obj("section", section, "name", section, "weight", weight, "yourScore", Math.round(avg * 10.0) / 10.0,
          "requiredScore", 75, "deficit", Math.max(0, 75 - avg)));
    }
    double match = weightTotal == 0 ? 0 : weighted / weightTotal;
    gaps.sort((a, b) -> Double.compare(Json.num(b, "deficit", 0) * Json.num(b, "weight", 0), Json.num(a, "deficit", 0) * Json.num(a, "weight", 0)));
    JsonArray top = new JsonArray();
    for (int i = 0; i < Math.min(3, gaps.size()); i++) {
      JsonObject g = gaps.get(i);
      if (Json.num(g, "deficit", 0) <= 0) continue;
      top.add(Json.obj("section", Json.str(g, "section", ""), "yourScore", Json.num(g, "yourScore", 0),
          "target", Json.num(g, "requiredScore", 75), "deficit", Math.round(Json.num(g, "deficit", 0) * 10.0) / 10.0,
          "tip", "Focus your next two practice sets on this section - it carries " + Math.round(Json.num(g, "weight", 0) * 100) + "% of the " + Json.str(company, "name", "") + " test weight."));
    }
    return Json.obj("company", Json.str(company, "name", ""), "companyId", Json.str(company, "id", ""),
        "matchPct", Math.round(match * 10.0) / 10.0, "sections", Json.arr(gaps.toArray()), "topGaps", top,
        "rounds", Json.array(company, "rounds"), "skills", required, "difficulty", Json.str(company, "difficulty", ""),
        "role", role.isBlank() ? joinArray(Json.array(company, "roles")) : role);
  }

  private static String joinArray(JsonArray a) {
    List<String> out = new ArrayList<>();
    for (JsonElement e : a) out.add(e.getAsString());
    return String.join(" / ", out);
  }

  private static JsonArray companySummary() {
    JsonArray out = new JsonArray();
    for (JsonElement e : Content.companies()) {
      JsonObject c = e.getAsJsonObject();
      out.add(Json.obj("id", Json.str(c, "id", ""), "name", Json.str(c, "name", ""),
          "fullName", Json.str(c, "fullName", ""), "difficulty", Json.str(c, "difficulty", ""),
          "roles", Json.array(c, "roles"),
          "rounds", Json.array(c, "rounds"),                 // the actual round objects (name/mode/detail)
          "roundCount", Json.array(c, "rounds").size(),
          "eligibility", Json.child(c, "eligibility"), "hiringSeason", Json.str(c, "hiringSeason", ""),
          "skills", Json.child(c, "skills")));
    }
    return out;
  }

  // ---------------- helpers ----------------

  static String urlDecode(String s) {
    try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
  }

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getProperty("port", System.getenv().getOrDefault("PORT", "8080")));
    boolean reseed = Arrays.asList(args).contains("--reseed");
    if (reseed) System.setProperty("placeiq.reseed", "true");

    Db.init();   // schema + content + demo users with sample history

    HttpServer server = server(port);
    System.out.println("PlaceIQ running on http://localhost:" + port + "  (db=" + System.getProperty("placeiq.db", "placeiq.db") + ")");
    System.out.println("Demo logins: student@placeiq.dev/student123 | mentor@placeiq.dev/mentor123 | admin@placeiq.dev/admin123");
  }

  /** Starts (but does not block on) the HTTP server - also used by the integration tests. */
  public static HttpServer server(int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
    server.createContext("/", Main::dispatch);
    server.setExecutor(Executors.newFixedThreadPool(16));
    server.start();
    return server;
  }

  private static void dispatch(HttpExchange ex) {
    try {
      String path = ex.getRequestURI().getPath();
      if (!path.startsWith("/api/")) { serveStatic(ex, path); return; }
      String method = ex.getRequestMethod();
      JsonObject body = readBody(ex);

      for (Object[] r : ROUTES) {
        String routeMethod = (String) r[0];
        Pattern pattern = (Pattern) r[1];
        String roles = (String) r[2];
        Matcher m = pattern.matcher(path);
        if (!m.matches()) continue;
        if (!routeMethod.equalsIgnoreCase(method)) continue;

        Ctx ctx = new Ctx(ex, body, ex.getRequestURI().getQuery());
        // extract path params in declaration order ({id} values arrive as groups 1..n)
        String[] keys = paramNames((String) r[4]);
        for (int i = 0; i < keys.length && i + 1 <= m.groupCount(); i++)
          ctx.params.put(keys[i], urlDecode(m.group(i + 1)));

        if (!"public".equals(roles)) {
          String token = bearer(ex);
          ctx.user = Auth.userFor(token);
          if (ctx.user == null) { send(ex, 401, Json.obj("error", "Sign in required.")); return; }
          Auth.touch(ctx.userId());
          boolean allowed = false;
          for (String want : roles.split(",")) if (want.equalsIgnoreCase(ctx.role())) allowed = true;
          if (!allowed) { send(ex, 403, Json.obj("error", "This area is for " + roles.replace(",", " / ") + " accounts.")); return; }
        }
        Object result = ((Handler) r[3]).handle(ctx);
        if (result != null) send(ex, 200, result);
        return;
      }
      send(ex, 404, Json.obj("error", "Unknown endpoint: " + method + " " + path));
    } catch (HttpError e) {
      send(ex, e.status, Json.obj("error", e.getMessage()));
    } catch (IOException clientWentAway) {
      // client disconnected mid-response; nothing to report
    } catch (Exception e) {
      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      System.out.println("[error] " + sw);
      send(ex, 500, Json.obj("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
    }
  }

  private static String[] paramNames(String path) {
    List<String> names = new ArrayList<>();
    Matcher m = ROUTE.matcher(path);
    while (m.find()) names.add(m.group(1));
    return names.toArray(new String[0]);
  }

  private static String bearer(HttpExchange ex) {
    String h = ex.getRequestHeaders().getFirst("Authorization");
    if (h == null) return null;
    return h.startsWith("Bearer ") ? h.substring(7) : h;
  }

  private static JsonObject readBody(HttpExchange ex) {
    try (InputStream in = ex.getRequestBody(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int n, total = 0;
      while ((n = in.read(buf)) > 0) {
        total += n;
        if (total > 8 * 1024 * 1024) break;   // 8 MB cap (resume uploads fit comfortably)
        bos.write(buf, 0, n);
      }
      return Json.parse(bos.toString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      return new JsonObject();
    }
  }

  private static void send(HttpExchange ex, int status, Object payload) {
    try {
      byte[] bytes = Json.GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
      ex.getResponseHeaders().add("Cache-Control", "no-store");
      ex.sendResponseHeaders(status, bytes.length);
      ex.getResponseBody().write(bytes);
      ex.close();
    } catch (IOException clientWentAway) {
      // normal when a browser tab closes or a test client aborts - not an application error
    }
  }

  private static void serveStatic(HttpExchange ex, String path) throws IOException {
    Path root = Paths.get(System.getProperty("placeiq.static", "static")).toAbsolutePath();
    Path file = path.equals("/") ? root.resolve("index.html") : root.resolve(path.substring(1)).normalize();
    if (!file.startsWith(root) || !Files.isRegularFile(file)) {
      byte[] notFound = ("Not found: " + path).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(404, notFound.length);
      ex.getResponseBody().write(notFound);
      ex.close();
      return;
    }
    byte[] bytes = Files.readAllBytes(file);
    Map<String, String> types = new HashMap<>();
    types.put("html", "text/html; charset=utf-8");
    types.put("css", "text/css; charset=utf-8");
    types.put("js", "application/javascript; charset=utf-8");
    types.put("svg", "image/svg+xml");
    types.put("json", "application/json");
    types.put("png", "image/png");
    String name = file.getFileName().toString();
    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "bin";
    ex.getResponseHeaders().add("Content-Type", types.getOrDefault(ext, "application/octet-stream"));
    ex.sendResponseHeaders(200, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }
}
