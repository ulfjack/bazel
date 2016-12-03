# Description:
#   Java interfaces to various native UNIX system services.
package(
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

java_library(
    name = "unix",
    srcs = glob(["*.java"]),
    resources = select({
        # todo(dslomov): Get rid of this whole library on Windows.
        "//src:windows": [],
        "//src:windows_msys": [],
        "//src:windows_msvc": [],
        "//conditions:default": ["//src/main/native:libunix.so"],
    }),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:concurrent",
        "//src/main/java/com/google/devtools/build/lib:os_util",
        "//src/main/java/com/google/devtools/build/lib:preconditions",
        "//src/main/java/com/google/devtools/build/lib:vfs",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//third_party:guava",
    ],
)