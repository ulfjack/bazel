#! /bin/bash

set -e

[ -x "output/bazel" ] || ./compile.sh

PLATFORM="$(uname -s | tr 'A-Z' 'a-z')"
CPU_FLAG=""
if [[ ${PLATFORM} == "darwin" ]]; then
  CPU_FLAG="--cpu=darwin"
  function md5_file() {
    md5 $1 | sed 's|^MD5 (\(.*\)) =|\1|'
  }
else
  function md5_file() {
    md5sum $1
  }
fi

function md5_outputs() {
  # genproto does not strip out timestamp, let skip it for now
  for i in $(find bazel-bin/ -type f -a \! -name 'libproto_*.jar'); do
    md5_file $i
  done
  for i in $(find bazel-genfiles/ -type f); do
    md5_file $i
  done
}

# TODO(bazel-team): improve the test so that tools are verified too.
output/bazel --blazerc=/dev/null clean ${CPU_FLAG}
output/bazel --blazerc=/dev/null build ${CPU_FLAG} --nostamp //src:bazel //src:tools
BOOTSTRAP=$(mktemp /tmp/bootstrap.XXXXXXXXXX)
SUM1=$(mktemp /tmp/bootstrap-sum.XXXXXXXXXX)

trap '{ rm -f $BOOTSTRAP $SUM1 $SUM2; }' EXIT

md5_outputs | sort -k 2 >$SUM1
cp -f bazel-bin/src/bazel $BOOTSTRAP

chmod +x $BOOTSTRAP

$BOOTSTRAP --blazerc=/dev/null clean ${CPU_FLAG}
$BOOTSTRAP --blazerc=/dev/null build ${CPU_FLAG} --nostamp //src:bazel //src:tools

SUM2=$(mktemp /tmp/bootstrap-sum.XXXXXXXXXX)
md5_outputs | sort -k 2 >$SUM2

diff -U 0 $SUM1 $SUM2 || (echo "Differences detected in outputs!" >&2; exit 1)

bazel-bin/src/bazel >/dev/null  # check that execution succeeds

$BOOTSTRAP --blazerc=/dev/null test ${CPU_FLAG} -k --test_output=errors //src/...

echo "Bootstrap test succeeded"
