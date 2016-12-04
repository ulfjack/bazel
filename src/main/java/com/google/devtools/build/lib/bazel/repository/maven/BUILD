package(
    default_visibility = ["//src:__subpackages__"],
)

java_library(
    name = "maven",
    srcs = glob(["*.java"]),
    runtime_deps = [
        "//third_party:aether",
        "//third_party:apache_commons_codec",
        "//third_party:apache_commons_logging",
        "//third_party:apache_httpclient",
        "//third_party:apache_httpcore",
        "//third_party:jsch",
        "//third_party:maven",
        "//third_party:maven_model",
        "//third_party:plexus_interpolation",
        "//third_party:plexus_utils",
        "//third_party:slf4j",
    ],
    deps = [
        ":maven-connector",
        "//src/main/java/com/google/devtools/build/lib:analysis",
        "//src/main/java/com/google/devtools/build/lib:concurrent",
        "//src/main/java/com/google/devtools/build/lib:events",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib:util",
        "//src/main/java/com/google/devtools/build/lib:packages-internal",
        "//src/main/java/com/google/devtools/build/lib:skylarkinterface",
        "//src/main/java/com/google/devtools/build/lib:vfs",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/cache",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/downloader",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/workspace/maven",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:aether",
        "//third_party:apache_commons_compress",
        "//third_party:guava",
        "//third_party:jgit",
        "//third_party:jsr305",
        "//third_party:maven",
        "//third_party:maven_model",
        "//third_party:plexus_component_annotations",
        "//third_party:xz",
    ],
)

java_library(
    name = "maven-connector",
    srcs = ["MavenConnector.java"],
    deps = [
        "//third_party:aether",
        "//third_party:maven_model",
    ],
)
