# End-to-End Test: USSD Gateway + gRPC Application Server

Full guide for testing the USSD flow through **USSD Gateway** with a **gRPC Application Server (AS)**, using two toolsets:

| Tool | Location | Role |
|------|----------|------|
| **jSS7 MAP Load Client** | `jSS7/map/load` | Sends MAP `ProcessUnstructuredSSRequest` over SCTP/M3UA — simulates an SS7 subscriber |
| **gRPC Python tester** | `ussdgateway/tools/grpc-as-tester` | AS server + direct gRPC load generator (bypasses MAP) |

Both tools share `menu_config.json` (multi-menu: Balance / Data / Subscribe).

> **Offline test package:** A ready-to-deploy bundle is available at `ussdgw-test/` (see `ussdgw-test/README.md`).

---

## 1. Lab architecture

```mermaid
flowchart LR
  subgraph ss7["SS7 path (true E2E)"]
    MAP["jSS7 MAP Load Client\n:8011 SCTP"]
    GW["USSD Gateway\n:8012 SCTP"]
    GRPC["gRPC AS Python\n:8443"]
    MAP -->|"ProcessUnstructuredSSRequest *100#"| GW
    GW -->|"gRPC Process (JSON envelope)"| GRPC
    GRPC -->|"XmlMAPDialog response"| GW
    GW -->|"UnstructuredSSRequest / End"| MAP
  end

  subgraph grpc_only["gRPC path (direct AS load)"]
    LT["loadtest_client.py"]
    GRPC2["gRPC AS Python\n:8443"]
    LT -->|"Begin + Continue turns"| GRPC2
  end
```

**Two test paths:**

1. **E2E SS7 → GW → gRPC AS** — use `jSS7/map/load` Client (requires SCTP to the gateway).
2. **gRPC-only** — use `loadtest_client.py` to call the AS directly (AS throughput/latency; no MAP).

---

## 2. Prerequisites

| Component | Version / notes |
|-----------|-----------------|
| JDK | 8 |
| Maven | 3.9.x |
| Docker | Gateway image `restcomm-ussd:7.2.1-SNAPSHOT` |
| Python | 3.9+ with `grpcio` |
| SCTP (Linux) | Kernel SCTP for MAP client ↔ gateway |
| jSS7 | Build simulator + map/load (`9.2.12`) |

**Build gateway Docker image** (if not already available):

```bash
cd ussdgateway/release-wildfly
./build-docker.sh
```

**Build jSS7 MAP load client:**

```bash
cd jSS7/map/load
mvn clean test package -Passemble
# Output: target/load/map-load.jar + lib/
```

**Build jSS7 MAP Simulator** (manual step-by-step testing):

```bash
cd jSS7
mvn clean install -pl tools/simulator -am -Dmaven.test.skip=true
# Binary: tools/simulator/bootstrap/target/simulator-ss7/bin/run.sh
```

**Python AS setup:**

```bash
cd ussdgateway/tools/grpc-as-tester
python3 -m venv .venv && ./.venv/bin/pip install -r requirements.txt
```

---

## 3. Demo configuration (must align)

### 3.1 SS7 / SCTP

| Parameter | Gateway | jSS7 MAP client / Simulator |
|-----------|---------|----------------------------|
| Gateway SCTP listen | `8012` (host network) or container map `2905:2905/sctp` | Peer `:8012` or `:2905` |
| Client SCTP bind | — | Local `:8011` |
| M3UA RC / NA | `101` / `102` | `101` / `102` |
| OPC / DPC | GW `2`, peer `1` | Client `1`, peer `2` |
| USSD SSN | `8` | Remote SSN **`8`** |
| MSC / HLR SSN | `8` / `6` | `8` / `6` |
| gRPC short code | `*100#` | USSD string `*100#` |

Reference files:

- Gateway seed: `release-wildfly/config-seed/SCTPManagement_sctp.xml`
- Simulator: `core/bootstrap/src/main/config/ss7-simulator/main_simulator2.xml`

### 3.2 gRPC routing rule

The default seed **does not** include a gRPC rule. Add to
`/opt/ussdgw/data/UssdManagement_scroutingrule.xml` (or patch before first deploy):

```xml
<item>
  <ruleType>GRPC</ruleType>
  <shortcode>*100#</shortcode>
  <networkid>0</networkid>
  <ruleurl>127.0.0.1:8443</ruleurl>
  <exactmatch>true</exactmatch>
</item>
```

- Gateway **in Docker**, AS **on host** (bridge network): use `host.docker.internal:8443` (Linux: add `extra_hosts: host.docker.internal:host-gateway`).
- Both on host (or gateway with `network_mode: host`): `127.0.0.1:8443`.

`ruleurl` is `host:port` only — the gateway is the gRPC **client**; the AS is the server.

The `ussdgw-test` package ships with this rule and bridge settings pre-configured in `gateway/config-seed/`.

### 3.3 Virtual Session Bridge (optional — adaptive timeout testing)

Edit `/opt/ussdgw/data/UssdManagement_ussdproperties.xml`:

```xml
<sessionbridgeenabled>true</sessionbridgeenabled>
<asyncgatetimeoutms>7000</asyncgatetimeoutms>
<dialogtimeout>25000</dialogtimeout>
```

| Property | Meaning |
|----------|---------|
| `asyncGateTimeoutMs` | Gate ceiling (ms); EWMA adaptive value ≤ this |
| `asyncWaitUserMessage` | S1 release text when the gate expires |
| `bridgeStateTtlSec` | Virtual session TTL (180 s) |
| `GRPC_DEADLINE_MS` | 30000 ms (RA deploy-config) |

Design details: [`docs/design/virtual-session-bridge.md`](design/virtual-session-bridge.md).

### 3.4 Menu tree (shared)

File: `tools/grpc-as-tester/menu_config.json` (same content in `jSS7/map/load/src/main/resources/`).

| Profile | Digit sequence | Result |
|---------|----------------|--------|
| `BALANCE` | `1` → `0` | View balance → Exit |
| `DATA` | `2` → `1` | Select 1 GB bundle → Final |
| `SUBSCRIBE` | `3` → `100` | Enter amount → Final |
| `RANDOM` | Random valid choice per node | — |

---

## 4. Start the lab

### Step 1 — Load Docker image

```bash
cd /opt/ussdgw-test
./scripts/01-load-docker-image.sh
docker images restcomm-ussd
```

### Step 2 — Host setup (`/opt/ussdgw`)

```bash
sudo ./scripts/02-setup-host.sh
```

Copies test config (GRPC rule `*100#` → `127.0.0.1:8443`, bridge enabled) into `/opt/ussdgw/data/`.

### Step 3 — Start USSD Gateway with `docker compose up` ⭐

Compose file: `ussdgw-test/gateway/docker-compose.yml`

```bash
cd /opt/ussdgw-test/gateway
docker compose up -d
docker compose ps
curl -fs http://localhost:9990/health && echo " OK"
```

Wait **3–5 minutes** for WildFly (first boot: SLEE deploy + JAR patch). Logs: `docker logs -f ussd-ng`

```bash
./scripts/08-check-gateway.sh
curl -fs http://localhost:9990/health && echo " OK"
```

Stop:

```bash
cd /opt/ussdgw-test/gateway
docker compose down
```

Shortcut: `./scripts/03-start-gateway.sh` (same as `cd gateway && docker compose up -d` + health wait).

**Shortcut for Steps 1–4:** `sudo ./scripts/start-all.sh` (load + setup + compose + gRPC AS).

### Step 4 — gRPC Application Server

```bash
cd /opt/ussdgw-test
./scripts/05-start-grpc-as.sh
tail -3 grpc-as.log   # expect: USSD gRPC AS listening on :8443
```

**From source tree** (dev machine):

```bash
cd ussdgateway/tools/grpc-as-tester
./.venv/bin/python ussd_as_server.py \
  --port 8443 \
  --min-delay 1 --max-delay 100 \
  --menu-config menu_config.json
```

### Step 5 — (Optional) MAP Simulator GUI

Use for step-by-step debugging instead of the load generator:

```bash
cd jSS7/tools/simulator/bootstrap/target/simulator-ss7/bin
cp ../../../../../../ussdgateway/core/bootstrap/src/main/config/ss7-simulator/main_simulator2.xml \
   ../data/main_simulator2.xml
./run.sh gui --name=main
```

In the GUI: select `USSD_TEST_CLIENT`, dial `*100#`, respond to menus manually.

---

## 5. E2E test — Tool 1: jSS7 MAP Load Client

Flow: **MAP client → Gateway SCTP → gRPC AS → multi-turn menu → End**.

### 5.1 Smoke test (one profile, few dialogs)

```bash
cd jSS7/map/load

java -cp "target/load/*" org.restcomm.protocols.ss7.map.load.ussd.Client \
  10 5 sctp 127.0.0.1 8011 -1 127.0.0.1 8012 IPSP 101 102 1 2 3 2 8 6 8 \
  1111112 9960639999 1 16 -100 0 "*100#" BALANCE 50 200
```

> **Port:** With `network_mode: host`, peer port is `8012`. With Docker SCTP map `2905:2905/sctp`, use `2905`.

| Arg (position) | Example | Meaning |
|----------------|---------|---------|
| 1–2 | `10` `5` | 10 dialogs, 5 concurrent |
| 25 | `*100#` | Short code matching gRPC scrule |
| 26 | `BALANCE` | Menu profile |
| 27–28 | `50` `200` | Think delay ms (adaptive gate) |

Or via Ant (defaults in `ussd_build.xml`):

```bash
ant -f ussd_build.xml assemble
ant -f ussd_build.xml client
```

From `ussdgw-test`:

```bash
./scripts/06-run-map-smoke.sh
```

### 5.2 Multi-menu load test

```bash
java -cp "target/load/*" org.restcomm.protocols.ss7.map.load.ussd.Client \
  100000 400 sctp 127.0.0.1 8011 -1 127.0.0.1 8012 IPSP 101 102 1 2 3 2 8 6 8 \
  1111112 9960639999 1 16 -100 5 "*100#" RANDOM 50 300
```

Arg 24 = `5` → run for **5 minutes** (duration mode).

Metrics CSV: `map-*.csv` in the working directory (`CreatedScenario`, `CompletedScenario`, `FailedScenario`).

### 5.3 Success criteria

- [ ] Client log: `AS1 is now ACTIVE`, final throughput reported
- [ ] `CompletedScenario` ≈ completed dialogs; `FailedScenario` low
- [ ] Gateway log: gRPC calls to AS; no `no routing rule` for `*100#`
- [ ] AS log: multiple sessions with menu turns
- [ ] CDR (if enabled): S1/S2 when bridge is enabled

---

## 6. Test — Tool 2: gRPC Python (`loadtest_client.py`)

Flow: **Load client → gRPC AS directly** (no MAP). Use to:

- Benchmark the AS alone (TPS/latency)
- Exercise multi-menu at the gRPC layer (same `menu_config.json`)

### 6.1 Single-shot (Begin only — high throughput)

```bash
cd ussdgateway/tools/grpc-as-tester
./.venv/bin/python loadtest_client.py \
  --target localhost:8443 \
  --tps 1000 --duration 10
```

### 6.2 Multi-menu full session

```bash
./.venv/bin/python loadtest_client.py \
  --target localhost:8443 \
  --tps 200 --duration 30 \
  --multi-menu --profile BALANCE \
  --think-min 50 --think-max 200 \
  --menu-config menu_config.json
```

Profiles: `BALANCE`, `DATA`, `SUBSCRIBE`, `RANDOM`.

Sample output:

```
  mode             : multi-menu
  completed        : 5842
  achieved TPS     : 194
  latency p95 (ms) : 12.34
```

From `ussdgw-test`:

```bash
./scripts/07-run-grpc-smoke.sh
```

### 6.3 Tool comparison

| | MAP Load Client | gRPC loadtest_client |
|--|-----------------|----------------------|
| Entry | SCTP/MAP | gRPC unary |
| Tests gateway routing | ✓ | ✗ |
| Tests MAP dialog / TCAP | ✓ | ✗ |
| Tests gRPC AS menu | ✓ (via GW) | ✓ (direct) |
| Multi-menu | ✓ profiles | ✓ `--multi-menu` |
| Adaptive delay | Think delay + AS delay | `--think-min/max` + AS delay |

---

## 7. Advanced scenarios

### 7.1 Adaptive timeout (EWMA gate)

**Gateway:** `sessionbridgeenabled=true`, `asyncgatetimeoutms=7000`

**AS:**

```bash
./.venv/bin/python ussd_as_server.py --port 8443 --min-delay 1 --max-delay 100
```

**MAP client:** profile `ADAPTIVE` or `RANDOM`, think delay `50–500` ms.

**Expected:** Gate adapts to AS latency; multi-turn dialogs still complete before `dialogtimeout` (25 s).

### 7.2 Bridge late-response (Channel A reconcile)

**AS** deliberately slower than the gate:

```bash
./.venv/bin/python ussd_as_server.py \
  --port 8443 --bridge-delay 8000 --bridge-every 1
```

**Expected:**

1. Gate (7 s) fires → MO release S1 (`asyncWaitUserMessage`)
2. Late AS response → gateway reconciles via `requestId` → NI push S2

Verify: gateway metrics/logs `bridge_late_*`, CDR S1 + S2. Spec: [`docs/design/bridge-unified-reconciliation-rfc.md`](design/bridge-unified-reconciliation-rfc.md).

### 7.3 Direct gRPC bridge test (no MAP)

Use `loadtest_client.py --multi-menu` with AS `--bridge-delay 8000` — tests AS + `requestId` echo in the envelope; **does not** cover the MAP/SCTP path.

---

## 8. Troubleshooting checklist

| Symptom | Common cause | Fix |
|---------|--------------|-----|
| `AS1` not ACTIVE | SCTP not connected | Check ports 8011↔8012, firewall, `modprobe sctp` |
| `Not valid short code` | Missing `*100#` GRPC scrule | Add rule per section 3.2 |
| AS connection refused | AS not running / wrong host from container | `host.docker.internal:8443` or `127.0.0.1:8443` with host network |
| MAP dialog timeout | Wrong SSN (147 vs 8) | Client `ussdSsn=8` |
| Menu stuck at one turn | Single-turn AS / wrong menu | Use `ussd_as_server.py` + `menu_config.json` |
| High `FailedScenario` | Think delay + bridge delay too long | Reduce `--bridge-delay` or increase `dialogtimeout` |
| gRPC load 0 ok | Wrong target / AS down | Check `--target host:8443`; AS must be listening |

**Log locations:**

- MAP client: `client/maplog.txt` (Ant) or stdout / `tools/jss7-map-load/map-*.csv`
- Gateway: `docker logs ussd-ng` or `docker logs ussdgw-e2e`
- gRPC AS: stdout or `ussdgw-test/grpc-as.log` (use `--verbose` for detail)

---

## 9. Related documentation

| Document | Content |
|----------|---------|
| [`tools/grpc-as-tester/`](../tools/grpc-as-tester/) | AS server + load client source |
| [`jSS7/map/load/USSD-LOADTEST.md`](../../jSS7/map/load/USSD-LOADTEST.md) | MAP load CLI reference |
| [`docs/design/virtual-session-bridge.md`](design/virtual-session-bridge.md) | Bridge FSM + adaptive timeout |
| [`docs/design/bridge-unified-reconciliation-rfc.md`](design/bridge-unified-reconciliation-rfc.md) | Late-response reconciliation |
| [`release-wildfly/DEPLOY-GUIDE.md`](../release-wildfly/DEPLOY-GUIDE.md) | Docker deploy + SCTP |
| [`ussdgw-test/README.md`](../../ussdgw-test/README.md) | Offline production test package |

---

*Last updated: 2026-06-21 — multi-menu MAP load + gRPC `--multi-menu`.*
