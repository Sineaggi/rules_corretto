package corretto.update;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

public final class VerifyTest {

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

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String versionsBzl(String sha, String stripPrefix, String url) {
        return "CORRETTO_JDK_CONFIGS = [\n    struct(\n"
            + "        name = \"corretto21_linux\",\n"
            + "        prefix = \"corretto\",\n"
            + "        version = \"21\",\n"
            + "        target_compatible_with = [\"@platforms//os:linux\", \"@platforms//cpu:x86_64\"],\n"
            + "        sha256 = \"" + sha + "\",\n"
            + "        strip_prefix = \"" + stripPrefix + "\",\n"
            + "        urls = [\"" + url + "\"],\n"
            + "    ),\n]\n";
    }

    public static void main(String[] args) throws Exception {
        String url = "https://corretto.aws/downloads/resources/x/a.tar.gz";
        byte[] archive = tarGz("top-dir");
        Fetcher fetcher = u -> new ByteArrayInputStream(archive);

        // Happy path.
        Verify.run(fetcher, versionsBzl(sha256(archive), "top-dir", url));

        // Wrong sha.
        boolean threw = false;
        try {
            Verify.run(fetcher, versionsBzl("0".repeat(64), "top-dir", url));
        } catch (IllegalStateException e) {
            threw = true;
            Check.isTrue(e.getMessage().contains("sha256"), "sha error: " + e.getMessage());
        }
        Check.isTrue(threw, "bad sha must throw");

        // Wrong strip_prefix.
        threw = false;
        try {
            Verify.run(fetcher, versionsBzl(sha256(archive), "other-dir", url));
        } catch (IllegalStateException e) {
            threw = true;
            Check.isTrue(e.getMessage().contains("strip_prefix"), "prefix error: " + e.getMessage());
        }
        Check.isTrue(threw, "bad prefix must throw");
        System.out.println("VerifyTest OK");
    }
}
