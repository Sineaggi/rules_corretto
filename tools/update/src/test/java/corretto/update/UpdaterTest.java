package corretto.update;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class UpdaterTest {

    private static final String V = "21.0.12.8.1";

    private static String entry(String resource) {
        return "{\"checksum_sha256\": \"sha-" + resource.hashCode() + "\", \"resource\": \"" + resource + "\"}";
    }

    private static String indexmap() {
        String r = "/downloads/resources/" + V + "/amazon-corretto-" + V;
        return "{"
            + "\"linux\": {\"x64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry(r + "-linux-x64.tar.gz") + "}}},"
            +            "\"aarch64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry(r + "-linux-aarch64.tar.gz") + "}}}},"
            + "\"alpine\": {\"x64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry(r + "-alpine-linux-x64.tar.gz") + "}}},"
            +             "\"aarch64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry(r + "-alpine-linux-aarch64.tar.gz") + "}}}},"
            + "\"macos\": {\"x64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry(r + "-macosx-x64.tar.gz") + "}}},"
            +            "\"aarch64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry(r + "-macosx-aarch64.tar.gz") + "}}}},"
            + "\"windows\": {\"x64\": {\"jdk\": {\"21\": {\"zip\": " + entry(r + "-windows-x64-jdk.zip") + "}}}}"
            + "}";
    }

    private static final String VERSION_INFO =
        "{\"supported_lts_releases\": [21], \"supported_feature_releases\": []}";

    private static byte[] tarGz(String topDir) throws IOException {
        byte[] header = new byte[512];
        byte[] name = (topDir + "/").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, header, 0, name.length);
        System.arraycopy("00000000000".getBytes(StandardCharsets.US_ASCII), 0, header, 124, 11);
        header[156] = '5';
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        System.arraycopy(String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII),
            0, header, 148, 8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(header);
            gz.write(new byte[1024]);
        }
        return bos.toByteArray();
    }

    private static byte[] zip(String topDir) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry(topDir + "/f"));
            z.write(1);
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    /** Fake CDN: serves metadata plus archive heads whose top dirs match derivation. */
    private static Fetcher fake(Map<String, byte[]> extra) {
        return url -> {
            if (url.equals(Updater.VERSION_INFO_URL)) {
                return new ByteArrayInputStream(VERSION_INFO.getBytes(StandardCharsets.UTF_8));
            }
            if (url.equals(Updater.INDEXMAP_URL)) {
                return new ByteArrayInputStream(indexmap().getBytes(StandardCharsets.UTF_8));
            }
            byte[] body = extra.get(url);
            if (body == null) {
                throw new IOException("unexpected fetch: " + url);
            }
            return new ByteArrayInputStream(body);
        };
    }

    private static Map<String, byte[]> archives(String winTopDir) throws IOException {
        String base = "https://corretto.aws/downloads/resources/" + V + "/amazon-corretto-" + V;
        Map<String, byte[]> m = new HashMap<>();
        m.put(base + "-linux-x64.tar.gz", tarGz("amazon-corretto-" + V + "-linux-x64"));
        m.put(base + "-linux-aarch64.tar.gz", tarGz("amazon-corretto-" + V + "-linux-aarch64"));
        m.put(base + "-alpine-linux-x64.tar.gz", tarGz("amazon-corretto-" + V + "-alpine-linux-x64"));
        m.put(base + "-alpine-linux-aarch64.tar.gz", tarGz("amazon-corretto-" + V + "-alpine-linux-aarch64"));
        m.put(base + "-macosx-x64.tar.gz", tarGz("amazon-corretto-21.jdk"));
        m.put(base + "-macosx-aarch64.tar.gz", tarGz("amazon-corretto-21.jdk"));
        m.put(base + "-windows-x64-jdk.zip", zip(winTopDir));
        return m;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("updater-test");
        Path versions = dir.resolve("versions.bzl");
        Path module = dir.resolve("MODULE.bazel");
        Files.writeString(versions, "# empty\nCORRETTO_JDK_CONFIGS = [\n]\n");
        Files.writeString(module, "module(name = \"rules_corretto\")\n"
            + StarlarkWriter.BEGIN_MARKER + "\nold\n" + StarlarkWriter.END_MARKER + "\n");

        // 1. Fresh state -> changed, 7 entries, lifecycle reports the new major.
        Updater updater = new Updater(fake(archives("jdk21.0.12_8")), versions, module, false);
        Updater.Result result = updater.run();
        Check.isTrue(result.changed(), "fresh state must be a change");
        Check.eq(7L, result.newVersionsBzl().lines()
            .filter(l -> l.contains("name = \"")).count());
        Check.isTrue(result.report().contains("JDK line added: 21"), "lifecycle in report: " + result.report());

        // 2. Write outputs, re-run -> unchanged, and no archive fetches happen
        //    (streamed checks are for changed entries only; fetcher without archives proves it).
        Files.writeString(versions, result.newVersionsBzl());
        Files.writeString(module, result.newModuleBazel());
        Updater.Result second = new Updater(fake(Map.of()), versions, module, false).run();
        Check.isTrue(!second.changed(), "identical state must not be a change");
        Check.eq(result.newVersionsBzl(), second.newVersionsBzl());

        // 3. A derived strip_prefix that doesn't match the streamed archive head fails loudly.
        Files.writeString(versions, "# empty\nCORRETTO_JDK_CONFIGS = [\n]\n");
        boolean threw = false;
        try {
            new Updater(fake(archives("jdkWRONG")), versions, module, false).run();
        } catch (IllegalStateException e) {
            threw = true;
            Check.isTrue(e.getMessage().contains("strip_prefix"),
                "error mentions strip_prefix: " + e.getMessage());
        }
        Check.isTrue(threw, "prefix mismatch must throw");
        System.out.println("UpdaterTest OK");
    }
}
