#!/usr/bin/env python3
"""
Load generator for the USSD gRPC Application Server.

Drives the same raw-bytes ``ussd.UssdApplicationService/Process`` method the gateway uses, at a
configurable target throughput (1,000 - 100,000+ TPS) for a fixed duration, and reports achieved
throughput and latency percentiles.

It uses asyncio gRPC for high concurrency on a single process and can fan out across multiple
processes (``--processes``) to reach very high TPS on multi-core hosts.

Usage:
    pip install -r requirements.txt
    python3 loadtest_client.py --target localhost:8443 --tps 1000 --duration 10
    python3 loadtest_client.py --target localhost:8443 --tps 100000 --duration 20 --processes 8
"""

import argparse
import asyncio
import multiprocessing as mp
import time

import grpc

import ussd_envelope as env
import ussd_xml as uxml

_RPC_PATH = "/" + env.FULL_METHOD


def _sample_request(i: int) -> bytes:
    session_id = "load-%d-%d" % (mp.current_process().pid, i)
    dialog = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<dialog type="Begin" appCntx="networkUnstructuredSsContext_version2" networkId="0" '
        'mapMessagesSize="1" returnMessageOnError="false" localId="%d">'
        '<errComponents/><rejectComponents/>'
        '<processUnstructuredSSRequest_Request invokeId="1" dataCodingScheme="15">'
        '<string>*100#</string></processUnstructuredSSRequest_Request></dialog>' % (i,)
    ).encode("utf-8")
    return env.encode_request(session_id, session_id, dialog, push=False, network_id=0)


async def _run_worker(target: str, tps: int, duration: int, max_inflight: int, result_q):
    channel = grpc.aio.insecure_channel(target)
    call = channel.unary_unary(
        _RPC_PATH,
        request_serializer=lambda b: b,
        response_deserializer=lambda b: b,
    )

    latencies = []
    errors = 0
    sent = 0
    sem = asyncio.Semaphore(max_inflight)
    interval = 1.0 / tps if tps > 0 else 0
    start = time.perf_counter()
    deadline = start + duration
    tasks = []

    async def one(idx):
        nonlocal errors
        await sem.acquire()
        t0 = time.perf_counter()
        try:
            await call(_sample_request(idx), timeout=30)
            latencies.append((time.perf_counter() - t0) * 1000.0)
        except Exception:  # noqa: BLE001
            errors += 1
        finally:
            sem.release()

    next_send = start
    i = 0
    while time.perf_counter() < deadline:
        now = time.perf_counter()
        if now < next_send:
            await asyncio.sleep(min(next_send - now, 0.001))
            continue
        tasks.append(asyncio.ensure_future(one(i)))
        i += 1
        sent += 1
        next_send += interval
        if len(tasks) > max_inflight * 4:
            tasks = [t for t in tasks if not t.done()]

    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)
    elapsed = time.perf_counter() - start
    await channel.close()
    result_q.put({"sent": sent, "errors": errors, "elapsed": elapsed, "latencies": latencies})


def _worker_proc(target, tps, duration, max_inflight, result_q):
    asyncio.run(_run_worker(target, tps, duration, max_inflight, result_q))


def _percentile(values, pct):
    if not values:
        return 0.0
    values = sorted(values)
    k = int(round((pct / 100.0) * (len(values) - 1)))
    return values[k]


def main():
    ap = argparse.ArgumentParser(description="USSD gRPC AS load generator")
    ap.add_argument("--target", default="localhost:8443")
    ap.add_argument("--tps", type=int, default=1000, help="target requests/sec (total)")
    ap.add_argument("--duration", type=int, default=10, help="test duration (seconds)")
    ap.add_argument("--processes", type=int, default=1, help="parallel worker processes")
    ap.add_argument("--max-inflight", type=int, default=2000, help="max concurrent in-flight per process")
    args = ap.parse_args()

    per_proc_tps = max(1, args.tps // args.processes)
    result_q = mp.Queue()
    procs = []
    for _ in range(args.processes):
        p = mp.Process(target=_worker_proc,
                       args=(args.target, per_proc_tps, args.duration, args.max_inflight, result_q))
        p.start()
        procs.append(p)

    results = [result_q.get() for _ in procs]
    for p in procs:
        p.join()

    total_sent = sum(r["sent"] for r in results)
    total_err = sum(r["errors"] for r in results)
    elapsed = max(r["elapsed"] for r in results) if results else 0
    all_lat = []
    for r in results:
        all_lat.extend(r["latencies"])
    ok = len(all_lat)

    print("=" * 60)
    print("USSD gRPC AS load test")
    print("  target           : %s" % args.target)
    print("  target TPS       : %d (%d procs x %d)" % (args.tps, args.processes, per_proc_tps))
    print("  duration         : %ds (elapsed %.2fs)" % (args.duration, elapsed))
    print("  sent             : %d" % total_sent)
    print("  ok / errors      : %d / %d" % (ok, total_err))
    if elapsed > 0:
        print("  achieved TPS     : %.0f" % (ok / elapsed))
    if all_lat:
        print("  latency p50 (ms) : %.2f" % _percentile(all_lat, 50))
        print("  latency p95 (ms) : %.2f" % _percentile(all_lat, 95))
        print("  latency p99 (ms) : %.2f" % _percentile(all_lat, 99))
        print("  latency max (ms) : %.2f" % max(all_lat))
    print("=" * 60)


if __name__ == "__main__":
    main()
