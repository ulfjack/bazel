package(default_visibility = ["//visibility:public"])

filegroup(
    name = "protoc",
    srcs = select({
        ":windows_mingw": ["protobuf/protoc.exe"],
        ":windows_msys64_mingw64": ["protobuf/protoc.exe"],
        ":windows_clang": ["protobuf/protoc.exe"],
        ":darwin": ["protobuf/protoc.darwin"],
        "//conditions:default": ["protobuf/protoc.amd64"],
    }),
)

java_import(
    name = "protobuf",
    jars = ["protobuf/protobuf-2.5.0.jar"],
)

java_import(
    name = "gson",
    jars = ["gson/gson-2.2.4.jar"],
)

java_import(
    name = "guava",
    jars = ["guava/guava-18.0.jar"],
)

java_import(
    name = "joda-time",
    jars = ["joda-time/joda-time-2.3.jar"],
)

java_import(
    name = "jsr305",
    jars = ["jsr305/jsr-305.jar"],
)

java_import(
    name = "guava-testlib",
    jars = ["guava/guava-testlib.jar"],
)

java_import(
    name = "mockito",
    jars = ["mockito/mockito-all-1.10.19.jar"],
)

java_import(
    name = "truth",
    jars = ["truth/truth-0.24+.jar"],
)

java_import(
    name = "apache_commons_compress",
    jars = ["apache_commons_compress/apache-commons-compress-1.9.jar"],
)

java_import(
    name = "junit4",
    jars = [
        "hamcrest/hamcrest-core-1.3.jar",
        "junit/junit-4.11.jar",
    ],
)

config_setting(
    name = "windows_mingw",
    values = {"compiler": "windows_mingw"},
)

config_setting(
    name = "windows_msys64_mingw64",
    values = {"compiler": "windows_msys64_mingw64"},
)

config_setting(
    name = "windows_clang",
    values = {"compiler": "windows_clang"},
)

config_setting(
    name = "darwin",
    values = {"cpu": "darwin"},
)
