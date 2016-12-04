# Description:
#   Profiling library.
package(
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

java_library(
    name = "profiler",
    srcs = glob(["*.java"]),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:util-varint",
        "//src/main/java/com/google/devtools/build/lib:clock",
        "//src/main/java/com/google/devtools/build/lib:concurrent",
        "//src/main/java/com/google/devtools/build/lib:preconditions",
        "//third_party:guava",
    ],
)

# Profiler chart library.
java_library(
    name = "profiler-output",
    srcs = glob([
        "analysis/*.java",
        "chart/*.java",
        "output/*.java",
        "statistics/*.java",
    ]),
    deps = [
        ":profiler",
        "//src/main/java/com/google/devtools/build/lib:util",
        "//src/main/java/com/google/devtools/build/lib:vfs",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)
