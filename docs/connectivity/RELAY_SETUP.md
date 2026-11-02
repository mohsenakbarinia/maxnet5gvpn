# MAXNET5G — Authorized Relay Setup & Architecture Guide (`RELAY_SETUP.md`)

## 1. Overview & Strict Legal/Security Policy

When a user's ISP or national routing policy restricts direct connections to foreign VPN gateways (`DOMESTIC_ONLY_SUSPECTED` or `RESTRICTED_INTERNATIONAL`), MAXNET5G can route encrypted VPN traffic through an **Authorized Relay** (`مسیر واسط مجاز`) that remains reachable from the user's network and has an upstream route to the international VPN gateway.

### Mandatory Restrictions (Non-Negotiable)
1. **Explicit Authorization Only**: A relay **must** be operated by the user or application operator, or used with explicit authorization.
2. **No Third-Party Abuse**: MAXNET5G **never** uses ride-hailing apps (e.g., Snapp), banking portals, government websites, CDNs, or unrelated commercial services as unauthorized relays.
3. **End-to-End Encryption & Authentication**: Every relay path uses authenticated, TLS/REALITY-encrypted transport (`VLESS`, `VMess`, `Trojan`, or `Shadowsocks`) end-to-end to the destination gateway. Plaintext or unauthenticated HTTP/SOCKS relays are rejected.
4. **Honest Two-Level Health Reporting**:
   - `RELAY_REACHABLE`: TCP/TLS handshake to the relay succeeded, but forwarding to the international gateway is unverified or failing.
   - `RELAY_FORWARDING_OK`: End-to-end VPN handshake and overlay probes (`L-HTTP`) through the destination gateway succeeded.
   - `RELAY_UNAVAILABLE`: The relay itself is unreachable.

---

## 2. Supported Relay Modes in MAXNET5G

### Mode A — Complete Relay-Fronted Profile (`FULL_PROFILE`)
The user imports a standard `vless://`, `vmess://`, `trojan://`, or `ss://` URI whose `server` and `port` already point to the authorized relay, while `sni` and `host` match the international gateway. No extra configuration is needed beyond importing the profile.

### Mode A2 — Runtime Endpoint Override (`ENDPOINT_OVERRIDE`)
The user adds an `AuthorizedRelay` entry in **Settings -> مدیریت مسیرهای واسط (Authorized Relays)**:
- **Name**: Display label (e.g., `Relay-Tehran-01`)
- **Host / IP**: Domain or IPv4/IPv6 address of the authorized relay
- **Port**: Listening TCP port on the relay (e.g., `443` or `8443`)
- **Supported Transports**: `WS`, `GRPC`, `TCP`, `HTTPUPGRADE`, `XHTTP`
- **Optional SNI Override / SPKI Pin**: Optional TLS SNI or `sha256/...` public key pin

When direct connection to a compatible `TRUSTED_USER` profile fails (or when `DOMESTIC_ONLY_SUSPECTED` is detected), `PathRegistry.materializeProfileForPath()` creates an in-memory connection configuration where:
- Outer TCP connection targets `relay.host:relay.port`
- Inner TLS `sni`, WebSocket/HTTPUpgrade/gRPC `Host` header, and VLESS/VMess/Trojan UUID/password remain bound to the destination international gateway.
- The original stored `VpnProfile` in Room is **never** mutated.

---

## 3. Server-Side Reference Configurations

### Option 1: TCP / TLS SNI Passthrough Relay (HAProxy or Nginx Stream)

Because Mode A2 preserves the TLS SNI and inner encryption of the international VPN gateway, the relay server does **not** need the gateway's TLS private key or user UUIDs. It simply forwards TCP/TLS stream traffic to the international gateway IP:

#### Nginx `stream` block (`/etc/nginx/nginx.conf`):
```nginx
stream {
    map $ssl_preread_server_name $upstream_vpn {
        gw1.example.com 203.0.113.50:443;
        default         203.0.113.50:443;
    }

    server {
        listen 443 reuseport;
        listen [::]:443 reuseport;
        proxy_pass $upstream_vpn;
        proxy_connect_timeout 5s;
        proxy_timeout 1h;
        ssl_preread on;
    }
}
```

### Option 2: Xray-Core Dokodemo-Door / Freedom Forwarding Relay

```json
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "tag": "relay-in",
      "port": 443,
      "protocol": "dokodemo-door",
      "settings": {
        "address": "203.0.113.50",
        "port": 443,
        "network": "tcp"
      }
    }
  ],
  "outbounds": [
    {
      "tag": "direct-to-gateway",
      "protocol": "freedom",
      "settings": {}
    }
  ]
}
```

---

## 4. Verification & Diagnostics

When connected through an Authorized Relay:
- The Home screen displays the badge **«از طریق مسیر واسط»** and headline **«اتصال از طریق مسیر واسط برقرار شد»** once `TunnelHealthVerifier` confirms end-to-end overlay reachability across at least 2 independent international test providers.
- If the relay is reachable (`RELAY_REACHABLE`) but its upstream link to the foreign gateway is severed, MAXNET5G does **not** report a false «Connected» state; it logs `VPN_HANDSHAKE_FAILURE` / `TUNNEL_NO_TRAFFIC` and continues evaluating remaining paths or enters `LONG_OUTAGE_WAIT`.
