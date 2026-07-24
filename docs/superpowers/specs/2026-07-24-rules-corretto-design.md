# rules_corretto — Design

**Date:** 2026-07-24
**Status:** Approved (brainstorming complete; next step: implementation plan)

## Summary

`rules_corretto` is a public Bazel Central Registry (BCR) module that provides Amazon
Corretto JDK runtime toolchains as an alternative to the Zulu/Temurin JDKs that
`rules_java` ships by default. After adding one `bazel_dep`, users select a Corretto
runtime with `--java_runtime_version=corretto_21` (or `corretto_8/11/17/25`,
`corretto_alpine_<N>` for musl), exactly parallel to the built-in `remotejdk_21`.

The module is a thin wrapper over `rules_java`'s public
`@rules_java//toolchains:remote_java_repository.bzl` API. Most of the project's real
code is the **updater tool**: a small Java binary (Gson as its only third-party
dependency, pulled as a pinned `http_jar`) that regenerates the pinned JDK config list
from Amazon's published metadata, runnable standalone at any time, with scheduled CI as
a thin cron wrapper that opens update PRs.

## Requirements (decided during brainstorming)

- **Audience:** public BCR ruleset — docs, CI, semver releases, automated update pipeline.
- **UX:** both flag-based zero-config (`bazel_dep` + `--java_runtime_version=corretto_N`)
  and a module extension escape hatch for pinning non-default Corretto builds.
- **JDK majors:** all Corretto-supported LTS lines (currently 8, 11, 17, 21, 25), driven
  by Amazon's `version-info.json` so new LTS lines appear automatically in update PRs.
  Feature releases (currently 26) behind an updater flag, off by default.
- **Platforms:** Corretto's full standalone-archive set — linux x64/aarch64,
  macos x64/aarch64, windows x64, alpine (musl) x64/aarch64. Platforms Amazon does not
  build (windows arm64, ppc64le, s390x, riscv64) are simply not provided.
- **Pinning model:** match rules_java — exactly one pinned Corretto build per
  major × platform; the module release version determines the patch level. A
  root-module-only extension tag is the escape hatch for other builds.
- **Updates:** an in-repo tool that rewrites the pinned list in place; scheduled CI runs
  the same tool weekly and opens a PR when the output changes.

## Approach decision

Three approaches were considered:

- **A. Thin wrapper over rules_java's public API (chosen).** Call
  `remote_java_repository(prefix = "corretto", ...)`; rules_java generates the
  lazy-fetch config repos, flag-matching `config_setting`s, and `java_runtime` targets.
- **B. Self-contained (rules_graalvm style).** Hand-roll the same pattern; no dependency
  on rules_java internals but duplicates subtle logic that must track Bazel's
  toolchain-type migration.
- **C. Upstream Corretto into rules_java.** No separate module, but no control of the
  timeline and rules_java is unlikely to ship a parallel vendor-prefixed set.

A was chosen: the load path `@rules_java//toolchains:remote_java_repository.bzl` has no
Starlark load-visibility restriction, has been shape-stable since rules_java 5.x, and is
the mechanism Bazel's own documentation prescribes for custom JVMs. `rules_corretto`
already needs a `bazel_dep` on rules_java regardless (the generated JDK BUILD file loads
`java_runtime` from it).

## How the mechanism works (verified against rules_java 9.6.1)

`remote_java_repository(name, version, target_compatible_with, prefix, **http_archive_kwargs)`
creates two repos:

1. `http_archive` named `name` — the JDK itself, with a generated BUILD file defining
   `java_runtime(name = "jdk", version = <major>)`.
2. A tiny local `<name>_toolchain_config_repo` containing `config_setting`s for the
   exact flag values `"{prefix}_{version}"` and `"{version}"`, plus two `toolchain()`
   targets (`@bazel_tools//tools/jdk:runtime_toolchain_type` and
   `:bootstrap_runtime_toolchain_type`) pointing at `@<name>//:jdk`.

`--java_runtime_version` is never parsed; toolchain resolution simply matches the flag
value against these `config_setting`s (`target_settings`) plus platform constraints.
Therefore `corretto_21` works out of the box with `prefix = "corretto"`. Registering
only the config repos preserves lazy fetching: no JDK downloads unless selected.

Non-root modules may call `register_toolchains` (rules_java itself does), so end users
need only the `bazel_dep`.

## Section 1: User experience

Zero-config path:

```starlark
# MODULE.bazel
bazel_dep(name = "rules_corretto", version = "1.0.0")
```

```
# .bazelrc
build --java_runtime_version=corretto_25
build --tool_java_runtime_version=corretto_25
```

Supported flag spellings: `corretto_8`, `corretto_11`, `corretto_17`, `corretto_21`,
`corretto_25`, and `corretto_alpine_<N>` for musl. Alpine is a second prefix
(`prefix = "corretto_alpine"`): glibc and musl toolchains share the same platform
constraints (`linux` + cpu), and the flag string disambiguates exactly.

Bare `--java_runtime_version=21` also matches (rules_java generates a bare-version
`config_setting` for every remote JDK repo); which toolchain wins then depends on module
graph registration order. Documented as unsupported — users who want bare `21` to
resolve to Corretto register `@corretto21_*_toolchain_config_repo//:all` in their root
MODULE.bazel, which takes priority over all non-root registrations.

Escape hatch (root-module-only, same policy as rules_java's `java_repository`
extension):

```starlark
corretto = use_extension("@rules_corretto//corretto:extensions.bzl", "corretto")
corretto.pin(
    version = "21",                    # major line to override
    corretto_version = "21.0.11.9.1",  # full Corretto version
    os = "linux",                      # linux | alpine | macos | windows
    cpu = "x86_64",                    # x86_64 | aarch64
    sha256 = "abc123...",              # user-computed (Amazon publishes checksums only for latest builds)
)
```

URLs and strip_prefix are derived from `corretto_version + os + cpu` (patterns below),
so the user supplies only the checksum — less ceremony than rules_java's escape hatch.
A pin replaces the default repo for that major × platform. `pin` from a non-root module
is an error.

## Section 2: Module architecture

```
rules_corretto/
├── MODULE.bazel                  # deps; inline repo-name list; use_repo + register_toolchains
├── corretto/
│   ├── BUILD.bazel
│   ├── extensions.bzl            # module extension
│   └── versions.bzl              # GENERATED: CORRETTO_JDK_CONFIGS struct list
├── tools/update/                 # updater Java binary (Gson via pinned http_jar)
├── examples/                     # real consumer workspace; doubles as integration test
├── test/                         # consistency + updater unit tests
└── .github/workflows/            # ci.yml, update.yml, release.yml
```

`versions.bzl` holds `CORRETTO_JDK_CONFIGS`: a list of
`struct(name, version, target_compatible_with, sha256, strip_prefix, urls)` — the same
shape as rules_java's `_REMOTE_JDK_CONFIGS_LIST`. Marked generated ("do not edit; run
`bazel run //tools/update -- --write`").

`extensions.bzl` (~20 lines): apply root-module `pin` tags over the defaults, then call
`remote_java_repository(...)` per config with the appropriate prefix
(`corretto` or `corretto_alpine`). Returns `extension_metadata(reproducible = True)` so
the extension stays out of MODULE.bazel.lock.

`MODULE.bazel`:

```starlark
module(name = "rules_corretto", version = "...", bazel_compatibility = [">=7.0.0"])
bazel_dep(name = "rules_java", version = "9.6.1")
bazel_dep(name = "platforms", version = "0.0.11")

corretto = use_extension("//corretto:extensions.bzl", "corretto")
# GENERATED BLOCK — kept in sync with corretto/versions.bzl by //tools/update
use_repo(corretto, "corretto8_linux_toolchain_config_repo", ...)
register_toolchains("@corretto8_linux_toolchain_config_repo//:all")
...
```

Constraints the design must respect (verified in rules_java 9.6.1 source):

- **`version` must be the bare major string** (`"21"`): rules_java substitutes it
  unquoted into the generated `java_runtime(version = ...)`. Full Corretto versions
  appear only in `urls`/`strip_prefix`.
- **MODULE.bazel cannot `load()`**, so the repo-name list is an inline literal there;
  the updater rewrites it (between marker comments) together with `versions.bzl`, and a
  checked-in consistency test fails CI if they diverge.
- **Register only `*_toolchain_config_repo//:all`** (both `:toolchain` and
  `:bootstrap_runtime_toolchain` are required), never the JDK repos — preserves lazy
  fetch. JDK repos need no `use_repo` (config repos reference them by apparent name
  within the same extension).

Repo naming mirrors rules_java for familiarity: `corretto21_linux`,
`corretto21_linux_aarch64`, `corretto21_macos`, `corretto21_macos_aarch64`,
`corretto21_win`, `corretto_alpine21_linux`, `corretto_alpine21_linux_aarch64`, etc.

**Scope boundary:** runtime toolchains only. Compilation toolchains (`java_toolchain`,
`--java_language_version`, turbine/ErrorProne) remain rules_java's. This is also why
JDK 8 is safe to ship: it is a runtime target, never the compile JVM.

## Section 3: Updater tool

A Java binary built by rules_java in this repo (dogfooding). All JSON parsing uses Gson
(2.11.0, fetched as a single pinned `http_jar` dev dependency — Gson has no transitive
deps, so no rules_jvm_external is needed):

```
bazel run //tools/update             # dry-run: print diff, exit non-zero if stale
bazel run //tools/update -- --write  # rewrite corretto/versions.bzl + MODULE.bazel block
bazel run //tools/update -- --verify # download changed archives; check sha256 + strip_prefix
```

**Data sources** (both in the `corretto/corretto-downloads` GitHub repo, `latest_links/`):

- `version-info.json` — which majors to emit: `supported_lts_releases`
  (currently `[8, 11, 17, 21, 25]`); `supported_feature_releases` included only with
  `--include-feature-releases`. This pass doubles as a validation layer: the updater
  diffs the supported-majors set against what `versions.bzl` currently contains and
  reports additions (new LTS promoted) and removals (line gone EOL) explicitly, so a
  human reviewing the update PR sees lifecycle changes called out rather than buried in
  config churn.
- `indexmap_with_checksum.json` (~249 KB, single fetch) — for the latest build of every
  major × os × arch × image type × format: the permanent CDN resource path
  (`/downloads/resources/<full_version>/<filename>`) and `checksum_sha256`.
  **No JDK archives are downloaded to compute checksums.**

Filter: `image_type == "jdk"`, format ∈ {`tar.gz`, `zip`}, os ∈ {linux, alpine, macos,
windows}, arch ∈ {x64, aarch64}.

**Derivation rules** (verified against real archives, 2026-07):

| Field | Rule |
|---|---|
| `version` | second path segment of `resource` (e.g. `21.0.12.8.1`) → emit major only |
| `urls` | `https://corretto.aws` + `resource` — permanent and immutable. The `downloads/latest/...` aliases are NOT listed (their content changes over time, breaking reproducibility). |
| `sha256` | `checksum_sha256` from the indexmap |
| `strip_prefix` (linux/alpine) | filename stem, e.g. `amazon-corretto-21.0.12.8.1-linux-x64` |
| `strip_prefix` (macos) | `amazon-corretto-<major>.jdk/Contents/Home` — bundle layout, stable across patch releases; puts JAVA_HOME at repo root |
| `strip_prefix` (windows) | `jdk<a>.<b>.<c>_<d>` for Corretto version `a.b.c.d.e` (e.g. `21.0.12.8.1` → `jdk21.0.12_8`); JDK 8 special case: `8.502.07.1` → `jdk1.8.0_502` (`jdk1.8.0_<update>`) |

**Streamed strip_prefix verification (cheap, every run):** because Windows and JDK 8
prefixes are derived rather than read from metadata, the updater verifies every derived
`strip_prefix` by streaming just the beginning of the archive over HTTP — enough to
read the first tar entry header (tar.gz) or first local file header (zip), which
carries the top-level directory name — then aborting the connection. Kilobytes per
archive instead of hundreds of megabytes, so this check runs on every updater
invocation for changed entries, and a derivation-rule drift (e.g. Amazon changing the
Windows layout) is caught at generation time, not in CI. The same
partial-stream-then-abort idea applies to JSON: Gson's `JsonParser.parseReader` reads
exactly one value and does not demand EOF, so metadata can be pulled from a live HTTP
stream without consuming the whole body.

**Full verification (`--verify`, CI on update PRs only):** downloads each archive
completely, recomputes sha256 against the indexmap value (defense-in-depth — the
checksum already comes from Amazon's metadata), and re-asserts the top-level
directory — the analogue of rules_java's `check_remote_jdk_configs.sh`.
*(Revised 2026-07-24: implemented as all-entries rather than changed-entries-only —
strictly safer, ~5–7 GB streamed per CI verify run, accepted.)*

Output is deterministic (stable ordering: major, then os, then arch) so diffs are
reviewable and re-runs are idempotent.

## Section 4: Testing, CI, release

- **Updater unit tests** (`java_test`): indexmap parsing, URL/strip_prefix derivation
  golden cases (including windows and JDK 8 oddities), versions.bzl emission, in-place
  MODULE.bazel block rewriting.
- **Consistency test** (Starlark): MODULE.bazel's inline repo-name list ==
  names derived from `versions.bzl`. Fails CI on divergence.
- **Integration test:** `examples/` is a real consumer workspace. CI matrix
  (ubuntu/macos/windows GitHub runners) runs
  `bazel run //:hello --java_runtime_version=corretto_<N>` and asserts
  `System.getProperty("java.vendor") == "Amazon.com Inc."`. Alpine has no CI job
  (decision 2026-07-24: Bazel inside musl containers is unreliable); alpine
  toolchains are covered by the updater's checksum + streamed strip_prefix
  verification.
- **update.yml:** weekly cron → `bazel run //tools/update -- --write` → if the working
  tree is dirty, open a PR. The PR's CI runs `--verify` plus the full suite. Human
  merges; the tool is equally runnable locally at any time.
- **release.yml:** tag-driven, following bazel-contrib rules-template conventions —
  release archive with integrity hash in release notes, BCR submission via the
  Publish-to-BCR workflow. Semver; a JDK-refresh-only release is a patch bump.

## Error handling and edge cases

- **Platforms Corretto doesn't build** (windows arm64, ppc64le, s390x, riscv64): no
  toolchain is provided; requesting `corretto_N` on such a target platform fails
  toolchain resolution with Bazel's standard "no matching toolchain" error. README
  documents the coverage matrix.
- **macOS bundle stripping:** stripping `Contents/Home` out of the signed
  `amazon-corretto-<N>.jdk` bundle breaks the bundle-level code-signature structure;
  individual binaries remain signed. Identical situation to every existing Bazel remote
  JDK on macOS — acceptable.
- **Updater network failures / metadata shape drift:** dry-run and `--write` fail loudly
  (non-zero exit, no partial writes — output is written atomically after full parse).
  Unknown os/arch/format keys in the indexmap are ignored with a warning, so Amazon
  adding platforms never breaks the cron; a missing *expected* combination (regression
  in the matrix) is an error.
- **EOL majors:** when Amazon drops a line from `supported_lts_releases`, the updater
  drops its configs; the PR diff makes the removal explicit and the release notes call
  it out as a breaking change (minor/major bump, not patch).

## Out of scope (YAGNI)

- WORKSPACE (non-bzlmod) support — Bazel 9 targets bzlmod only.
- JRE image types, debug symbols, jmods-only archives.
- Compilation toolchain configuration.
- Historical version catalog (append-only list) — revisit only if users ask.
- Mirror URLs (e.g. mirror.bazel.build does not mirror corretto.aws; single permanent
  URL is sufficient given the CDN's stability).

## Existing seed code disposition

- `java/repositories.bzl` (copied Zulu list): reference material; superseded by
  generated `corretto/versions.bzl`; deleted once the generator lands.
- `java/bazel/src/main/java/com/example/ProjectRunner.java`: already implemented the
  first-level pass — fetching and parsing `version-info.json` (the supported-majors
  validation layer above). Its role is reimplemented, with tests, as the updater's
  `VersionInfo` + `Main`.
- `JsonParser.java`: originally planned as a vendored MIT parser, but its only findable
  upstream (TheKodeToad's parser in PrismLauncher) is GPL-3.0-only, incompatible with
  this repo's Apache-2.0 license. **Decision (2026-07-24): all JSON reading uses Gson
  instead** — license-clean, battle-tested, and its `parseReader` preserves the needed
  read-one-value-then-abort streaming property.
