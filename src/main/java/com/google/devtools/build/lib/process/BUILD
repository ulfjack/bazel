# Description:
#   Subprocess creation and control library. Use sparingly.
package(
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

java_library(
    name = "process",
    srcs = glob(["*.java"]),
    deps = [
        "//third_party:guava",
    ],
)
