#!/usr/bin/env bash
# Downloads a set of real Matter cluster definitions plus the global type files from the
# connectedhomeip repository (data_model/<VERSION>) into samples/.
#
#   ./fetch-samples.sh            # Matter 1.7 (default)
#   VERSION=1.4 ./fetch-samples.sh
#
# Available versions: https://github.com/project-chip/connectedhomeip/tree/master/data_model
set -eu
VERSION="${VERSION:-1.7}"
BASE="https://raw.githubusercontent.com/project-chip/connectedhomeip/master/data_model/$VERSION"
cd "$(dirname "$0")"
mkdir -p samples/globals

CLUSTERS="OnOff LevelControl ColorControl DoorLock Thermostat WindowCovering Descriptor-Cluster \
BasicInformationCluster TemperatureMeasurement Switch Identify FanControl"

for f in $CLUSTERS; do
    curl -sSf -o "samples/$f.xml" "$BASE/clusters/$f.xml" && echo "ok  clusters/$f.xml" || echo "FAILED $f"
done
for f in Bitmaps Commands Enums Structs TypeDefs; do
    curl -sSf -o "samples/globals/$f.xml" "$BASE/globals/$f.xml" && echo "ok  globals/$f.xml" || echo "FAILED globals/$f"
done
