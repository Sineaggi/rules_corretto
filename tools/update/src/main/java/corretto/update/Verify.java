package corretto.update;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Verify {
    private Verify() {}

    private static final Pattern ENTRY = Pattern.compile(
        "sha256 = \"([0-9a-f]{64}|[0-9a-zA-Z-]+)\",\\s*"
            + "strip_prefix = \"([^\"]+)\",\\s*"
            + "urls = \\[\"([^\"]+)\"\\]");

    static void run(Fetcher fetcher, String versionsBzl) {
        Matcher m = ENTRY.matcher(versionsBzl);
        int count = 0;
        while (m.find()) {
            count++;
            String expectedSha = m.group(1);
            String stripPrefix = m.group(2);
            String url = m.group(3);
            String format = url.endsWith(".zip") ? "zip" : "tar.gz";
            try (InputStream raw = fetcher.open(url)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                DigestInputStream din = new DigestInputStream(raw, digest);
                // Read the top-level dir from the front of the stream; the digest
                // wrapper sees every raw byte the header read consumed...
                String topDir = StreamCheck.topLevelDir(din, format);
                // ...then drain the remainder so the digest covers the whole file.
                din.transferTo(OutputStreamSink.NULL);
                String actualSha = HexFormat.of().formatHex(digest.digest());
                if (!expectedSha.equals(actualSha)) {
                    throw new IllegalStateException(
                        "sha256 mismatch for " + url + ": expected " + expectedSha
                            + " got " + actualSha);
                }
                String expectedTop = StreamCheck.firstSegment(stripPrefix);
                if (!expectedTop.equals(topDir)) {
                    throw new IllegalStateException(
                        "strip_prefix mismatch for " + url + ": strip_prefix first segment '"
                            + expectedTop + "' but archive top-level dir is '" + topDir + "'");
                }
            } catch (IOException e) {
                throw new IllegalStateException("verify failed fetching " + url, e);
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
        }
        if (count == 0) {
            throw new IllegalStateException("verify: no entries parsed from versions.bzl");
        }
        System.out.println("verify: " + count + " archives checked");
    }

    /** /dev/null OutputStream (java.io.OutputStream.nullOutputStream() as a constant). */
    private static final class OutputStreamSink {
        static final java.io.OutputStream NULL = java.io.OutputStream.nullOutputStream();
    }
}
