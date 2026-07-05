package launcher;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * HTTP client for TESUJI server API.
 * Uses only Java 8 built-in classes (no external dependencies).
 */
public class TesujiClient {

    private final String baseUrl;
    private final String token;
    private static final int TIMEOUT = 30000; // 30s

    public TesujiClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    /**
     * GET /api/divisions
     * Returns list of divisions: [{id, name}, ...]
     */
    public List<Division> getDivisions() throws IOException {
        String json = httpGet("/api/divisions");
        List<Division> result = new ArrayList<Division>();

        // Parse: { "success": true, "divisions": [{...}, ...] }
        String divisionsArray = extractJsonArray(json, "divisions");
        if (divisionsArray == null) return result;

        List<String> objects = splitJsonArray(divisionsArray);
        for (String obj : objects) {
            String id = extractJsonString(obj, "id");
            String name = extractJsonString(obj, "name");
            if (id != null) {
                result.add(new Division(id, name != null ? name : id));
            }
        }
        return result;
    }

    /**
     * GET /api/divisions/:id/matches?round=:round
     * Returns match data for a specific division and round.
     */
    public MatchData getMatches(String divisionId, String round) throws IOException {
        String json = httpGet("/api/divisions/" + divisionId + "/matches?round=" + round);

        MatchData data = new MatchData();
        data.divisionId = divisionId;
        data.round = round;

        // Parse rounds list
        String roundsArray = extractJsonArray(json, "rounds");
        if (roundsArray != null) {
            data.rounds = new ArrayList<String>();
            List<String> items = splitJsonArrayStrings(roundsArray);
            for (String s : items) data.rounds.add(s);
        }

        data.currentRound = extractJsonString(json, "currentRound");

        // Parse matches array
        String matchesArray = extractJsonArray(json, "matches");
        if (matchesArray != null) {
            data.matches = new ArrayList<MatchInfo>();
            List<String> objects = splitJsonArray(matchesArray);
            for (String obj : objects) {
                MatchInfo m = new MatchInfo();
                m.table = extractJsonString(obj, "table");
                m.black = extractJsonString(obj, "black");
                m.white = extractJsonString(obj, "white");
                m.result = extractJsonString(obj, "result");
                m.round = extractJsonString(obj, "round");
                m.submittedBy = extractJsonString(obj, "submittedBy");
                String isForced = extractJsonValue(obj, "isForced");
                m.isForced = "true".equals(isForced);
                data.matches.add(m);
            }
        }

        return data;
    }

    /**
     * Check connection to TESUJI server
     */
    public boolean testConnection() {
        try {
            httpGet("/api/divisions");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== HTTP ====================

    private String httpGet(String path) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);

        // Auth header — TESUJI uses x-admin-token
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("x-admin-token", token);
        }

        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("HTTP " + status + " from " + path);
        }

        BufferedReader br = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8")
        );
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        conn.disconnect();
        return sb.toString();
    }

    private String httpPost(String path, String jsonBody) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("x-admin-token", token);
        }
        byte[] body = jsonBody.getBytes("UTF-8");
        conn.getOutputStream().write(body);
        conn.getOutputStream().flush();

        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("HTTP " + status + " from POST " + path);
        }
        BufferedReader br = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8")
        );
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();
        return sb.toString();
    }

    private String httpDelete(String path) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("x-admin-token", token);
        }
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("HTTP " + status + " from DELETE " + path);
        }
        BufferedReader br = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8")
        );
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();
        return sb.toString();
    }

    // ==================== Export APIs ====================

    /**
     * Ensure division exists on TESUJI. Creates if not found.
     */
    public void ensureDivision(String id, String name) throws IOException {
        List<Division> existing = getDivisions();
        for (Division d : existing) {
            if (d.id.equals(id)) {
                System.out.println("[TesujiClient] Division " + id + " already exists");
                return;
            }
        }
        String json = "{\"id\":\"" + escJson(id) + "\",\"name\":\"" + escJson(name) + "\"}";
        httpPost("/api/divisions", json);
        System.out.println("[TesujiClient] Created division " + id + ": " + name);
    }

    /**
     * DELETE /api/divisions/:id/rounds/:round
     * Clear existing round before re-uploading.
     */
    public void deleteRound(String divisionId, String round) throws IOException {
        httpDelete("/api/divisions/" + divisionId + "/rounds/" + round);
    }

    /**
     * POST /api/divisions/:id/matches
     * Upload pairings for a round.
     */
    public void exportPairings(String divisionId, String round, List<ExportMatch> matches) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\"round\":\"").append(escJson(round)).append("\",\"matches\":[");
        for (int i = 0; i < matches.size(); i++) {
            ExportMatch m = matches.get(i);
            if (i > 0) json.append(",");
            json.append("{\"table\":\"").append(escJson(m.table)).append("\"");
            json.append(",\"black\":\"").append(escJson(m.black)).append("\"");
            json.append(",\"white\":\"").append(escJson(m.white)).append("\"");
            json.append(",\"blackScore\":").append(m.blackScore == null ? "null" : "\"" + escJson(m.blackScore) + "\"");
            json.append(",\"whiteScore\":").append(m.whiteScore == null ? "null" : "\"" + escJson(m.whiteScore) + "\"").append("}");
        }
        json.append("]}");
        httpPost("/api/divisions/" + divisionId + "/matches", json.toString());
    }

    /**
     * POST /api/divisions/:id/standings
     * Upload wall list / standings table.
     */
    public void exportStandings(String divisionId, List<String> headers, List<List<String>> rows) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\"standings\":{\"headers\":[");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escJson(headers.get(i))).append("\"");
        }
        json.append("],\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(",");
            json.append("[");
            List<String> row = rows.get(i);
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) json.append(",");
                json.append("\"").append(escJson(row.get(j))).append("\"");
            }
            json.append("]");
        }
        json.append("]}}");
        httpPost("/api/divisions/" + divisionId + "/standings", json.toString());
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t");
    }

    // ==================== Simple JSON Parser ====================
    // (No dependencies — lightweight parsing for known structures)

    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx = json.indexOf(':', idx + search.length());
        if (idx < 0) return null;
        idx++;
        // Skip whitespace
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;

        if (json.charAt(idx) == '"') {
            // String value
            int start = idx + 1;
            int end = start;
            while (end < json.length()) {
                if (json.charAt(end) == '\\') { end += 2; continue; }
                if (json.charAt(end) == '"') break;
                end++;
            }
            return unescapeJson(json.substring(start, end));
        } else if (json.charAt(idx) == 'n') {
            return null; // null
        } else {
            // Number or boolean
            int start = idx;
            while (idx < json.length() && json.charAt(idx) != ',' && json.charAt(idx) != '}'
                   && json.charAt(idx) != ']') idx++;
            return json.substring(start, idx).trim();
        }
    }

    static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx = json.indexOf(':', idx + search.length());
        if (idx < 0) return null;
        idx++;
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;
        int start = idx;
        if (json.charAt(idx) == '"') {
            start++;
            int end = start;
            while (end < json.length()) {
                if (json.charAt(end) == '\\') { end += 2; continue; }
                if (json.charAt(end) == '"') break;
                end++;
            }
            return unescapeJson(json.substring(start, end));
        }
        while (idx < json.length() && json.charAt(idx) != ',' && json.charAt(idx) != '}'
               && json.charAt(idx) != ']') idx++;
        return json.substring(start, idx).trim();
    }

    static String extractJsonArray(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx = json.indexOf('[', idx);
        if (idx < 0) return null;
        int depth = 0;
        int start = idx;
        for (int i = idx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return json.substring(start + 1, i); }
        }
        return null;
    }

    static List<String> splitJsonArray(String arrayContent) {
        List<String> result = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        boolean inString = false;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '\\' && inString) { i++; continue; }
            if (c == '"') inString = !inString;
            if (!inString) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    String part = arrayContent.substring(start, i).trim();
                    if (!part.isEmpty()) result.add(part);
                    start = i + 1;
                }
            }
        }
        String last = arrayContent.substring(start).trim();
        if (!last.isEmpty()) result.add(last);
        return result;
    }

    static List<String> splitJsonArrayStrings(String arrayContent) {
        List<String> result = new ArrayList<String>();
        boolean inString = false;
        int start = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '\\' && inString) { i++; continue; }
            if (c == '"') {
                if (!inString) { start = i + 1; inString = true; }
                else { result.add(arrayContent.substring(start, i)); inString = false; }
            }
        }
        return result;
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\t", "\t");
    }

    // ==================== Data Classes ====================

    public static class Division {
        public String id;
        public String name;
        public Division(String id, String name) { this.id = id; this.name = name; }
        public String toString() { return name + " (" + id + ")"; }
    }

    public static class MatchData {
        public String divisionId;
        public String round;
        public String currentRound;
        public List<String> rounds;
        public List<MatchInfo> matches;
    }

    public static class MatchInfo {
        public String table;
        public String black;
        public String white;
        public String result; // "1-0", "0-1", "?-?" etc.
        public String round;
        public String submittedBy;
        public boolean isForced;
    }

    public static class ExportMatch {
        public String table;
        public String black;
        public String white;
        // McMahon score entering this round, formatted exactly as MacMahon's own
        // pairing display shows it in "(...)" — getScoreDisplayString(
        // getScoreAfterRound(currentRound-1)). A String, not int, because it may
        // carry half/quarter points (e.g. "1½" from a jigo). null = unknown.
        public String blackScore;
        public String whiteScore;
        public ExportMatch(String table, String black, String white, String blackScore, String whiteScore) {
            this.table = table; this.black = black; this.white = white;
            this.blackScore = blackScore; this.whiteScore = whiteScore;
        }
    }
}
