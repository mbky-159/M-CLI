#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run as root: sudo bash deploy/server/bootstrap-ubuntu.sh" >&2
  exit 1
fi

if [[ ! -f /etc/os-release ]]; then
  echo "Unsupported host: /etc/os-release is missing" >&2
  exit 1
fi
. /etc/os-release
if [[ ${ID:-} != "ubuntu" || ${VERSION_ID:-} != "24.04" ]]; then
  echo "Expected Ubuntu 24.04; found ${PRETTY_NAME:-unknown}" >&2
  exit 1
fi

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  ca-certificates curl git jq openjdk-17-jre-headless python3 docker.io docker-compose-v2

if ! getent group mcli >/dev/null; then
  groupadd --system mcli
fi
if ! id mcli >/dev/null 2>&1; then
  useradd --system --gid mcli --home-dir /var/lib/m-cli --shell /usr/sbin/nologin mcli
fi
if ! id mcli-deploy >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash mcli-deploy
fi

install -d -o root -g root -m 0755 /opt/m-cli /opt/m-cli/releases
install -d -o mcli -g mcli -m 0700 /var/lib/m-cli /var/lib/m-cli/workspace
install -d -o root -g mcli -m 0750 /etc/m-cli

if [[ ! -f /etc/m-cli/m-cli.env ]]; then
  install -o root -g mcli -m 0640 /dev/null /etc/m-cli/m-cli.env
  cat >/etc/m-cli/m-cli.env <<'EOF'
# Add exactly one model provider key and replace the Runtime API value.
PAICLI_RUNTIME_API_KEY=replace-with-a-long-random-value
# DEEPSEEK_API_KEY=
PAICLI_RUNTIME_DIR=/var/lib/m-cli/runtime
PAICLI_LOG_DIR=/var/lib/m-cli/logs
EOF
  echo "Created /etc/m-cli/m-cli.env; configure it before the first deployment."
fi

install -o root -g root -m 0644 deploy/systemd/m-cli.service /etc/systemd/system/m-cli.service
install -o root -g root -m 0755 deploy/server/release.sh /usr/local/sbin/m-cli-release
cat >/etc/sudoers.d/m-cli-deploy <<'EOF'
mcli-deploy ALL=(root) NOPASSWD: /usr/local/sbin/m-cli-release
EOF
chmod 0440 /etc/sudoers.d/m-cli-deploy
visudo -cf /etc/sudoers.d/m-cli-deploy
systemctl daemon-reload
systemctl enable m-cli.service

systemctl enable --now docker
echo "Bootstrap complete. M-CLI remains loopback-only on 127.0.0.1:8080."
echo "Add the dedicated CI public key to /home/mcli-deploy/.ssh/authorized_keys."
echo "Next: install gVisor runsc from its official repository, then run deploy/check_sandbox.py --run."
