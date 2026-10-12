package placeiq;

import com.google.gson.*;
import java.util.*;

/**
 * All scoring maths lives here: per-question award, section/overall percentages,
 * readiness levels, improvement deltas, Placement Readiness Score and the adaptive
 * difficulty model. Swapping the adaptive model means editing only {@link #nextDifficulty}.
 */
public final class Scoring {

  // ---------- per question ----------

  /** Marks awarded for one answered question (supports negative marking). */
  public static double award(JsonObject question, JsonObject response, double negativeMarking) {
    String type = Json.str(question, "type", "mcq");
    JsonArray answer = Json.array(question, "answer");
    JsonArray given = Json.array(response, "answer");
    boolean attempted = !given.isEmpty() && !(given.size() == 1 && given.get(0).isJsonPrimitive()
        && given.get(0).getAsString().isBlank());
    if (!attempted) return 0;

    switch (type) {
      case "coding":
      case "multi":
      case "written":
        break;
      default: { // mcq / short: all or nothing
        boolean correct = sameAnswers(answer, given);
        return correct ? 1.0 : -negativeMarking;
      }
    }

    if ("coding".equals(type)) {
      // mocked runner: award the fraction of static checks the code satisfies (0..1)
      JsonArray checks = Json.array(question, "checks");
      String code = given.size() > 0 ? given.get(0).getAsString() : "";
      if (checks.size() == 0) return 0;
      double got = 0;
      for (JsonElement e : checks) {
        JsonObject chk = e.getAsJsonObject();
        String needle = Json.str(chk, "value", "");
        boolean hit = false;
        for (String alt : needle.split("\\|")) if (!alt.isBlank() && code.contains(alt)) hit = true;
        if (hit) got += Json.num(chk, "points", 0);
      }
      return Json.clamp(got, 0, 1);
    }
    if ("multi".equals(type)) { // exact set match, no partial credit
      return sameSet(answer, given) ? 1.0 : -negativeMarking;
    }
    // written / short-answer rubric
    JsonObject rubric = Json.child(question, "rubric");
    if (rubric == null) return 0;
    JsonArray keywords = Json.array(rubric, "keywords");
    String text = given.size() > 0 ? given.get(0).getAsString() : "";
    return rubricScore(keywords, text, Json.intOf(rubric, "minWords", 0), Json.intOf(rubric, "maxWords", 5000));
  }

  /** Deterministic offline rubric: keyword coverage (80%) + length/spelling sanity (20%). */
  public static double rubricScore(JsonArray keywords, String text, int minWords, int maxWords) {
    if (text == null || text.isBlank()) return 0;
    String lower = text.toLowerCase();
    int words = lower.trim().split("\\s+").length;
    double coverage = keywords.size() == 0 ? 0.5
        : (double) keywords.asList().stream().filter(k -> lower.contains(k.getAsString().toLowerCase())).count() / keywords.size();
    double lengthScore = words >= Math.max(minWords, 20) && words <= maxWords ? 1.0
        : words >= Math.max(minWords, 20) * 0.6 ? 0.6 : 0.3;
    return Json.clamp(0.8 * coverage + 0.2 * lengthScore, 0, 1);
  }

  private static boolean sameAnswers(JsonArray expected, JsonArray given) {
    if (expected.size() != given.size()) return false;
    for (int i = 0; i < expected.size(); i++)
      if (!expected.get(i).getAsString().equalsIgnoreCase(given.get(i).getAsString())) return false;
    return true;
  }

  private static boolean sameSet(JsonArray expected, JsonArray given) {
    Set<String> e = new TreeSet<>(), g = new TreeSet<>();
    expected.forEach(x -> e.add(x.getAsString().toUpperCase()));
    given.forEach(x -> g.add(x.getAsString().toUpperCase()));
    return e.equals(g);
  }

  // ---------- attempt level ----------

  /**
   * Aggregates answers into section stats + overall percentage.
   * Each answered question is worth 1 mark, so sectionPct = awarded / count x 100, clamped to 0..100.
   */
  public static JsonObject aggregate(JsonArray sectionDefs, List<JsonObject> answers) {
    Map<String, double[]> perSection = new LinkedHashMap<>();   // section -> {awarded, count, correct, answered, seconds}
    for (JsonObject a : answers) {
      String section = Json.str(a, "section", "other");
      double[] acc = perSection.computeIfAbsent(section, k -> new double[5]);
      acc[0] += Json.num(a, "awarded", 0);
      acc[1] += Json.num(a, "max", 1);
      acc[2] += Json.num(a, "correct", 0);
      acc[3] += Json.num(a, "answered", 0);
      acc[4] += Json.num(a, "timeSec", 0);
    }
    JsonArray stats = new JsonArray();
    double weightedSum = 0, weightTotal = 0;
    for (JsonElement se : sectionDefs) {
      JsonObject def = se.getAsJsonObject();
      String id = Json.str(def, "id", "");
      double[] acc = perSection.get(id);
      if (acc == null) continue;
      double pct = acc[1] == 0 ? 0 : Json.clamp(acc[0] / acc[1] * 100.0, 0, 100);
      double weight = Json.num(def, "weight", 0.2);
      weightedSum += pct * weight;
      weightTotal += weight;
      JsonObject s = Json.obj(
          "section", id,
          "name", Json.str(def, "name", id),
          "percentage", Math.round(pct * 100.0) / 100.0,
          "readiness", levelFromScore(pct, Db.config()),
          "correct", (int) acc[2],
          "questions", (int) acc[1],
          "accuracy", acc[1] == 0 ? 0 : Math.round(acc[2] / acc[1] * 100.0) / 100.0,
          "avgTimeSec", acc[3] == 0 ? 0 : Math.round(acc[4] / acc[3] * 100.0) / 100.0);
      stats.add(s);
    }
    double overall = weightTotal == 0 ? 0 : weightedSum / weightTotal;
    int correct = 0, total = 0;
    double seconds = 0;
    for (JsonObject a : answers) {
      correct += Json.intOf(a, "correct", 0);
      total++;
      seconds += Json.num(a, "timeSec", 0);
    }
    return Json.obj(
        "sections", stats,
        "overallPct", Math.round(overall * 100.0) / 100.0,
        "readiness", levelFromScore(overall, Db.config()),
        "accuracy", total == 0 ? 0 : Math.round((double) correct / total * 100.0) / 100.0,
        "questions", total,
        "avgTimeSec", total == 0 ? 0 : Math.round(seconds / total * 100.0) / 100.0);
  }

  /** Config-driven readiness banding. Boundaries come from config.readinessThresholds. */
  public static String levelFromScore(double pct, JsonObject config) {
    JsonObject t = Json.child(config, "readinessThresholds");
    double beginnerMax = Json.num(t, "beginnerMax", 45);
    double intermediateMax = Json.num(t, "intermediateMax", 75);
    if (pct < beginnerMax) return "Beginner";
    if (pct < intermediateMax) return "Intermediate";
    return "Advanced";
  }

  // ---------- improvement ----------

  /**
   * Improvement between the first and the latest point of a series.
   * Single attempt => zero delta. Zero baseline => percent change is null (undefined) but the
   * point delta is still reported, so a student who started at 0 is not shown "Infinity%".
   */
  public static JsonObject improvement(List<double[]> series, List<String> labels) {
    JsonObject out = new JsonObject();
    if (series.isEmpty() || series.get(0).length == 0) {
      return Json.obj("attempts", 0, "first", null, "latest", null, "deltaPoints", 0, "percentChange", null,
          "trend", "no-data");
    }
    double first = series.get(0)[0], latest = series.get(series.size() - 1)[0];
    double delta = latest - first;
    Double pct = series.size() < 2 || first <= 0 ? null : Math.round(delta / first * 1000.0) / 10.0;
    String trend = series.size() < 2 ? "single-attempt" : delta > 1 ? "improving" : delta < -1 ? "declining" : "flat";
    return Json.obj(
        "attempts", series.size(),
        "first", Math.round(first * 100.0) / 100.0,
        "latest", Math.round(latest * 100.0) / 100.0,
        "deltaPoints", Math.round(delta * 100.0) / 100.0,
        "percentChange", pct,
        "trend", trend,
        "labels", Json.arr(labels.toArray()));
  }

  /** Ordinary least-squares slope per attempt, used for mentor at-risk detection. */
  public static double slope(List<Double> values) {
    int n = values.size();
    if (n < 2) return 0;
    double sx = 0, sy = 0, sxy = 0, sxx = 0;
    for (int i = 0; i < n; i++) { sx += i; sy += values.get(i); sxy += i * values.get(i); sxx += (double) i * i; }
    double denom = n * sxx - sx * sx;
    return denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
  }

  // ---------- Placement Readiness Score ----------

  /**
   * PRS = weighted composite of assessment, mock test, mock interview, resume and course
   * completion. Missing components are dropped and the remaining weights renormalised, so a
   * first-week student is not punished for lacking a resume report.
   */
  public static JsonObject prs(JsonObject components, JsonObject config) {
    JsonObject weights = Json.child(config, "placementReadinessWeights");
    double sum = 0, weightTotal = 0;
    JsonArray parts = new JsonArray();
    for (String key : new String[]{"assessment", "mockTest", "mockInterview", "resume", "courseCompletion"}) {
      JsonObject c = components.has(key) ? components.getAsJsonObject(key) : null;
      if (c == null) continue;
      double weight = Json.num(weights, key, 0);
      double value = Json.num(c, "value", 0);
      double contribution = weight * value;
      sum += contribution;
      weightTotal += weight;
      parts.add(Json.obj("component", key, "value", Math.round(value * 100.0) / 100.0,
          "weight", weight, "contribution", Math.round(contribution * 100.0) / 100.0,
          "detail", Json.str(c, "detail", "")));
    }
    double score = weightTotal == 0 ? 0 : sum / weightTotal;
    return Json.obj("score", Math.round(score * 100.0) / 100.0, "parts", parts,
        "readiness", levelFromScore(score, config));
  }

  // ---------- adaptive engine ----------

  /** Cut points for the Elo-style ability model (config.adaptive). */
  public static String nextDifficulty(double ability, String previousDifficulty, boolean lastCorrect) {
    if ("rule".equalsIgnoreCase(Json.str(Json.child(Db.config(), "adaptive"), "model", "elo"))) {
      if (!lastCorrect) return "easy".equals(previousDifficulty) ? "easy" : "medium";
      return "easy".equals(previousDifficulty) ? "medium" : "hard";
    }
    if (ability < 0.33) return "easy";
    if (ability < 0.66) return "medium";
    return "hard";
  }

  /** Elo update: beating a harder question moves ability more than beating an easy one. */
  public static double updateAbility(double ability, String difficulty, boolean correct) {
    JsonObject cfg = Json.child(Db.config(), "adaptive");
    double k = Json.num(cfg, "kFactor", 0.35);
    double expected = ability / (ability + difficultyRating(difficulty));
    double actual = correct ? 1.0 : 0.0;
    double next = ability + k * (actual - expected);
    return Json.clamp(next, Json.num(cfg, "abilityFloor", 0), Json.num(cfg, "abilityCeil", 1));
  }

  public static double difficultyRating(String difficulty) {
    switch (difficulty == null ? "medium" : difficulty) {
      case "easy": return 0.3;
      case "hard": return 0.9;
      default: return 0.6;
    }
  }

  /** Weak-skill ranking: impact weight x deficit (deficit = target accuracy - actual accuracy). */
  public static List<JsonObject> rankGaps(List<JsonObject> topicStats, JsonObject skillsById, JsonObject config) {
    JsonObject gapCfg = Json.child(config, "skillGap");
    double target = Json.num(gapCfg, "accuracyTarget", 0.75);
    List<JsonObject> gaps = new ArrayList<>();
    for (JsonObject t : topicStats) {
      double accuracy = Json.num(t, "accuracy", 0);
      double deficit = Math.max(0, target - accuracy);
      if (deficit <= 0.001) continue;
      String skillId = Json.str(t, "skillId", "");
      JsonObject skill = skillsById.has(skillId) ? skillsById.getAsJsonObject(skillId) : null;
      double impact = skill == null ? 0.5 : Json.num(skill, "impact", 0.5);
      double speedPenalty = Json.num(t, "avgTimeSec", 0) > 100 ? 0.05 : 0;
      gaps.add(Json.obj(
          "skillId", skillId,
          "skill", skill == null ? Json.str(t, "topic", skillId) : Json.str(skill, "name", skillId),
          "section", Json.str(t, "section", ""),
          "accuracy", Math.round(accuracy * 1000.0) / 10.0,
          "deficit", Math.round(deficit * 1000.0) / 10.0,
          "impact", impact,
          "attempts", Json.intOf(t, "attempts", 0),
          "severity", Math.round((impact * deficit + speedPenalty) * 1000.0) / 10.0));
    }
    gaps.sort((a, b) -> Double.compare(Json.num(b, "severity", 0), Json.num(a, "severity", 0)));
    return gaps;
  }
}
