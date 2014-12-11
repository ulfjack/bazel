java_library(
    name = "shell",
    srcs = glob(["com/google/devtools/build/lib/shell/*.java"]),
    visibility = ["//src:__subpackages__"],
    deps = ["//third_party:guava"],
)

java_library(
    name = "bazel-core",
    srcs = glob(
        ["**/*.java"],
        exclude = ["com/google/devtools/build/lib/shell/*.java"],
    ),
    resources = glob([
        "**/*.txt",
        "**/*.html",
        "**/*.css",
        "**/*.js",
    ]),
    visibility = ["//src/test/java:__subpackages__"],
    deps = [
        ":shell",
        "//src/main/protobuf:proto_build",
        "//src/main/protobuf:proto_bundlemerge",
        "//src/main/protobuf:proto_crosstool_config",
        "//src/main/protobuf:proto_extra_actions_base",
        "//src/main/protobuf:proto_test_status",
        "//src/main/protobuf:proto_xcodegen",
        "//src/tools/xcode-common",
        "//third_party:apache_commons_compress",
        "//third_party:gson",
        "//third_party:guava",
        "//third_party:joda-time",
        "//third_party:jsr305",
        "//third_party:protobuf",
    ],
)

java_binary(
    name = "bazel-main",
    main_class = "com.google.devtools.build.lib.bazel.BazelMain",
    visibility = ["//src:__pkg__"],
    runtime_deps = [
        ":bazel-core",
    ],
)
