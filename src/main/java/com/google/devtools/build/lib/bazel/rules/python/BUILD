package(
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

java_library(
    name = "python",
    srcs = glob(["*.java"]),
    resources = glob(
        [
            "*.txt",
        ],
    ),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:analysis",
        "//src/main/java/com/google/devtools/build/lib:build-info",
        "//src/main/java/com/google/devtools/build/lib:concurrent",
        "//src/main/java/com/google/devtools/build/lib:packages-internal",
        "//src/main/java/com/google/devtools/build/lib:python-rules",
        "//src/main/java/com/google/devtools/build/lib:util",
        "//src/main/java/com/google/devtools/build/lib:vfs",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/cpp",
        "//src/main/java/com/google/devtools/build/lib/rules/cpp",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
    ],
)
