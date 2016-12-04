package(
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

java_library(
    name = "rules",
    srcs = glob(
        [
            "**/*.java",
        ],
        exclude = [
            "workspace/*.java",
        ],
    ),
    resources = glob(
        [
            "**/*.txt",
            "**/*.WORKSPACE",
        ],
    ),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:RpcSupport",
        "//src/main/java/com/google/devtools/build/lib:android-rules",
        "//src/main/java/com/google/devtools/build/lib:bazel",
        "//src/main/java/com/google/devtools/build/lib:build-base",
        "//src/main/java/com/google/devtools/build/lib:build-info",
        "//src/main/java/com/google/devtools/build/lib:collect",
        "//src/main/java/com/google/devtools/build/lib:concurrent",
        "//src/main/java/com/google/devtools/build/lib:events",
        "//src/main/java/com/google/devtools/build/lib:exec",
        "//src/main/java/com/google/devtools/build/lib:ideinfo",
        "//src/main/java/com/google/devtools/build/lib:java-compilation",
        "//src/main/java/com/google/devtools/build/lib:java-rules",
        "//src/main/java/com/google/devtools/build/lib:packages-internal",
        "//src/main/java/com/google/devtools/build/lib:proto-rules",
        "//src/main/java/com/google/devtools/build/lib:python-rules",
        "//src/main/java/com/google/devtools/build/lib:util",
        "//src/main/java/com/google/devtools/build/lib:vfs",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/maven",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/workspace",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/workspace/maven",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/query2:query-output",
        "//src/main/java/com/google/devtools/build/lib/rules/apple",
        "//src/main/java/com/google/devtools/build/lib/rules/apple/cpp",
        "//src/main/java/com/google/devtools/build/lib/rules/apple/swift",
        "//src/main/java/com/google/devtools/build/lib/rules/config",
        "//src/main/java/com/google/devtools/build/lib/rules/cpp",
        "//src/main/java/com/google/devtools/build/lib/rules/cpp/proto:CcProtoLibrary",
        "//src/main/java/com/google/devtools/build/lib/rules/genrule",
        "//src/main/java/com/google/devtools/build/lib/rules/genquery",
        "//src/main/java/com/google/devtools/build/lib/rules/objc",
        "//src/main/java/com/google/devtools/build/lib/rules/platform",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:crosstool_config_java_proto",
        "//third_party:android_common_25_0_0",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:joda_time",
        "//third_party:jsr305",
    ],
)
