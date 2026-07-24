package corretto.update;

import java.util.List;

public final class StarlarkWriter {
    private StarlarkWriter() {}

    public static final String BEGIN_MARKER =
        "# BEGIN GENERATED REPOS - managed by //tools/update, do not edit";
    public static final String END_MARKER = "# END GENERATED REPOS";

    public static String versionsBzl(List<Artifact> artifacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("# GENERATED FILE - do not edit by hand.\n");
        sb.append("# Regenerate with: bazel run //tools/update -- --write\n\n");
        sb.append("CORRETTO_JDK_CONFIGS = [\n");
        for (Artifact a : artifacts) {
            sb.append("    struct(\n");
            sb.append("        name = \"").append(Derive.repoName(a)).append("\",\n");
            sb.append("        prefix = \"").append(Derive.prefix(a)).append("\",\n");
            sb.append("        version = \"").append(a.major()).append("\",\n");
            sb.append("        target_compatible_with = [");
            List<String> tcw = Derive.targetCompatibleWith(a);
            for (int i = 0; i < tcw.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('"').append(tcw.get(i)).append('"');
            }
            sb.append("],\n");
            sb.append("        sha256 = \"").append(a.sha256()).append("\",\n");
            sb.append("        strip_prefix = \"").append(Derive.stripPrefix(a)).append("\",\n");
            sb.append("        urls = [\"").append(Derive.url(a)).append("\"],\n");
            sb.append("    ),\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    public static String moduleBlock(List<Artifact> artifacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("use_repo(\n    corretto,\n");
        for (Artifact a : artifacts) {
            sb.append("    \"").append(Derive.repoName(a)).append("_toolchain_config_repo\",\n");
        }
        sb.append(")\n");
        for (Artifact a : artifacts) {
            sb.append("\nregister_toolchains(\"@")
                .append(Derive.repoName(a))
                .append("_toolchain_config_repo//:all\")\n");
        }
        return sb.toString();
    }

    public static String replaceBlock(String moduleContent, String newBlock) {
        int begin = moduleContent.indexOf(BEGIN_MARKER);
        int end = moduleContent.indexOf(END_MARKER);
        if (begin < 0 || end < 0 || end < begin) {
            throw new IllegalStateException(
                "MODULE.bazel generated-repos markers missing or malformed");
        }
        return moduleContent.substring(0, begin + BEGIN_MARKER.length())
            + "\n" + newBlock
            + moduleContent.substring(end);
    }
}
