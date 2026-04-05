#!/usr/bin/env bash
# =============================================================================
# OpenClaw — VPS first-time setup script
# Run once as root on a fresh Debian/Ubuntu VPS.
# =============================================================================
set -euo pipefail

echo "==> Creating openclaw system user..."
id -u openclaw &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin openclaw

echo "==> Creating directories..."
mkdir -p /opt/openclaw/releases
mkdir -p /etc/openclaw
chown -R openclaw:openclaw /opt/openclaw

echo "==> Copying env file template..."
if [ ! -f /etc/openclaw/env ]; then
  cp "$(dirname "$0")/env.example" /etc/openclaw/env
  chmod 600 /etc/openclaw/env
  chown openclaw:openclaw /etc/openclaw/env
  echo "    IMPORTANT: Edit /etc/openclaw/env and fill in your secrets!"
else
  echo "    /etc/openclaw/env already exists — skipping."
fi

echo "==> Installing systemd service..."
cp "$(dirname "$0")/openclaw.service" /etc/systemd/system/openclaw.service
systemctl daemon-reload
systemctl enable openclaw

echo "==> Granting systemctl restart permission to deploy user..."
# Allow the deploy SSH user (e.g. 'deploy') to restart openclaw without sudo password
DEPLOY_USER="${1:-deploy}"
SUDOERS_FILE="/etc/sudoers.d/openclaw"
echo "${DEPLOY_USER} ALL=(ALL) NOPASSWD: /bin/systemctl restart openclaw, /bin/systemctl status openclaw" \
  > "$SUDOERS_FILE"
chmod 440 "$SUDOERS_FILE"
visudo -cf "$SUDOERS_FILE"

echo ""
echo "==> Done. Next steps:"
echo "    1. Edit /etc/openclaw/env with your secrets"
echo "    2. Add GitHub Secrets: VPS_HOST, VPS_USER, VPS_SSH_KEY"
echo "    3. Push a tag to trigger the first deploy:"
echo "       git tag v1.0.0 && git push --tags"
echo "    4. After deploy: curl -X POST http://127.0.0.1:18080/api/career/scrape/run \\"
echo "         -H 'X-API-Key: <your-key>'"
