# Handoff: rules_corretto

State as of 2026-08-18, `main` @ `e9f8302`. The module is feature-complete and
verified locally: 35 pinned Corretto configs (majors 8/11/17/21/25 × 7
platforms), flag-based selection (`--java_runtime_version=corretto_21`),
root-module pin override, standalone updater with weekly CI, and an examples
module that proves a fully single-vendor action graph (JavaBuilder/turbine on
Corretto via a custom `default_java_toolchain`). `bazel test //...` passes
11/11; MODULE.bazel is buildifier-stable.

## Launch checklist (user actions)

1. **Repo hygiene** — two untracked files in the root are pre-git
   rules_jvm_external experiments, unused by the project. Delete or keep:
   `maven_install.json`, `rules_jvm_external++maven+maven_install.json`.

2. **Push** — `git push -u origin main` to
   [github.com/Sineaggi/rules_corretto](https://github.com/Sineaggi/rules_corretto).
   CI (`ci.yml`) runs on push/PR across ubuntu/macos/windows and asserts the
   example prints `vendor=Amazon.com Inc.`.

3. **Enable PR creation for the update workflow** — `update.yml` (Mondays
   06:00 UTC) opens PRs via `peter-evans/create-pull-request`. In repo
   Settings → Actions → General, enable *"Allow GitHub Actions to create and
   approve pull requests"*, or the weekly run will fail at the PR step.

4. **First release** — push a `v*` tag (e.g. `v0.1.0`). `release.yml` builds
   the `git archive` tarball and creates the GitHub release. The version in
   MODULE.bazel stays `0.0.0` in-repo; BCR patches it at publish time.

5. **BCR submission** — `.bcr/` templates are ready (metadata, source URL
   using `{TAG}`, presubmit scoped to `@rules_corretto//corretto:all`,
   fixedReleaser `Sineaggi`). Easiest path: install the
   [Publish to BCR](https://github.com/apps/publish-to-bcr) GitHub app on the
   repo, then cutting a release auto-opens the registry PR. Manual
   alternative: fork `bazelbuild/bazel-central-registry` and add the module
   entry by hand.

## Optional polish (flagged in final review, not blocking)

- **Updater HTTP timeouts** — `Fetcher.http()` sets no connect/read timeouts;
  a hung Amazon endpoint would stall the weekly job until the Actions
  timeout. Add `HttpClient` connect timeout + per-request timeout.
- **CI Bazel caching** — no disk/repo cache in `ci.yml`; each run re-fetches
  JDKs and rebuilds. `bazelbuild/setup-bazelisk` + a cache step (or
  `--disk_cache` keyed on lockfiles) would cut CI time substantially.
- **Test size attributes** — `bazel test //...` warns some tests declare a
  larger `size` than needed. Add `size = "small"` to the `java_test` targets
  in `tools/update/BUILD.bazel`.

## Orientation for future work

- `corretto/versions.bzl` and the marked block in `MODULE.bazel` are
  **generated** — never hand-edit; run `bazel run //tools/update -- --write`.
  The `sync_test` fails CI if they drift.
- Updater entry points: dry-run `bazel run //tools/update` (exit 1 = stale),
  `-- --write` to regenerate, `-- --verify` to full-download-check every
  archive, `-- --include-feature-releases` when Amazon adds a non-LTS line.
- The updater reports JDK lifecycle changes (new LTS / EOL) from Amazon's
  `version-info.json`; a new LTS appears as an update-PR diff adding configs.
- Design/spec/plan live under `docs/superpowers/`; the README's
  "Runtime vs. tool runtime" section documents which JVM each flag actually
  controls (note: rules_java pins JavaBuilder's JVM — only the custom
  toolchain in `examples/` overrides it).
