# Maven rules for WORKSPACE files.
package(
    default_visibility = ["//src:__subpackages__"],
)

java_library(
    name = "maven",
    srcs = glob(["*.java"]),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:analysis",
        "//src/main/java/com/google/devtools/build/lib:syntax",
        "//src/main/java/com/google/devtools/build/lib:packages-internal",
        "//src/main/java/com/google/devtools/build/lib/rules:other-rules",
    ],
)