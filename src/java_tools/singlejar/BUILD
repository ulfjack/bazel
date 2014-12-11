package(default_visibility = ["//src:__pkg__"])

java_binary(
    name = "SingleJar",
    srcs = glob(["java/**/*.java"]),
    main_class = "com.google.devtools.build.singlejar.SingleJar",
    deps = [
        "//src/main/java:shell",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)
