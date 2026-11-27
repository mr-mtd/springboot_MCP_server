#!/bin/bash
# EC2 user data for the transport MCP server (Amazon Linux 2023, t3.micro).
#
# What it does, once, at first boot:
#   1. installs Docker and adds 1 GB of swap (a t3.micro has 1 GB of RAM)
#   2. writes /opt/transport-mcp/run.sh, which reads the DB password from SSM Parameter Store
#      and (re)starts the container
#   3. runs it
#
# The container listens on 127.0.0.1:8080 only, so nothing is reachable from outside the instance.
# Reach it with SSM port forwarding (see deploy/ec2/README.md). Later, an orchestrator container
# on the same host joins the "transport" Docker network and calls http://transport-mcp-server:8080/mcp.
#
# To deploy a new image later:  sudo /opt/transport-mcp/run.sh
set -euxo pipefail

REGION=us-east-2

dnf install -y docker
systemctl enable --now docker

if [ ! -f /swapfile ]; then
  fallocate -l 1G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap defaults 0 0' >> /etc/fstab
fi

mkdir -p /opt/transport-mcp
cat > /opt/transport-mcp/run.sh <<SCRIPT
#!/bin/bash
set -euo pipefail

IMAGE=ghcr.io/mr-mtd/transport-mcp-server:feature-dev
DB_URL='jdbc:postgresql://transport-db.cps0062uqgpv.us-east-2.rds.amazonaws.com:5432/transport?sslmode=verify-full&sslrootcert=/app/certs/global-bundle.pem'

# Read at start time, never written to disk. The instance role may read only this parameter.
DB_PASSWORD=\$(aws ssm get-parameter --region $REGION --name /transport-mcp/db-password --with-decryption --query Parameter.Value --output text)
export DB_PASSWORD

docker network inspect transport >/dev/null 2>&1 || docker network create transport
docker pull "\$IMAGE"
docker rm -f transport-mcp-server >/dev/null 2>&1 || true
# One line on purpose: inside this unquoted heredoc a trailing backslash would be eaten anyway.
docker run -d --name transport-mcp-server --network transport --restart unless-stopped -p 127.0.0.1:8080:8080 --memory 600m -e JAVA_TOOL_OPTIONS='-Xmx300m -XX:+UseSerialGC' -e DB_URL="\$DB_URL" -e DB_USERNAME=transport_mcp -e DB_PASSWORD "\$IMAGE"
docker image prune -f >/dev/null
echo "Started \$IMAGE"
SCRIPT
chmod 700 /opt/transport-mcp/run.sh

/opt/transport-mcp/run.sh
