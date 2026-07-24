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
