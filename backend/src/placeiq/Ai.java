package placeiq;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * AI features sit behind this interface. The default provider is fully offline and deterministic;
 * set PLACEIQ_AI_KEY (and optionally PLACEIQ_AI_URL / PLACEIQ_AI_MODEL) to use a hosted model.
 * If the hosted call fails for any reason we silently fall back to the offline rubric, so the
 * product never depends on the network.
 */
public interface Ai {

  JsonObject scoreAnswer(JsonObject rubric, String question, String answer, String mode);

  JsonObject generateInterviewQuestion(String mode, String company, String role, JsonObject profile);

  JsonArray resumeSuggestions(JsonObject analysis);

  static Ai provider() {
    String key = System.getenv("PLACEIQ_AI_KEY");
    return key == null || key.isBlank() ? new Offline() : new Hosted(key);
  }

  // ---------------- offline deterministic provider ----------------

  final class Offline implements Ai {
    @Override public JsonObject scoreAnswer(JsonObject rubric, String question, String answer, String mode) {
      JsonArray keywords = Json.array(rubric, "keywords");
      int minWords = Json.intOf(rubric, "minWords", 30);
      int maxWords = Json.intOf(rubric, "maxWords", 400);
      double keywordHit = Scoring.rubricScore(keywords, answer, minWords, maxWords);
      String text = answer == null ? "" : answer.toLowerCase();
      int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;

      double relevance = Json.clamp(keywordHit * 100 * 1.05, 0, 100);
      double structure = Json.clamp(keywordHit * 100 * 0.9 + whitespaceSignal(answer) * 15, 0, 100);
      double clarity = Json.clamp(20 + Math.min(words, 140) / 140.0 * 55 + punctuationSignal(answer) * 10, 0, 100);
      double depth = Json.clamp(depthSignal(text, rubric) * 100, 0, 100);
      double overall = (relevance + structure + clarity + depth) / 4.0;

      JsonArray feedback = new JsonArray();
      if (words < minWords) feedback.add("Answer is short (" + words + " words). Aim for " + minWords + "-" + maxWords + " words with one concrete example.");
      if (keywordHit < 0.5) feedback.add("Missed key ideas the interviewer listens for: " + missingKeywords(keywords, text) + ".");
      if (whitespaceSignal(answer) < 1) feedback.add("Structure it explicitly - name the situation, your action, and the result.");
      if (depthSignal(text, rubric) < 0.5) feedback.add("Add a measurable outcome (numbers, %, time saved) to show depth.");
      if (feedback.size() == 0) feedback.add("Strong answer: relevant, structured and specific. Keep the same shape for the next one.");
      if (mode != null && mode.equalsIgnoreCase("technical"))
        feedback.add("Follow up on the trade-offs: state time/space complexity and one alternative approach.");

      return Json.obj("relevance", round(relevance), "structure", round(structure), "clarity", round(clarity),
          "depth", round(depth), "overall", round(overall), "words", words,
          "feedback", feedback, "provider", "offline-rubric",
          "modelAnswer", Json.str(rubric, "modelAnswer", ""));
    }

    @Override public JsonObject generateInterviewQuestion(String mode, String company, String role, JsonObject profile) {
      JsonObject companyData = Content.company(company);
      JsonArray pool = new JsonArray();
      if ("hr".equalsIgnoreCase(mode) || "behavioral".equalsIgnoreCase(mode)) {
        pool = Json.array(companyData, "hrQuestions");
        if (pool.size() == 0) {
          String[] generic = {
            "Tell me about yourself in two minutes.",
            "Describe a time you worked under a deadline. What did you do differently?",
            "What is one weakness you are actively fixing, and what is your evidence?",
            "Tell me about a conflict in a team project and how you resolved it.",
            "Why this role, and where do you see yourself in three years?"};
          for (String q : generic) pool.add(q);
        }
      } else {
        for (JsonElement e : Json.array(companyData, "codingPatterns")) {
          JsonObject p = e.getAsJsonObject();
          pool.add("(" + Json.str(p, "topic", "Technical") + ") " + Json.str(p, "sample", ""));
        }
        for (JsonElement e : Json.array(companyData, "aptitudePatterns")) {
          JsonObject p = e.getAsJsonObject();
          pool.add("(" + Json.str(p, "topic", "Aptitude") + ") " + Json.str(p, "sample", ""));
        }
        pool.add("Walk me through your final-year project: the problem, design decisions and one thing you would rebuild.");
        pool.add("Explain the difference between an interface and an abstract class with an example from your own code.");
      }
      // deterministic rotation: same student gets the next question each attempt
      String seed = (Json.str(profile, "email", "x") + mode + Json.intOf(profile, "id", 0)).hashCode() + "";
      int idx = Math.abs(seed.hashCode() + Json.str(profile, "id", "").hashCode()) % pool.size();
      JsonObject out = Json.obj("prompt", pool.get(idx).getAsString(), "provider", "offline-rubric",
          "topic", "hr".equalsIgnoreCase(mode) ? "HR" : "Technical", "poolSize", pool.size());
      JsonObject rubric = new JsonObject();
      rubric.add("keywords", Json.arr("result", "team", "I ", "because", "example"));
      rubric.addProperty("minWords", "technical".equalsIgnoreCase(mode) ? 50 : 60);
      rubric.addProperty("maxWords", 250);
      out.add("rubric", rubric);
      return out;
    }

    @Override public JsonArray resumeSuggestions(JsonObject analysis) {
      JsonArray out = new JsonArray();
      for (JsonElement e : Json.array(analysis, "missingSections"))
        out.add("Add a clearly labelled " + e.getAsString() + " section - ATS parsers look for these headings.");
      double keywordCoverage = Json.num(analysis, "keywordCoverage", 0);
      if (keywordCoverage < 0.5)
        out.add("Mirror the target role's vocabulary: only " + Math.round(keywordCoverage * 100) + "% of expected role keywords appear.");
      if (Json.intOf(analysis, "quantifiedBullets", 0) < 3)
        out.add("Quantify at least three bullets (numbers, %, scale) - recruiters read results, not duties.");
      if (Json.intOf(analysis, "pages", 1) > 1) out.add("Trim to a single page for campus applications.");
      if (Json.num(analysis, "actionVerbRatio", 0) < 0.4)
        out.add("Start each bullet with a strong action verb (built, automated, reduced, led).");
      return out;
    }

    private static String missingKeywords(JsonArray keywords, String text) {
      StringBuilder sb = new StringBuilder();
      for (JsonElement k : keywords) if (!text.contains(k.getAsString().toLowerCase())) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(k.getAsString());
      }
      return sb.length() == 0 ? "none" : sb.toString();
    }

    private static double whitespaceSignal(String answer) {
      if (answer == null) return 0;
      int paras = answer.split("\n\\s*\n").length;
      boolean hasFirst = answer.toLowerCase().contains("first") || answer.toLowerCase().contains("situation");
      boolean hasResult = answer.toLowerCase().contains("result") || answer.toLowerCase().contains("outcome");
      return Math.min(1, (paras >= 2 ? 0.4 : 0) + (hasFirst ? 0.3 : 0) + (hasResult ? 0.3 : 0));
    }

    private static double punctuationSignal(String answer) {
      if (answer == null || answer.isBlank()) return 0;
      long sentences = answer.chars().filter(ch -> ch == '.' || ch == '?' || ch == '!').count();
      return sentences >= 3 ? 1 : sentences / 3.0;
    }

    private static double depthSignal(String text, JsonObject rubric) {
      boolean numbers = text.matches("(?s).*\\d.*");
      boolean tradeoff = text.contains("trade-off") || text.contains("tradeoff") || text.contains("complexity")
          || text.contains("alternative") || text.contains("because");
      boolean example = text.contains("example") || text.contains("for instance") || text.contains("in my project");
      return (numbers ? 0.4 : 0) + (tradeoff ? 0.35 : 0) + (example ? 0.25 : 0);
    }

    private static double round(double v) { return Math.round(v * 10.0) / 10.0; }
  }

  // ---------------- optional hosted provider ----------------

  final class Hosted implements Ai {
    private final String key, url, model;

    Hosted(String key) {
      this.key = key;
      this.url = System.getenv().getOrDefault("PLACEIQ_AI_URL", "https://api.openai.com/v1/chat/completions");
      this.model = System.getenv().getOrDefault("PLACEIQ_AI_MODEL", "gpt-4o-mini");
    }

    private String complete(String system, String user) throws IOException {
      JsonObject body = Json.obj("model", model, "temperature", 0.4,
          "messages", Json.arr(Json.obj("role", "system", "content", system), Json.obj("role", "user", "content", user)),
          "response_format", Json.obj("type", "json_object"));
      HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
      con.setRequestMethod("POST");
      con.setConnectTimeout(6000);
      con.setReadTimeout(20000);
      con.setDoOutput(true);
      con.setRequestProperty("Content-Type", "application/json");
      con.setRequestProperty("Authorization", "Bearer " + key);
      try (OutputStream os = con.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
      if (con.getResponseCode() >= 300) throw new IOException("AI provider returned " + con.getResponseCode());
      try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return Json.parse(sb.toString()).getAsJsonArray("choices").get(0).getAsJsonObject()
            .getAsJsonObject("message").get("content").getAsString();
      }
    }

    @Override public JsonObject scoreAnswer(JsonObject rubric, String question, String answer, String mode) {
      try {
        String sys = "You are a campus placement interview coach. Score the candidate answer 0-100 on relevance, " +
            "structure, clarity and depth. Reply as JSON with keys relevance, structure, clarity, depth, overall, " +
            "feedback (array of 2-4 short strings) and modelAnswer.";
        String usr = "Mode: " + mode + "\nQuestion: " + question + "\nExpected keywords: " + Json.array(rubric, "keywords") +
            "\nCandidate answer:\n" + answer;
        JsonObject out = Json.parse(complete(sys, usr));
        out.addProperty("provider", "hosted:" + model);
        return out;
      } catch (Exception e) {
        JsonObject fallback = new Offline().scoreAnswer(rubric, question, answer, mode);
        fallback.addProperty("fallbackReason", "hosted provider unavailable, used offline rubric");
        return fallback;
      }
    }

    @Override public JsonObject generateInterviewQuestion(String mode, String company, String role, JsonObject profile) {
      try {
        String sys = "Generate one realistic " + mode + " interview question for a campus placement at " + company +
            " for the role " + role + ". Reply as JSON: {prompt, topic, rubric:{keywords:[], minWords, maxWords, modelAnswer}}.";
        JsonObject out = Json.parse(complete(sys, "Student profile: " + profile));
        out.addProperty("provider", "hosted:" + model);
        return out;
      } catch (Exception e) {
        return new Offline().generateInterviewQuestion(mode, company, role, profile);
      }
    }

    @Override public JsonArray resumeSuggestions(JsonObject analysis) {
      try {
        String out = complete("You are an ATS resume reviewer. Reply as JSON {suggestions:[...] }",
            "Resume analysis: " + analysis);
        return Json.array(Json.parse(out), "suggestions");
      } catch (Exception e) {
        return new Offline().resumeSuggestions(analysis);
      }
    }
  }
}
