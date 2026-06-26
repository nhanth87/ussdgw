# gRPC AS tester tools

| Script | Role | Default target |
|--------|------|----------------|
| `ussd_as_server.py` | gRPC **Pull** AS (MO) | listen `:8443` |
| `loadtest_client.py` | gRPC Pull load client | `localhost:8443` |
| `grpc_push_client.py` | gRPC **Push** NI load client | `localhost:8453` (GW push server) |

## gRPC Push NI (gateway as server)

Enable in web management: **Server Settings → gRPC Push** (`GrpcPushServerEnabled`, port `8453`).

```bash
python3 grpc_push_client.py --target localhost:8453 \
  --mode multi --profile BALANCE --tps 1000 --duration 30 \
  --menu-config menu_config.json
```

Bridge S2 (late response): `--request-id <mo-request-id>` in envelope.

See `ussdgw-test/docs/e2e-grpc-ussd-test.md` — AT-GP1 / AT-GP2.
