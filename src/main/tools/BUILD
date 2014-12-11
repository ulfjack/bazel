package(default_visibility = ["//src:__subpackages__"])

cc_binary(
    name = "process-wrapper",
    srcs = ["process-wrapper.c"],
    copts = ["-std=c99"],
)

cc_binary(
    name = "build-runfiles",
    srcs = ["build-runfiles.cc"],
)

exports_files(["build_interface_so"])
