# GENERATED FILE - do not edit by hand.
# Regenerate with: bazel run //tools/update -- --write
# (This seed copy is hand-written until //tools/update exists; see Task 9.)

CORRETTO_JDK_CONFIGS = [
    struct(
        name = "corretto21_linux",
        prefix = "corretto",
        version = "21",
        target_compatible_with = ["@platforms//os:linux", "@platforms//cpu:x86_64"],
        sha256 = "75faed442d38a89c27f920e45ab24f9f71ff8ca6b732bfea90cdb500decd3c6b",
        strip_prefix = "amazon-corretto-21.0.12.8.1-linux-x64",
        urls = ["https://corretto.aws/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-linux-x64.tar.gz"],
    ),
    struct(
        name = "corretto21_macos_aarch64",
        prefix = "corretto",
        version = "21",
        target_compatible_with = ["@platforms//os:macos", "@platforms//cpu:aarch64"],
        sha256 = "cb230d7ac82784a4438663cdaf91d0d04037a9b4fb99ea41e138d88ce1224ab7",
        strip_prefix = "amazon-corretto-21.jdk/Contents/Home",
        urls = ["https://corretto.aws/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-macosx-aarch64.tar.gz"],
    ),
]
