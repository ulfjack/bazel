#!/bin/bash
#
# Copyright 2015 The Bazel Authors. All rights reserved.
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
# Test //external mechanisms
#

# Load test environment
src=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source $src/test-setup.sh \
  || { echo "test-setup.sh not found!" >&2; exit 1; }
source $src/remote_helpers.sh \
  || { echo "remote_helpers.sh not found!" >&2; exit 1; }

function set_up() {
  bazel clean --expunge >& $TEST_log
  mkdir -p zoo
  cat > zoo/BUILD <<EOF
java_binary(
    name = "ball-pit",
    srcs = ["BallPit.java"],
    main_class = "BallPit",
    deps = ["//external:mongoose"],
)
EOF

  cat > zoo/BallPit.java <<EOF
import carnivore.Mongoose;

public class BallPit {
    public static void main(String args[]) {
        Mongoose.frolic();
    }
}
EOF
}

function zip_up() {
  repo2_zip=$TEST_TMPDIR/fox.zip
  zip -0 -r $repo2_zip WORKSPACE fox
}

function tar_gz_up() {
  repo2_zip=$TEST_TMPDIR/fox.tar.gz
  tar czf $repo2_zip WORKSPACE fox
}

# Test downloading a file from a repository.
# This creates a simple repository containing:
#
# repo/
#   WORKSPACE
#   zoo/
#     BUILD
#     female.sh
#
# And a .zip file, which contains:
#
# WORKSPACE
# fox/
#   BUILD
#   male
#   male_relative -> male
#   male_absolute -> /fox/male
function http_archive_helper() {
  zipper=$1
  local write_workspace
  [[ $# -gt 1 ]] && [[ "$2" = "nowrite" ]] && write_workspace=1 || write_workspace=0
  local do_symlink
  [[ $# -gt 1 ]] && [[ "$2" = "do_symlink" ]] && do_symlink=1 || do_symlink=0

  if [[ $write_workspace = 0 ]]; then
    # Create a zipped-up repository HTTP response.
    repo2=$TEST_TMPDIR/repo2
    rm -rf $repo2
    mkdir -p $repo2/fox
    cd $repo2
    touch WORKSPACE
    cat > fox/BUILD <<EOF
filegroup(
    name = "fox",
    srcs = ["male"],
    visibility = ["//visibility:public"],
)
EOF
    what_does_the_fox_say="Fraka-kaka-kaka-kaka-kow"
    cat > fox/male <<EOF
#!/bin/bash
echo $what_does_the_fox_say
EOF
    chmod +x fox/male
    if [[ $do_symlink = 1 ]]; then
      ln -s male fox/male_relative
      ln -s /fox/male fox/male_absolute
    fi
    # Add some padding to the .zip to test that Bazel's download logic can
    # handle breaking a response into chunks.
    dd if=/dev/zero of=fox/padding bs=1024 count=10240 >& $TEST_log
    $zipper >& $TEST_log
    repo2_name=$(basename $repo2_zip)
    sha256=$(sha256sum $repo2_zip | cut -f 1 -d ' ')
  fi
  serve_file $repo2_zip

  cd ${WORKSPACE_DIR}
  if [[ $write_workspace = 0 ]]; then
    cat > WORKSPACE <<EOF
http_archive(
    name = 'endangered',
    url = 'http://localhost:$nc_port/$repo2_name',
    sha256 = '$sha256'
)
EOF

    cat > zoo/BUILD <<EOF
sh_binary(
    name = "breeding-program",
    srcs = ["female.sh"],
    data = ["@endangered//fox"],
)
EOF

    cat > zoo/female.sh <<EOF
#!/bin/bash
./external/endangered/fox/male
EOF
    chmod +x zoo/female.sh
fi

  bazel run //zoo:breeding-program >& $TEST_log --show_progress_rate_limit=0 \
    || echo "Expected build/run to succeed"
  kill_nc
  expect_log $what_does_the_fox_say

  if [[ $do_symlink = 1 ]]; then
    if [[ -x ${realpath_path} ]]; then
      realpath=${realpath_path}
    else
      realpath=realpath
    fi
    base_external_path=bazel-out/../external/endangered/fox
    assert_equals $(${realpath} ${base_external_path}/male) \
      $(${realpath} ${base_external_path}/male_relative)
    assert_equals $(${realpath} ${base_external_path}/male) \
      $(${realpath} ${base_external_path}/male_absolute)
  fi
}

function test_http_archive_zip() {
  http_archive_helper zip_up

  # Test with the extension
  serve_file $repo2_zip
  cat > WORKSPACE <<EOF
http_archive(
    name = 'endangered',
    url = 'http://localhost:$nc_port/bleh',
    sha256 = '$sha256',
    type = 'zip',
)
EOF
  bazel run //zoo:breeding-program >& $TEST_log \
    || echo "Expected build/run to succeed"
  kill_nc
  expect_log $what_does_the_fox_say
}

function test_http_archive_tgz() {
  http_archive_helper tar_gz_up "do_symlink"
  bazel shutdown
  http_archive_helper tar_gz_up "do_symlink"
}

function test_http_archive_no_server() {
  nc_port=$(pick_random_unused_tcp_port) || exit 1
  cat > WORKSPACE <<EOF
http_archive(name = 'endangered', url = 'http://localhost:$nc_port/repo.zip',
    sha256 = 'dummy')
EOF

  cat > zoo/BUILD <<EOF
sh_binary(
    name = "breeding-program",
    srcs = ["female.sh"],
    data = ["@endangered//fox"],
)
EOF

  cat > zoo/female.sh <<EOF
#!/bin/bash
cat fox/male
EOF
  chmod +x zoo/female.sh

  bazel fetch //zoo:breeding-program >& $TEST_log && fail "Expected fetch to fail"
  expect_log "Connection refused"
}

function test_http_archive_mismatched_sha256() {
  # Create a zipped-up repository HTTP response.
  repo2=$TEST_TMPDIR/repo2
  rm -rf $repo2
  mkdir -p $repo2
  cd $repo2
  touch WORKSPACE
  repo2_zip=$TEST_TMPDIR/fox.zip
  zip -r $repo2_zip WORKSPACE
  serve_file $repo2_zip
  wrong_sha256=0000000000000000000000000000000000000000

  cd ${WORKSPACE_DIR}
  cat > WORKSPACE <<EOF
http_archive(name = 'endangered', url = 'http://localhost:$nc_port/repo.zip',
    sha256 = '$wrong_sha256')
EOF

  cat > zoo/BUILD <<EOF
sh_binary(
    name = "breeding-program",
    srcs = ["female.sh"],
    data = ["@endangered//fox"],
)
EOF

  cat > zoo/female.sh <<EOF
#!/bin/bash
cat fox/male
EOF
  chmod +x zoo/female.sh

  bazel fetch //zoo:breeding-program >& $TEST_log && echo "Expected fetch to fail"
  kill_nc
  expect_log "does not match expected SHA-256"
}

# Bazel should not re-download the .zip unless the user requests it or the
# WORKSPACE file changes, so doing a BUILD where the "wrong" .zip is available
# on the server should work if the correct .zip is already available.
function test_sha256_caching() {
  # Download with correct sha256.
  http_archive_helper zip_up

  # Create another HTTP response.
  http_response=$TEST_TMPDIR/http_response
  cat > $http_response <<EOF
HTTP/1.0 200 OK



EOF

  nc_log=$TEST_TMPDIR/nc.log
  nc_port=$(pick_random_unused_tcp_port) || exit 1
  # "Webserver" for http_archive rule.
  nc_l $nc_port < $http_response >& $nc_log &
  pid=$!

  bazel fetch //zoo:breeding-program || fail "Fetch failed"
  bazel run //zoo:breeding-program >& $TEST_log \
    || echo "Expected run to succeed"
  kill_nc
  expect_log $what_does_the_fox_say
}

function test_changed_zip() {
  nc_port=$(pick_random_unused_tcp_port) || fail "Couldn't get TCP port"
  http_archive_helper zip_up
  http_archive_helper zip_up "nowrite"
  expect_not_log "Downloading from"
  local readonly output_base=$(bazel info output_base)
  local readonly repo_zip=$output_base/external/endangered/fox.zip
  rm $repo_zip || fail "Couldn't delete $repo_zip"
  touch $repo_zip || fail "Couldn't touch $repo_zip"
  [[ -s $repo_zip ]] && fail "File size not 0"
  http_archive_helper zip_up "nowrite"
  expect_log "Downloading from"
  [[ -s $repo_zip ]] || fail "File size was 0"
}

function test_cached_across_server_restart() {
  http_archive_helper zip_up
  bazel shutdown >& $TEST_log || fail "Couldn't shut down"
  bazel run //zoo:breeding-program >& $TEST_log --show_progress_rate_limit=0 \
    || echo "Expected build/run to succeed"
  expect_log $what_does_the_fox_say
  expect_not_log "Downloading from"
}

# Tests downloading a jar and using it as a Java dependency.
function test_jar_download() {
  serve_jar

  cat > WORKSPACE <<EOF
http_jar(name = 'endangered', url = 'http://localhost:$nc_port/lib.jar',
    sha256 = '$sha256')
EOF

  mkdir -p zoo
  cat > zoo/BUILD <<EOF
java_binary(
    name = "ball-pit",
    srcs = ["BallPit.java"],
    main_class = "BallPit",
    deps = ["@endangered//jar"],
)
EOF

  cat > zoo/BallPit.java <<EOF
import carnivore.Mongoose;

public class BallPit {
    public static void main(String args[]) {
        Mongoose.frolic();
    }
}
EOF

  bazel fetch //zoo:ball-pit || fail "Fetch failed"
  bazel run //zoo:ball-pit >& $TEST_log || echo "Expected run to succeed"
  kill_nc
  expect_log "Tra-la!"
}

# Tests downloading a file and using it as a dependency.
function test_http_download() {
  local test_file=$TEST_TMPDIR/toto
  cat > $test_file <<EOF
#!/bin/bash
echo "Tra-la!"
EOF
  local sha256=$(sha256sum $test_file | cut -f 1 -d ' ')
  serve_file $test_file
  cd ${WORKSPACE_DIR}

  cat > WORKSPACE <<EOF
http_file(name = 'toto', url = 'http://localhost:$nc_port/toto',
    sha256 = '$sha256', executable = True)
EOF

  mkdir -p test
  cat > test/BUILD <<EOF
sh_binary(
    name = "test",
    srcs = ["test.sh"],
    data = ["@toto//file"],
)
EOF

  cat > test/test.sh <<EOF
#!/bin/bash
echo "symlink:"
ls -l external/toto/file
echo "dest:"
ls -l \$(readlink -f external/toto/file/toto)
external/toto/file/toto
EOF

  chmod +x test/test.sh
  bazel run //test >& $TEST_log || echo "Expected run to succeed"
  kill_nc
  expect_log "Tra-la!"
}

# Tests downloading a file with a redirect.
function test_http_redirect() {
  local test_file=$TEST_TMPDIR/toto
  echo "Tra-la!" >$test_file
  local sha256=$(sha256sum $test_file | cut -f 1 -d ' ')
  serve_file $test_file
  cd ${WORKSPACE_DIR}
  serve_redirect "http://localhost:$nc_port/toto"

  cat > WORKSPACE <<EOF
http_file(name = 'toto', url = 'http://localhost:$redirect_port/toto',
    sha256 = '$sha256')
EOF

  mkdir -p test
  cat > test/BUILD <<EOF
sh_binary(
    name = "test",
    srcs = ["test.sh"],
    data = ["@toto//file"],
)
EOF

  cat > test/test.sh <<EOF
#!/bin/bash
cat external/toto/file/toto
EOF

  chmod +x test/test.sh
  bazel fetch //test || fail "Fetch failed"
  bazel run //test >& $TEST_log || echo "Expected run to succeed"
  kill_nc
  expect_log "Tra-la!"
}


function test_invalid_rule() {
  # http_jar with missing URL field.
  cat > WORKSPACE <<EOF
http_jar(name = 'endangered', sha256 = 'dummy')
EOF

  bazel fetch //external:endangered >& $TEST_log && fail "Expected fetch to fail"
  expect_log "missing value for mandatory attribute 'url' in 'http_jar' rule"
}

function test_new_remote_repo() {
  # Create a zipped-up repository HTTP response.
  local repo2=$TEST_TMPDIR/repo2
  rm -rf $repo2
  mkdir -p $repo2/fox
  cd $repo2
  local what_does_the_fox_say="Fraka-kaka-kaka-kaka-kow"
  echo $what_does_the_fox_say > fox/male
  local repo2_zip=$TEST_TMPDIR/fox.zip
  rm -f $repo2_zip
  zip -r $repo2_zip fox
  local sha256=$(sha256sum $repo2_zip | cut -f 1 -d ' ')
  serve_file $repo2_zip

  cd ${WORKSPACE_DIR}
  cat > fox.BUILD <<EOF
filegroup(
    name = "fox",
    srcs = ["fox/male"],
    visibility = ["//visibility:public"],
)
EOF

  cat > WORKSPACE <<EOF
new_http_archive(
    name = 'endangered',
    url = 'http://localhost:$nc_port/repo.zip',
    sha256 = '$sha256',
    build_file = 'fox.BUILD'
)
EOF

  mkdir -p zoo
  cat > zoo/BUILD <<EOF
sh_binary(
    name = "breeding-program",
    srcs = ["female.sh"],
    data = ["@endangered//:fox"],
)
EOF

  cat > zoo/female.sh <<EOF
#!/bin/bash
cat external/endangered/fox/male
EOF
  chmod +x zoo/female.sh

  bazel run //zoo:breeding-program >& $TEST_log \
    || echo "Expected build/run to succeed"
  kill_nc
  expect_log $what_does_the_fox_say
}

function test_fetch() {
  serve_jar

  cat > WORKSPACE <<EOF
maven_jar(
    name = 'endangered',
    artifact = "com.example.carnivore:carnivore:1.23",
    repository = 'http://localhost:$nc_port/',
    sha1 = '$sha1',
)
bind(name = 'mongoose', actual = '@endangered//jar')
EOF

  output_base=$(bazel info output_base)
  external_dir=$output_base/external
  needle=endangered
  [[ $(ls $external_dir | grep $needle) ]] && fail "$needle already in $external_dir"
  bazel fetch //zoo:ball-pit >& $TEST_log || fail "Fetch failed"
  [[ $(ls $external_dir | grep $needle) ]] || fail "$needle not added to $external_dir"

  # Rerun fetch while nc isn't serving anything to make sure the fetched result
  # is cached.
  bazel fetch //zoo:ball-pit >& $TEST_log || fail "Incremental fetch failed"

  # Make sure fetch isn't needed after a bazel restart.
  bazel shutdown
  bazel build //zoo:ball-pit >& $TEST_log || fail "Fetch shouldn't be required"

  # But it is required after a clean.
  bazel clean --expunge || fail "Clean failed"
  bazel build --fetch=false //zoo:ball-pit >& $TEST_log && fail "Expected build to fail"
  expect_log "bazel fetch //..."
}

function test_prefix_stripping_tar_gz() {
  mkdir -p x/y/z
  echo "abc" > x/y/z/w
  tar czf x.tar.gz x
  local sha256=$(sha256sum x.tar.gz | cut -f 1 -d ' ')
  serve_file x.tar.gz

  cat > WORKSPACE <<EOF
new_http_archive(
    name = "x",
    url = "http://localhost:$nc_port/x.tar.gz",
    sha256 = "$sha256",
    strip_prefix = "x/y/z",
    build_file = "x.BUILD",
)
EOF
  cat > x.BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w"],
)
EOF

  bazel build @x//:catter &> $TEST_log || fail "Build failed"
  assert_contains "abc" bazel-genfiles/external/x/catter.out
}

function test_prefix_stripping_zip() {
  mkdir -p x/y/z
  echo "abc" > x/y/z/w
  zip -r x x
  local sha256=$(sha256sum x.zip | cut -f 1 -d ' ')
  serve_file x.zip

  cat > WORKSPACE <<EOF
new_http_archive(
    name = "x",
    url = "http://localhost:$nc_port/x.zip",
    sha256 = "$sha256",
    strip_prefix = "x/y/z",
    build_file = "x.BUILD",
)
EOF
  cat > x.BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w"],
)
EOF

  bazel build @x//:catter &> $TEST_log || fail "Build failed"
  assert_contains "abc" bazel-genfiles/external/x/catter.out
}

function test_moving_build_file() {
  echo "abc" > w
  tar czf x.tar.gz w
  local sha256=$(sha256sum x.tar.gz | cut -f 1 -d ' ')
  serve_file x.tar.gz

  cat > WORKSPACE <<EOF
new_http_archive(
    name = "x",
    url = "http://localhost:$nc_port/x.tar.gz",
    sha256 = "$sha256",
    build_file = "x.BUILD",
)
EOF
  cat > x.BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w"],
)
EOF

  bazel build @x//:catter || fail "Build failed"
  assert_contains "abc" bazel-genfiles/external/x/catter.out
  mv x.BUILD x.BUILD.new || fail "Moving x.BUILD failed"
  sed 's/x.BUILD/x.BUILD.new/g' WORKSPACE > WORKSPACE.tmp || \
    fail "Editing WORKSPACE failed"
  mv WORKSPACE.tmp WORKSPACE
  bazel build @x//:catter || fail "Build failed"
  assert_contains "abc" bazel-genfiles/external/x/catter.out
}

function test_changing_build_file() {
  echo "abc" > w
  echo "def" > w.new
  tar czf x.tar.gz w w.new
  local sha256=$(sha256sum x.tar.gz | cut -f 1 -d ' ')
  serve_file x.tar.gz

  cat > WORKSPACE <<EOF
new_http_archive(
    name = "x",
    url = "http://localhost:$nc_port/x.tar.gz",
    sha256 = "$sha256",
    build_file = "x.BUILD",
)
EOF
  cat > x.BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w"],
)
EOF

  cat > x.BUILD.new <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w.new"],
)
EOF

  bazel build @x//:catter || fail "Build failed"
  assert_contains "abc" bazel-genfiles/external/x/catter.out
  sed 's/x.BUILD/x.BUILD.new/g' WORKSPACE > WORKSPACE.tmp || \
    fail "Editing WORKSPACE failed"
  mv WORKSPACE.tmp WORKSPACE
  bazel build @x//:catter || fail "Build failed"
  assert_contains "def" bazel-genfiles/external/x/catter.out
}

run_suite "external tests"
