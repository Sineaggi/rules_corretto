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
