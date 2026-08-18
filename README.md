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

## Runtime vs. tool runtime

`--java_runtime_version` selects the JVM your `java_binary` / `java_test`
targets run on. `--tool_java_runtime_version` selects the JVM for Java
programs Bazel runs in the exec configuration — `java_binary` tools used by
genrules and the like, plus rules_java's bootclasspath derivation. The flags
are independent, and mixing versions is normal:
`--tool_java_runtime_version=corretto_21` with
`--java_runtime_version=corretto_8` runs modern tooling while your code still
targets and runs on JDK 8.

The tool runtime resolves for the **exec** platform (the machine executing
build actions), so the Windows aarch64 gap in the matrix above affects ARM
Windows build hosts — including remote executors — even when they build for
another platform.

Cross-version compilation needs no extra setup: the registered toolchains
include the bootstrap runtime rules_java derives the bootclasspath from when
`--java_language_version` is lower than the runtime's major version.

### Fully single-vendor builds

The two flags do not cover everything: rules_java's default *compilation*
toolchain pins the JVM that runs its build tools (JavaBuilder, turbine) to a
specific `remotejdk_<N>`, and that Zulu JDK is downloaded and used regardless
of either flag. To put those tools on Corretto too, define and register your
own compilation toolchain:

```starlark
# BUILD (root package)
load("@rules_java//toolchains:default_java_toolchain.bzl", "DEFAULT_TOOLCHAIN_CONFIGURATION", "default_java_toolchain")
load("@rules_java//toolchains:java_toolchain_alias.bzl", "java_runtime_version_alias")

java_runtime_version_alias(
    name = "corretto_21_runtime",
    runtime_version = "corretto_21",
)

default_java_toolchain(
    name = "corretto_toolchain",
    configuration = DEFAULT_TOOLCHAIN_CONFIGURATION | {"java_runtime": ":corretto_21_runtime"},
    source_version = "21",
    target_version = "21",
)
```

```starlark
# MODULE.bazel — root-registered toolchains outrank rules_java's defaults
register_toolchains("//:corretto_toolchain_definition")
```

With this plus both flags, no other JDK appears in the action graph. Two
things remain outside this module's reach: the JDK embedded in the `bazel`
binary (it runs the Bazel server), and the build tools' own jars
(`remote_java_tools`), which are vendor-neutral and simply run on Corretto.

The `examples/` module exercises this full setup.

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
