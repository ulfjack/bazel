#!/bin/bash
#
# Copyright 2015 Google Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Test rules provided in Bazel not tested by examples
#

# Load test environment
source $(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/test-setup.sh \
  || { echo "test-setup.sh not found!" >&2; exit 1; }

function write_hello_library_files() {
  mkdir -p java/main
  cat >java/main/BUILD <<EOF
java_binary(name = 'main',
    deps = ['//java/hello_library'],
    srcs = ['Main.java'],
    main_class = 'main.Main')
EOF

  cat >java/main/Main.java <<EOF
package main;
import hello_library.HelloLibrary;
public class Main {
  public static void main(String[] args) {
    HelloLibrary.funcHelloLibrary();
    System.out.println("Hello, World!");
  }
}
EOF

  mkdir -p java/hello_library
  cat >java/hello_library/BUILD <<EOF
package(default_visibility=['//visibility:public'])
java_library(name = 'hello_library',
             srcs = ['HelloLibrary.java']);
EOF

  cat >java/hello_library/HelloLibrary.java <<EOF
package hello_library;
public class HelloLibrary {
  public static void funcHelloLibrary() {
    System.out.print("Hello, Library!;");
  }
}
EOF
}

function test_compiles_hello_library_using_persistent_javac() {
  write_hello_library_files
  bazel --batch clean
  bazel build --experimental_persistent_javac //java/main:main || fail "build failed"
  bazel-bin/java/main/main | grep -q "Hello, Library!;Hello, World!" \
    || fail "comparison failed"
  bazel_pid=$(bazel info | fgrep server_pid | cut -d' ' -f2)
  # DANGER. This contains arcane shell wizardry that was carefully crafted to be compatible with
  # both BSD and GNU tools so that this works under Linux and OS X.
  bazel_children=$(ps ax -o ppid,pid | awk '{$1=$1};1' | egrep "^${bazel_pid} " | cut -d' ' -f2)
  bazel shutdown || fail "shutdown failed"
  sleep 10
  unkilled_children=$(for pid in $bazel_children; do ps -p $pid | sed 1d; done)
  if [ ! -z "$unkilled_children" ]; then
    fail "Worker processes were still running: ${unkilled_children}"
  fi
}

run_suite "Worker integration tests"
