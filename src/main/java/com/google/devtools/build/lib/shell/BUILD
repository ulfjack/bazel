# Description:
#   Shell utilities for Bazel
package(
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

java_library(
    name = "shell",
    srcs = glob(["*.java"]),
    deps = [
        "//third_party:guava",
    ],
)
