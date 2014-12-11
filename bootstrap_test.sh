#! /bin/bash

set -e

[ -x "output/bazel" ] || ./compile.sh

PLATFORM="$(uname -s | tr 'A-Z' 'a-z')"
CPU_FLAG=""
if [[ ${PLATFORM} == "darwin" ]]; then
  CPU_FLAG="--cpu=darwin"
fi

# TODO(bazel-team): improve the test so that tools are verified too.
output/bazel --blazerc=/dev/null build ${CPU_FLAG} --nostamp //src:bazel //src:tools
BOOTSTRAP=$(mktemp /tmp/bootstrap.XXXXXXXXXX)
cp -f bazel-bin/src/bazel $BOOTSTRAP
chmod +x $BOOTSTRAP

$BOOTSTRAP --blazerc=/dev/null clean ${CPU_FLAG}
$BOOTSTRAP --blazerc=/dev/null build ${CPU_FLAG} --nostamp //src:bazel //src:tools

SUM1=$(md5sum $BOOTSTRAP | cut -f 1 -d " ")
SUM2=$(md5sum bazel-bin/src/bazel | cut -f 1 -d " ")

[[ "$SUM1" = "$SUM2" ]] # Check that bazel is stable

bazel-bin/src/bazel >/dev/null  # check that execution succeeds

$BOOTSTRAP --blazerc=/dev/null test ${CPU_FLAG} -k --test_output=errors //src/...

rm -f $BOOTSTRAP
echo "Bootstrap test succeeded"
