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

# Add ipblocker plugin to plugin.cfg after acl
if ! grep -q "ipblocker:ipblocker" plugin.cfg; then
  sed -i '/^acl:acl$/a ipblocker:ipblocker' plugin.cfg
fi

make coredns
cd $root_dir

Corefile="./volumes/coredns/Corefile"
mkdir -p ./volumes/coredns
mkdir -p ./volumes/coredns/ipblocker_db
chown 1000:1000 ./volumes/coredns/ipblocker_db
chmod 755 ./volumes/coredns/ipblocker_db
if [ ! -f "$Corefile" ]; then
cp ./templates/Corefile $Corefile
fi

cd java/container-modules/coredns/
./gradlew build
cd build/libs/
mkdir -p "$coredns_plugin_java_dir"
cp -r coredns-launcher.jar "$coredns_plugin_java_dir"

cd $root_dir
docker compose -f compose.yml build
