#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "release.sh must run through sudo" >&2
  exit 1
fi
if [[ $# -ne 3 ]]; then
  echo "Usage: release.sh <uploaded-jar> <release-id> <sha256>" >&2
  exit 1
fi

source_jar=$1
release_id=$2
expected_sha=$3

if [[ ! $release_id =~ ^[0-9a-f]{7,40}$ || ! $expected_sha =~ ^[0-9a-f]{64}$ ]]; then
  echo "Invalid release id or checksum" >&2
  exit 1
fi
source_jar=$(readlink -f -- "$source_jar")
if [[ $source_jar != /tmp/m-cli-upload-*.jar || ! -f $source_jar || -L $source_jar ]]; then
  echo "Release input must be a regular /tmp/m-cli-upload-*.jar file" >&2
  exit 1
fi

actual_sha=$(sha256sum "$source_jar" | awk '{print $1}')
if [[ $actual_sha != "$expected_sha" ]]; then
  echo "Checksum mismatch" >&2
  exit 1
fi

release_dir=/opt/m-cli/releases/$release_id
if [[ -e $release_dir ]]; then
  echo "Release already exists: $release_id" >&2
  exit 1
fi
install -d -o root -g root -m 0755 "$release_dir"
install -o root -g root -m 0644 "$source_jar" "$release_dir/m-cli.jar"

previous_target=""
if [[ -L /opt/m-cli/current ]]; then
  previous_target=$(readlink -f /opt/m-cli/current)
fi
ln -s "$release_dir" /opt/m-cli/current.next
mv -Tf /opt/m-cli/current.next /opt/m-cli/current

if ! systemctl restart m-cli.service; then
  failure=1
else
  failure=0
  for _ in {1..30}; do
    if curl --fail --silent --max-time 2 http://127.0.0.1:8080/healthz >/dev/null; then
      failure=0
      break
    fi
    failure=1
    sleep 1
  done
fi

if [[ $failure -ne 0 ]]; then
  journalctl -u m-cli.service -n 80 --no-pager >&2 || true
  if [[ -n $previous_target && -d $previous_target ]]; then
    ln -s "$previous_target" /opt/m-cli/current.rollback
    mv -Tf /opt/m-cli/current.rollback /opt/m-cli/current
    systemctl restart m-cli.service || true
  fi
  echo "Release failed health check and was rolled back" >&2
  exit 1
fi

rm -f -- "$source_jar"
echo "Released $release_id successfully"
