package launcher;

/**
 * Build-time self tests for the launcher's pure logic. Plain main(), no
 * test framework — the project deliberately has zero external dependencies.
 * Run by build.sh / build.ps1 right after compilation (headless); a
 * non-zero exit aborts the build. Not packaged into the shipped jar.
 */
public class SelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testExtractJsonString();
        testExtractJsonArray();
        testSplitJsonArray();
        testEscUnescape();
        testSameDivisionId();
        testGetMajorVersion();
        testCompareTournamentFileNames();
        testDirLooksLikeJavaAtLeastMin();
        testNamesMatch();
        testNormalizeResult();

        System.out.println("SelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ==================== TesujiClient: JSON parser ====================

    private static void testExtractJsonString() {
        eq("plain string", "hello", TesujiClient.extractJsonString("{\"a\":\"hello\"}", "a"));
        eq("escaped quote", "he said \"hi\"", TesujiClient.extractJsonString("{\"a\":\"he said \\\"hi\\\"\"}", "a"));
        eq("thai unicode escape", "ก", TesujiClient.extractJsonString("{\"a\":\"\\u0e01\"}", "a"));
        eq("null value", null, TesujiClient.extractJsonString("{\"a\":null}", "a"));
        eq("number value", "42", TesujiClient.extractJsonString("{\"a\":42}", "a"));
        eq("missing key", null, TesujiClient.extractJsonString("{\"b\":\"x\"}", "a"));
        // Malformed JSON ending in a backslash must not crash (bounds clamp)
        eq("trailing backslash no crash", "x\\", TesujiClient.extractJsonString("{\"a\":\"x\\", "a"));
    }

    private static void testExtractJsonArray() {
        eq("simple array", "1,2,3", TesujiClient.extractJsonArray("{\"a\":[1,2,3]}", "a"));
        eq("nested arrays", "[1],[2]", TesujiClient.extractJsonArray("{\"a\":[[1],[2]]}", "a"));
        eq("missing array", null, TesujiClient.extractJsonArray("{\"b\":[]}", "a"));
    }

    private static void testSplitJsonArray() {
        java.util.List<String> parts = TesujiClient.splitJsonArray("{\"x\":\"a,b\"},{\"y\":2}");
        eq("split count", "2", String.valueOf(parts.size()));
        eq("split keeps comma inside string", "{\"x\":\"a,b\"}", parts.get(0));

        java.util.List<String> strs = TesujiClient.splitJsonArrayStrings("\"one\",\"two\"");
        eq("string array count", "2", String.valueOf(strs.size()));
        eq("string array second", "two", strs.get(1));
    }

    private static void testEscUnescape() {
        eq("escape quotes+newline", "a\\\"b\\nc", TesujiClient.escJson("a\"b\nc"));
        eq("round trip incl. thai", "a\"b\nc\tด", TesujiClient.unescapeJson(TesujiClient.escJson("a\"b\nc\tด")));
        eq("null escapes to empty", "", TesujiClient.escJson(null));
    }

    private static void testSameDivisionId() {
        ok("01 == 1", TesujiClient.sameDivisionId("01", "1"));
        ok("1 == 01", TesujiClient.sameDivisionId("1", "01"));
        ok("exact match", TesujiClient.sameDivisionId("7", "7"));
        ok("trims spaces", TesujiClient.sameDivisionId(" 2", "02 "));
        ok("2 != 10", !TesujiClient.sameDivisionId("2", "10"));
        ok("null != x", !TesujiClient.sameDivisionId(null, "1"));
        ok("non-numeric exact", TesujiClient.sameDivisionId("A", "A"));
        ok("non-numeric different", !TesujiClient.sameDivisionId("A", "B"));
    }

    // ==================== MacMahonLauncher ====================

    private static void testGetMajorVersion() {
        eq("25", "25", String.valueOf(JavaFinder.getMajorVersion("25")));
        eq("25.0.1", "25", String.valueOf(JavaFinder.getMajorVersion("25.0.1")));
        eq("legacy 1.8.0_292", "8", String.valueOf(JavaFinder.getMajorVersion("1.8.0_292")));
        eq("17.0.2", "17", String.valueOf(JavaFinder.getMajorVersion("17.0.2")));
        eq("garbage", "0", String.valueOf(JavaFinder.getMajorVersion("abc")));
    }

    private static void testCompareTournamentFileNames() {
        ok("2 before 10 (numeric, not text)", MacMahonLauncher.compareTournamentFileNames("2 - b.xml", "10 - a.xml") < 0);
        ok("01 before 02", MacMahonLauncher.compareTournamentFileNames("01 - x.xml", "02 - y.xml") < 0);
        ok("same number falls back to name", MacMahonLauncher.compareTournamentFileNames("1 - a.xml", "1 - b.xml") < 0);
        ok("no digits alphabetical", MacMahonLauncher.compareTournamentFileNames("apple.xml", "banana.xml") < 0);
    }

    private static void testDirLooksLikeJavaAtLeastMin() {
        ok("jdk25.0.3_9", JavaFinder.dirLooksLikeJavaAtLeastMin("jdk25.0.3_9"));
        ok("jdk-26 (future major)", JavaFinder.dirLooksLikeJavaAtLeastMin("jdk-26"));
        ok("temurin-25.jre", JavaFinder.dirLooksLikeJavaAtLeastMin("temurin-25.jre"));
        ok("LibericaJDK-25", JavaFinder.dirLooksLikeJavaAtLeastMin("LibericaJDK-25"));
        ok("old jdk1.8.0_292 rejected", !JavaFinder.dirLooksLikeJavaAtLeastMin("jdk1.8.0_292"));
        ok("jdk-17 rejected", !JavaFinder.dirLooksLikeJavaAtLeastMin("jdk-17.0.5"));
        ok("no digits rejected", !JavaFinder.dirLooksLikeJavaAtLeastMin("Edge"));
    }

    // ==================== SyncDialog ====================

    private static void testNamesMatch() {
        ok("exact", SyncDialog.namesMatch("Somchai Jaidee", "Somchai Jaidee"));
        ok("case/whitespace", SyncDialog.namesMatch("somchai  jaidee", "Somchai Jaidee"));
        ok("reversed order", SyncDialog.namesMatch("Jaidee Somchai", "Somchai Jaidee"));
        ok("comma form", SyncDialog.namesMatch("Jaidee, Somchai", "jaidee somchai"));
        ok("partial contains", SyncDialog.namesMatch("สมชาย", "สมชาย ใจดี"));
        ok("different names", !SyncDialog.namesMatch("Somchai", "Prasert"));
        ok("empty matches anything", SyncDialog.namesMatch("", "Somchai"));
    }

    private static void testNormalizeResult() {
        eq("B+ -> 1-0", "1-0", SyncDialog.normalizeResult("B+"));
        eq("w -> 0-1", "0-1", SyncDialog.normalizeResult("w"));
        eq("1-0 passthrough", "1-0", SyncDialog.normalizeResult("1-0"));
        eq("jigo", "1/2-1/2", SyncDialog.normalizeResult("Jigo"));
        eq("half symbol", "1/2-1/2", SyncDialog.normalizeResult("½-½"));
        eq("null -> empty", "", SyncDialog.normalizeResult(null));
    }

    // ==================== assert helpers ====================

    private static void eq(String what, String expected, String actual) {
        boolean same = (expected == null) ? actual == null : expected.equals(actual);
        report(what, same, "expected [" + expected + "] got [" + actual + "]");
    }

    private static void ok(String what, boolean condition) {
        report(what, condition, "condition is false");
    }

    private static void report(String what, boolean pass, String detail) {
        if (pass) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what + " — " + detail);
        }
    }
}