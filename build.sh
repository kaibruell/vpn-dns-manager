#!/bin/bash
# Build Script Automation

root_dir=$(pwd)
wireguard_lib_dir="$root_dir/container-modules/docker-wireguard/lib"


# Build the 'wireguard-launcher.jar' and move it to wireguard_lib_dir
cd java/container-modules/wireguard/
./gradlew build
cd build/libs/
mkdir -p $wireguard_lib_dir
cp -r wireguard-launcher.jar "$wireguard_lib_dir"

cd $root_dir
docker compose -f compose.yml build
