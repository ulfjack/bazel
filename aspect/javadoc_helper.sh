#!/bin/bash
OUTPUT_ZIP="$(pwd)/$1"
shift
TMPDIR="$(mktemp -d ${TMPDIR:-/tmp}/javadoc.XXXXXXXX)"
javadoc -quiet -d "${TMPDIR}" "$@" 2> /dev/null
(cd ${TMPDIR} && zip -r -q "${OUTPUT_ZIP}" .)
echo "${TMPDIR}"
echo "$@"
echo "${OUTPUT_ZIP}"
