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

# The Android level under test is selectable so the same gate can cover a compatibility matrix;
# every run records the image it used in redroid-host.txt.
IMAGE="${REDROID_IMAGE:-redroid/redroid:14.0.0_64only-latest}"
{
  echo "REDROID_IMAGE=$IMAGE"
  echo "container_name=${REDROID_CONTAINER:-redroid}"
} >> "$OUT/redroid-host.txt"
# Registry pulls fail transiently (rate limits, DNS) and the failure used to leave no evidence at
# all. Pull explicitly, retry, and keep the output.
for attempt in 1 2 3; do
  if docker pull "$IMAGE" > "$OUT/redroid-pull.txt" 2>&1; then
    break
  fi
  echo "docker pull attempt $attempt failed"; tail -n 5 "$OUT/redroid-pull.txt" || true
  sleep $((attempt * 5))
done

if ! docker run -d --name "${REDROID_CONTAINER:-redroid}" --privileged \
  -p 127.0.0.1:5555:5555 \
  -v "$PWD/redroid-data:/data" \
  "$IMAGE" \
  androidboot.use_memfd=1 \
  androidboot.redroid_gpu_mode=guest \
  androidboot.redroid_width=1080 \
  androidboot.redroid_height=2400 \
  androidboot.redroid_dpi=420 \
  androidboot.redroid_fps=30 > "$OUT/redroid-run.txt" 2>&1; then
  echo "docker run failed"
  cat "$OUT/redroid-run.txt" || true
  docker ps -a > "$OUT/redroid-ps.txt" 2>&1 || true
  docker images > "$OUT/redroid-images.txt" 2>&1 || true
  jar_dir="$PWD/redroid-data"
  ls -ld "$jar_dir" > "$OUT/redroid-data-dir.txt" 2>&1 || true
  exit 1
fi

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
  # Must match the container this run actually created: hard-coding "redroid" made every matrix
  # level abort on its first poll while the container was still booting (run 43151133377).
  if ! docker ps --format '{{.Names}}' | grep -qx "${REDROID_CONTAINER:-redroid}"; then
    docker logs "${REDROID_CONTAINER:-redroid}" > "$OUT/redroid-docker.log" 2>&1 || true
    echo "redroid container exited before boot"
    exit 1
  fi
  sleep 2
done

# Kernel side is the usual suspect when the emulator container dies immediately, so record it.
{
  echo "--- modules"
  lsmod | grep -E "binder|ashmem" || true
  echo "--- binderfs"
  ls -l /dev/binderfs 2>&1 || true
  mount | grep binder || true
  echo "--- kernel log (binder/avc)"
  sudo dmesg | grep -iE "binder|avc|seccomp" | tail -n 40 || true
} > "$OUT/redroid-kernel.txt" 2>&1 || true
docker logs "${REDROID_CONTAINER:-redroid}" > "$OUT/redroid-docker.log" 2>&1 || true

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
docker logs "${REDROID_CONTAINER:-redroid}" > "$OUT/redroid-docker.log" 2>&1 || true
echo "Timed out waiting for native ARM64 redroid"
cat "$OUT/redroid-timeout.txt" || true
tail -n 40 "$OUT/redroid-docker.log" || true
exit 1
