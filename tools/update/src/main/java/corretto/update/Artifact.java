package corretto.update;

public record Artifact(
    int major, String os, String arch, String fullVersion, String resource, String sha256) {

    public String fileName() {
        return resource.substring(resource.lastIndexOf('/') + 1);
    }

    public String format() {
        return os.equals("windows") ? "zip" : "tar.gz";
    }
}
