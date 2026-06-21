#!/usr/bin/env python3
"""
Load generator for the USSD gRPC Application Server.

Supports single-shot Begin requests (default) or full multi-menu sessions that mirror
``menu_config.json`` / jSS7 MAP load client profiles.

Usage:
    python3 loadtest_client.py --target localhost:8443 --tps 1000 --duration 10
    python3 loadtest_client.py --target localhost:8443 --tps 200 --duration 30 \\
        --multi-menu --profile BALANCE --think-min 50 --think-max 200
"""

import argparse
import asyncio
import json
import multiprocessing as mp
import random
import time

import grpc

import ussd_envelope as env
import ussd_xml as uxml
import warmup

_RPC_PATH = "/" + env.FULL_METHOD


class MenuWalker:
    """Minimal client-side menu state — mirrors ussd_as_server.MenuEngine choices."""

    PROFILES = {
        "BALANCE": ["1", "0"],
        "DATA": ["2", "1"],
        "SUBSCRIBE": ["3", "100"],
    }

    def __init__(self, config_path: str):
        with open(config_path, "r", encoding="utf-8") as f:
            cfg = json.load(f)
        self.root = cfg["root"]
        self.nodes = cfg["nodes"]

    def _scripted(self, node_name, profile):
        script = self.PROFILES.get(profile.upper(), [])
        node = self.nodes.get(node_name, self.nodes[self.root])
        opts = node.get("options", {})
        for digit in script:
            if digit in opts or "*" in opts:
                return digit
        keys = [k for k in opts.keys() if k != "*"]
        return random.choice(keys) if keys else None

    def walk(self, profile: str):
        """Yield (ussd_string, end) steps after the initial MO Begin."""
        node_name = self.root
        script = self.PROFILES.get(profile.upper())
        script_idx = 0
        while True:
            node = self.nodes.get(node_name, self.nodes[self.root])
            if node.get("final"):
                return
            opts = node.get("options", {})
            if script and script_idx < len(script):
                choice = script[script_idx]
                script_idx += 1
            else:
                keys = [k for k in opts.keys() if k != "*"]
                choice = random.choice(keys) if keys else None
            if choice is None:
                return
            next_name = opts.get(choice) or opts.get("*")
            yield choice, False
            if next_name == "__end__":
                return
            if next_name is None:
                return
            nxt = self.nodes.get(next_name, {})
            node_name = next_name
            if nxt.get("final"):
                return


def _begin_request(session_id: str, local_id: int, ussd: str = "*100#") -> bytes:
    dialog = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<dialog type="Begin" appCntx="networkUnstructuredSsContext_version2" networkId="0" '
        'mapMessagesSize="1" returnMessageOnError="false" localId="%d">'
        '<errComponents/><rejectComponents/>'
        '<processUnstructuredSSRequest_Request invokeId="1" dataCodingScheme="15">'
        '<string>%s</string></processUnstructuredSSRequest_Request></dialog>'
        % (local_id, ussd)
    ).encode("utf-8")
    request_id = "r-%s-0" % session_id
    return env.encode_request(session_id, session_id, dialog, push=False, network_id=0,
                              request_id=request_id)


def _continue_request(session_id: str, choice: str, invoke_id: int, req_idx: int) -> bytes:
    dialog = uxml.build_response(
        message_type="ussd_response",
        text=choice,
        invoke_id=invoke_id,
        end=False,
        user_object="sessionId=%s" % session_id,
        network_id=0,
    )
    request_id = "r-%s-%d" % (session_id, req_idx)
    return env.encode_request(session_id, session_id, dialog, push=False, network_id=0,
                              request_id=request_id)


async def _run_single_shot(target, tps, duration, max_inflight, use_warmup, result_q):
    channel = grpc.aio.insecure_channel(target)
    call = channel.unary_unary(_RPC_PATH, request_serializer=lambda b: b, response_deserializer=lambda b: b)
    latencies, errors, sent = [], 0, 0
    sem = asyncio.Semaphore(max_inflight)
    start = time.perf_counter()
    deadline = start + duration
    tasks = []

    async def one(idx):
        nonlocal errors
        await sem.acquire()
        t0 = time.perf_counter()
        try:
            await call(_begin_request("load-%d-%d" % (mp.current_process().pid, idx), idx), timeout=30)
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
    result_q.put({"sent": sent, "errors": errors, "elapsed": elapsed, "latencies": latencies, "sessions": sent})


async def _run_multi_menu(target, tps, duration, max_inflight, profile, think_min, think_max,
                          menu_config, use_warmup, result_q):
    channel = grpc.aio.insecure_channel(target)
    call = channel.unary_unary(_RPC_PATH, request_serializer=lambda b: b, response_deserializer=lambda b: b)
    walker = MenuWalker(menu_config)
    latencies, errors, sessions = [], 0, 0
    sem = asyncio.Semaphore(max_inflight)
    start = time.perf_counter()
    deadline = start + duration
    tasks = []

    async def session(idx):
        nonlocal errors, sessions
        await sem.acquire()
        t0 = time.perf_counter()
        sid = "mm-%d-%d" % (mp.current_process().pid, idx)
        try:
            resp_b = await call(_begin_request(sid, idx), timeout=30)
            resp = env.decode_response(resp_b)
            parsed = uxml.parse_request(resp.get("payload", b""))
            invoke_id = parsed.get("invoke_id", 1)
            req_idx = 1
            for choice, _ in walker.walk(profile):
                if think_max > 0:
                    await asyncio.sleep(random.randint(max(0, think_min), think_max) / 1000.0)
                resp_b = await call(_continue_request(sid, choice, invoke_id, req_idx), timeout=30)
                req_idx += 1
                resp = env.decode_response(resp_b)
                parsed = uxml.parse_request(resp.get("payload", b""))
                if parsed.get("message_type") == "process_response":
                    break
                invoke_id = parsed.get("invoke_id", invoke_id)
            latencies.append((time.perf_counter() - t0) * 1000.0)
            sessions += 1
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
        tasks.append(asyncio.ensure_future(session(i)))
        i += 1
        next_send += interval
        if len(tasks) > max_inflight * 4:
            tasks = [t for t in tasks if not t.done()]
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)
    elapsed = time.perf_counter() - start
    await channel.close()
    result_q.put({"sent": i, "errors": errors, "elapsed": elapsed, "latencies": latencies, "sessions": sessions})


def _worker_proc(args_tuple):
    (target, tps, duration, max_inflight, multi_menu, profile, think_min, think_max,
     menu_config, use_warmup, result_q) = args_tuple
    if multi_menu:
        asyncio.run(_run_multi_menu(target, tps, duration, max_inflight, profile, think_min, think_max,
                                    menu_config, use_warmup, result_q))
    else:
        asyncio.run(_run_single_shot(target, tps, duration, max_inflight, use_warmup, result_q))


def _percentile(values, pct):
    if not values:
        return 0.0
    values = sorted(values)
    k = int(round((pct / 100.0) * (len(values) - 1)))
    return values[k]


def main():
    ap = argparse.ArgumentParser(description="USSD gRPC AS load generator")
    ap.add_argument("--target", default="localhost:8443")
    ap.add_argument("--tps", type=int, default=1000, help="target sessions/sec (total)")
    ap.add_argument("--duration", type=int, default=10, help="test duration (seconds)")
    ap.add_argument("--processes", type=int, default=1, help="parallel worker processes")
    ap.add_argument("--max-inflight", type=int, default=2000, help="max concurrent in-flight per process")
    ap.add_argument("--multi-menu", action="store_true", help="walk full menu tree per session")
    ap.add_argument("--profile", default="RANDOM", help="BALANCE, DATA, SUBSCRIBE, or RANDOM")
    ap.add_argument("--think-min", type=int, default=0, help="min delay between menu turns (ms)")
    ap.add_argument("--think-max", type=int, default=0, help="max delay between menu turns (ms)")
    ap.add_argument("--menu-config", default="menu_config.json")
    warmup.add_warmup_arguments(ap)
    args = ap.parse_args()

    per_proc_tps = max(1, args.tps // args.processes)
    result_q = mp.Queue()
    procs = []
    worker_args = (args.target, per_proc_tps, args.duration, args.max_inflight,
                   args.multi_menu, args.profile, args.think_min, args.think_max,
                   args.menu_config, args.warmup, result_q)
    for _ in range(args.processes):
        p = mp.Process(target=_worker_proc, args=(worker_args,))
        p.start()
        procs.append(p)

    results = [result_q.get() for _ in procs]
    for p in procs:
        p.join()

    total_sent = sum(r["sent"] for r in results)
    total_sessions = sum(r.get("sessions", 0) for r in results)
    total_err = sum(r["errors"] for r in results)
    elapsed = max(r["elapsed"] for r in results) if results else 0
    all_lat = []
    for r in results:
        all_lat.extend(r["latencies"])
    ok = len(all_lat)

    print("=" * 60)
    print("USSD gRPC AS load test")
    print("  target           : %s" % args.target)
    print("  mode             : %s" % ("multi-menu" if args.multi_menu else "single-shot"))
    if args.multi_menu:
        print("  profile          : %s" % args.profile)
        print("  think delay (ms) : %d-%d" % (args.think_min, args.think_max))
    print("  target TPS       : %d (%d procs x %d)" % (args.tps, args.processes, per_proc_tps))
    print("  %s" % warmup.warmup_summary(args.tps, args.warmup))
    print("  duration         : %ds (elapsed %.2fs)" % (args.duration, elapsed))
    print("  started          : %d" % total_sent)
    print("  completed        : %d" % total_sessions)
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
