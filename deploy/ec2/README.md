# Deploying on EC2 (free tier)

```
            ┌──────────────── EC2 t3.micro (no inbound ports) ────────────────┐
SSM ───────▶│  docker network "transport"                                      │
port-forward│    transport-mcp-server :8080 (bound to 127.0.0.1)               │──▶ RDS :5432
            │    (later) orchestrator ─▶ http://transport-mcp-server:8080/mcp  │   (allows the
            └──────────────────────────────────────────────────────────────────┘    instance's SG)
```

- **Image:** CI pushes `ghcr.io/mr-mtd/transport-mcp-server` on every push to `main` and
  `feature/dev` (tags `main`, `feature-dev`, `sha-<commit>`). The GHCR package must be public, or
  the instance would need a pull token.
- **DB password:** SSM Parameter Store SecureString `/transport-mcp/db-password`, read by
  `run.sh` at container start.
- **Instance role** `transport-mcp-ec2`: `AmazonSSMManagedInstanceCore` (Session Manager) and
  `ssm:GetParameter` on that one parameter.
- **Security groups:** `transport-mcp-sg` has no inbound rules. `transport-db-sg` allows 5432 from
  `transport-mcp-sg`.
- **Boot:** [`user-data.sh`](user-data.sh) installs Docker, adds swap and starts the container.

## Reaching it

With the AWS CLI and the Session Manager plugin installed locally:

```bash
aws ssm start-session --region us-east-2 --target <instance-id> \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8080"],"localPortNumber":["9080"]}'
```

Then `http://localhost:9080/swagger`, `http://localhost:9080/actuator/health`, or MCP Inspector on
`http://localhost:9080/mcp`.

## Updating

Push to `feature/dev`, wait for CI, then in a Session Manager shell: `sudo /opt/transport-mcp/run.sh`.
Logs: `sudo docker logs -f transport-mcp-server`.
