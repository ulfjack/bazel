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

# Load test environment
source $(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/test-setup.sh \
  || { echo "test-setup.sh not found!" >&2; exit 1; }

export JAVA_RUNFILES=$TEST_SRCDIR

function setup_repo() {
  mkdir -p $1
  touch $1/WORKSPACE
  echo $2 > $1/thing
  cat > $1/BUILD <<EOF
genrule(
    name = "x",
    srcs = ["thing"],
    cmd = "cat \$(location thing) > \$@",
    outs = ["out"],
)
EOF
}

function test_workspace_changes() {
  repo_a=$TEST_TMPDIR/a
  repo_b=$TEST_TMPDIR/b
  setup_repo $repo_a hi
  setup_repo $repo_b bye

  cat > WORKSPACE <<EOF
local_repository(
    name = "x",
    path = "$repo_a",
)
EOF

  bazel build @x//:x || fail "build failed"
  assert_contains "hi" bazel-genfiles/out

  cat > WORKSPACE <<EOF
local_repository(
    name = "x",
    path = "$repo_b",
)
EOF

  bazel build @x//:x || fail "build failed"
  assert_contains "bye" bazel-genfiles/out
}


function test_path_with_spaces() {
  ws="a b"
  mkdir "$ws"
  cd "$ws"
  touch WORKSPACE

  bazel info &> $TEST_log && fail "Info succeeeded"
  bazel help &> $TEST_log || fail "Help failed"
}

run_suite "workspace tests"
