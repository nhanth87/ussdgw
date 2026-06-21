# HTTP USSD load tools

Replaces manual XML paste in the legacy Swing HTTP Simulator for load / adaptive-timeout testing.

| Script | Role | Scenario |
|--------|------|----------|
| `http_as_server.py` | **Pull** AS (listen) | Gateway POSTs MO dialog → auto menu XML response |
| `http_push_loadtest.py` | **Push** load client | POST XmlMAPDialog to gateway `/restcomm` at 1000 TPS |

Shared with gRPC / MAP load: `menu_config.json`, same menu profiles (`BALANCE`, `DATA`, `SUBSCRIBE`).

## Pull (HTTP MO → AS)

Routing rule example: `*519#` → `http://127.0.0.1:8049/`

```bash
python3 http_as_server.py --port 8049 --min-delay 1 --max-delay 100
# adaptive / bridge timeout test:
python3 http_as_server.py --bridge-delay 8000 --bridge-every 10
```

Then run MAP load with short code `*519#` (see `ussdgw-test/scripts/12-run-http-pull-smoke.sh`).

## Push (gateway NI)

```bash
pip install -r requirements.txt
python3 http_push_loadtest.py --target http://127.0.0.1:8080/restcomm \\
  --mode multi --profile BALANCE --tps 1000 --duration 30
```

Modes: `notify`, `request`, `multi` (multi-menu, no hand-written XML).

Legacy GUI simulator distro: `../` (parent `http-simulator` Maven bootstrap).
