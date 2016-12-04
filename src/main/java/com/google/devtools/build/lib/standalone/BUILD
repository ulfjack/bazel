package(
    default_visibility = ["//src:__subpackages__"],
)

java_library(
    name = "standalone",
    srcs = glob(["*.java"]),
    data = [
        "//src/main/tools:process-wrapper",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:analysis",
        "//src/main/java/com/google/devtools/build/lib:events",
        "//src/main/java/com/google/devtools/build/lib:exec",
        "//src/main/java/com/google/devtools/build/lib:io",
        "//src/main/java/com/google/devtools/build/lib:packages-internal",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib:util",
        "//src/main/java/com/google/devtools/build/lib:vfs",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/process",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:test_status_java_proto",
        "//third_party:guava",
    ],
)

filegroup(
    name = "srcs",
    testonly = 0,  # All srcs should be not test only, overwrite package default.
    srcs = glob(["**"]),
)
