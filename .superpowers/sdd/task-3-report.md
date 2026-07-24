# Task 3 Report: Updater scaffolding — Gson dependency, test harness, dogfooded build

## Correction notice

This report replaces an earlier version written against commit `71f99d3`. That
earlier report was **inaccurate in two ways**, both caught in review:

1. It described the work as moving/vendoring a third-party `JsonParser`. In
   fact `71f99d3` wrote a **from-scratch** `JsonParser.java` implementation
   under `tools/update/src/main/java/corretto/update/` — nothing was moved
   from anywhere, and the class carried no license header, so its provenance
   was unstated and, on later inspection, its only findable upstream analog
   is GPL-3.0-only. Framing it as a vendored move misrepresented what the
   commit actually did.
2. It claimed a legacy `java/` directory was deleted as part of that commit.
   No such directory exists anywhere in this repository's git history; there
   was nothing to delete. That claim was simply false.

Per user decision, the from-scratch parser is dropped entirely. **All JSON
parsing in this project now goes through Gson** (pinned jar, dev-only
dependency). This report describes that fix, done on top of the current
branch (commit `a95ae71`, which already corrected the plan/spec docs).

## What changed in this fix

**Deleted** (superseded implementation from `71f99d3`):
- `tools/update/src/main/java/corretto/update/JsonParser.java`
- `tools/update/src/test/java/corretto/update/JsonParserTest.java`

**Created:**
- `tools/update/gson.bzl` — a `module_extension` (`gson_ext`) that fetches
  Gson 2.11.0 as a single pinned `http_jar` (sha256
  `57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b`), no
  transitive deps.
- `tools/update/src/test/java/corretto/update/JsonSmokeTest.java` — a
  plain-`main` smoke test exercising the Gson tree API (`JsonObject`,
  `JsonArray`, `JsonElement`).

**Modified:**
- `MODULE.bazel` — appended, *after* the `# END GENERATED REPOS` marker
  (never inside the generated block):
  ```starlark
  gson = use_extension("//tools/update:gson.bzl", "gson_ext", dev_dependency = True)
  use_repo(gson, "gson")
  ```
  `dev_dependency = True` means consumers of this module never fetch Gson;
  it exists only for this repo's own test/tooling.
- `tools/update/BUILD.bazel` — `update_lib` now globs
  `src/main/java/corretto/update/*.java` with `allow_empty = True` (no
  main-tree sources exist until Task 4) and depends on `@gson//jar`. The
  `json_parser_test` target is replaced by `json_smoke_test`
  (`use_testrunner = False`, plain `main_class`), depending on `:test_lib`
  and `@gson//jar`.

**Unchanged:**
- `tools/update/src/test/java/corretto/update/Check.java` — already
  byte-identical to the revised brief; kept as-is.

## A defect found in the brief, and how it was resolved

The brief's `JsonSmokeTest.java` used
`com.google.gson.JsonParser.parseReader(Reader)` for both assertions,
including one meant to demonstrate that parsing "reads ONE value and does
not demand EOF" (leaving `" TRAILING GARBAGE"` unread after `{"x": 1}`).

Running the test against the actual pinned jar (Gson 2.11.0) failed with:

```
com.google.gson.JsonSyntaxException: com.google.gson.stream.MalformedJsonException:
Use JsonReader.setStrictness(Strictness.LENIENT) to accept malformed JSON at line 1 column 11 path $
```

Investigation (a small standalone probe compiled and run against the exact
jar pulled into the Bazel cache) showed that in Gson 2.11.0,
`JsonParser.parseReader(Reader)` internally peeks past the parsed value to
confirm the document is fully consumed, and throws on non-whitespace
trailing content. `JsonParser.parseReader(JsonReader)` — the overload that
takes an already-constructed `JsonReader` — does **not** do this check; it
simply stops reading after the first value, exactly as the brief describes.

So the brief's premise (streaming contract, no forced EOF) is correct for
Gson 2.11.0, but the specific overload named in its interface note is the
wrong one for that use case. `JsonSmokeTest.java` was adjusted to construct a
`JsonReader` explicitly and pass that to `JsonParser.parseReader` for the
trailing-garbage assertion, with a comment recording why. The first
assertion (no trailing content) still uses the plain `Reader` overload,
which works fine there. Later tasks that need to parse from a live,
early-closed HTTP stream (per the brief's Task 8 note) must use the
`JsonReader` overload, not the bare `Reader` one.

## Test result

```
bazel test //tools/update:json_smoke_test --test_output=errors
```

```
//tools/update:json_smoke_test                                           PASSED in 0.1s
Executed 1 out of 1 test: 1 test passes.
```

Test log output: `JsonSmokeTest OK`.

## Current tree state (`tools/update/`)

```
tools/update/
├── BUILD.bazel
├── gson.bzl
└── src
    ├── main/java/corretto/update/        (empty; populated starting Task 4)
    └── test/java/corretto/update/
        ├── Check.java
        └── JsonSmokeTest.java
```

`MODULE.bazel.lock` was also updated by the `bazel test` run above (Bazel's
normal lockfile behavior on first resolution of the new extension graph in
this workspace) and is included in this commit.

## Status: COMPLETE

Gson-only JSON parsing scaffolding is in place, the superseded from-scratch
parser and its test are removed, the smoke test passes with pristine output,
and this report now accurately reflects both the current state and the
mistakes in the report it replaces.
