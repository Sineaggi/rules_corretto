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
