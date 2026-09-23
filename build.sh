#!/usr/bin/env bash
# Compiles the converter and runs it over samples/ (global types from samples/globals) into AdHoc/.
set -eu
cd "$(dirname "$0")"
rm -rf out
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.Matter2AdHoc samples AdHoc
