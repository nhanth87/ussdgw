#!/usr/bin/env python3
"""
gRPC NI Push load client — gateway acts as gRPC server (default port 8453).

Sends ``ussd.UssdApplicationService/Process`` with ``push=true`` and XmlMAPDialog payload.
Mirrors ``http_push_loadtest.py`` for the gRPC push ingress (GrpcServerSbb).

Usage:
    python3 grpc_push_client.py --target localhost:8453 \\
        --mode multi --profile BALANCE --tps 1000 --duration 30

Bridge S2 (late AS response with request id):
    python3 grpc_push_client.py --target localhost:8453 \\
        --mode request --msisdn 251911234567 \\
        --request-id r-abc-0 --payload-file menu.xml
"""

import argparse
import asyncio
import multiprocessing as mp
import random
import time
import uuid

import grpc

import menu_engine
import ussd_envelope as env
import ussd_xml as uxml
import warmup

_RPC_PATH = "/" + env.FULL_METHOD


async def _unary(call, body: bytes, timeout: int = 30):
    return await call(body, timeout=timeout)


async def _run_notify(target, tps, duration, msisdn, text, max_inflight, use_warmup, result_q):
    channel = grpc.aio.insecure_channel(target)
    call = channel.unary_unary(_RPC_PATH, request_serializer=lambda b: b, response_deserializer=lambda b: b)
    latencies, errors, sent = [], 0, 0
    sem = asyncio.Semaphore(max_inflight)
    start = time.perf_counter()
    deadline = start + duration
    tasks = []

    async def one_push(idx):
        nonlocal errors
        await sem.acquire()
        t0 = time.perf_counter()
        sid = "gp-%d-%s" % (idx, uuid.uuid4().hex[:8])
        try:
            payload = uxml.build_push_notify(text, msisdn)
            req = env.encode_request(sid, sid, payload, push=True, network_id=0)
            await _unary(call, req)
            end_req = env.encode_request(sid, sid, uxml.build_dialog_end(), push=True, network_id=0)
            await _unary(call, end_req)
            latencies.append((time.perf_counter() - t0) * 1000.0)
        except Exception:  # noqa: BLE001
            errors += 1
        finally:
            sem.release()

    next_send = start
    i = 0
    while time.perf_counter() < deadline:
        now = time.perf_counter()
        elapsed = now - start
        current_tps = warmup.target_tps_at(elapsed, tps, use_warmup)
        interval = 1.0 / current_tps if current_tps > 0 else 0
        if now < next_send:
            await asyncio.sleep(min(next_send - now, 0.001))
            continue
        tasks.append(asyncio.ensure_future(one_push(i)))
        i += 1
        sent += 1
        next_send += interval
        if len(tasks) > max_inflight * 4:
            tasks = [t for t in tasks if not t.done()]
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)
    await channel.close()
    elapsed = time.perf_counter() - start
    result_q.put({"sent": sent, "errors": errors, "elapsed": elapsed, "latencies": latencies, "ok": len(latencies)})


async def _run_multi(target, tps, duration, msisdn, profile, menu_config, think_min, think_max,
                     max_inflight, use_warmup, request_id, result_q):
    walker = menu_engine.MenuEngine(menu_config)
    root_text = walker.menu_text()
    channel = grpc.aio.insecure_channel(target)
    call = channel.unary_unary(_RPC_PATH, request_serializer=lambda b: b, response_deserializer=lambda b: b)
    latencies, errors, sent = [], 0, 0
    sem = asyncio.Semaphore(max_inflight)
    start = time.perf_counter()
    deadline = start + duration
    tasks = []

    async def session_push(idx):
        nonlocal errors
        await sem.acquire()
        t0 = time.perf_counter()
        sub = "%s%04d" % (msisdn.rstrip("0123456789") or "2519", idx % 10000)
        sid = "gp-%d-%s" % (idx, uuid.uuid4().hex[:8])
        try:
            rid = request_id if request_id else None
            body = uxml.build_push_request(root_text, sub)
            req = env.encode_request(sid, sid, body, push=True, network_id=0, request_id=rid)
            await _unary(call, req)
            invoke = 2
            for digit in walker.walk_profile(profile):
                if think_max > 0:
                    await asyncio.sleep(random.randint(max(0, think_min), think_max) / 1000.0)
                prompt = "Choice %s received" % digit
                body = uxml.build_push_request(prompt, sub, invoke_id=invoke)
                req = env.encode_request(sid, sid, body, push=True, network_id=0)
                await _unary(call, req)
                invoke += 1
            end_req = env.encode_request(sid, sid, uxml.build_dialog_end(), push=True, network_id=0)
            await _unary(call, end_req)
            latencies.append((time.perf_counter() - t0) * 1000.0)
        except Exception:  # noqa: BLE001
            errors += 1
        finally:
            sem.release()

    next_send = start
    i = 0
    while time.perf_counter() < deadline:
        now = time.perf_counter()
        elapsed = now - start
        current_tps = warmup.target_tps_at(elapsed, tps, use_warmup)
        interval = 1.0 / current_tps if current_tps > 0 else 0
        if now < next_send:
            await asyncio.sleep(min(next_send - now, 0.001))
            continue
        tasks.append(asyncio.ensure_future(session_push(i)))
        i += 1
        sent += 1
        next_send += interval
        if len(tasks) > max_inflight * 4:
            tasks = [t for t in tasks if not t.done()]
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)
    await channel.close()
    elapsed = time.perf_counter() - start
    result_q.put({"sent": sent, "errors": errors, "elapsed": elapsed, "latencies": latencies, "ok": len(latencies)})


def _worker(args_tuple):
    (mode, target, tps, duration, msisdn, text, profile, think_min, think_max,
     menu_config, max_inflight, use_warmup, request_id, result_q) = args_tuple
    if mode == "notify":
        asyncio.run(_run_notify(target, tps, duration, msisdn, text, max_inflight, use_warmup, result_q))
    else:
        asyncio.run(_run_multi(target, tps, duration, msisdn, profile, menu_config,
                               think_min, think_max, max_inflight, use_warmup, request_id, result_q))


def _percentile(values, pct):
    if not values:
        return 0.0
    values = sorted(values)
    k = int(round((pct / 100.0) * (len(values) - 1)))
    return values[k]


def main():
    ap = argparse.ArgumentParser(description="USSD gRPC NI Push load client (gateway push server)")
    ap.add_argument("--target", default="localhost:8453",
                    help="gRPC push ingress host:port (web mgmt: GrpcPushServerPort, default 8453)")
    ap.add_argument("--mode", choices=["notify", "request", "multi"], default="multi")
    ap.add_argument("--tps", type=int, default=1000)
    ap.add_argument("--duration", type=int, default=30)
    ap.add_argument("--processes", type=int, default=1)
    ap.add_argument("--max-inflight", type=int, default=2000)
    ap.add_argument("--msisdn", default="251911234567")
    ap.add_argument("--notify-text", default="Your balance is 100 ETB")
    ap.add_argument("--profile", default="BALANCE")
    ap.add_argument("--think-min", type=int, default=50)
    ap.add_argument("--think-max", type=int, default=200)
    ap.add_argument("--menu-config", default="menu_config.json")
    ap.add_argument("--request-id", default=None,
                    help="Virtual Session Bridge S2: gateway request id (reconcile PUSH_GRPC)")
    warmup.add_warmup_arguments(ap)
    args = ap.parse_args()

    if args.mode == "request":
        args.mode = "multi"
        args.profile = "BALANCE"

    per_tps = max(1, args.tps // args.processes)
    result_q = mp.Queue()
    worker_args = (args.mode, args.target, per_tps, args.duration, args.msisdn,
                   args.notify_text, args.profile, args.think_min, args.think_max,
                   args.menu_config, args.max_inflight, args.warmup, args.request_id, result_q)
    procs = []
    for _ in range(args.processes):
        p = mp.Process(target=_worker, args=(worker_args,))
        p.start()
        procs.append(p)
    results = [result_q.get() for _ in procs]
    for p in procs:
        p.join()

    total_sent = sum(r["sent"] for r in results)
    total_err = sum(r["errors"] for r in results)
    total_ok = sum(r.get("ok", 0) for r in results)
    elapsed = max(r["elapsed"] for r in results) if results else 0
    all_lat = []
    for r in results:
        all_lat.extend(r["latencies"])

    print("=" * 60)
    print("USSD gRPC NI Push load test")
    print("  target     : %s" % args.target)
    print("  mode       : %s" % args.mode)
    if args.request_id:
        print("  request-id : %s (bridge S2)" % args.request_id)
    print("  target TPS : %d (%d x %d)" % (args.tps, args.processes, per_tps))
    print("  %s" % warmup.warmup_summary(args.tps, args.warmup))
    print("  duration   : %ds (elapsed %.2fs)" % (args.duration, elapsed))
    print("  started    : %d" % total_sent)
    print("  ok / errors: %d / %d" % (total_ok, total_err))
    if elapsed > 0:
        print("  achieved TPS: %.0f" % (total_ok / elapsed))
    if all_lat:
        print("  p50 (ms)   : %.2f" % _percentile(all_lat, 50))
        print("  p95 (ms)   : %.2f" % _percentile(all_lat, 95))
        print("  p99 (ms)   : %.2f" % _percentile(all_lat, 99))
    print("=" * 60)


if __name__ == "__main__":
    main()
