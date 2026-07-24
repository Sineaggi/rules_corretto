package corretto.update;

import java.nio.file.Path;
import java.util.List;

public final class Main {
    public static void main(String[] args) throws Exception {
        List<String> argList = List.of(args);
        boolean write = argList.contains("--write");
        boolean includeFeature = argList.contains("--include-feature-releases");
        boolean verify = argList.contains("--verify");

        String workspace = System.getenv("BUILD_WORKSPACE_DIRECTORY");
        if (workspace == null) {
            System.err.println("Run via: bazel run //tools/update -- [--write|--verify]");
            System.exit(2);
        }
        Path versions = Path.of(workspace, "corretto", "versions.bzl");
        Path module = Path.of(workspace, "MODULE.bazel");

        Updater updater = new Updater(Fetcher.http(), versions, module, includeFeature);
        Updater.Result result = updater.run();
        System.out.print(result.report());

        if (verify) {
            Verify.run(Fetcher.http(), result.newVersionsBzl());  // Task 11
            System.out.println("verify: all archives match");
        }

        if (result.changed()) {
            if (write) {
                Updater.writeAtomically(versions, result.newVersionsBzl());
                Updater.writeAtomically(module, result.newModuleBazel());
                System.out.println("wrote corretto/versions.bzl and MODULE.bazel");
            } else {
                System.out.println("stale: re-run with --write to update");
                System.exit(1);
            }
        }
    }
}
