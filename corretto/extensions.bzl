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
