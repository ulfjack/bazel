# Description:
#   Java interfaces to various native Windows system services.
package(
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

java_library(
    name = "windows",
    srcs = glob([
        "*.java",
    ]),
    visibility = ["//visibility:public"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:base-util",
        "//src/main/java/com/google/devtools/build/lib:clock",
        "//src/main/java/com/google/devtools/build/lib:concurrent",
        "//src/main/java/com/google/devtools/build/lib:os_util",
        "//src/main/java/com/google/devtools/build/lib:preconditions",
        "//src/main/java/com/google/devtools/build/lib:util",
        "//src/main/java/com/google/devtools/build/lib:vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/com/google/devtools/build/lib/process",
        "//src/main/java/com/google/devtools/build/lib:util-shell",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)
