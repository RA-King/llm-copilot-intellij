package com.llmcopilot.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecretScanTest {

    // Built from parts so the file itself does not trip a secret scanner.
    private static final String AWS = "AKIA" + "IOSFODNN7EXAMPLQ";
    private static final String GITHUB = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
    private static final String STRIPE = "sk_live_" + "4eC39HqLyjWDarjtT1zdp7dc";

    private static List<String> kinds(String line) {
        return SecretScan.scanLine(line).stream().map(SecretScan.Hit::kind).toList();
    }

    @Test void recognisesProviderKeyFormats() {
        assertEquals(List.of("AWS access key"), kinds("String key = \"" + AWS + "\";"));
        assertEquals(List.of("GitHub token"), kinds("token: " + GITHUB));
        assertEquals(List.of("Stripe secret key"), kinds("stripe.key = '" + STRIPE + "'"));
        assertEquals(List.of("private key"), kinds("-----BEGIN OPENSSH PRIVATE KEY-----"));
    }

    @Test void findsAPasswordAssignedInCodeOrHeldInAConnectionString() {
        assertEquals(List.of("hard-coded password"), kinds("db_password = \"Tr0ub4dor&3x\""));
        assertEquals(List.of("password in a connection string"), kinds("url = postgres://app:s3cr3tpw@db.internal:5432/app"));
    }

    @Test void leavesPlaceholdersAndLookupsAlone() {
        assertEquals(List.of(), kinds("password = \"changeme123\""));
        assertEquals(List.of(), kinds("api_key = \"<your-api-key>\""));
        assertEquals(List.of(), kinds("secret: \"${SECRET_FROM_ENV}\""));
        assertEquals(List.of(), kinds("password = System.getenv(\"DB_PASSWORD\")"));
        assertEquals(List.of(), kinds("String sky = \"AKIA is a prefix\";"));
    }

    @Test void neverShowsTheWholeSecret() {
        String preview = SecretScan.scanLine("k = \"" + GITHUB + "\"").get(0).preview();
        assertFalse(preview.contains(GITHUB.substring(4, 30)));
        assertTrue(preview.startsWith("ghp_"));
        assertEquals("*****", SecretScan.mask("short"));
    }

    @Test void numbersLinesFromWhereTheTextStarts() {
        List<SecretScan.Finding> found = SecretScan.scanText("a\nb = \"" + AWS + "\"\r\nc", 40);
        assertEquals(1, found.size());
        assertEquals(41, found.get(0).line());
        assertEquals("AWS access key", found.get(0).kind());
    }

    @Test void reportsOnlyWhatADiffAddsAtItsLineInTheNewFile() {
        String diff = String.join("\n",
            "diff --git a/src/Config.java b/src/Config.java",
            "--- a/src/Config.java",
            "+++ b/src/Config.java",
            "@@ -10,3 +10,4 @@ class Config {",
            "     String region = \"eu-west-1\";",
            "-    String key = \"" + AWS + "\";",
            "+    String key = System.getenv(\"AWS_KEY\");",
            "+    String token = \"" + GITHUB + "\";",
            "     int retries = 3;",
            "diff --git a/.env.example b/.env.example",
            "new file mode 100644",
            "--- /dev/null",
            "+++ b/.env.example",
            "@@ -0,0 +1 @@",
            "+password = \"changeme123\"");
        List<SecretScan.Finding> found = SecretScan.scanDiff(diff);
        assertEquals(1, found.size());
        assertEquals("src/Config.java", found.get(0).file());
        assertEquals(12, found.get(0).line());
        assertTrue(SecretScan.describe(found.get(0)).matches("^Possible GitHub token in plain text \\(ghp_\\*+R8\\)\\. .*"));
    }

    @Test void secretFindingsReadAsReviewErrors() {
        List<CodeReview.Finding> review = SecretScan.asReviewFindings(SecretScan.scanText("key = \"" + AWS + "\"", 3));
        assertEquals(CodeReview.Severity.ERROR, review.get(0).severity());
        assertEquals(3, review.get(0).line());
    }
}
