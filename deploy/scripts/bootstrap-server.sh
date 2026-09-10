#!/usr/bin/env bash
set -Eeuo pipefail

DEPLOY_USER="scientific"
SWAP_FILE="/swapfile"
SWAP_SIZE="6G"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script as root." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y ca-certificates curl git gnupg openssl ufw

if ! id "${DEPLOY_USER}" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "${DEPLOY_USER}"
fi

install -d -m 700 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" "/home/${DEPLOY_USER}/.ssh"
install -m 600 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" \
  /root/.ssh/authorized_keys "/home/${DEPLOY_USER}/.ssh/authorized_keys"

usermod -aG sudo "${DEPLOY_USER}"
printf '%s ALL=(ALL) NOPASSWD:ALL\n' "${DEPLOY_USER}" > "/etc/sudoers.d/${DEPLOY_USER}"
chmod 440 "/etc/sudoers.d/${DEPLOY_USER}"

install -m 0755 -d /etc/apt/keyrings
if [[ ! -f /etc/apt/keyrings/docker.asc ]]; then
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
fi

. /etc/os-release
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
EOF

apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
usermod -aG docker "${DEPLOY_USER}"

if ! swapon --show=NAME --noheadings | grep -Fxq "${SWAP_FILE}"; then
  if [[ ! -f "${SWAP_FILE}" ]]; then
    fallocate -l "${SWAP_SIZE}" "${SWAP_FILE}"
    chmod 600 "${SWAP_FILE}"
    mkswap "${SWAP_FILE}"
  fi
  swapon "${SWAP_FILE}"
fi
if ! grep -Fq "${SWAP_FILE} none swap sw 0 0" /etc/fstab; then
  printf '%s none swap sw 0 0\n' "${SWAP_FILE}" >> /etc/fstab
fi

cat > /etc/ssh/sshd_config.d/99-scientific-search.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
sshd -t
systemctl reload ssh

ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw allow from 172.28.0.0/24 to any port 1234 proto tcp comment 'Scientific Search LLM bridge'
ufw allow from 172.28.0.0/24 to any port 1235 proto tcp comment 'Scientific Search Qwen bridge'
ufw allow from 172.28.0.0/24 to any port 1236 proto tcp comment 'Scientific Search topic LLM bridge'
ufw --force enable

echo "Server bootstrap completed."
docker --version
docker compose version
ufw status verbose
free -h
