package corretto.update;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public final class StreamCheck {
    private StreamCheck() {}

    /**
     * Reads only the first entry header of the archive and returns the first path
     * segment of its name. The caller closes {@code in} afterwards, which aborts the
     * rest of an HTTP transfer — kilobytes read instead of the full archive.
     */
    public static String topLevelDir(InputStream in, String format) throws IOException {
        switch (format) {
            case "tar.gz": {
                GZIPInputStream gz = new GZIPInputStream(in);
                byte[] header = new byte[512];
                new DataInputStream(gz).readFully(header);
                int len = 0;
                while (len < 100 && header[len] != 0) {
                    len++;
                }
                return firstSegment(new String(header, 0, len, StandardCharsets.US_ASCII));
            }
            case "zip": {
                DataInputStream d = new DataInputStream(in);
                byte[] fixed = new byte[30]; // local file header is 30 bytes
                d.readFully(fixed);
                if (!(fixed[0] == 'P' && fixed[1] == 'K' && fixed[2] == 3 && fixed[3] == 4)) {
                    throw new IOException("not a zip local file header");
                }
                int nameLen = (fixed[26] & 0xff) | ((fixed[27] & 0xff) << 8);
                byte[] name = new byte[nameLen];
                d.readFully(name);
                return firstSegment(new String(name, StandardCharsets.US_ASCII));
            }
            default:
                throw new IllegalArgumentException("unknown format: " + format);
        }
    }

    public static String firstSegment(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }
}
