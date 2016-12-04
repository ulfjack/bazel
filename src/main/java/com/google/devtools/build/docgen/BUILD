# Description:
#   Documentation generator for Bazel
package(
    default_visibility = ["//src:__subpackages__"],
)

java_library(
    name = "docgen_rules",
    srcs = glob(["rules/*.java"]),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:analysis",
        "//src/main/java/com/google/devtools/build/lib:packages",
        "//src/main/java/com/google/devtools/build/lib:skylarkinterface",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "docgen_javalib",
    srcs = glob(
        ["**/*.java"],
        exclude = ["rules/*.java"],
    ),
    resources = [":template_files"],
    exports = [
        ":docgen_rules",
    ],
    deps = [
        ":docgen_rules",
        "//src/main/java/com/google/devtools/build/lib:analysis",
        "//src/main/java/com/google/devtools/build/lib:packages",
        "//src/main/java/com/google/devtools/build/lib:skylarkinterface",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/rules:android-rules",
        "//src/main/java/com/google/devtools/build/lib/rules:java-compilation",
        "//src/main/java/com/google/devtools/build/lib/rules:java-rules",
        "//src/main/java/com/google/devtools/build/lib/rules/apple",
        "//src/main/java/com/google/devtools/build/lib/rules/apple/swift",
        "//src/main/java/com/google/devtools/build/lib/rules/cpp",
        "//src/main/java/com/google/devtools/build/lib/rules/objc",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:apache_velocity",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_binary(
    name = "docgen_bin",
    srcs = ["BuildEncyclopediaGenerator.java"],
    main_class = "com.google.devtools.build.docgen.BuildEncyclopediaGenerator",
    deps = [
        ":docgen_javalib",
        "//src/main/java/com/google/devtools/build/lib:analysis",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules",
        "//src/main/java/com/google/devtools/common/options",
    ],
)

java_binary(
    name = "skydoc_bin",
    srcs = ["SkylarkDocumentationGenerator.java"],
    main_class = "com.google.devtools.build.docgen.SkylarkDocumentationGenerator",
    runtime_deps = [
        "//src/main/java/com/google/devtools/build/lib/bazel/repository",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/maven",
    ],
    deps = [":docgen_javalib"],
)

filegroup(
    name = "template_files",
    srcs = glob([
        "templates/**/*.html",
        "templates/**/*.vm",
    ]),
)

filegroup(
    name = "srcs",
    testonly = 0,  # All srcs should be not test only, overwrite package default.
    srcs = glob(["**"]),
)
