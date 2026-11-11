#!/usr/bin/env bash
set -euo pipefail
OUT=evidence
mkdir -p "$OUT"

{
  uname -a
  uname -m
  docker version
} > "$OUT/redroid-host.txt" 2>&1

sudo apt-get update
sudo apt-get install -y adb
sudo apt-get install -y linux-modules-extra-$(uname -r) || true
sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
sudo modprobe ashmem_linux 2>/dev/null || true
sudo mkdir -p /dev/binderfs
if ! mountpoint -q /dev/binderfs; then
  sudo mount -t binder binder /dev/binderfs
fi
for name in binder hwbinder vndbinder; do
  sudo ln -sfn "/dev/binderfs/$name" "/dev/$name"
done
ls -l /dev/binderfs /dev/binder /dev/hwbinder /dev/vndbinder > "$OUT/binder-devices.txt"

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
    adb -s localhost:5555 shell getprop ro.product.cpu.abilist | tee "$OUT/redroid-abis.txt"
    adb -s localhost:5555 shell getprop ro.build.version.release | tee "$OUT/redroid-android-version.txt"
    exit 0
  fi
  if ! docker ps --format '{{.Names}}' | grep -qx redroid; then
    docker logs redroid > "$OUT/redroid-docker.log" 2>&1 || true
    echo "redroid container exited before boot"
    exit 1
  fi
  sleep 2
done

# Timeout: keep everything that explains why the emulator never became usable, because the job log
# is the only other place and it needs repository admin rights to read.
{
  echo "--- docker ps -a"
  docker ps -a
  echo "--- adb devices"
  adb devices
  echo "--- last adb connect/boot answers"
  adb connect localhost:5555 || true
  adb -s localhost:5555 shell getprop sys.boot_completed || true
  adb -s localhost:5555 shell getprop init.svc.bootanim || true
  echo "--- binder devices"
  ls -l /dev/binderfs 2>&1 || true
} > "$OUT/redroid-timeout.txt" 2>&1 || true
docker logs redroid > "$OUT/redroid-docker.log" 2>&1 || true
echo "Timed out waiting for native ARM64 redroid"
cat "$OUT/redroid-timeout.txt" || true
tail -n 40 "$OUT/redroid-docker.log" || true
exit 1
