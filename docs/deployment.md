# Deployment and hardening

mihon-source runs third-party code: every extension you install executes inside the service's JVM. Its classloader hides the service's own classes, but it is **not a sandbox**: an extension can do anything the process can, including reading files the process can read and opening network connections. So treat the container as the security boundary and give it as little as possible.

[`compose.example.yaml`](../compose.example.yaml) puts all of this together.

## The container

| Setting | Why |
|---|---|
| `read_only: true` | Nothing outside `/data` can be changed, by the service or by an extension. Tested: the service runs and installs extensions with a read-only root. |
| `tmpfs: /tmp` (e.g. 64 MB) | The JVM and the JavaScript engine need a scratch directory. |
| Volume on `/data` only | All state lives there ([configuration](configuration.md)). Nothing else needs to persist. |
| `cap_drop: [ALL]` and `no-new-privileges` | The service needs no Linux capabilities. |
| Non-root user | The image runs as uid 10001. Don't override it with root. |
| `mem_limit`, `pids_limit` | Bound what a misbehaving extension can take. About 1 GB is plenty for dozens of extensions. |
| No Docker socket, no host network | Nothing in mihon-source needs them. |

The image has a health check: it turns healthy once the service is ready (installed extensions loaded, `GET /readyz` answers 200), so compose can wait for it with `depends_on: {mihon-source: {condition: service_healthy}}`. Starting with many extensions can take a while, hence the 60-second start period. The probe also runs by hand as `mihon-source healthcheck [URL]` and follows `MIHON_PORT`; set `MIHON_HEALTHCHECK_URL` if the service listens elsewhere.

Run it as its own container, separate from anything holding other data: an extension can read whatever this container can see.

## Secrets

- Set the admin token yourself with `MIHON_ADMIN_TOKEN_FILE`, pointing at a Docker secret, rather than an environment variable that shows up in `docker inspect`. Every `MIHON_*` variable also has a `_FILE` form.
- Back up `/data/secret.key`, or set `MIHON_SECRET_KEY`/`MIHON_SECRET_KEY_FILE`. Stored proxy passwords can't be decrypted without it.
- Tokens, API keys, cookies and proxy credentials are never logged. Logs are safe to ship to a log collector.

## Network

- **TLS:** mihon-source serves plain HTTP and gRPC. For anything beyond localhost or a private network, put a reverse proxy with TLS in front (it must allow HTTP/2 for gRPC clients). Give clients scoped API keys, not the admin token.
- **Metrics:** `/metrics` on the main port needs a token. `MIHON_METRICS_PORT` serves it without one, so only publish that port on an internal network.
- **Solvers:** run FlareSolverr/Byparr without publishing their ports. Only mihon-source needs to reach them.
- **Outbound access:** extensions need to reach their sites, so outbound HTTP(S) is required. Restricting it further (egress filtering, a proxy for everything) is possible with network profiles.

## VPNs

mihon-source doesn't run VPN tunnels itself. A tunnel needs `NET_ADMIN` and a `tun` device, which conflicts with the hardening above. Instead, run a VPN container that exposes a proxy, and point network profiles at it. [gluetun](https://github.com/qdm12/gluetun) does this for many providers, with OpenVPN or WireGuard, and has a built-in HTTP proxy (port 8888).

### Some sources through a VPN (recommended)

```yaml
services:
  gluetun:
    image: qmcgaw/gluetun:latest
    cap_add: [NET_ADMIN]
    devices: [/dev/net/tun]
    environment:
      VPN_SERVICE_PROVIDER: <your provider>
      VPN_TYPE: wireguard            # or openvpn
      WIREGUARD_PRIVATE_KEY: <key>   # or OPENVPN_USER / OPENVPN_PASSWORD, see gluetun's docs
      SERVER_COUNTRIES: <country>
      HTTPPROXY: "on"                # the built-in HTTP proxy, on port 8888
    restart: unless-stopped
    # No published ports: only mihon-source talks to the proxy.

  mihon-source:
    # ... as in compose.example.yaml
```

Check gluetun's documentation for your provider's variables. Then, in the admin UI (Integrations) or with `PutProfile`:

1. Add a network profile `vpn` with the proxy URL `http://gluetun:8888`.
2. Assign it to the sources that should use the VPN, or make it the default profile so every source does.

Sources without the profile keep their direct connection, and you can have several VPN containers (one per country, say) as separate profiles. If gluetun's tunnel goes down, its proxy stops forwarding, so those sources fail instead of falling back to your own connection.

### Everything through the VPN

Alternatively, attach mihon-source to gluetun's network namespace:

```yaml
services:
  gluetun:
    # ... as above, plus:
    ports:
      - "8902:8902"   # mihon-source's port is published on gluetun now
  mihon-source:
    network_mode: "service:gluetun"
    # no "ports:" here
```

All of mihon-source's traffic then goes through the VPN, and gluetun's kill switch blocks traffic when the tunnel is down. It's all or nothing, though: every source and every solver call uses the VPN, per-source choices aren't possible, and mihon-source is reachable only through gluetun's published ports.
