#!/bin/bash
# Siletry API server setup (Ubuntu 24.04 on EC2). Run as: sudo bash setup-server.sh
set -e

# 1. Docker, git, swap (in case the launch script didn't run)
if ! command -v docker >/dev/null; then
  apt-get update -y
  apt-get install -y docker.io docker-compose-v2 git
fi
systemctl enable --now docker
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# 2. Code
mkdir -p /opt/siletry && cd /opt/siletry
if [ -d app ]; then git -C app pull; else git clone https://github.com/aman19kriti14/siletry-backend-final app; fi

# 3. Docker Compose: the API + Caddy (automatic HTTPS for api.siletry.com)
cat > docker-compose.yml <<'EOF'
services:
  api:
    build: ./app
    restart: always
    env_file: .env
    environment:
      PORT: "8080"
    expose: ["8080"]
    logging:
      driver: json-file
      options: { max-size: "20m", max-file: "5" }
  caddy:
    image: caddy:2
    restart: always
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
    depends_on: [api]
volumes:
  caddy_data:
EOF

cat > Caddyfile <<'EOF'
api.siletry.com {
  reverse_proxy api:8080
}
EOF

# 4. Settings file (filled in by hand next; never committed to git)
if [ ! -f .env ]; then
cat > .env <<'EOF'
SILETRY_ENV=production
PUBLIC_BASE_URL=https://api.siletry.com
ALLOWED_ORIGINS=https://app.siletry.com,https://siletry.com,https://www.siletry.com
SEED_DEMO=false
DEV_TOOLS=false

# Database (RDS)
PGHOST=siletry-db.c1uwkq6401nx.ap-south-1.rds.amazonaws.com
PGPORT=5432
PGDATABASE=postgres
PGUSER=siletryadmin
PGPASSWORD=

# Copy these from Render -> Environment (same values!)
JWT_SECRET=
CREDENTIALS_KEY=
META_VERIFY_TOKEN=
META_APP_SECRET=
META_ADMIN_PHONE_NUMBER_ID=
META_ADMIN_ACCESS_TOKEN=
META_ADMIN_DISPLAY_NUMBER=
OPS_API_KEY=
OPS_ALERT_TO=
LEADS_IP_SALT=
EOF
chmod 600 .env
fi

echo
echo "Setup done. Next: fill in /opt/siletry/.env  (sudo nano /opt/siletry/.env)"
