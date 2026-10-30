#!/usr/bin/env bash
# Wrapper script for ACID Emulator E2E execution (ARCHITECTURE.md §12.1).
# Keeps ACID forwarder and ADB invocations inside one shell process and optionally
# leases/returns an API 35 Pixel 9 Pro emulator when ACID_LEASE=1.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:${PATH}"
ACID_BIN="/google/bin/releases/mobile-devx-platform/acid/acid"

LEASED_ID=""
cleanup() {
  if [[ -n "${LEASED_ID}" && "${KEEP_ACID_LEASE:-0}" != "1" ]]; then
    echo "[acid] Returning leased ACID device ${LEASED_ID}..."
    "${ACID_BIN}" return_acid_device "${LEASED_ID}" || true
  elif [[ -n "${LEASED_ID}" ]]; then
    echo "[acid] Keeping leased ACID device ${LEASED_ID} active (KEEP_ACID_LEASE=1)."
  fi
}
trap cleanup EXIT

extract_port() {
  awk '{for(i=1;i<=NF;i++) if ($i ~ /^localhost:[0-9]+$/) {sub("localhost:","",$i); print $i; exit}}'
}

SESSIONS_OUT="$("${ACID_BIN}" sessions 2>&1 || true)"
PORT="$(echo "${SESSIONS_OUT}" | extract_port)"

if [[ -z "${PORT}" && "${WAIT_EXISTING_ACID:-0}" != "1" ]]; then
  echo "[acid] No active ACID session found; leasing PIXEL_9_PRO API 35..."
  LEASE_OUT="$("${ACID_BIN}" lease_android_emulator PIXEL_9_PRO 35 2>&1)"
  echo "${LEASE_OUT}"
  LEASED_ID="$(echo "${LEASE_OUT}" | sed -n 's/.*[?&]id=\([A-Za-z0-9_-]*\).*/\1/p' | head -n1)"
fi

for attempt in $(seq 1 36); do
  if [[ -n "${PORT}" ]]; then
    break
  fi
  echo "[acid] Waiting for leased emulator to appear in 'acid sessions' (${attempt}/36)..."
  sleep 5
  SESSIONS_OUT="$("${ACID_BIN}" sessions 2>&1 || true)"
  PORT="$(echo "${SESSIONS_OUT}" | extract_port)"
done
echo "${SESSIONS_OUT}"

if [[ -z "${PORT}" ]]; then
  echo "ERROR: Could not resolve localhost:<PORT> from acid sessions" >&2
  exit 1
fi

SERIAL="localhost:${PORT}"
echo "[acid] Connecting ADB to ${SERIAL}..."
adb connect "${SERIAL}"
adb -s "${SERIAL}" wait-for-device

python3 "${SCRIPT_DIR}/run_acid_e2e.py" --serial "${SERIAL}" "$@"
