package(
    default_visibility = ["//src:__subpackages__"],
)

java_library(
    name = "repository",
    srcs = glob(
        [
            "*.java",
            "skylark/*.java",
        ],
    ),
    runtime_deps = [
        "//third_party:apache_commons_codec",
        "//third_party:apache_commons_logging",
        "//third_party:apache_httpclient",
        "//third_party:apache_httpcore",
        "//third_party:jsch",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/workspace",
        "//src/main/java/com/google/devtools/build/lib:analysis",
        "//src/main/java/com/google/devtools/build/lib:concurrent",
        "//src/main/java/com/google/devtools/build/lib:events",
        "//src/main/java/com/google/devtools/build/lib:io",
        "//src/main/java/com/google/devtools/build/lib:other-rules",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib:util",
        "//src/java_tools/singlejar/java/com/google/devtools/build/zip",
        "//src/main/java/com/google/devtools/build/lib:packages-internal",
        "//src/main/java/com/google/devtools/build/lib:skylarkinterface",
        "//src/main/java/com/google/devtools/build/lib:vfs",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/cache",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/downloader",
        "//src/main/java/com/google/devtools/build/lib/process",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:apache_commons_compress",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jgit",
        "//third_party:jsr305",
        "//third_party:xz",
    ],
)