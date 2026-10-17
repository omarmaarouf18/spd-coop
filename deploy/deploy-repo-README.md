# spd-coop-deploy

Production deployment of the Logic Link PD co-op server (architecture §4.3, decision D-015 in the main repo).

> **Bot-only repository.** Every file here is synced from the main repo's `deploy/` folder by its `build-and-publish.yml` workflow, using a GitHub App. **Never edit, commit or push here by hand.** Change the reference file in the main repo (`deploy/…`) and let the bot sync it. A manual change gets overwritten on the next sync.

| File | Source in the main repo |
|---|---|
| `docker-compose.yml` | `deploy/docker-compose.prod.yml`, with `:IMAGE_TAG` replaced by the commit SHA |
| `Caddyfile` | `deploy/Caddyfile` |
| `.env.example` | `deploy/.env.example` |
| `.gitignore` | `deploy/deploy-repo.gitignore` |
| `.github/workflows/deploy.yml` | `deploy/deploy.yml` |
| `README.md` | `deploy/deploy-repo-README.md` |

Every push to `main` runs `deploy.yml` on the VPS's self-hosted runner (label `spd-coop-vps`). It runs these steps in order:
1. `.env` symlink.
2. `docker compose config`.
3. Pre-flight `server -check-env`. If it fails, the running containers are left untouched.
4. Pull, then `up -d`.
5. Health check: `/server -healthcheck` inside the container, plus `https://$DOMAIN/healthz` from outside.
6. If the health check fails, an automatic rollback to the previous commit's `docker-compose.yml`, then a log dump.

## First-time VPS setup (owner, once)

1. Install Docker Engine with the compose plugin. Allow only ports 22, 80 and 443 in the firewall.
2. Create the user and its environment file:
   ```bash
   sudo useradd -m -s /bin/bash -G docker deploybot
   sudo -u deploybot install -m 600 /dev/null /home/deploybot/.env
   sudoedit /home/deploybot/.env   # contents from .env.example, with the real DOMAIN
   ```
3. Add a DNS **A record** from `DOMAIN` to the VPS IP. Caddy requests the TLS certificate on first start, so ports 80 and 443 must be reachable.
4. Install a self-hosted GitHub Actions runner **for this repository**, as `deploybot`, with the label `spd-coop-vps`, running as a service. Use the "New self-hosted runner" instructions under *Settings → Actions → Runners*, then run `sudo ./svc.sh install deploybot` and `sudo ./svc.sh start`.

The server image is public on GHCR (`ghcr.io/omarmaarouf18/spd-coop-server`), so the VPS pulls it without credentials.

## Manual deploy and rollback

- **Re-deploy the current commit:** *Actions → Deploy → Run workflow*.
- **Roll back** to an older release: in the main repo, re-run `build-and-publish` (*Run workflow*) on an older commit of `main`. That pins its image again. In an emergency on the VPS (as `deploybot`, in the runner's checkout of this repo):
  ```bash
  git log --oneline -5 -- docker-compose.yml
  git checkout <older-commit> -- docker-compose.yml
  docker compose up -d --remove-orphans
  ```
  The next bot push restores the tracked file.

## Logs

```bash
docker compose ps
docker compose logs --tail=200 server
docker compose logs --tail=200 caddy
```
Logs are `json-file`, rotated at 10 MB × 3 per container.

## Backup of the server state

The state lives in the Docker volume **`spd-coop_server_data`** (`/data/coop.db`, SQLite). The volume name is pinned, so it is the same whatever directory compose runs from.

```bash
# backup (brief stop for a consistent copy)
docker compose stop server
docker run --rm -v spd-coop_server_data:/data:ro -v "$PWD":/backup alpine \
  tar czf "/backup/server_data-$(date +%Y%m%d-%H%M).tgz" -C /data .
docker compose start server

# restore
docker compose stop server
docker run --rm -v spd-coop_server_data:/data -v "$PWD":/backup alpine \
  sh -c 'rm -rf /data/* && tar xzf /backup/<file>.tgz -C /data'
docker compose start server
```
Keep backups off the VPS. Caddy's certificates (`spd-coop_caddy_data`) don't need a backup, because Caddy re-issues them.
