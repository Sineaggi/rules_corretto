package corretto.update;

import java.io.StringReader;
import java.util.List;
import java.util.Set;

public final class VersionInfoTest {
    private static final String SAMPLE = """
        {
            "supported_lts_releases": [8, 11, 17, 21, 25],
            "supported_feature_releases": [26],
            "preview_releases": [],
            "end_of_life_releases": [15, 16, 18, 19, 20, 22, 23, 24]
        }
        """;

    public static void main(String[] args) throws Exception {
        VersionInfo info = VersionInfo.parse(new StringReader(SAMPLE));
        Check.eq(List.of(8, 11, 17, 21, 25), info.lts());
        Check.eq(List.of(26), info.feature());
        Check.eq(List.of(8, 11, 17, 21, 25), info.majors(false));
        Check.eq(List.of(8, 11, 17, 21, 25, 26), info.majors(true));

        Check.eq("", VersionInfo.lifecycleReport(Set.of(8, 11), Set.of(8, 11)));
        String report = VersionInfo.lifecycleReport(Set.of(8, 11, 17), Set.of(11, 17, 29));
        Check.isTrue(report.contains("added: 29"), "report should mention addition: " + report);
        Check.isTrue(report.contains("removed (EOL): 8"), "report should mention removal: " + report);
        System.out.println("VersionInfoTest OK");
    }
}
