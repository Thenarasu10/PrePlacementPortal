package placeiq;

import com.google.gson.*;
import java.util.*;

/**
 * Code execution is behind this interface so a real sandbox can be plugged in later.
 *
 * SAFETY NOTE: the shipped {@link MockRunner} never executes student code - it inspects the
 * submitted source for the patterns described by each question's "checks". That is safe on any
 * host but only gives partial credit. A production runner (Docker + gVisor + no network +
 * cpu/memory/wall-clock limits) can implement this interface without touching the rest of the app;
 * the real test cases in each question's "tests" field are what such a runner should execute.
 */
public interface CodeRunner {

  JsonObject run(JsonObject question, String source);

  /** Safe, dependency-free default: static checks only. */
  final class MockRunner implements CodeRunner {
    @Override public JsonObject run(JsonObject question, String source) {
      JsonArray checks = Json.array(question, "checks");
      String code = source == null ? "" : source;
      double earned = 0, total = 0;
      JsonArray results = new JsonArray();
      for (JsonElement e : checks) {
        JsonObject chk = e.getAsJsonObject();
        double points = Json.num(chk, "points", 0);
        total += points;
        boolean hit = false;
        for (String alt : Json.str(chk, "value", "").split("\\|"))
          if (!alt.isBlank() && code.contains(alt)) hit = true;
        if (hit) earned += points;
        results.add(Json.obj("label", Json.str(chk, "label", ""), "passed", hit, "points", points));
      }
      JsonArray tests = Json.array(question, "tests");
      JsonArray cases = new JsonArray();
      for (JsonElement t : tests) {  // reported as "not executed" - honesty over fake green ticks
        JsonObject tc = t.getAsJsonObject();
        cases.add(Json.obj("input", Json.str(tc, "input", ""), "expected", Json.str(tc, "expected", ""),
            "status", "not-executed"));
      }
      double score = total == 0 ? 0 : earned / total;
      return Json.obj(
          "mode", "mocked",
          "executed", false,
          "score", Math.round(score * 100.0) / 100.0,
          "checks", results,
          "testCases", cases,
          "note", "Static analysis only - no code was executed. Plug a sandboxed runner into CodeRunner to evaluate the " +
                  tests.size() + " hidden test case(s) for real.");
    }
  }
}
