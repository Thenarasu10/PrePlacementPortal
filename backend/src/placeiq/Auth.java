package placeiq;

import com.google.gson.JsonObject;
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
      return Arrays.equals(pbkdf2(password, salt, iter), Base64.getDecoder().decode(p[3]));
    } catch (Exception e) { return false; }
  }

  private static byte[] pbkdf2(String pw, byte[] salt, int iter) throws Exception {
    javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(pw.toCharArray(), salt, iter, 256);
    return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
  }

  /** Creates a session row and returns its token. */
  public static String createSession(int userId) throws SQLException {
    String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "INSERT INTO sessions(token,user_id,created_at,expires_at) VALUES(?,?,?,?)")) {
      ps.setString(1, token);
      ps.setInt(2, userId);
      ps.setString(3, Time.now());
      ps.setString(4, Time.nowMinusDays(-7));
      ps.executeUpdate();
    }
    return token;
  }

  public static int login(String email, String password) throws SQLException {
    int id;
    String hash;
    // close the read cursor before any write: SQLite blocks a writer while a reader is open on
    // another connection, which would silently cost the full busy timeout on every sign-in.
    try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
        "SELECT id,password_hash FROM users WHERE email=?")) {
      ps.setString(1, email == null ? "" : email.trim().toLowerCase());
      try (ResultSet r = ps.executeQuery()) {
        if (!r.next()) return -1;
        id = r.getInt("id");
        hash = r.getString("password_hash");
      }
    }
    if (!verify(password, hash)) return -2;
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
