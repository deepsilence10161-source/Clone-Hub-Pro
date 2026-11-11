#!/usr/bin/env bash
set -euo pipefail
OUT=redroid-probe
mkdir -p "$OUT"

cleanup() {
  status=$?
  trap - EXIT
  docker logs redroid > "$OUT/docker.log" 2>&1 || true
  docker inspect redroid > "$OUT/docker-inspect.json" 2>&1 || true
  adb devices -l > "$OUT/adb-devices.txt" 2>&1 || true
  adb -s localhost:5555 shell getprop > "$OUT/getprop.txt" 2>&1 || true
  adb -s localhost:5555 exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
  docker rm -f redroid > /dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

{
  uname -a
  uname -m
  docker version
  cat /proc/filesystems
  zcat /proc/config.gz 2>/dev/null | grep -E 'CONFIG_ANDROID|CONFIG_ASHMEM|CONFIG_MEMFD' || true
} > "$OUT/host.txt" 2>&1

sudo apt-get update
sudo apt-get install -y adb linux-modules-extra-$(uname -r)
sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
sudo modprobe ashmem_linux 2>/dev/null || true
ls -l /dev/binder* | tee "$OUT/binder-devices.txt"

docker run -d --name redroid --privileged \
  -p 127.0.0.1:5555:5555 \
  -v "$PWD/redroid-data:/data" \
  redroid/redroid:14.0.0_64only-latest \
  androidboot.use_memfd=1 \
  androidboot.redroid_gpu_mode=guest \
  androidboot.redroid_width=1080 \
  androidboot.redroid_height=2400 \
  androidboot.redroid_dpi=420 \
  androidboot.redroid_fps=30

adb start-server
for attempt in $(seq 1 90); do
  adb connect localhost:5555 || true
  boot=$(adb -s localhost:5555 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [ "$boot" = "1" ]; then
    echo "Native ARM64 redroid booted"
    adb -s localhost:5555 shell getprop ro.product.cpu.abilist | tee "$OUT/abis.txt"
    adb -s localhost:5555 shell getprop ro.build.version.release | tee "$OUT/android-version.txt"
    exit 0
  fi
  if ! docker ps --format '{{.Names}}' | grep -qx redroid; then
    echo "redroid container exited before boot"
    exit 1
  fi
  sleep 2
done

echo "Timed out waiting for native ARM64 redroid"
exit 1
