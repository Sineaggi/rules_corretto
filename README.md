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
