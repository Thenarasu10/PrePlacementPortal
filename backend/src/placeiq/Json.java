package placeiq;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Small JSON helpers so every layer speaks the same dialect. */
public final class Json {
  public static final Gson GSON = new GsonBuilder().serializeNulls().create();
  public static final Gson PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

  /** Data root; override with -Dplaceiq.data=/path/to/data */
  public static Path dataDir() {
    return Paths.get(System.getProperty("placeiq.data", "data"));
  }

  public static JsonObject obj(Object... kv) {
    JsonObject o = new JsonObject();
    for (int i = 0; i + 1 < kv.length; i += 2) o.add(String.valueOf(kv[i]), toJson(kv[i + 1]));
    return o;
  }

  @SuppressWarnings("unchecked")
  public static JsonElement toJson(Object v) {
    if (v == null) return JsonNull.INSTANCE;
    if (v instanceof JsonElement) return (JsonElement) v;
    if (v instanceof String) return new JsonPrimitive((String) v);
    if (v instanceof Number) return new JsonPrimitive((Number) v);
    if (v instanceof Boolean) return new JsonPrimitive((Boolean) v);
    if (v instanceof Map) {
      JsonObject o = new JsonObject();
      ((Map<String, Object>) v).forEach((k, val) -> o.add(k, toJson(val)));
      return o;
    }
    if (v instanceof Collection) {
      JsonArray a = new JsonArray();
      for (Object x : (Collection<Object>) v) a.add(toJson(x));
      return a;
    }
    return GSON.toJsonTree(v);
  }

  public static JsonArray arr(Object... items) {
    JsonArray a = new JsonArray();
    for (Object i : items) a.add(toJson(i));
    return a;
  }

  public static String str(JsonObject o, String key, String fallback) {
    return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
  }

  public static double num(JsonObject o, String key, double fallback) {
    try { return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : fallback; }
    catch (Exception e) { return fallback; }
  }

  public static boolean bool(JsonObject o, String key, boolean fallback) {
    try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : fallback; }
    catch (Exception e) { return fallback; }
  }

  public static boolean bool(JsonObject o, String key) {
    if (o == null || !o.has(key) || o.get(key).isJsonNull()) return false;
    try { return o.get(key).getAsBoolean(); } catch (Exception e) { return false; }
  }

  public static int intOf(JsonObject o, String key, int fallback) {
    return (int) Math.round(num(o, key, fallback));
  }

  public static JsonArray array(JsonObject o, String key) {
    if (o != null && o.has(key) && o.get(key).isJsonArray()) return o.getAsJsonArray(key);
    return new JsonArray();
  }

  /** Nested object accessor that never returns null. */
  public static JsonObject child(JsonObject o, String key) {
    if (o != null && o.has(key) && o.get(key).isJsonObject()) return o.getAsJsonObject(key);
    return new JsonObject();
  }

  /** Parses any JSON column (object, array or scalar) without forcing a shape. */
  public static JsonElement any(String s) {
    try { return s == null || s.isBlank() ? new JsonObject() : JsonParser.parseString(s); }
    catch (Exception e) { return new JsonObject(); }
  }

  public static JsonElement parseElement(String s) {
    try { return s == null || s.isBlank() ? new JsonArray() : JsonParser.parseString(s); }
    catch (Exception e) { return new JsonArray(); }
  }

  public static JsonObject parse(String s) {
    try { return s == null || s.isBlank() ? new JsonObject() : JsonParser.parseString(s).getAsJsonObject(); }
    catch (Exception e) { return new JsonObject(); }
  }

  public static JsonObject read(Path p) throws IOException {
    return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
  }

  public static String write(Object o) { return PRETTY.toJson(o); }

  public static String round2(double v) { return String.valueOf(Math.round(v * 100.0) / 100.0); }

  /** Clamp helper used by every scoring path. */
  public static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
