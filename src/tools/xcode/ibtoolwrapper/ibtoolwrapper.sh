#!/bin/bash
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
# ibtoolwrapper runs ibtool and zips up the output.
# This script only runs on darwin and you must have Xcode installed.
#
# $1 OUTZIP - the path to place the output zip file.
# $2 ARCHIVEROOT - the path in the zip to place the output, or an empty
#                  string for the root of the zip. e.g. 'Payload/foo.app'. If
#                  this tool outputs a single file, ARCHIVEROOT is the name of
#                  the only file in the zip file.

set -eu

OUTZIP=$(tools/objc/realpath "$1")
ARCHIVEROOT="$2"
shift 2
TEMPDIR=$(mktemp -d -t ZippingOutput)
trap "rm -rf \"$TEMPDIR\"" EXIT
FULLPATH="$TEMPDIR/$ARCHIVEROOT"
PARENTDIR=$(dirname "$FULLPATH")
mkdir -p "$PARENTDIR"
FULLPATH=$(tools/objc/realpath "$FULLPATH")

# IBTool needs to have absolute paths sent to it, so we call realpaths on
# on all arguments seeing if we can expand them.
# Radar 21045660 ibtool has difficulty dealing with relative paths.
IBTOOLARGS=()
for i in $@; do
  if [ -e "$i" ]; then
    IBTOOLARGS+=($(tools/objc/realpath "$i"))
  else
    IBTOOLARGS+=($i)
  fi
done

# If we are running into problems figuring out ibtool issues, there are a couple
# of env variables that may help. Both of the following must be set to work.
#   IBToolDebugLogFile=<OUTPUT FILE PATH>
#   IBToolDebugLogLevel=4
# you may also see if
#   IBToolNeverDeque=1
# helps.
/usr/bin/xcrun ibtool --errors --warnings --notices \
    --auto-activate-custom-fonts --output-format human-readable-text \
    --compile "$FULLPATH" ${IBTOOLARGS[@]}

# Need to push/pop tempdir so it isn't the current working directory
# when we remove it via the EXIT trap.
pushd "$TEMPDIR" > /dev/null
zip -y -r -q "$OUTZIP" .
popd > /dev/null
