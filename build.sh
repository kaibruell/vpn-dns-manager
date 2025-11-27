#!/bin/bash
# Build Script Automation

root_dir=$(pwd)
wireguard_lib_dir="$root_dir/container-modules/docker-wireguard/lib"
coredns_plugin_java_dir="$root_dir/container-modules/coredns/plugin/ipblocker/java"

# Build the 'wireguard-launcher.jar' and move it to wireguard_lib_dir
cd java/container-modules/wireguard/
./gradlew build
cd build/libs/
mkdir -p $wireguard_lib_dir
cp -r wireguard-launcher.jar "$wireguard_lib_dir"
cd $root_dir

cd container-modules/coredns
make coredns
cd $root_dir

cd java/container-modules/coredns/
./gradlew build
cd build/libs/
mkdir -p "$coredns_plugin_java_dir"
cp -r coredns-launcher.jar "$coredns_plugin_java_dir"

cd $root_dir
docker compose -f compose.yml build
