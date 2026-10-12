package placeiq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

/** Credential-based auth stub: PBKDF2 password hashing + server-side session tokens. */
public final class Auth {
  private static final SecureRandom RND = new SecureRandom();
  private static final int ITER = 120_000;

  public static String hash(String password) throws Exception {
    byte[] salt = new byte[16];
    RND.nextBytes(salt);
    byte[] dk = pbkdf2(password, salt, ITER);
    return "pbkdf2$" + ITER + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(dk);
  }

  public static boolean verify(String password, String stored) {
    try {
      String[] p = stored.split("\\$");
      if (p.length != 4) return false;
      int iter = Integer.parseInt(p[1]);
      byte[] salt = Base64.getDecoder().decode(p[2]);
      // constant-time comparison: a plain Arrays.equals leaks how many leading bytes matched
      return MessageDigest.isEqual(pbkdf2(password, salt, iter), Base64.getDecoder().decode(p[3]));
    } catch (Exception e) { return false; }
  }

  /** True when the stored hash was made with a weaker setting than we use now. */
  private static boolean needsRehash(String stored) {
    try { return Integer.parseInt(stored.split("\\$")[1]) < ITER; } catch (Exception e) { return false; }
  }

  /** Password policy for sign-up. Returns null when the password is acceptable. */
  public static String passwordProblem(String password, String email) {
    if (password == null) return "Choose a password.";
    if (password.length() < 8) return "Use at least 8 characters.";
    if (password.length() > 128) return "Passwords are limited to 128 characters.";
    if (!password.matches(".*[a-z].*")) return "Add at least one lowercase letter.";
    if (!password.matches(".*[A-Z].*")) return "Add at least one uppercase letter.";
    if (!password.matches(".*[0-9].*")) return "Add at least one digit.";
    String lower = password.toLowerCase();
    String[] banned = {"password", "12345678", "qwerty", "letmein", "welcome", "admin123", "iloveyou", "abc12345"};
    for (String b : banned) if (lower.contains(b)) return "That password is too common - pick something less guessable.";
    String local = (email == null ? "" : email.split("@")[0].toLowerCase());
    if (local.length() >= 4 && lower.contains(local)) return "Do not reuse your email address as the password.";
    return null;   // long enough, mixed case, has a digit, not a known-common string
  }

  private static byte[] pbkdf2(String pw, byte[] salt, int iter) throws Exception {
    javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(pw.toCharArray(), salt, iter, 256);
    return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
  }

  /** Session lifetime. Short enough that a stolen token expires, long enough for a demo day. */
  public static final int SESSION_HOURS = 24;
  private static final int MAX_SESSIONS_PER_USER = 5;

  /** Creates a session row and returns its token. */
  public static String createSession(int userId) throws SQLException {
    String token = newToken();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO sessions(token,user_id,created_at,expires_at) VALUES(?,?,?,?)")) {
      ps.setString(1, token);
      ps.setInt(2, userId);
      ps.setString(3, Time.now());
      ps.setString(4, Time.nowPlusHours(SESSION_HOURS));
      ps.executeUpdate();
    }
    pruneSessions(userId);
    return token;
  }

  /** 256 bits of entropy from SecureRandom, hex encoded (not a UUID - no shared structure). */
  private static String newToken() {
    byte[] b = new byte[32];
    RND.nextBytes(b);
    StringBuilder sb = new StringBuilder(64);
    for (byte x : b) sb.append(String.format("%02x", x));
    return sb.toString();
  }

  /** Drops this user's expired sessions and keeps only the newest few, so a leaked token dies sooner. */
  private static void pruneSessions(int userId) throws SQLException {
    try (Connection c = Db.get()) {
      try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?")) {
        ps.setString(1, Time.now());
        ps.executeUpdate();
      }
      try (PreparedStatement ps = c.prepareStatement(
          "DELETE FROM sessions WHERE user_id=? AND token NOT IN " +
          "(SELECT token FROM sessions WHERE user_id=? ORDER BY rowid DESC LIMIT ?)")) {   // rowid: created_at has
                                                                                          // only second precision, so
                                                                                          // ties could evict the new one
        ps.setInt(1, userId);
        ps.setInt(2, userId);
        ps.setInt(3, MAX_SESSIONS_PER_USER);
        ps.executeUpdate();
      }
    }
  }

  public static int activeSessions(int userId) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT COUNT(*) n FROM sessions WHERE user_id=? AND expires_at > ?")) {
      ps.setInt(1, userId);
      ps.setString(2, Time.now());
      ResultSet r = ps.executeQuery();
      return r.next() ? r.getInt("n") : 0;
    } catch (SQLException e) { return 0; }
  }

  /** Signs this user out everywhere - used by the profile screen and by an admin on suspicion. */
  public static int signOutAll(int userId) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE user_id=?")) {
      ps.setInt(1, userId);
      return ps.executeUpdate();
    }
  }

  /* ------------------------------------------------------------------ brute-force protection */

  public static final int MAX_FAILS_BEFORE_LOCK = 5;
  private static final int[] LOCK_MINUTES = {15, 60, 240};   // 5 fails -> 15 min, 10 -> 1 h, 15+ -> 4 h

  private static String attemptKey(String email, String ip) { return email + "|" + (ip == null ? "-" : ip); }

  /** Milliseconds-free: how long this email+ip must wait before another try. 0 = not locked. */
  public static long lockMinutesLeft(String email, String ip) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT locked_until FROM login_attempts WHERE key=?")) {
      ps.setString(1, attemptKey(email, ip));
      ResultSet r = ps.executeQuery();
      if (!r.next()) return 0;
      String until = r.getString("locked_until");
      return until == null ? 0 : Time.minutesUntil(until);
    } catch (SQLException e) { return 0; }
  }

  /** Records a failed sign-in and locks the key once the threshold is crossed. */
  private static void recordFailure(String email, String ip) {
    String key = attemptKey(email, ip);
    try (Connection c = Db.get()) {
      int fails = 0;
      try (PreparedStatement ps = c.prepareStatement("SELECT fails FROM login_attempts WHERE key=?")) {
        ps.setString(1, key);
        ResultSet r = ps.executeQuery();
        if (r.next()) fails = r.getInt("fails");
      }
      fails++;
      String lockedUntil = null;
      if (fails >= MAX_FAILS_BEFORE_LOCK) {
        int step = Math.min(LOCK_MINUTES.length - 1, (fails - MAX_FAILS_BEFORE_LOCK) / MAX_FAILS_BEFORE_LOCK);
        lockedUntil = Time.nowPlusMinutes(LOCK_MINUTES[step]);
      }
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO login_attempts(key,email,ip,fails,first_at,last_at,locked_until) VALUES(?,?,?,?,?,?,?) " +
          "ON CONFLICT(key) DO UPDATE SET fails=excluded.fails, last_at=excluded.last_at, locked_until=excluded.locked_until")) {
        ps.setString(1, key);
        ps.setString(2, email);
        ps.setString(3, ip);
        ps.setInt(4, fails);
        ps.setString(5, Time.now());
        ps.setString(6, Time.now());
        ps.setString(7, lockedUntil);
        ps.executeUpdate();
      }
      audit(email, ip, lockedUntil == null ? "login_failed" : "account_locked",
          "failed attempt #" + fails + (lockedUntil == null ? "" : ", locked for " + Time.minutesUntil(lockedUntil) + " min"));
    } catch (SQLException ignored) { }
  }

  private static void clearFailures(String email, String ip) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("DELETE FROM login_attempts WHERE key=?")) {
      ps.setString(1, attemptKey(email, ip));
      ps.executeUpdate();
    } catch (SQLException ignored) { }
  }

  /** Failed attempts and lockouts, newest first - shown on the admin security panel. */
  public static JsonArray securityLog(int limit) {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT * FROM security_events ORDER BY id DESC LIMIT ?")) {
      ps.setInt(1, limit);
      ResultSet r = ps.executeQuery();
      while (r.next())
        out.add(Json.obj("email", r.getString("email"), "ip", r.getString("ip"), "kind", r.getString("kind"),
            "detail", r.getString("detail"), "createdAt", r.getString("created_at")));
    } catch (SQLException ignored) { }
    return out;
  }

  public static JsonArray lockedAccounts() {
    JsonArray out = new JsonArray();
    try (Connection c = Db.get(); Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT * FROM login_attempts WHERE locked_until > '" + Time.now() + "' ORDER BY last_at DESC")) {
      while (r.next())
        out.add(Json.obj("email", r.getString("email"), "ip", r.getString("ip"), "fails", r.getInt("fails"),
            "lockedUntil", r.getString("locked_until"), "minutesLeft", Time.minutesUntil(r.getString("locked_until"))));
    } catch (SQLException ignored) { }
    return out;
  }

  /** Admin override: clears the lock for every key belonging to an email. */
  public static int unlock(String email) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("DELETE FROM login_attempts WHERE email=?")) {
      ps.setString(1, email == null ? "" : email.trim().toLowerCase());
      int n = ps.executeUpdate();
      audit(email, null, "unlocked", "lock cleared by an administrator");
      return n;
    }
  }

  private static void audit(String email, String ip, String kind, String detail) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO security_events(email,ip,kind,detail,created_at) VALUES(?,?,?,?,?)")) {
      ps.setString(1, email);
      ps.setString(2, ip);
      ps.setString(3, kind);
      ps.setString(4, detail);
      ps.setString(5, Time.now());
      ps.executeUpdate();
    } catch (SQLException ignored) { }
  }

  /**
   * Verifies credentials. Returns the user id, or a negative code:
   * -1 bad credentials (unknown email and wrong password are deliberately identical),
   * -3 too many attempts (caller answers 429 with the remaining minutes).
   */
  public static int login(String email, String password, String ip) throws SQLException {
    String key = email == null ? "" : email.trim().toLowerCase();
    if (key.isEmpty() || password == null || password.isEmpty()) return -1;
    if (password.length() > 128) return -1;                 // never feed a huge string to PBKDF2
    if (lockMinutesLeft(key, ip) > 0) return -3;

    int id;
    String hash;
    // close the read cursor before any write: SQLite blocks a writer while a reader is open on
    // another connection, which would silently cost the full busy timeout on every sign-in.
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT id,password_hash FROM users WHERE email=?")) {
      ps.setString(1, key);
      try (ResultSet r = ps.executeQuery()) {
        if (!r.next()) {
          try { verify(password, hash("decoy-" + key)); } catch (Exception ignored) { }   // same work as a real check: no timing tell
          recordFailure(key, ip);
          return -1;
        }
        id = r.getInt("id");
        hash = r.getString("password_hash");
      }
    }
    if (!verify(password, hash)) {
      recordFailure(key, ip);
      return -1;
    }
    clearFailures(key, ip);
    audit(key, ip, "login_ok", "signed in");
    if (needsRehash(hash)) {
      try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("UPDATE users SET password_hash=? WHERE id=?")) {
        ps.setString(1, hash(password));
        ps.setInt(2, id);
        ps.executeUpdate();
      } catch (Exception ignored) { }
    }
    touch(id);
    return id;
  }

  public static JsonObject register(JsonObject b) throws Exception {
    String email = Json.str(b, "email", "").trim().toLowerCase();
    String pw = Json.str(b, "password", "");
    if (email.isEmpty() || pw.length() < 6) return null;
    int newId;
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO users(name,email,password_hash,role,college,branch,graduation_year,domain,target_role,target_company,cgpa,created_at,last_active) " +
        "VALUES(?,?,?,'student',?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, Json.str(b, "name", email.split("@")[0]));
      ps.setString(2, email);
      ps.setString(3, hash(pw));
      ps.setString(4, Json.str(b, "college", ""));
      ps.setString(5, Json.str(b, "branch", ""));
      ps.setInt(6, Json.intOf(b, "graduationYear", 2026));
      ps.setString(7, Json.str(b, "domain", "CS/IT"));
      ps.setString(8, Json.str(b, "targetRole", "Software Engineer"));
      ps.setString(9, Json.str(b, "targetCompany", ""));
      ps.setDouble(10, Json.num(b, "cgpa", 0));
      ps.setString(11, Time.now());
      ps.setString(12, Time.now());
      ps.executeUpdate();
      ResultSet k = ps.getGeneratedKeys();
      k.next();
      int id = k.getInt(1);
      k.close();
      newId = id;
    } catch (SQLException dup) {
      return null; // email already exists
    }
    return me(newId);
  }

  public static void deleteSession(String token) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE token=?")) {
      ps.setString(1, token);
      ps.executeUpdate();
    }
  }

  /** Resolves a bearer token to a user row, or null. */
  public static JsonObject userFor(String token) throws SQLException {
    if (token == null || token.isBlank()) return null;
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token=? AND s.expires_at > ?")) {
      ps.setString(1, token);
      ps.setString(2, Time.now());
      ResultSet r = ps.executeQuery();
      return r.next() ? row(r) : null;
    }
  }

  public static JsonObject me(int userId) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE id=?")) {
      ps.setInt(1, userId);
      ResultSet r = ps.executeQuery();
      return r.next() ? row(r) : null;
    }
  }

  public static void updateProfile(int userId, JsonObject b) throws SQLException {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "UPDATE users SET name=?, college=?, branch=?, graduation_year=?, domain=?, target_role=?, target_company=?, cgpa=? WHERE id=?")) {
      JsonObject cur = me(userId);
      ps.setString(1, Json.str(b, "name", Json.str(cur, "name", "")));
      ps.setString(2, Json.str(b, "college", Json.str(cur, "college", "")));
      ps.setString(3, Json.str(b, "branch", Json.str(cur, "branch", "")));
      ps.setInt(4, Json.intOf(b, "graduationYear", Json.intOf(cur, "graduationYear", 2026)));
      ps.setString(5, Json.str(b, "domain", Json.str(cur, "domain", "CS/IT")));
      ps.setString(6, Json.str(b, "targetRole", Json.str(cur, "targetRole", "")));
      ps.setString(7, Json.str(b, "targetCompany", Json.str(cur, "targetCompany", "")));
      ps.setDouble(8, Json.num(b, "cgpa", Json.num(cur, "cgpa", 0)));
      ps.setInt(9, userId);
      ps.executeUpdate();
    }
  }

  /** Verifies the current password, stores the new hash and returns the new-hash helpers need. */
  public static String changePassword(int userId, String current, String next, String email) {
    try {
      String stored;
      try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT password_hash FROM users WHERE id=?")) {
        ps.setInt(1, userId);
        ResultSet r = ps.executeQuery();
        if (!r.next()) return "Account not found.";
        stored = r.getString("password_hash");
      }
      if (!verify(current, stored)) return "Your current password is not correct.";
      String weak = passwordProblem(next, email);
      if (weak != null) return weak;
      try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("UPDATE users SET password_hash=? WHERE id=?")) {
        ps.setString(1, hash(next));
        ps.setInt(2, userId);
        ps.executeUpdate();
      }
      audit(email, null, "password_changed", "password updated by the account owner");
      return null;   // null = success
    } catch (Exception e) {
      return "The password could not be updated.";
    }
  }

  public static void touch(int userId) {
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("UPDATE users SET last_active=? WHERE id=?")) {
      ps.setString(1, Time.now());
      ps.setInt(2, userId);
      ps.executeUpdate();
    } catch (SQLException ignored) { }
  }

  /** Role check used by every mentor/admin route. */
  public static boolean hasRole(JsonObject user, String... roles) {
    if (user == null) return false;
    String r = Json.str(user, "role", "");
    for (String want : roles) if (want.equals(r)) return true;
    return false;
  }

  /** Full user row exposed to the client. Never includes the password hash. */
  public static JsonObject row(ResultSet r) throws SQLException {
    return Json.obj(
        "id", r.getInt("id"),
        "name", r.getString("name"),
        "email", r.getString("email"),
        "role", r.getString("role"),
        "college", r.getString("college"),
        "branch", r.getString("branch"),
        "graduationYear", r.getInt("graduation_year"),
        "domain", r.getString("domain"),
        "targetRole", r.getString("target_role"),
        "targetCompany", r.getString("target_company"),
        "cgpa", r.getDouble("cgpa"),
        "createdAt", r.getString("created_at"),
        "lastActive", r.getString("last_active"));
  }

  /** Generic snake_case row mapper (used by list endpoints). */
  public static JsonObject genericRow(ResultSet r) throws SQLException {
    JsonObject o = new JsonObject();
    ResultSetMetaData m = r.getMetaData();
    for (int i = 1; i <= m.getColumnCount(); i++) {
      String label = m.getColumnLabel(i);
      Object v = r.getObject(i);
      if (v == null) o.add(label, null);
      else if (v instanceof Number) o.addProperty(label, (Number) v);
      else o.addProperty(label, String.valueOf(v));
    }
    return o;
  }
}
