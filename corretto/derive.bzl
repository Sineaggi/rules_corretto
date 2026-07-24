"""URL/strip_prefix/name derivation for Corretto artifacts.

Starlark mirror of tools/update's Derive.java, used only for the corretto.pin
extension tag. Keep the two in sync; both are covered by golden tests using the
same verified example values.
"""

_OS_FILE_PART = {
    "alpine": "alpine-linux",
    "linux": "linux",
    "macos": "macosx",
    "windows": "windows",
}

_CPU_FILE_PART = {
    "aarch64": "aarch64",
    "x86_64": "x64",
}

def corretto_url(corretto_version, os, cpu):
    base = "https://corretto.aws/downloads/resources/{v}/amazon-corretto-{v}-{os}-{cpu}".format(
        v = corretto_version,
        os = _OS_FILE_PART[os],
        cpu = _CPU_FILE_PART[cpu],
    )
    if os == "windows":
        return base + "-jdk.zip"
    return base + ".tar.gz"

def corretto_strip_prefix(corretto_version, os, cpu):
    major = corretto_version.split(".")[0]
    if os == "macos":
        return "amazon-corretto-{}.jdk/Contents/Home".format(major)
    if os == "windows":
        p = corretto_version.split(".")
        if major == "8":
            return "jdk1.8.0_{}".format(int(p[1]))
        return "jdk{}.{}.{}_{}".format(p[0], p[1], p[2], int(p[3]))
    return "amazon-corretto-{v}-{os}-{cpu}".format(
        v = corretto_version,
        os = _OS_FILE_PART[os],
        cpu = _CPU_FILE_PART[cpu],
    )

def corretto_repo_name(major, os, cpu):
    base = "corretto_alpine" if os == "alpine" else "corretto"
    os_part = {"alpine": "linux", "linux": "linux", "macos": "macos", "windows": "win"}[os]
    arch_part = "" if cpu == "x86_64" else "_aarch64"
    return base + major + "_" + os_part + arch_part

def corretto_config(version, corretto_version, os, cpu, sha256):
    if not corretto_version.startswith(version + "."):
        fail("corretto_version {} does not belong to major {}".format(corretto_version, version))
    os_constraint = "@platforms//os:windows" if os == "windows" else (
        "@platforms//os:macos" if os == "macos" else "@platforms//os:linux"
    )
    return struct(
        name = corretto_repo_name(version, os, cpu),
        prefix = "corretto_alpine" if os == "alpine" else "corretto",
        version = version,
        target_compatible_with = [os_constraint, "@platforms//cpu:" + cpu],
        sha256 = sha256,
        strip_prefix = corretto_strip_prefix(corretto_version, os, cpu),
        urls = [corretto_url(corretto_version, os, cpu)],
    )
