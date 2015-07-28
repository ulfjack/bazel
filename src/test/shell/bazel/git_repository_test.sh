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
# Test git_repository and new_git_repository workspace rules.
#

# Load test environment
source $(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/test-setup.sh \
  || { echo "test-setup.sh not found!" >&2; exit 1; }

# Global test setup.
#
# Unpacks the test Git repositories in the test temporary directory.
function set_up() {
  bazel clean --expunge
  local repos_dir=$TEST_TMPDIR/repos
  if [ -e "$repos_dir" ]; then
    rm -rf $repos_dir
  fi

  mkdir -p $repos_dir
  cp $testdata_path/pluto-repo.tar.gz $repos_dir
  cp $testdata_path/outer-planets-repo.tar.gz $repos_dir
  cd $repos_dir
  tar zxvf pluto-repo.tar.gz
  tar zxvf outer-planets-repo.tar.gz
}

# Test cloning a Git repository using the git_repository rule.
#
# This test uses the pluto Git repository at tag 1-build, which contains the
# following files:
#
# pluto/
#   WORKSPACE
#   BUILD
#   info
#
# Then, set up workspace with the following files:
#
# $WORKSPACE_DIR/
#   WORKSPACE
#   planets/
#     BUILD
#     planet_info.sh
#
# //planets has a dependency on a target in the pluto Git repository.
function test_git_repository() {
  local pluto_repo_dir=$TEST_TMPDIR/repos/pluto
  # Commit 85b8224 corresponds to tag 1-build. See testdata/pluto.git_log.
  local commit_hash="b87de93"

  # Create a workspace that clones the repository at the first commit.
  cd $WORKSPACE_DIR
  cat > WORKSPACE <<EOF
git_repository(
    name = "pluto",
    remote = "$pluto_repo_dir",
    commit = "$commit_hash",
)
EOF
  mkdir -p planets
  cat > planets/BUILD <<EOF
sh_binary(
    name = "planet-info",
    srcs = ["planet_info.sh"],
    data = ["@pluto//:pluto"],
)
EOF

  cat > planets/planet_info.sh <<EOF
#!/bin/bash
cat external/pluto/info
EOF
  chmod +x planets/planet_info.sh

  bazel run //planets:planet-info >& $TEST_log \
    || echo "Expected build/run to succeed"
  expect_log "Pluto is a dwarf planet"
}

# Test cloning a Git repository using the new_git_repository rule.
#
# This test uses the pluto Git repository at tag 0-initial, which contains the
# following files:
#
# pluto/
#   info
#
# Set up workspace with the following files:
#
# $WORKSPACE_DIR/
#   WORKSPACE
#   pluto.BUILD
#   planets/
#     BUILD
#     planet_info.sh
#
# //planets has a dependency on a target in the $TEST_TMPDIR/pluto Git
# repository.
function test_new_git_repository() {
  local pluto_repo_dir=$TEST_TMPDIR/repos/pluto

  # Create a workspace that clones the repository at the first commit.
  cd $WORKSPACE_DIR
  cat > WORKSPACE <<EOF
new_git_repository(
    name = "pluto",
    remote = "$pluto_repo_dir",
    tag = "0-initial",
    build_file = "pluto.BUILD",
)
EOF

  cat > pluto.BUILD <<EOF
filegroup(
    name = "pluto",
    srcs = ["info"],
    visibility = ["//visibility:public"],
)
EOF

  mkdir -p planets
  cat > planets/BUILD <<EOF
sh_binary(
    name = "planet-info",
    srcs = ["planet_info.sh"],
    data = ["@pluto//:pluto"],
)
EOF

  cat > planets/planet_info.sh <<EOF
#!/bin/bash
cat external/pluto/info
EOF
  chmod +x planets/planet_info.sh

  bazel run //planets:planet-info >& $TEST_log \
    || echo "Expected build/run to succeed"
  expect_log "Pluto is a planet"
}

# Test cloning a Git repository that has a submodule using the
# new_git_repository rule.
#
# This test uses the outer-planets Git repository at revision 1-submodule, which
# contains the following files:
#
# outer_planets/
#   neptune/
#     info
#   pluto/  --> submodule ../pluto
#     info
#
# Set up workspace with the following files:
#
# $WORKSPACE_DIR/
#   WORKSPACE
#   outer_planets.BUILD
#   planets/
#     BUILD
#     planet_info.sh
#
# planets has a dependency on targets in the $TEST_TMPDIR/outer_planets Git
# repository.
function test_new_git_repository_submodules() {
  local outer_planets_repo_dir=$TEST_TMPDIR/repos/outer-planets

  # Create a workspace that clones the outer_planets repository.
  cd $WORKSPACE_DIR
  cat > WORKSPACE <<EOF
new_git_repository(
    name = "outer-planets",
    remote = "$outer_planets_repo_dir",
    tag = "1-submodule",
    init_submodules = 1,
    build_file = "outer_planets.BUILD",
)
EOF

  cat > outer_planets.BUILD <<EOF
filegroup(
    name = "neptune",
    srcs = ["neptune/info"],
    visibility = ["//visibility:public"],
)

filegroup(
    name = "pluto",
    srcs = ["pluto/info"],
    visibility = ["//visibility:public"],
)
EOF

  mkdir -p planets
  cat > planets/BUILD <<EOF
sh_binary(
    name = "planet-info",
    srcs = ["planet_info.sh"],
    data = [
        "@outer-planets//:neptune",
        "@outer-planets//:pluto",
    ],
)
EOF

  cat > planets/planet_info.sh <<EOF
#!/bin/bash
cat external/outer-planets/neptune/info
cat external/outer-planets/pluto/info
EOF
  chmod +x planets/planet_info.sh

  bazel run //planets:planet-info >& $TEST_log \
    || echo "Expected build/run to succeed"
  expect_log "Neptune is a planet"
  expect_log "Pluto is a planet"
}

# Helper function for setting up the workspace as follows
#
# $WORKSPACE_DIR/
#   WORKSPACE
#   planets/
#     planet_info.sh
#     BUILD
function setup_error_test() {
  cd $WORKSPACE_DIR
  mkdir -p planets
  cat > planets/planet_info.sh <<EOF
#!/bin/bash
cat external/pluto/info
EOF

  cat > planets/BUILD <<EOF
sh_binary(
    name = "planet-info",
    srcs = ["planet_info.sh"],
    data = ["@pluto//:pluto"],
)
EOF
}

# Verifies that rule fails if both tag and commit are set.
#
# This test uses the pluto Git repository at tag 1-build, which contains the
# following files:
#
# pluto/
#   WORKSPACE
#   BUILD
#   info
function test_git_repository_both_commit_tag_error() {
  setup_error_test
  local pluto_repo_dir=$TEST_TMPDIR/pluto
  # Commit 85b8224 corresponds to tag 1-build. See testdata/pluto.git_log.
  local commit_hash="b87de93"

  cd $WORKSPACE_DIR
  cat > WORKSPACE <<EOF
git_repository(
    name = "pluto",
    remote = "$pluto_repo_dir",
    tag = "1-build",
    commit = "$commit_hash",
)
EOF

  bazel fetch //planets:planet-info >& $TEST_log \
    || echo "Expect run to fail."
  expect_log "One of either commit or tag must be defined"
}

# Verifies that rule fails if neither tag or commit are set.
#
# This test uses the pluto Git repository at tag 1-build, which contains the
# following files:
#
# pluto/
#   WORKSPACE
#   BUILD
#   info
function test_git_repository_no_commit_tag_error() {
  setup_error_test
  local pluto_repo_dir=$TEST_TMPDIR/pluto

  cd $WORKSPACE_DIR
  cat > WORKSPACE <<EOF
git_repository(
    name = "pluto",
    remote = "$pluto_repo_dir",
)
EOF

  bazel fetch //planets:planet-info >& $TEST_log \
    || echo "Expect run to fail."
  expect_log "One of either commit or tag must be defined"
}

run_suite "git_repository tests"
