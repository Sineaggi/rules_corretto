# rules_corretto Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A public BCR module providing Amazon Corretto JDK runtime toolchains selectable via `--java_runtime_version=corretto_<N>`, plus a small Java updater (Gson-only dependency) that regenerates the pinned JDK list from Amazon's published metadata.

**Architecture:** Thin wrapper over rules_java's public `remote_java_repository` API called with `prefix = "corretto"` (or `"corretto_alpine"` for musl). A generated `corretto/versions.bzl` struct list drives a small module extension; MODULE.bazel self-registers lazy-fetch toolchain config repos. The updater fetches `indexmap_with_checksum.json` (one HTTP request supplies permanent URLs and SHA256s), derives strip_prefixes, cheaply verifies them by streaming only archive headers, and rewrites `versions.bzl` plus a marked block in MODULE.bazel.

**Tech Stack:** Bazel 9.2.0 (bzlmod only), rules_java 9.6.1, Java 21 (Gson 2.11.0 as the only third-party jar, pinned via http_jar), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-07-24-rules-corretto-design.md` — read it before starting any task.

## Global Constraints

- Bazel `9.2.0` (`.bazelversion`), `bazel_compatibility = [">=7.4.0"]`, bzlmod only — no WORKSPACE support.
- `bazel_dep(name = "rules_java", version = "9.6.1")`, `bazel_dep(name = "platforms", version = "0.0.11")`.
- `remote_java_repository`'s `version` argument MUST be the bare major string (`"21"`, never `"21.0.12.8.1"`) — rules_java substitutes it unquoted into generated Starlark.
- Register ONLY `<name>_toolchain_config_repo//:all` targets, never the JDK `http_archive` repos (preserves lazy fetch). JDK repos get no `use_repo`.
- Flag prefixes: `corretto` (glibc/macos/windows) and `corretto_alpine` (musl). Repo naming: `corretto<major>_<os>[_aarch64]` with os ∈ {`linux`, `macos`, `win`}; alpine uses `corretto_alpine<major>_linux[_aarch64]`.
- JDK majors come from Amazon's `supported_lts_releases` (currently 8, 11, 17, 21, 25); feature releases only behind `--include-feature-releases`.
- Platform matrix per major (7 entries): linux x64/aarch64, alpine x64/aarch64, macos x64/aarch64, windows x64. A missing expected combination is an updater error; unknown metadata keys are ignored.
- `urls` list contains exactly one URL: `https://corretto.aws` + the indexmap `resource` path (permanent). Never the `downloads/latest/` aliases; no mirrors.
- Updater is Java 21 (`java.net.http`). Its ONLY third-party dependency is Gson 2.11.0, fetched as a pinned `http_jar` (sha256 `57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b`) through a `dev_dependency` module extension — no rules_jvm_external, no JUnit. All JSON reading goes through Gson (`com.google.gson.JsonParser.parseReader`), encapsulated inside `VersionInfo`/`IndexMap`.
- Generated output is deterministic: sort by (major asc, os rank linux<alpine<macos<windows, arch rank x64<aarch64). All file writes are atomic (temp file + move); no partial writes on failure.
- Tests use `java_test(use_testrunner = False)` with plain-main assertion classes — no JUnit dependency.
- Every commit message ends with:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_017zSFhubisFBDdPCYbRF95D`

---

### Task 1: Module skeleton — extension, seed versions.bzl, toolchain registration

**Files:**
- Modify: `MODULE.bazel` (full rewrite)
- Modify: `BUILD.bazel` (root; remove stale commented content)
- Create: `corretto/BUILD.bazel`
- Create: `corretto/extensions.bzl`
- Create: `corretto/versions.bzl` (hand-seeded with 2 real entries; fully generated later in Task 9)
- Create: `.bazelrc`

**Interfaces:**
- Produces: `CORRETTO_JDK_CONFIGS` in `corretto/versions.bzl` — a list of `struct(name, prefix, version, target_compatible_with, sha256, strip_prefix, urls)`. Every later task treats this shape as fixed.
- Produces: module extension `corretto` at `//corretto:extensions.bzl`.
- Produces: MODULE.bazel marker comments `# BEGIN GENERATED REPOS - managed by //tools/update, do not edit` / `# END GENERATED REPOS` that Task 7's rewriter targets. The exact marker strings matter.

- [ ] **Step 1: Fetch real seed data for the two bootstrap entries**

The dev machine is macos/aarch64; seed that platform plus linux/x64 so both local runs and linux CI resolve. Get current values from Amazon's indexmap (this is the updater's own data source — a one-time manual bootstrap):

```bash
curl -s https://raw.githubusercontent.com/corretto/corretto-downloads/main/latest_links/indexmap_with_checksum.json \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
for os_,arch in [('linux','x64'),('macos','aarch64')]:
    e=d[os_][arch]['jdk']['21']['tar.gz']
    print(os_, arch, e['checksum_sha256'], e['resource'])
"
```

Expected output shape (versions/hashes may be newer than this example):

```
linux x64 75faed442d38a89c27f920e45ab24f9f71ff8ca6b732bfea90cdb500decd3c6b /downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-linux-x64.tar.gz
macos aarch64 <sha256> /downloads/resources/<ver>/amazon-corretto-<ver>-macosx-aarch64.tar.gz
```

Use the printed values in Step 3. Note the linux strip_prefix is the filename stem; the macos strip_prefix is always `amazon-corretto-21.jdk/Contents/Home` regardless of patch version.

- [ ] **Step 2: Rewrite MODULE.bazel**

```starlark
module(
    name = "rules_corretto",
    version = "0.0.0",
    compatibility_level = 1,
    bazel_compatibility = [">=7.4.0"],
)

bazel_dep(name = "rules_java", version = "9.6.1")
bazel_dep(name = "platforms", version = "0.0.11")

bazel_dep(name = "bazel_skylib", version = "1.7.1", dev_dependency = True)

corretto = use_extension("//corretto:extensions.bzl", "corretto")

# BEGIN GENERATED REPOS - managed by //tools/update, do not edit
use_repo(
    corretto,
    "corretto21_linux_toolchain_config_repo",
    "corretto21_macos_aarch64_toolchain_config_repo",
)

register_toolchains("@corretto21_linux_toolchain_config_repo//:all")

register_toolchains("@corretto21_macos_aarch64_toolchain_config_repo//:all")
# END GENERATED REPOS
```

- [ ] **Step 3: Create corretto/versions.bzl with the two seed entries**

Substitute the sha256/resource values printed in Step 1 (linux strip_prefix = filename minus `.tar.gz`):

```starlark
# GENERATED FILE - do not edit by hand.
# Regenerate with: bazel run //tools/update -- --write
# (This seed copy is hand-written until //tools/update exists; see Task 9.)

CORRETTO_JDK_CONFIGS = [
    struct(
        name = "corretto21_linux",
        prefix = "corretto",
        version = "21",
        target_compatible_with = ["@platforms//os:linux", "@platforms//cpu:x86_64"],
        sha256 = "<linux x64 sha256 from Step 1>",
        strip_prefix = "amazon-corretto-<ver>-linux-x64",
        urls = ["https://corretto.aws/downloads/resources/<ver>/amazon-corretto-<ver>-linux-x64.tar.gz"],
    ),
    struct(
        name = "corretto21_macos_aarch64",
        prefix = "corretto",
        version = "21",
        target_compatible_with = ["@platforms//os:macos", "@platforms//cpu:aarch64"],
        sha256 = "<macos aarch64 sha256 from Step 1>",
        strip_prefix = "amazon-corretto-21.jdk/Contents/Home",
        urls = ["https://corretto.aws/downloads/resources/<ver>/amazon-corretto-<ver>-macosx-aarch64.tar.gz"],
    ),
]
```

- [ ] **Step 4: Create corretto/extensions.bzl**

```starlark
"""Module extension providing Amazon Corretto JDK runtime toolchains."""

load("@rules_java//toolchains:remote_java_repository.bzl", "remote_java_repository")
load("//corretto:versions.bzl", "CORRETTO_JDK_CONFIGS")

def _corretto_impl(mctx):
    for cfg in CORRETTO_JDK_CONFIGS:
        remote_java_repository(
            name = cfg.name,
            prefix = cfg.prefix,
            version = cfg.version,
            target_compatible_with = cfg.target_compatible_with,
            sha256 = cfg.sha256,
            strip_prefix = cfg.strip_prefix,
            urls = cfg.urls,
        )
    return mctx.extension_metadata(reproducible = True)

corretto = module_extension(
    implementation = _corretto_impl,
)
```

- [ ] **Step 5: Create corretto/BUILD.bazel and clean the root BUILD.bazel**

`corretto/BUILD.bazel`:

```starlark
package(default_visibility = ["//visibility:public"])

exports_files([
    "extensions.bzl",
    "versions.bzl",
])
```

Root `BUILD.bazel` — replace entire content with:

```starlark
# Intentionally minimal; see corretto/ and tools/.
```

- [ ] **Step 6: Create .bazelrc**

```
common --java_language_version=21
common --java_runtime_version=corretto_21
common --tool_java_runtime_version=corretto_21
```

(Dogfooding: this repo's own Java code builds and runs on the Corretto toolchains it ships.)

- [ ] **Step 7: Verify toolchain repos resolve**

```bash
bazel query '@corretto21_macos_aarch64_toolchain_config_repo//:all'
```

Expected output includes:

```
@@+corretto+corretto21_macos_aarch64_toolchain_config_repo//:bootstrap_runtime_toolchain
@@+corretto+corretto21_macos_aarch64_toolchain_config_repo//:toolchain
```

(Exact canonical-name spelling may differ slightly; the two toolchain targets must both appear.) Also confirm nothing downloaded a JDK: `bazel query` must complete without fetching `corretto21_*` http_archives.

- [ ] **Step 8: Commit**

```bash
git add MODULE.bazel MODULE.bazel.lock BUILD.bazel .bazelrc corretto/
git commit -m "feat: module skeleton with corretto module extension and seed JDK 21 configs"
```

---

### Task 2: examples/ consumer workspace + first end-to-end run

**Files:**
- Create: `examples/MODULE.bazel`
- Create: `examples/.bazelversion`
- Create: `examples/BUILD.bazel`
- Create: `examples/src/main/java/examples/Hello.java`
- Create: `examples/.bazelrc`

**Interfaces:**
- Consumes: `rules_corretto` module from Task 1 via `local_path_override`.
- Produces: `//examples` is the integration-test workspace CI uses in Task 13. The binary target is `//:hello` inside the `examples` module and prints `vendor=<java.vendor> version=<java.version> home=<java.home>` on one line.

- [ ] **Step 1: Create the example module**

`examples/MODULE.bazel`:

```starlark
module(name = "rules_corretto_examples")

bazel_dep(name = "rules_corretto", version = "0.0.0")
local_path_override(
    module_name = "rules_corretto",
    path = "..",
)

bazel_dep(name = "rules_java", version = "9.6.1")
```

`examples/.bazelversion`:

```
9.2.0
```

`examples/.bazelrc`:

```
common --java_language_version=21
```

`examples/BUILD.bazel`:

```starlark
load("@rules_java//java:defs.bzl", "java_binary")

java_binary(
    name = "hello",
    srcs = ["src/main/java/examples/Hello.java"],
    main_class = "examples.Hello",
)
```

`examples/src/main/java/examples/Hello.java`:

```java
package examples;

public final class Hello {
    public static void main(String[] args) {
        System.out.println(
            "vendor=" + System.getProperty("java.vendor")
                + " version=" + System.getProperty("java.version")
                + " home=" + System.getProperty("java.home"));
    }
}
```

- [ ] **Step 2: Run WITHOUT the flag — verify default is untouched**

```bash
cd examples && bazel run //:hello
```

Expected: prints a vendor that is the local JDK's (NOT necessarily Amazon) — proves depending on rules_corretto changes nothing until opted in.

- [ ] **Step 3: Run WITH the flag — verify Corretto is selected**

```bash
cd examples && bazel run //:hello --java_runtime_version=corretto_21
```

Expected (first run downloads the ~200 MB Corretto 21 archive once):

```
vendor=Amazon.com Inc. version=21.0.<x> home=...corretto21_macos_aarch64...
```

- [ ] **Step 4: Commit**

```bash
git add examples/
git commit -m "feat: example consumer workspace exercising corretto_21 end to end"
```

---

### Task 3: Updater scaffolding — Gson dependency, test harness, dogfooded build

> Revised 2026-07-24: the original task vendored a third-party JsonParser whose
> only findable upstream is GPL-3.0-only. Per user decision, all JSON goes
> through Gson instead. The legacy `java/` tree predated git history and is
> already absent from the working tree — there is nothing to move or delete.

**Files:**
- Create: `tools/update/BUILD.bazel`
- Create: `tools/update/gson.bzl`
- Modify: `MODULE.bazel` (dev-dependency extension block, OUTSIDE the generated markers)
- Create: `tools/update/src/test/java/corretto/update/Check.java`
- Create: `tools/update/src/test/java/corretto/update/JsonSmokeTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `@gson//jar` — Gson 2.11.0 on the compile/runtime classpath of `update_lib`. Later tasks parse JSON with `com.google.gson.JsonParser.parseReader(Reader)` and the `JsonObject`/`JsonArray`/`JsonElement` tree API, always inside `VersionInfo`/`IndexMap` — no Gson types escape those classes.
- Produces: test helper `corretto.update.Check` — `Check.eq(Object expected, Object actual)`, `Check.isTrue(boolean cond, String msg)`, both throwing `AssertionError`.
- Produces: BUILD targets `//tools/update:update_lib` (java_library over `src/main/java/corretto/update/*.java`, deps `@gson//jar`) and `//tools/update:test_lib`; the `java_test` pattern (`use_testrunner = False`, plain main) all later tests copy.

- [ ] **Step 1: Create the Gson repository extension**

`tools/update/gson.bzl`:

```starlark
"""Dev-only dependency: Gson as a single pinned jar (Gson has no transitive deps)."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def _gson_impl(mctx):
    http_jar(
        name = "gson",
        sha256 = "57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b",
        urls = ["https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"],
    )
    return mctx.extension_metadata(reproducible = True)

gson_ext = module_extension(
    implementation = _gson_impl,
)
```

Append to `MODULE.bazel`, AFTER the `# END GENERATED REPOS` marker (never inside the markers):

```starlark
gson = use_extension("//tools/update:gson.bzl", "gson_ext", dev_dependency = True)
use_repo(gson, "gson")
```

- [ ] **Step 2: Write the test helper and the failing Gson smoke test**

`tools/update/src/test/java/corretto/update/Check.java`:

```java
package corretto.update;

import java.util.Objects;

public final class Check {
    private Check() {}

    public static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected: " + expected + "\nactual:   " + actual);
        }
    }

    public static void isTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
```

`tools/update/src/test/java/corretto/update/JsonSmokeTest.java`:

```java
package corretto.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;

public final class JsonSmokeTest {
    public static void main(String[] args) {
        JsonObject root = JsonParser.parseReader(new StringReader(
            "{\"a\": [1, 2], \"b\": {\"c\": \"d\"}, \"e\": true}")).getAsJsonObject();
        Check.eq(2, root.getAsJsonArray("a").size());
        Check.eq("d", root.getAsJsonObject("b").get("c").getAsString());
        Check.isTrue(root.get("e").getAsBoolean(), "boolean parses");
        // Streaming note: as of Gson 2.11.0, JsonParser.parseReader(Reader)
        // demands EOF after the value (throws JsonSyntaxException on trailing
        // content), while JsonParser.parseReader(JsonReader) stops after one
        // value. The updater's metadata fetches are complete single-document
        // bodies, so the Reader overload is fine there; anything needing the
        // read-one-value-then-abort property must build the JsonReader itself.
        JsonReader trailing = new JsonReader(new StringReader("{\"x\": 1} TRAILING GARBAGE"));
        JsonObject first = JsonParser.parseReader(trailing).getAsJsonObject();
        Check.eq(1, first.get("x").getAsInt());
        System.out.println("JsonSmokeTest OK");
    }
}
```

- [ ] **Step 3: Write tools/update/BUILD.bazel**

```starlark
load("@rules_java//java:defs.bzl", "java_binary", "java_library", "java_test")

package(default_visibility = ["//visibility:private"])

java_library(
    name = "update_lib",
    srcs = glob(
        ["src/main/java/corretto/update/*.java"],
        allow_empty = True,
    ),
    deps = ["@gson//jar"],
)

java_library(
    name = "test_lib",
    srcs = ["src/test/java/corretto/update/Check.java"],
)

java_test(
    name = "json_smoke_test",
    srcs = ["src/test/java/corretto/update/JsonSmokeTest.java"],
    main_class = "corretto.update.JsonSmokeTest",
    use_testrunner = False,
    deps = [
        ":test_lib",
        "@gson//jar",
    ],
)
```

(`allow_empty = True` because no main-tree sources exist until Task 4; remove nothing later — the glob just starts matching.)

- [ ] **Step 4: Run the test; verify it passes**

```bash
bazel test //tools/update:json_smoke_test --test_output=errors
```

Expected: `PASSED` with output `JsonSmokeTest OK`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: updater scaffolding with pinned gson dependency and plain-main test harness"
```

---

### Task 4: VersionInfo — supported-majors parsing and lifecycle diff

**Files:**
- Create: `tools/update/src/main/java/corretto/update/VersionInfo.java`
- Create: `tools/update/src/test/java/corretto/update/VersionInfoTest.java`
- Modify: `tools/update/BUILD.bazel` (add test target)

**Interfaces:**
- Consumes: `@gson//jar` via `:update_lib` (Task 3). Gson types stay internal to this class.
- Produces: `public record VersionInfo(List<Integer> lts, List<Integer> feature)` with:
  - `public static VersionInfo parse(Reader in)` — parses `version-info.json`.
  - `public List<Integer> majors(boolean includeFeature)` — sorted ascending.
  - `public static String lifecycleReport(Set<Integer> current, Set<Integer> desired)` — human-readable lines `"LTS added: 29"` / `"removed (EOL): 11"`, empty string when no change. Task 9's Main prints this into the PR-facing output.

- [ ] **Step 1: Write the failing test**

`tools/update/src/test/java/corretto/update/VersionInfoTest.java`:

```java
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
```

Add to `tools/update/BUILD.bazel`:

```starlark
java_test(
    name = "version_info_test",
    srcs = ["src/test/java/corretto/update/VersionInfoTest.java"],
    main_class = "corretto.update.VersionInfoTest",
    use_testrunner = False,
    deps = [
        ":test_lib",
        ":update_lib",
    ],
)
```

- [ ] **Step 2: Run to verify it fails**

```bash
bazel test //tools/update:version_info_test --test_output=errors
```

Expected: FAIL — compilation error, `VersionInfo` does not exist.

- [ ] **Step 3: Implement VersionInfo**

`tools/update/src/main/java/corretto/update/VersionInfo.java`:

```java
package corretto.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public record VersionInfo(List<Integer> lts, List<Integer> feature) {

    public static VersionInfo parse(Reader in) {
        JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
        return new VersionInfo(
            ints(root.getAsJsonArray("supported_lts_releases")),
            ints(root.getAsJsonArray("supported_feature_releases")));
    }

    public List<Integer> majors(boolean includeFeature) {
        List<Integer> result = new ArrayList<>(lts);
        if (includeFeature) {
            result.addAll(feature);
        }
        return result.stream().sorted().toList();
    }

    public static String lifecycleReport(Set<Integer> current, Set<Integer> desired) {
        StringBuilder sb = new StringBuilder();
        for (int added : new TreeSet<>(desired)) {
            if (!current.contains(added)) {
                sb.append("JDK line added: ").append(added).append('\n');
            }
        }
        for (int removed : new TreeSet<>(current)) {
            if (!desired.contains(removed)) {
                sb.append("JDK line removed (EOL): ").append(removed).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<Integer> ints(JsonArray array) {
        List<Integer> result = new ArrayList<>();
        for (JsonElement e : array) {
            result.add(e.getAsInt());
        }
        return result;
    }
}
```

(`parse` declares no checked exceptions — Gson throws unchecked `JsonSyntaxException`/`JsonIOException`; also drop the now-unused `throws Exception`-only imports if the compiler flags them.)

- [ ] **Step 4: Run to verify it passes**

```bash
bazel test //tools/update:version_info_test --test_output=errors
```

Expected: PASS. (Note the test asserts `contains("added: 29")` which matches `"JDK line added: 29"`.)

- [ ] **Step 5: Commit**

```bash
git add tools/update
git commit -m "feat: parse Corretto version-info.json and report lifecycle changes"
```

---

### Task 5: Artifact + IndexMap — parse and filter indexmap_with_checksum.json

**Files:**
- Create: `tools/update/src/main/java/corretto/update/Artifact.java`
- Create: `tools/update/src/main/java/corretto/update/IndexMap.java`
- Create: `tools/update/src/test/java/corretto/update/IndexMapTest.java`
- Modify: `tools/update/BUILD.bazel` (add test target)

**Interfaces:**
- Consumes: `@gson//jar` via `:update_lib` (Task 3). Gson types stay internal to IndexMap.
- Produces: `public record Artifact(int major, String os, String arch, String fullVersion, String resource, String sha256)` — `os` ∈ {`linux`, `alpine`, `macos`, `windows`} (indexmap key spelling), `arch` ∈ {`x64`, `aarch64`}, `fullVersion` like `"21.0.12.8.1"`, `resource` like `"/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-linux-x64.tar.gz"`.
- Produces: `public static List<Artifact> parse(Reader in, List<Integer> majors)` in `IndexMap` — filtered to image_type `jdk`, format `tar.gz` (`zip` for windows), the 7-combo matrix; throws `IllegalStateException` naming the missing combo if an expected one is absent; silently skips unknown os/arch keys. Result sorted by (major, os rank linux<alpine<macos<windows, arch rank x64<aarch64).

- [ ] **Step 1: Write the failing test**

`tools/update/src/test/java/corretto/update/IndexMapTest.java`:

```java
package corretto.update;

import java.io.StringReader;
import java.util.List;

public final class IndexMapTest {

    private static String entry(String resource, String sha) {
        return "{\"checksum\": \"m\", \"checksum_sha256\": \"" + sha
            + "\", \"checksum_sha384\": \"x\", \"resource\": \"" + resource + "\"}";
    }

    // Minimal indexmap covering major 21 completely, plus noise that must be ignored:
    // an unknown os (al2023), an unknown arch (x86), a jre image, and a .sig format.
    private static String sample() {
        String v = "21.0.12.8.1";
        return "{"
            + "\"linux\": {"
            +   "\"x64\": {\"jdk\": {\"21\": {"
            +     "\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-linux-x64.tar.gz", "sha-linux-x64") + ","
            +     "\"tar.gz.sig\": " + entry("/x.sig", "nope")
            +   "}},"
            +   "\"jre\": {\"21\": {\"tar.gz\": " + entry("/jre.tar.gz", "nope") + "}}},"
            +   "\"aarch64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-linux-aarch64.tar.gz", "sha-linux-aarch64") + "}}},"
            +   "\"x86\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/x86.tar.gz", "nope") + "}}}"
            + "},"
            + "\"alpine\": {"
            +   "\"x64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-alpine-linux-x64.tar.gz", "sha-alpine-x64") + "}}},"
            +   "\"aarch64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-alpine-linux-aarch64.tar.gz", "sha-alpine-aarch64") + "}}}"
            + "},"
            + "\"macos\": {"
            +   "\"x64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-macosx-x64.tar.gz", "sha-macos-x64") + "}}},"
            +   "\"aarch64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-macosx-aarch64.tar.gz", "sha-macos-aarch64") + "}}}"
            + "},"
            + "\"windows\": {"
            +   "\"x64\": {\"jdk\": {\"21\": {\"zip\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-windows-x64-jdk.zip", "sha-win-x64") + "}}}"
            + "},"
            + "\"al2023\": {\"x64\": {\"jdk\": {\"21\": {\"rpm\": " + entry("/r.rpm", "nope") + "}}}}"
            + "}";
    }

    public static void main(String[] args) throws Exception {
        List<Artifact> artifacts = IndexMap.parse(new StringReader(sample()), List.of(21));
        Check.eq(7, artifacts.size());

        Artifact first = artifacts.get(0);
        Check.eq("linux", first.os());
        Check.eq("x64", first.arch());
        Check.eq(21, first.major());
        Check.eq("21.0.12.8.1", first.fullVersion());
        Check.eq("sha-linux-x64", first.sha256());

        // Deterministic order: linux x64, linux aarch64, alpine x64, alpine aarch64,
        // macos x64, macos aarch64, windows x64.
        Check.eq(
            List.of("linux/x64", "linux/aarch64", "alpine/x64", "alpine/aarch64",
                "macos/x64", "macos/aarch64", "windows/x64"),
            artifacts.stream().map(a -> a.os() + "/" + a.arch()).toList());

        // A missing expected combo is an error naming the combo.
        boolean threw = false;
        try {
            IndexMap.parse(new StringReader(sample()), List.of(21, 17));
        } catch (IllegalStateException e) {
            threw = true;
            Check.isTrue(e.getMessage().contains("17"), "message names the major: " + e.getMessage());
        }
        Check.isTrue(threw, "missing major 17 must throw");
        System.out.println("IndexMapTest OK");
    }
}
```

Add to `tools/update/BUILD.bazel`:

```starlark
java_test(
    name = "index_map_test",
    srcs = ["src/test/java/corretto/update/IndexMapTest.java"],
    main_class = "corretto.update.IndexMapTest",
    use_testrunner = False,
    deps = [
        ":test_lib",
        ":update_lib",
    ],
)
```

- [ ] **Step 2: Run to verify it fails**

```bash
bazel test //tools/update:index_map_test --test_output=errors
```

Expected: FAIL — `Artifact`/`IndexMap` do not exist.

- [ ] **Step 3: Implement Artifact and IndexMap**

`tools/update/src/main/java/corretto/update/Artifact.java`:

```java
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
```

`tools/update/src/main/java/corretto/update/IndexMap.java`:

```java
package corretto.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class IndexMap {
    private IndexMap() {}

    // Expected matrix: os -> archs. Windows aarch64 does not exist upstream.
    private static final List<String> OS_ORDER = List.of("linux", "alpine", "macos", "windows");
    private static final List<String> ARCH_ORDER = List.of("x64", "aarch64");

    private static List<String> archesFor(String os) {
        return os.equals("windows") ? List.of("x64") : ARCH_ORDER;
    }

    public static List<Artifact> parse(Reader in, List<Integer> majors) {
        JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
        List<Artifact> result = new ArrayList<>();
        for (String os : OS_ORDER) {
            for (String arch : archesFor(os)) {
                for (int major : majors) {
                    result.add(lookup(root, os, arch, major));
                }
            }
        }
        result.sort(Comparator
            .comparingInt(Artifact::major)
            .thenComparingInt(a -> OS_ORDER.indexOf(a.os()))
            .thenComparingInt(a -> ARCH_ORDER.indexOf(a.arch())));
        return result;
    }

    private static JsonObject childObject(JsonElement node, String key) {
        if (node == null || !node.isJsonObject()) {
            return null;
        }
        JsonElement child = node.getAsJsonObject().get(key);
        return (child != null && child.isJsonObject()) ? child.getAsJsonObject() : null;
    }

    private static Artifact lookup(JsonObject root, String os, String arch, int major) {
        String format = os.equals("windows") ? "zip" : "tar.gz";
        JsonObject node = childObject(root, os);
        node = childObject(node, arch);
        node = childObject(node, "jdk");
        node = childObject(node, String.valueOf(major));
        JsonObject entry = childObject(node, format);
        if (entry == null) {
            throw new IllegalStateException(
                "indexmap is missing expected combination: " + os + "/" + arch + "/jdk/"
                    + major + "/" + format);
        }
        JsonElement resource = entry.get("resource");
        JsonElement sha256 = entry.get("checksum_sha256");
        if (resource == null || sha256 == null) {
            throw new IllegalStateException(
                "indexmap entry incomplete for " + os + "/" + arch + "/" + major);
        }
        // resource = /downloads/resources/<fullVersion>/<file>
        String[] parts = resource.getAsString().split("/");
        String fullVersion = parts[3];
        return new Artifact(major, os, arch, fullVersion, resource.getAsString(), sha256.getAsString());
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
bazel test //tools/update:index_map_test --test_output=errors
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/update
git commit -m "feat: parse and filter Corretto indexmap into artifact records"
```

---

### Task 6: Derive — URLs, strip_prefixes, repo names, platform constraints

**Files:**
- Create: `tools/update/src/main/java/corretto/update/Derive.java`
- Create: `tools/update/src/test/java/corretto/update/DeriveTest.java`
- Modify: `tools/update/BUILD.bazel` (add test target)

**Interfaces:**
- Consumes: `Artifact` (Task 5).
- Produces (all static on `corretto.update.Derive`, all taking `Artifact a`):
  - `String url(Artifact a)` → `"https://corretto.aws" + a.resource()`
  - `String stripPrefix(Artifact a)` — linux/alpine: filename stem; macos: `amazon-corretto-<major>.jdk/Contents/Home`; windows: `jdk<a>.<b>.<c>_<d>` with JDK 8 special case `jdk1.8.0_<update>`.
  - `String repoName(Artifact a)` → e.g. `corretto21_linux`, `corretto21_macos_aarch64`, `corretto21_win`, `corretto_alpine21_linux_aarch64`.
  - `String prefix(Artifact a)` → `"corretto"` or `"corretto_alpine"`.
  - `List<String> targetCompatibleWith(Artifact a)` → e.g. `["@platforms//os:linux", "@platforms//cpu:x86_64"]`.

- [ ] **Step 1: Write the failing test (golden cases straight from the spec's verified table)**

`tools/update/src/test/java/corretto/update/DeriveTest.java`:

```java
package corretto.update;

import java.util.List;

public final class DeriveTest {

    private static Artifact art(int major, String os, String arch, String fullVersion, String file) {
        return new Artifact(major, os, arch, fullVersion,
            "/downloads/resources/" + fullVersion + "/" + file, "sha");
    }

    public static void main(String[] args) {
        Artifact linux = art(21, "linux", "x64", "21.0.12.8.1",
            "amazon-corretto-21.0.12.8.1-linux-x64.tar.gz");
        Artifact alpine = art(21, "alpine", "aarch64", "21.0.12.8.1",
            "amazon-corretto-21.0.12.8.1-alpine-linux-aarch64.tar.gz");
        Artifact macos = art(21, "macos", "aarch64", "21.0.12.8.1",
            "amazon-corretto-21.0.12.8.1-macosx-aarch64.tar.gz");
        Artifact win = art(21, "windows", "x64", "21.0.12.8.1",
            "amazon-corretto-21.0.12.8.1-windows-x64-jdk.zip");
        Artifact win8 = art(8, "windows", "x64", "8.502.07.1",
            "amazon-corretto-8.502.07.1-windows-x64-jdk.zip");
        Artifact win11 = art(11, "windows", "x64", "11.0.32.9.1",
            "amazon-corretto-11.0.32.9.1-windows-x64-jdk.zip");

        Check.eq("https://corretto.aws/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-linux-x64.tar.gz",
            Derive.url(linux));

        Check.eq("amazon-corretto-21.0.12.8.1-linux-x64", Derive.stripPrefix(linux));
        Check.eq("amazon-corretto-21.0.12.8.1-alpine-linux-aarch64", Derive.stripPrefix(alpine));
        Check.eq("amazon-corretto-21.jdk/Contents/Home", Derive.stripPrefix(macos));
        Check.eq("jdk21.0.12_8", Derive.stripPrefix(win));      // 21.0.12.8.1 -> jdk21.0.12_8
        Check.eq("jdk11.0.32_9", Derive.stripPrefix(win11));    // 11.0.32.9.1 -> jdk11.0.32_9
        Check.eq("jdk1.8.0_502", Derive.stripPrefix(win8));     // 8.502.07.1  -> jdk1.8.0_502

        Check.eq("corretto21_linux", Derive.repoName(linux));
        Check.eq("corretto_alpine21_linux_aarch64", Derive.repoName(alpine));
        Check.eq("corretto21_macos_aarch64", Derive.repoName(macos));
        Check.eq("corretto21_win", Derive.repoName(win));

        Check.eq("corretto", Derive.prefix(linux));
        Check.eq("corretto_alpine", Derive.prefix(alpine));

        Check.eq(List.of("@platforms//os:linux", "@platforms//cpu:x86_64"),
            Derive.targetCompatibleWith(linux));
        Check.eq(List.of("@platforms//os:linux", "@platforms//cpu:aarch64"),
            Derive.targetCompatibleWith(alpine));
        Check.eq(List.of("@platforms//os:macos", "@platforms//cpu:aarch64"),
            Derive.targetCompatibleWith(macos));
        Check.eq(List.of("@platforms//os:windows", "@platforms//cpu:x86_64"),
            Derive.targetCompatibleWith(win));
        System.out.println("DeriveTest OK");
    }
}
```

Add to `tools/update/BUILD.bazel`:

```starlark
java_test(
    name = "derive_test",
    srcs = ["src/test/java/corretto/update/DeriveTest.java"],
    main_class = "corretto.update.DeriveTest",
    use_testrunner = False,
    deps = [
        ":test_lib",
        ":update_lib",
    ],
)
```

- [ ] **Step 2: Run to verify it fails**

```bash
bazel test //tools/update:derive_test --test_output=errors
```

Expected: FAIL — `Derive` does not exist.

- [ ] **Step 3: Implement Derive**

`tools/update/src/main/java/corretto/update/Derive.java`:

```java
package corretto.update;

import java.util.List;

public final class Derive {
    private Derive() {}

    public static String url(Artifact a) {
        return "https://corretto.aws" + a.resource();
    }

    public static String stripPrefix(Artifact a) {
        switch (a.os()) {
            case "linux":
            case "alpine": {
                String file = a.fileName();
                return file.substring(0, file.length() - ".tar.gz".length());
            }
            case "macos":
                return "amazon-corretto-" + a.major() + ".jdk/Contents/Home";
            case "windows": {
                // fullVersion a.b.c.d[.e]; verified: 21.0.12.8.1 -> jdk21.0.12_8,
                // 8.502.07.1 -> jdk1.8.0_502 (leading zeros dropped via int parse).
                String[] p = a.fullVersion().split("\\.");
                if (a.major() == 8) {
                    return "jdk1.8.0_" + Integer.parseInt(p[1]);
                }
                return "jdk" + p[0] + "." + p[1] + "." + p[2] + "_" + Integer.parseInt(p[3]);
            }
            default:
                throw new IllegalArgumentException("unknown os: " + a.os());
        }
    }

    public static String repoName(Artifact a) {
        String base = a.os().equals("alpine") ? "corretto_alpine" : "corretto";
        String osPart = switch (a.os()) {
            case "linux", "alpine" -> "linux";
            case "macos" -> "macos";
            case "windows" -> "win";
            default -> throw new IllegalArgumentException("unknown os: " + a.os());
        };
        String archPart = a.arch().equals("x64") ? "" : "_" + a.arch();
        return base + a.major() + "_" + osPart + archPart;
    }

    public static String prefix(Artifact a) {
        return a.os().equals("alpine") ? "corretto_alpine" : "corretto";
    }

    public static List<String> targetCompatibleWith(Artifact a) {
        String os = switch (a.os()) {
            case "linux", "alpine" -> "@platforms//os:linux";
            case "macos" -> "@platforms//os:macos";
            case "windows" -> "@platforms//os:windows";
            default -> throw new IllegalArgumentException("unknown os: " + a.os());
        };
        String cpu = a.arch().equals("x64")
            ? "@platforms//cpu:x86_64"
            : "@platforms//cpu:aarch64";
        return List.of(os, cpu);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
bazel test //tools/update:derive_test --test_output=errors
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/update
git commit -m "feat: derive urls, strip prefixes, repo names, and constraints from artifacts"
```

---

### Task 7: StarlarkWriter — emit versions.bzl and rewrite the MODULE.bazel block

**Files:**
- Create: `tools/update/src/main/java/corretto/update/StarlarkWriter.java`
- Create: `tools/update/src/test/java/corretto/update/StarlarkWriterTest.java`
- Modify: `tools/update/BUILD.bazel` (add test target)

**Interfaces:**
- Consumes: `Artifact`, `Derive` (Tasks 5–6).
- Produces (static on `corretto.update.StarlarkWriter`):
  - `String versionsBzl(List<Artifact> artifacts)` — complete file content, entries in list order (already sorted by IndexMap).
  - `String moduleBlock(List<Artifact> artifacts)` — the content BETWEEN the markers (one `use_repo(...)` then one `register_toolchains` per repo).
  - `String replaceBlock(String moduleContent, String newBlock)` — swaps the text between `# BEGIN GENERATED REPOS - managed by //tools/update, do not edit` and `# END GENERATED REPOS`; throws `IllegalStateException` if markers are missing or out of order.
- The exact marker strings match Task 1's MODULE.bazel.

- [ ] **Step 1: Write the failing test**

`tools/update/src/test/java/corretto/update/StarlarkWriterTest.java`:

```java
package corretto.update;

import java.util.List;

public final class StarlarkWriterTest {

    private static final Artifact LINUX = new Artifact(21, "linux", "x64", "21.0.12.8.1",
        "/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-linux-x64.tar.gz", "abc");
    private static final Artifact ALPINE = new Artifact(21, "alpine", "x64", "21.0.12.8.1",
        "/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-alpine-linux-x64.tar.gz", "def");

    public static void main(String[] args) {
        String bzl = StarlarkWriter.versionsBzl(List.of(LINUX, ALPINE));

        Check.isTrue(bzl.startsWith("# GENERATED FILE - do not edit by hand."),
            "generated header present");
        Check.isTrue(bzl.contains("CORRETTO_JDK_CONFIGS = ["), "list declared");
        Check.isTrue(bzl.contains("        name = \"corretto21_linux\",\n"), "entry name");
        Check.isTrue(bzl.contains("        prefix = \"corretto_alpine\",\n"), "alpine prefix");
        Check.isTrue(bzl.contains("        version = \"21\",\n"), "bare major only");
        Check.isTrue(!bzl.contains("version = \"21.0.12.8.1\""), "full version never in version field");
        Check.isTrue(bzl.contains(
            "        sha256 = \"abc\",\n"
            + "        strip_prefix = \"amazon-corretto-21.0.12.8.1-linux-x64\",\n"
            + "        urls = [\"https://corretto.aws/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-linux-x64.tar.gz\"],\n"),
            "sha/strip/urls block");
        Check.isTrue(bzl.contains(
            "        target_compatible_with = [\"@platforms//os:linux\", \"@platforms//cpu:x86_64\"],\n"),
            "constraints");

        // Emission is deterministic.
        Check.eq(bzl, StarlarkWriter.versionsBzl(List.of(LINUX, ALPINE)));

        String block = StarlarkWriter.moduleBlock(List.of(LINUX, ALPINE));
        Check.isTrue(block.contains("    \"corretto21_linux_toolchain_config_repo\",\n"), "use_repo entry");
        Check.isTrue(block.contains(
            "register_toolchains(\"@corretto21_linux_toolchain_config_repo//:all\")"), "register line");
        Check.isTrue(block.contains(
            "register_toolchains(\"@corretto_alpine21_linux_toolchain_config_repo//:all\")"),
            "alpine register line");

        String module = "module(name = \"rules_corretto\")\n\n"
            + "# BEGIN GENERATED REPOS - managed by //tools/update, do not edit\n"
            + "OLD CONTENT\n"
            + "# END GENERATED REPOS\n"
            + "\n# trailing\n";
        String rewritten = StarlarkWriter.replaceBlock(module, block);
        Check.isTrue(!rewritten.contains("OLD CONTENT"), "old block gone");
        Check.isTrue(rewritten.contains("corretto21_linux_toolchain_config_repo"), "new block in");
        Check.isTrue(rewritten.contains("# trailing"), "content after block preserved");
        Check.isTrue(rewritten.contains("module(name = \"rules_corretto\")"), "content before block preserved");
        // Idempotent: replacing again with the same block changes nothing.
        Check.eq(rewritten, StarlarkWriter.replaceBlock(rewritten, block));

        boolean threw = false;
        try {
            StarlarkWriter.replaceBlock("no markers here", block);
        } catch (IllegalStateException e) {
            threw = true;
        }
        Check.isTrue(threw, "missing markers must throw");
        System.out.println("StarlarkWriterTest OK");
    }
}
```

Add to `tools/update/BUILD.bazel`:

```starlark
java_test(
    name = "starlark_writer_test",
    srcs = ["src/test/java/corretto/update/StarlarkWriterTest.java"],
    main_class = "corretto.update.StarlarkWriterTest",
    use_testrunner = False,
    deps = [
        ":test_lib",
        ":update_lib",
    ],
)
```

- [ ] **Step 2: Run to verify it fails**

```bash
bazel test //tools/update:starlark_writer_test --test_output=errors
```

Expected: FAIL — `StarlarkWriter` does not exist.

- [ ] **Step 3: Implement StarlarkWriter**

`tools/update/src/main/java/corretto/update/StarlarkWriter.java`:

```java
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
```

- [ ] **Step 4: Run to verify it passes**

```bash
bazel test //tools/update:starlark_writer_test --test_output=errors
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/update
git commit -m "feat: emit versions.bzl and rewrite MODULE.bazel generated block"
```

---

### Task 8: StreamCheck — read an archive's top-level dir from a partial stream

**Files:**
- Create: `tools/update/src/main/java/corretto/update/StreamCheck.java`
- Create: `tools/update/src/test/java/corretto/update/StreamCheckTest.java`
- Modify: `tools/update/BUILD.bazel` (add test target)

**Interfaces:**
- Consumes: nothing (pure stream utility).
- Produces (static on `corretto.update.StreamCheck`):
  - `String topLevelDir(InputStream in, String format)` — `format` ∈ {`"tar.gz"`, `"zip"`}; reads only the first entry header and returns the first path segment of the first entry's name (e.g. `amazon-corretto-21.0.12.8.1-linux-x64`). Callers close the stream (aborting the HTTP transfer).
  - `static String firstSegment(String path)` — helper: `"a/b/c"` → `"a"` (also used by Task 9 to reduce a strip_prefix to its comparable first segment).
- This is the spec's "partial-stream-then-abort" cheap verification.

- [ ] **Step 1: Write the failing test (fixtures built in-memory)**

`tools/update/src/test/java/corretto/update/StreamCheckTest.java`:

```java
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
```

Add to `tools/update/BUILD.bazel`:

```starlark
java_test(
    name = "stream_check_test",
    srcs = ["src/test/java/corretto/update/StreamCheckTest.java"],
    main_class = "corretto.update.StreamCheckTest",
    use_testrunner = False,
    deps = [
        ":test_lib",
        ":update_lib",
    ],
)
```

- [ ] **Step 2: Run to verify it fails**

```bash
bazel test //tools/update:stream_check_test --test_output=errors
```

Expected: FAIL — `StreamCheck` does not exist.

- [ ] **Step 3: Implement StreamCheck**

`tools/update/src/main/java/corretto/update/StreamCheck.java`:

```java
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
```

- [ ] **Step 4: Run to verify it passes**

```bash
bazel test //tools/update:stream_check_test --test_output=errors
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/update
git commit -m "feat: read archive top-level dir from a partial stream for cheap prefix verification"
```

---

### Task 9: Main — orchestration, dry-run/--write, streamed checks, real generation

**Files:**
- Create: `tools/update/src/main/java/corretto/update/Fetcher.java`
- Create: `tools/update/src/main/java/corretto/update/Updater.java`
- Create: `tools/update/src/main/java/corretto/update/Main.java`
- Create: `tools/update/src/test/java/corretto/update/UpdaterTest.java`
- Modify: `tools/update/BUILD.bazel` (add `update` binary + test target)
- Modify (generated): `corretto/versions.bzl`, `MODULE.bazel` (by running the tool)

**Interfaces:**
- Consumes: everything from Tasks 3–8.
- Produces: `public interface Fetcher { InputStream open(String url) throws IOException; }` with `Fetcher.http()` returning the real `java.net.http` implementation. Task 11's `--verify` reuses it.
- Produces: `public record Updater(Fetcher fetcher, Path versionsBzl, Path moduleBazel, boolean includeFeature)` with `public Result run() throws IOException` where `public record Result(String newVersionsBzl, String newModuleBazel, String report, boolean changed)`. Streamed prefix checks happen inside `run()` for changed entries only.
- Produces: `bazel run //tools/update` (dry-run; exit 1 if stale) and `-- --write` (atomic in-place rewrite). Uses `BUILD_WORKSPACE_DIRECTORY` to find the checked-out tree.
- Constants: `Updater.VERSION_INFO_URL` and `Updater.INDEXMAP_URL` point at `https://raw.githubusercontent.com/corretto/corretto-downloads/main/latest_links/{version-info.json,indexmap_with_checksum.json}`.

- [ ] **Step 1: Write the failing test (fake fetcher, no network)**

`tools/update/src/test/java/corretto/update/UpdaterTest.java`:

```java
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
```

Add to `tools/update/BUILD.bazel`:

```starlark
java_binary(
    name = "update",
    main_class = "corretto.update.Main",
    visibility = ["//visibility:public"],
    runtime_deps = [":update_lib"],
)

java_test(
    name = "updater_test",
    srcs = ["src/test/java/corretto/update/UpdaterTest.java"],
    main_class = "corretto.update.UpdaterTest",
    use_testrunner = False,
    deps = [
        ":test_lib",
        ":update_lib",
    ],
)
```

- [ ] **Step 2: Run to verify it fails**

```bash
bazel test //tools/update:updater_test --test_output=errors
```

Expected: FAIL — `Fetcher`/`Updater` do not exist.

- [ ] **Step 3: Implement Fetcher, Updater, Main**

`tools/update/src/main/java/corretto/update/Fetcher.java`:

```java
package corretto.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@FunctionalInterface
public interface Fetcher {
    InputStream open(String url) throws IOException;

    static Fetcher http() {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        return url -> {
            try {
                HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder().GET().uri(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted fetching " + url, e);
            }
        };
    }
}
```

`tools/update/src/main/java/corretto/update/Updater.java`:

```java
package corretto.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Updater(Fetcher fetcher, Path versionsBzl, Path moduleBazel, boolean includeFeature) {

    public static final String VERSION_INFO_URL =
        "https://raw.githubusercontent.com/corretto/corretto-downloads/main/latest_links/version-info.json";
    public static final String INDEXMAP_URL =
        "https://raw.githubusercontent.com/corretto/corretto-downloads/main/latest_links/indexmap_with_checksum.json";

    public record Result(
        String newVersionsBzl, String newModuleBazel, String report, boolean changed) {}

    public Result run() throws IOException {
        VersionInfo info;
        try (InputStream in = fetcher.open(VERSION_INFO_URL)) {
            info = VersionInfo.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<Artifact> artifacts;
        try (InputStream in = fetcher.open(INDEXMAP_URL)) {
            artifacts = IndexMap.parse(
                new InputStreamReader(in, StandardCharsets.UTF_8), info.majors(includeFeature));
        }

        String currentVersions = Files.readString(versionsBzl);
        String currentModule = Files.readString(moduleBazel);

        // Streamed strip_prefix verification, changed entries only (spec §3).
        Set<String> currentShas = extract(currentVersions, "sha256 = \"([0-9a-zA-Z-]+)\"");
        for (Artifact a : artifacts) {
            if (!currentShas.contains(a.sha256())) {
                String expected = StreamCheck.firstSegment(Derive.stripPrefix(a));
                String actual;
                try (InputStream in = fetcher.open(Derive.url(a))) {
                    actual = StreamCheck.topLevelDir(in, a.format());
                }
                if (!expected.equals(actual)) {
                    throw new IllegalStateException(
                        "derived strip_prefix mismatch for " + Derive.repoName(a)
                            + ": derived first segment '" + expected
                            + "' but archive top-level dir is '" + actual + "'");
                }
            }
        }

        String newVersions = StarlarkWriter.versionsBzl(artifacts);
        String newModule = StarlarkWriter.replaceBlock(
            currentModule, StarlarkWriter.moduleBlock(artifacts));

        Set<Integer> currentMajors = new TreeSet<>();
        for (String v : extract(currentVersions, "version = \"(\\d+)\"")) {
            currentMajors.add(Integer.parseInt(v));
        }
        String lifecycle =
            VersionInfo.lifecycleReport(currentMajors, new TreeSet<>(info.majors(includeFeature)));

        boolean changed =
            !newVersions.equals(currentVersions) || !newModule.equals(currentModule);
        String report = (changed ? "configs changed\n" : "up to date\n") + lifecycle;
        return new Result(newVersions, newModule, report, changed);
    }

    /** Atomic write: temp file in the same directory, then move into place. */
    public static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static Set<String> extract(String content, String regex) {
        Set<String> result = new HashSet<>();
        Matcher m = Pattern.compile(regex).matcher(content);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }
}
```

`tools/update/src/main/java/corretto/update/Main.java`:

```java
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
```

Until Task 11 exists, stub `Verify` so this compiles:

`tools/update/src/main/java/corretto/update/Verify.java`:

```java
package corretto.update;

public final class Verify {
    private Verify() {}

    static void run(Fetcher fetcher, String versionsBzl) {
        throw new UnsupportedOperationException("--verify arrives in Task 11");
    }
}
```

- [ ] **Step 4: Run to verify tests pass**

```bash
bazel test //tools/update:updater_test --test_output=errors
```

Expected: PASS.

- [ ] **Step 5: Real run — generate the full config set**

```bash
bazel run //tools/update -- --write
```

Expected output: `configs changed`, lifecycle lines for majors 8/11/17/25 (21 already present), `wrote corretto/versions.bzl and MODULE.bazel`. The streamed checks fetch only archive heads — this should take seconds, not minutes. Then inspect:

```bash
grep -c 'name = "' corretto/versions.bzl     # expect 35  (5 majors x 7 platforms)
grep -c register_toolchains MODULE.bazel     # expect 35
```

Dry-run must now be clean and idempotent:

```bash
bazel run //tools/update
```

Expected: `up to date`, exit 0.

- [ ] **Step 6: Confirm the example still works on the regenerated files**

```bash
cd examples && bazel run //:hello --java_runtime_version=corretto_21
```

Expected: `vendor=Amazon.com Inc. ...` (cached from Task 2 unless the patch version moved).

- [ ] **Step 7: Commit**

```bash
git add tools/update corretto/versions.bzl MODULE.bazel MODULE.bazel.lock
git commit -m "feat: updater main with dry-run, atomic --write, and streamed prefix checks; generate full 8/11/17/21/25 config set"
```

---

### Task 10: check-sync — offline consistency test between versions.bzl and MODULE.bazel

**Files:**
- Create: `tools/update/src/main/java/corretto/update/SyncCheck.java`
- Create: `tools/update/src/test/java/corretto/update/SyncCheckTest.java`
- Modify: `tools/update/BUILD.bazel` (add unit test + the repo-level `sync_test`)

**Interfaces:**
- Consumes: `StarlarkWriter` markers (Task 7).
- Produces: `public static void check(String versionsBzl, String moduleBazel)` on `corretto.update.SyncCheck` — throws `IllegalStateException` describing the first divergence between repo names in `versions.bzl` (`name = "..."` fields) and the generated MODULE block (both the `use_repo` names minus the `_toolchain_config_repo` suffix and the `register_toolchains` lines). Order-sensitive.
- Produces: `//tools/update:sync_test` — a `java_test` with `data` on the real checked-in files; this is the CI guard from spec §4.

- [ ] **Step 1: Write the failing unit test**

`tools/update/src/test/java/corretto/update/SyncCheckTest.java`:

```java
package corretto.update;

public final class SyncCheckTest {

    private static String module(String... repos) {
        StringBuilder sb = new StringBuilder("module(name = \"x\")\n")
            .append(StarlarkWriter.BEGIN_MARKER).append('\n')
            .append("use_repo(\n    corretto,\n");
        for (String r : repos) {
            sb.append("    \"").append(r).append("_toolchain_config_repo\",\n");
        }
        sb.append(")\n");
        for (String r : repos) {
            sb.append("register_toolchains(\"@").append(r)
                .append("_toolchain_config_repo//:all\")\n");
        }
        return sb.append(StarlarkWriter.END_MARKER).append('\n').toString();
    }

    private static String versions(String... names) {
        StringBuilder sb = new StringBuilder("CORRETTO_JDK_CONFIGS = [\n");
        for (String n : names) {
            sb.append("    struct(\n        name = \"").append(n).append("\",\n    ),\n");
        }
        return sb.append("]\n").toString();
    }

    public static void main(String[] args) {
        // In sync: no exception.
        SyncCheck.check(versions("corretto21_linux", "corretto21_win"),
            module("corretto21_linux", "corretto21_win"));

        // Out of sync: missing repo in MODULE.
        boolean threw = false;
        try {
            SyncCheck.check(versions("corretto21_linux", "corretto21_win"),
                module("corretto21_linux"));
        } catch (IllegalStateException e) {
            threw = true;
            Check.isTrue(e.getMessage().contains("corretto21_win"), "names divergence: " + e.getMessage());
        }
        Check.isTrue(threw, "must throw on divergence");

        // Out of sync: order differs.
        threw = false;
        try {
            SyncCheck.check(versions("corretto21_linux", "corretto21_win"),
                module("corretto21_win", "corretto21_linux"));
        } catch (IllegalStateException e) {
            threw = true;
        }
        Check.isTrue(threw, "must throw on order divergence");
        System.out.println("SyncCheckTest OK");
    }
}
```

Add to `tools/update/BUILD.bazel`:

```starlark
java_test(
    name = "sync_check_test",
    srcs = ["src/test/java/corretto/update/SyncCheckTest.java"],
    main_class = "corretto.update.SyncCheckTest",
    use_testrunner = False,
    deps = [
        ":test_lib",
        ":update_lib",
    ],
)
```

- [ ] **Step 2: Run to verify it fails**

```bash
bazel test //tools/update:sync_check_test --test_output=errors
```

Expected: FAIL — `SyncCheck` does not exist.

- [ ] **Step 3: Implement SyncCheck (with a main for the repo-level test)**

`tools/update/src/main/java/corretto/update/SyncCheck.java`:

```java
package corretto.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SyncCheck {
    private SyncCheck() {}

    public static void check(String versionsBzl, String moduleBazel) {
        List<String> configNames = extract(versionsBzl, "name = \"([a-z0-9_]+)\"");

        int begin = moduleBazel.indexOf(StarlarkWriter.BEGIN_MARKER);
        int end = moduleBazel.indexOf(StarlarkWriter.END_MARKER);
        if (begin < 0 || end < 0) {
            throw new IllegalStateException("MODULE.bazel markers missing");
        }
        String block = moduleBazel.substring(begin, end);
        List<String> useRepoNames =
            extract(block, "\"([a-z0-9_]+)_toolchain_config_repo\"");
        List<String> registered =
            extract(block, "register_toolchains\\(\"@([a-z0-9_]+)_toolchain_config_repo//:all\"\\)");

        if (!configNames.equals(useRepoNames)) {
            throw new IllegalStateException(
                "versions.bzl and MODULE.bazel use_repo diverge.\nversions.bzl: " + configNames
                    + "\nMODULE.bazel: " + useRepoNames
                    + "\nRun: bazel run //tools/update -- --write");
        }
        if (!configNames.equals(registered)) {
            throw new IllegalStateException(
                "versions.bzl and MODULE.bazel register_toolchains diverge.\nversions.bzl: "
                    + configNames + "\nregistered: " + registered
                    + "\nRun: bazel run //tools/update -- --write");
        }
    }

    /** Entry point for the repo-level sync_test (reads real files from runfiles). */
    public static void main(String[] args) throws Exception {
        String srcdir = System.getenv("TEST_SRCDIR");
        check(
            Files.readString(Path.of(srcdir, "_main", "corretto", "versions.bzl")),
            Files.readString(Path.of(srcdir, "_main", "MODULE.bazel")));
        System.out.println("SyncCheck OK");
    }

    private static List<String> extract(String content, String regex) {
        List<String> result = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(content);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }
}
```

Note: the `use_repo` regex also matches the substring inside `register_toolchains("@..._toolchain_config_repo//:all")` lines — that is why `check` scopes both extractions to the marker block and why the `use_repo` extraction pattern must NOT match those. Fix by anchoring: use pattern `"    \"([a-z0-9_]+)_toolchain_config_repo\","` (leading four-space indent and trailing comma, present only in the `use_repo` list). Update the implementation accordingly:

```java
        List<String> useRepoNames =
            extract(block, "    \"([a-z0-9_]+)_toolchain_config_repo\",");
```

- [ ] **Step 4: Run unit test; verify it passes**

```bash
bazel test //tools/update:sync_check_test --test_output=errors
```

Expected: PASS.

- [ ] **Step 5: Add the repo-level guard test and run it against the real files**

Add to `tools/update/BUILD.bazel`:

```starlark
java_test(
    name = "sync_test",
    main_class = "corretto.update.SyncCheck",
    use_testrunner = False,
    data = [
        "//:MODULE.bazel",
        "//corretto:versions.bzl",
    ],
    runtime_deps = [":update_lib"],
)
```

Root `BUILD.bazel` — add:

```starlark
exports_files(["MODULE.bazel"])
```

Run:

```bash
bazel test //tools/update:sync_test --test_output=errors
```

Expected: PASS (files were both just generated by Task 9).

- [ ] **Step 6: Commit**

```bash
git add tools/update BUILD.bazel
git commit -m "feat: offline consistency guard between versions.bzl and MODULE.bazel"
```

---

### Task 11: --verify — full-download sha256 and top-level-dir verification

**Files:**
- Modify: `tools/update/src/main/java/corretto/update/Verify.java` (replace stub)
- Create: `tools/update/src/test/java/corretto/update/VerifyTest.java`
- Modify: `tools/update/BUILD.bazel` (add test target)

**Interfaces:**
- Consumes: `Fetcher`, `StreamCheck`, `StarlarkWriter` output format.
- Produces: `static void run(Fetcher fetcher, String versionsBzl)` on `corretto.update.Verify` — parses `sha256`, `strip_prefix`, `urls` triples out of a versions.bzl string (regex, same style as SyncCheck), downloads each URL COMPLETELY, computes SHA-256 over the raw bytes while also reading the top-level dir from the same stream, and throws `IllegalStateException` naming the repo on any mismatch. CI-only by design (spec §3): this is defense-in-depth behind the indexmap checksums.

- [ ] **Step 1: Write the failing test**

`tools/update/src/test/java/corretto/update/VerifyTest.java`:

```java
package corretto.update;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
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
```

Add to `tools/update/BUILD.bazel`:

```starlark
java_test(
    name = "verify_test",
    srcs = ["src/test/java/corretto/update/VerifyTest.java"],
    main_class = "corretto.update.VerifyTest",
    use_testrunner = False,
    deps = [
        ":test_lib",
        ":update_lib",
    ],
)
```

- [ ] **Step 2: Run to verify it fails**

```bash
bazel test //tools/update:verify_test --test_output=errors
```

Expected: FAIL — `Verify.run` throws `UnsupportedOperationException` (the Task 9 stub).

- [ ] **Step 3: Implement Verify**

Replace `tools/update/src/main/java/corretto/update/Verify.java` entirely:

```java
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
```

Caveat baked into this design: `GZIPInputStream` inside `StreamCheck.topLevelDir` may buffer past the header — that's fine, every buffered byte still flowed through the `DigestInputStream`, and the drain reads whatever remains. The digest always covers exactly the full raw file.

- [ ] **Step 4: Run to verify it passes**

```bash
bazel test //tools/update:verify_test --test_output=errors
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/update
git commit -m "feat: full-download --verify mode checking sha256 and archive layout"
```

---

### Task 12: corretto.pin — root-module extension tag for overriding a build

**Files:**
- Create: `corretto/derive.bzl`
- Modify: `corretto/extensions.bzl`
- Create: `corretto/tests/BUILD.bazel`
- Create: `corretto/tests/derive_test.bzl`
- Modify: `examples/MODULE.bazel` (pin smoke test)

**Interfaces:**
- Consumes: `CORRETTO_JDK_CONFIGS` shape (Task 1), extension from Task 1.
- Produces in `corretto/derive.bzl` (Starlark mirrors of Task 6's Java, for the pin path only):
  - `corretto_url(corretto_version, os, cpu)` — os ∈ {`linux`, `alpine`, `macos`, `windows`}, cpu ∈ {`x86_64`, `aarch64`}.
  - `corretto_strip_prefix(corretto_version, os, cpu)`
  - `corretto_repo_name(major, os, cpu)`
  - `corretto_config(version, corretto_version, os, cpu, sha256)` — returns a struct in the `CORRETTO_JDK_CONFIGS` shape.
- Produces: `corretto.pin` tag with attrs `version` (string, mandatory, bare major), `corretto_version` (string, mandatory), `os` (string, mandatory), `cpu` (string, mandatory), `sha256` (string, mandatory). Root-module-only; replaces the default config with the same repo name.

- [ ] **Step 1: Write the failing Starlark unit tests**

`corretto/tests/derive_test.bzl`:

```starlark
"""Unit tests for corretto/derive.bzl."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load("//corretto:derive.bzl", "corretto_config", "corretto_repo_name", "corretto_strip_prefix", "corretto_url")

def _derive_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(
        env,
        "https://corretto.aws/downloads/resources/21.0.11.9.1/amazon-corretto-21.0.11.9.1-linux-x64.tar.gz",
        corretto_url("21.0.11.9.1", "linux", "x86_64"),
    )
    asserts.equals(
        env,
        "https://corretto.aws/downloads/resources/21.0.11.9.1/amazon-corretto-21.0.11.9.1-macosx-aarch64.tar.gz",
        corretto_url("21.0.11.9.1", "macos", "aarch64"),
    )
    asserts.equals(
        env,
        "https://corretto.aws/downloads/resources/21.0.11.9.1/amazon-corretto-21.0.11.9.1-alpine-linux-x64.tar.gz",
        corretto_url("21.0.11.9.1", "alpine", "x86_64"),
    )
    asserts.equals(
        env,
        "https://corretto.aws/downloads/resources/21.0.11.9.1/amazon-corretto-21.0.11.9.1-windows-x64-jdk.zip",
        corretto_url("21.0.11.9.1", "windows", "x86_64"),
    )

    asserts.equals(env, "amazon-corretto-21.0.11.9.1-linux-x64", corretto_strip_prefix("21.0.11.9.1", "linux", "x86_64"))
    asserts.equals(env, "amazon-corretto-21.jdk/Contents/Home", corretto_strip_prefix("21.0.11.9.1", "macos", "aarch64"))
    asserts.equals(env, "jdk21.0.11_9", corretto_strip_prefix("21.0.11.9.1", "windows", "x86_64"))
    asserts.equals(env, "jdk1.8.0_442", corretto_strip_prefix("8.442.06.1", "windows", "x86_64"))

    asserts.equals(env, "corretto21_linux", corretto_repo_name("21", "linux", "x86_64"))
    asserts.equals(env, "corretto_alpine21_linux_aarch64", corretto_repo_name("21", "alpine", "aarch64"))
    asserts.equals(env, "corretto21_win", corretto_repo_name("21", "windows", "x86_64"))

    cfg = corretto_config(
        version = "21",
        corretto_version = "21.0.11.9.1",
        os = "linux",
        cpu = "x86_64",
        sha256 = "deadbeef",
    )
    asserts.equals(env, "corretto21_linux", cfg.name)
    asserts.equals(env, "corretto", cfg.prefix)
    asserts.equals(env, "21", cfg.version)
    asserts.equals(env, ["@platforms//os:linux", "@platforms//cpu:x86_64"], cfg.target_compatible_with)
    asserts.equals(env, "deadbeef", cfg.sha256)

    return unittest.end(env)

derive_test = unittest.make(_derive_test_impl)

def derive_test_suite(name):
    unittest.suite(name, derive_test)
```

`corretto/tests/BUILD.bazel`:

```starlark
load(":derive_test.bzl", "derive_test_suite")

derive_test_suite(name = "derive_tests")
```

- [ ] **Step 2: Run to verify it fails**

```bash
bazel test //corretto/tests:all --test_output=errors
```

Expected: FAIL — `corretto/derive.bzl` does not exist.

- [ ] **Step 3: Implement corretto/derive.bzl**

```starlark
"""URL/strip_prefix/name derivation for Corretto artifacts.

Starlark mirror of tools/update's Derive.java, used only for the corretto.pin
extension tag. Keep the two in sync; both are covered by golden tests using the
same verified example values.
"""

_OS_FILE_PART = {
    "alpine": "alpine-linux",
    "linux": "linux",
    "macos": "macosx",
    "windows": "windows",
}

_CPU_FILE_PART = {
    "aarch64": "aarch64",
    "x86_64": "x64",
}

def corretto_url(corretto_version, os, cpu):
    base = "https://corretto.aws/downloads/resources/{v}/amazon-corretto-{v}-{os}-{cpu}".format(
        v = corretto_version,
        os = _OS_FILE_PART[os],
        cpu = _CPU_FILE_PART[cpu],
    )
    if os == "windows":
        return base + "-jdk.zip"
    return base + ".tar.gz"

def corretto_strip_prefix(corretto_version, os, cpu):
    major = corretto_version.split(".")[0]
    if os == "macos":
        return "amazon-corretto-{}.jdk/Contents/Home".format(major)
    if os == "windows":
        p = corretto_version.split(".")
        if major == "8":
            return "jdk1.8.0_{}".format(int(p[1]))
        return "jdk{}.{}.{}_{}".format(p[0], p[1], p[2], int(p[3]))
    return "amazon-corretto-{v}-{os}-{cpu}".format(
        v = corretto_version,
        os = _OS_FILE_PART[os],
        cpu = _CPU_FILE_PART[cpu],
    )

def corretto_repo_name(major, os, cpu):
    base = "corretto_alpine" if os == "alpine" else "corretto"
    os_part = {"alpine": "linux", "linux": "linux", "macos": "macos", "windows": "win"}[os]
    arch_part = "" if cpu == "x86_64" else "_aarch64"
    return base + major + "_" + os_part + arch_part

def corretto_config(version, corretto_version, os, cpu, sha256):
    if not corretto_version.startswith(version + "."):
        fail("corretto_version {} does not belong to major {}".format(corretto_version, version))
    os_constraint = "@platforms//os:windows" if os == "windows" else (
        "@platforms//os:macos" if os == "macos" else "@platforms//os:linux"
    )
    return struct(
        name = corretto_repo_name(version, os, cpu),
        prefix = "corretto_alpine" if os == "alpine" else "corretto",
        version = version,
        target_compatible_with = [os_constraint, "@platforms//cpu:" + cpu],
        sha256 = sha256,
        strip_prefix = corretto_strip_prefix(corretto_version, os, cpu),
        urls = [corretto_url(corretto_version, os, cpu)],
    )
```

- [ ] **Step 4: Run to verify Starlark tests pass**

```bash
bazel test //corretto/tests:all --test_output=errors
```

Expected: PASS.

- [ ] **Step 5: Wire the pin tag into the extension**

Replace `corretto/extensions.bzl` with:

```starlark
"""Module extension providing Amazon Corretto JDK runtime toolchains."""

load("@rules_java//toolchains:remote_java_repository.bzl", "remote_java_repository")
load("//corretto:derive.bzl", "corretto_config")
load("//corretto:versions.bzl", "CORRETTO_JDK_CONFIGS")

_pin = tag_class(
    doc = "Override the shipped Corretto build for one major x platform. Root module only.",
    attrs = {
        "version": attr.string(
            doc = "Bare JDK major to override, e.g. '21'.",
            mandatory = True,
        ),
        "corretto_version": attr.string(
            doc = "Full Corretto version, e.g. '21.0.11.9.1'.",
            mandatory = True,
        ),
        "os": attr.string(
            mandatory = True,
            values = ["linux", "alpine", "macos", "windows"],
        ),
        "cpu": attr.string(
            mandatory = True,
            values = ["x86_64", "aarch64"],
        ),
        "sha256": attr.string(
            doc = "SHA-256 of the archive. Amazon publishes checksums only for the " +
                  "latest build of each line; compute this yourself for older builds.",
            mandatory = True,
        ),
    },
)

def _corretto_impl(mctx):
    configs = {cfg.name: cfg for cfg in CORRETTO_JDK_CONFIGS}
    for mod in mctx.modules:
        for pin in mod.tags.pin:
            if not mod.is_root:
                fail("corretto.pin may only be used from the root module")
            cfg = corretto_config(
                version = pin.version,
                corretto_version = pin.corretto_version,
                os = pin.os,
                cpu = pin.cpu,
                sha256 = pin.sha256,
            )
            if cfg.name not in configs:
                fail("corretto.pin targets unknown platform/major combination: " + cfg.name)
            configs[cfg.name] = cfg
    for cfg in configs.values():
        remote_java_repository(
            name = cfg.name,
            prefix = cfg.prefix,
            version = cfg.version,
            target_compatible_with = cfg.target_compatible_with,
            sha256 = cfg.sha256,
            strip_prefix = cfg.strip_prefix,
            urls = cfg.urls,
        )
    return mctx.extension_metadata(reproducible = True)

corretto = module_extension(
    implementation = _corretto_impl,
    tag_classes = {"pin": _pin},
)
```

- [ ] **Step 6: Smoke-test the pin mechanics from examples/**

The current shipped values pin cleanly (same repo, known sha — verifies the mechanism without a stale-checksum problem). Read the current corretto21_linux values:

```bash
grep -A 7 'name = "corretto21_linux"' corretto/versions.bzl
```

Append to `examples/MODULE.bazel` (substituting the real `corretto_version` — the version segment in the url — and `sha256` just printed):

```starlark
corretto = use_extension("@rules_corretto//corretto:extensions.bzl", "corretto")
corretto.pin(
    version = "21",
    corretto_version = "<current full version from versions.bzl>",
    os = "linux",
    cpu = "x86_64",
    sha256 = "<current sha256 from versions.bzl>",
)
```

Verify the extension still evaluates and toolchains resolve:

```bash
cd examples && bazel query '@rules_corretto//corretto:all' >/dev/null && bazel run //:hello --java_runtime_version=corretto_21
```

Expected: `vendor=Amazon.com Inc. ...` (unchanged behavior; pin applied without error). Then REMOVE the pin block again from `examples/MODULE.bazel` — it was a mechanics check, not permanent example content. (The README's pin documentation is Task 14.)

- [ ] **Step 7: Commit**

```bash
git add corretto examples
git commit -m "feat: corretto.pin root-module tag with starlark derivation"
```

---

### Task 13: GitHub Actions — CI, scheduled update PRs, release

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/update.yml`
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: `bazel test //...` green (Tasks 1–12), `examples/` (Task 2), updater CLI contract (Task 9: exit 1 when stale, `--write`, `--verify`).
- Produces: CI required checks used by BCR presubmit (Task 14 mirrors the test targets in `.bcr/presubmit.yml`).

- [ ] **Step 1: Write ci.yml**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  test:
    strategy:
      fail-fast: false
      matrix:
        os: [ubuntu-latest, macos-latest, windows-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - name: Unit and consistency tests
        run: bazel test //... --test_output=errors
      - name: Example resolves Corretto 21
        shell: bash
        working-directory: examples
        run: |
          out=$(bazel run //:hello --java_runtime_version=corretto_21)
          echo "$out"
          echo "$out" | grep -q "vendor=Amazon.com Inc."
      - name: Example default runtime untouched
        shell: bash
        working-directory: examples
        run: bazel run //:hello

  updater-fresh:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Configs are current (dry-run)
        run: bazel run //tools/update
        continue-on-error: true  # staleness is the cron's job to fix, not a CI failure
```

> Revised 2026-07-24 (user decision): no alpine CI job. Running Bazel inside a
> musl container is unreliable (glibc-linked Node for actions/checkout,
> bazelisk/bazel); alpine toolchains ship covered by the updater's checksum and
> streamed strip_prefix verification only.

- [ ] **Step 2: Write update.yml**

```yaml
name: Update JDK configs

on:
  schedule:
    - cron: "0 6 * * 1"  # Mondays 06:00 UTC
  workflow_dispatch:

permissions:
  contents: write
  pull-requests: write

jobs:
  update:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Regenerate configs
        id: regen
        run: |
          bazel run //tools/update -- --write | tee update-report.txt
      - name: Verify changed archives (full download)
        run: |
          if ! git diff --quiet; then
            bazel run //tools/update -- --verify
          fi
      - name: Open PR
        uses: peter-evans/create-pull-request@v7
        with:
          branch: auto/update-jdk-configs
          commit-message: "chore: update Corretto JDK configs"
          title: "Update Corretto JDK configs"
          body-path: update-report.txt
          delete-branch: true
```

- [ ] **Step 3: Write release.yml**

```yaml
name: Release

on:
  push:
    tags:
      - "v*"

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build release archive
        run: |
          TAG="${GITHUB_REF_NAME}"
          PREFIX="rules_corretto-${TAG#v}"
          git archive --format=tar.gz --prefix="${PREFIX}/" -o "${PREFIX}.tar.gz" "${TAG}"
          SHA=$(shasum -a 256 "${PREFIX}.tar.gz" | cut -d' ' -f1)
          {
            echo "## Using bzlmod"
            echo '```starlark'
            echo "bazel_dep(name = \"rules_corretto\", version = \"${TAG#v}\")"
            echo '```'
            echo ""
            echo "SHA-256: \`${SHA}\`"
          } > release-notes.md
      - name: Create GitHub release
        uses: softprops/action-gh-release@v2
        with:
          body_path: release-notes.md
          files: "*.tar.gz"
```

- [ ] **Step 4: Validate workflow syntax**

```bash
python3 -c "import yaml,glob; [yaml.safe_load(open(f)) for f in glob.glob('.github/workflows/*.yml')]; print('YAML OK')"
```

Expected: `YAML OK`. (If PyYAML is unavailable locally: `python3 -m pip install --user pyyaml` first, or rely on the first push to a branch to validate.)

- [ ] **Step 5: Commit**

```bash
git add .github
git commit -m "ci: build/test matrix with alpine job, weekly update PRs, tag-driven releases"
```

---

### Task 14: Documentation, licensing, BCR metadata

**Files:**
- Create: `LICENSE` (Apache-2.0)
- Modify: `README.md` (full rewrite)
- Create: `.bcr/metadata.template.json`
- Create: `.bcr/source.template.json`
- Create: `.bcr/presubmit.yml`
- Create: `.bcr/config.yml`

**Interfaces:**
- Consumes: everything — this task documents the finished module.
- Produces: BCR publishing readiness via the Publish to BCR GitHub app templates.

- [ ] **Step 1: Confirm the GitHub owner/repo with the user**

The `.bcr` templates and README badges need the canonical `github.com/<owner>/rules_corretto` location and the maintainer's GitHub username. **Ask the user before this step** — do not guess. Substitute `<OWNER>` and `<GH_USER>` below with the answers.

- [ ] **Step 2: Add LICENSE**

```bash
curl -fsSL https://www.apache.org/licenses/LICENSE-2.0.txt > LICENSE
```

(No NOTICE file needed: the updater's only third-party code is the Gson jar, a
build-time dependency fetched by hash — nothing third-party is vendored into
this repository.)

- [ ] **Step 3: Rewrite README.md**

```markdown
# rules_corretto

[Amazon Corretto](https://aws.amazon.com/corretto/) JDK toolchains for Bazel.

Adds Corretto as a selectable Java **runtime** alongside the JDKs `rules_java`
ships. Compilation toolchains (`--java_language_version`, turbine, ErrorProne)
are untouched — this module only provides runtimes.

## Setup

```starlark
# MODULE.bazel
bazel_dep(name = "rules_corretto", version = "<latest>")
```

```
# .bazelrc
build --java_runtime_version=corretto_21
build --tool_java_runtime_version=corretto_21
```

Nothing downloads until a build actually selects a Corretto runtime.

## Supported runtimes

`--java_runtime_version` values: `corretto_8`, `corretto_11`, `corretto_17`,
`corretto_21`, `corretto_25` — plus `corretto_alpine_<N>` for musl/Alpine.

| os / arch | x86_64 | aarch64 |
|---|---|---|
| Linux (glibc) | yes | yes |
| Linux (musl/Alpine) | yes | yes |
| macOS | yes | yes |
| Windows | yes | no (Amazon does not build it) |

Exactly one Corretto build is pinned per major version; the module release
determines the patch level. Always spell the flag `corretto_<N>` — bare `21`
also matches but resolution order is then undefined across modules.

## Pinning a different Corretto build

Root module only. Amazon publishes checksums only for the latest build of each
line, so supply the sha256 yourself for older builds:

```starlark
corretto = use_extension("@rules_corretto//corretto:extensions.bzl", "corretto")
corretto.pin(
    version = "21",
    corretto_version = "21.0.11.9.1",
    os = "linux",          # linux | alpine | macos | windows
    cpu = "x86_64",        # x86_64 | aarch64
    sha256 = "…",
)
```

URLs and archive prefixes are derived; only the checksum is yours to provide.

## Updating (maintainers)

```
bazel run //tools/update              # dry-run; exit 1 if configs are stale
bazel run //tools/update -- --write   # rewrite corretto/versions.bzl + MODULE.bazel
bazel run //tools/update -- --verify  # full-download check of every archive
```

The updater fetches Amazon's published index (one request: URLs + SHA-256s),
verifies derived archive prefixes by streaming only each archive's first entry
header, and reports JDK lifecycle changes (new LTS / EOL). A weekly workflow
runs it and opens a PR.
```

- [ ] **Step 4: Add .bcr templates**

`.bcr/metadata.template.json`:

```json
{
    "homepage": "https://github.com/<OWNER>/rules_corretto",
    "maintainers": [
        {
            "name": "<GH_USER>",
            "github": "<GH_USER>"
        }
    ],
    "repository": ["github:<OWNER>/rules_corretto"],
    "versions": [],
    "yanked_versions": {}
}
```

`.bcr/source.template.json`:

```json
{
    "integrity": "",
    "strip_prefix": "{REPO}-{VERSION}",
    "url": "https://github.com/{OWNER}/{REPO}/releases/download/{TAG}/{REPO}-{VERSION}.tar.gz"
}
```

`.bcr/presubmit.yml`:

```yaml
matrix:
  platform: ["debian11", "macos", "ubuntu2004", "windows"]
  bazel: [7.x, 8.x, 9.x]
tasks:
  verify_targets:
    name: Verify build targets
    platform: ${{ platform }}
    bazel: ${{ bazel }}
    build_targets:
      - "@rules_corretto//corretto/..."
bcr_test_module:
  module_path: "examples"
  matrix:
    platform: ["debian11", "macos", "ubuntu2004", "windows"]
    bazel: [7.x, 8.x, 9.x]
  tasks:
    run_test_module:
      name: Run example
      platform: ${{ platform }}
      bazel: ${{ bazel }}
      run_targets:
        - "//:hello"
```

`.bcr/config.yml`:

```yaml
fixedReleaser:
  login: <GH_USER>
  email: clayton.m.walker@gmail.com
```

Note: `bazel_compatibility` is `>=7.4.0` but presubmit lists 7.x — if 7.x fails in practice (e.g. `extension_metadata(reproducible=)` availability), narrow both to `[8.x, 9.x]` and bump `bazel_compatibility` to `>=8.0.0` in the same commit.

- [ ] **Step 5: Full-repo verification**

```bash
bazel test //... --test_output=errors
cd examples && bazel run //:hello --java_runtime_version=corretto_21 && cd ..
```

Expected: all tests PASS; example prints `vendor=Amazon.com Inc.`.

- [ ] **Step 6: Commit**

```bash
git add LICENSE README.md .bcr
git commit -m "docs: README, Apache-2.0 license, BCR templates"
```

---

## Self-Review Notes

- **Spec coverage:** UX flag path (T1/T2), alpine prefix (T1 constraint + T9 generation + T13 alpine CI job), extension pin escape hatch (T12), updater with indexmap source (T5/T9), lifecycle validation layer (T4/T9), streamed prefix verification (T8/T9), full `--verify` (T11), MODULE/versions sync guard (T10), deterministic/atomic output (T7/T9), weekly cron + PR (T13), release + BCR (T13/T14), seed-code disposition (T3), runtime-only scope + docs (T14). EOL-major removal surfaces via T4's lifecycleReport and T13's PR body.
- **Known deferred item:** spec's "unknown metadata keys are ignored **with a warning**" — T5 ignores silently by construction (it only looks up expected combos). Acceptable: unexpected *missing* combos still fail loudly, which is the load-bearing half. If warnings are wanted later, add a key-walk to IndexMap.
- **Type consistency check:** `Artifact` record shape (T5) matches all consumers; `StarlarkWriter.BEGIN_MARKER` string equals Task 1's MODULE.bazel comment; `Updater.Result` fields used by Main/tests consistently; `corretto_config` struct fields match `CORRETTO_JDK_CONFIGS` shape consumed by `_corretto_impl`.
