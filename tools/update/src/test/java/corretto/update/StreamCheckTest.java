package corretto.update;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class StreamCheckTest {

    /** Builds a minimal one-entry tar.gz. Tar header: 512 bytes, name at 0 (100 bytes),
     * size octal at 124 (12 bytes), typeflag at 156, checksum at 148 (8 bytes). */
    private static byte[] tarGz(String entryName) throws Exception {
        byte[] header = new byte[512];
        byte[] name = entryName.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, header, 0, name.length);
        byte[] size = "00000000000".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        header[156] = '5'; // directory
        // checksum: field treated as spaces, then sum of all bytes, six octal digits + NUL + space
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        byte[] chk = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(chk, 0, header, 148, chk.length);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(header);
            gz.write(new byte[1024]); // end-of-archive blocks
        }
        return bos.toByteArray();
    }

    private static byte[] zip(String entryName) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry(entryName));
            z.write("hi".getBytes(StandardCharsets.US_ASCII));
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        Check.eq("amazon-corretto-21.0.12.8.1-linux-x64",
            StreamCheck.topLevelDir(
                new ByteArrayInputStream(tarGz("amazon-corretto-21.0.12.8.1-linux-x64/")),
                "tar.gz"));
        Check.eq("amazon-corretto-21.jdk",
            StreamCheck.topLevelDir(
                new ByteArrayInputStream(tarGz("amazon-corretto-21.jdk/Contents/Home/bin/java")),
                "tar.gz"));
        Check.eq("jdk21.0.12_8",
            StreamCheck.topLevelDir(
                new ByteArrayInputStream(zip("jdk21.0.12_8/readme.txt")), "zip"));

        Check.eq("amazon-corretto-21.jdk",
            StreamCheck.firstSegment("amazon-corretto-21.jdk/Contents/Home"));
        Check.eq("plain", StreamCheck.firstSegment("plain"));
        System.out.println("StreamCheckTest OK");
    }
}
