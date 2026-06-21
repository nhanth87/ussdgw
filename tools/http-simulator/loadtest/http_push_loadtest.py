#!/usr/bin/env python3
"""
HTTP Push load generator — auto-builds XmlMAPDialog (no manual XML).

Posts to gateway push URL (default /restcomm). Modes:
  notify  — unstructuredSSNotify only
  request — single NI USSD request
  multi   — multi-step push menu (profile BALANCE/DATA/SUBSCRIBE)

Usage:
    python3 http_push_loadtest.py --target http://127.0.0.1:8080/restcomm \\
        --mode multi --profile BALANCE --tps 1000 --duration 30
"""

import argparse
import asyncio
import multiprocessing as mp
import random
import time
import uuid

import aiohttp

import menu_engine
import ussd_xml as uxml
import warmup


async def _post(session, url, body: bytes, timeout: int):
    headers = {"Content-Type": "text/xml; charset=utf-8"}
    async with session.post(url, data=body, headers=headers, timeout=timeout) as resp:
        await resp.read()
        return resp.status


async def _run_notify(target, tps, duration, msisdn, text, max_inflight, use_warmup, result_q):
    latencies, errors, sent = [], 0, 0
    sem = asyncio.Semaphore(max_inflight)
    start = time.perf_counter()
    deadline = start + duration
    tasks = []

    async with aiohttp.ClientSession() as session:
        async def one_sess(idx):
            nonlocal errors
            await sem.acquire()
            t0 = time.perf_counter()
            try:
                body = uxml.build_push_notify(text, msisdn)
                await _post(session, target, body, 30)
                await _post(session, target, uxml.build_dialog_end(), 30)
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
            tasks.append(asyncio.ensure_future(one_sess(i)))
            i += 1
            sent += 1
            next_send += interval
            if len(tasks) > max_inflight * 4:
                tasks = [t for t in tasks if not t.done()]
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
    elapsed = time.perf_counter() - start
    result_q.put({"sent": sent, "errors": errors, "elapsed": elapsed, "latencies": latencies, "ok": len(latencies)})


async def _run_multi(target, tps, duration, msisdn, profile, menu_config, think_min, think_max,
                     max_inflight, use_warmup, result_q):
    walker = menu_engine.MenuEngine(menu_config)
    root_text = walker.menu_text()
    latencies, errors, sent = [], 0, 0
    sem = asyncio.Semaphore(max_inflight)
    start = time.perf_counter()
    deadline = start + duration
    tasks = []

    async with aiohttp.ClientSession() as session:
        async def session_push(idx):
            nonlocal errors
            await sem.acquire()
            t0 = time.perf_counter()
            sub = "%s%04d" % (msisdn.rstrip("0123456789") or "2519", idx % 10000)
            try:
                await _post(session, target, uxml.build_push_request(root_text, sub), 30)
                for digit in walker.walk_profile(profile):
                    if think_max > 0:
                        await asyncio.sleep(random.randint(max(0, think_min), think_max) / 1000.0)
                    prompt = "Choice %s received" % digit
                    await _post(session, target, uxml.build_push_request(prompt, sub), 30)
                await _post(session, target, uxml.build_dialog_end(), 30)
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
    elapsed = time.perf_counter() - start
    result_q.put({"sent": sent, "errors": errors, "elapsed": elapsed, "latencies": latencies, "ok": len(latencies)})


def _worker(args_tuple):
    (mode, target, tps, duration, msisdn, text, profile, think_min, think_max,
     menu_config, max_inflight, use_warmup, result_q) = args_tuple
    if mode == "notify":
        asyncio.run(_run_notify(target, tps, duration, msisdn, text, max_inflight, use_warmup, result_q))
    else:
        asyncio.run(_run_multi(target, tps, duration, msisdn, profile, menu_config,
                               think_min, think_max, max_inflight, use_warmup, result_q))


def _percentile(values, pct):
    if not values:
        return 0.0
    values = sorted(values)
    k = int(round((pct / 100.0) * (len(values) - 1)))
    return values[k]


def main():
    ap = argparse.ArgumentParser(description="USSD HTTP Push load generator")
    ap.add_argument("--target", default="http://127.0.0.1:8080/restcomm")
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
    warmup.add_warmup_arguments(ap)
    args = ap.parse_args()

    if args.mode == "request":
        args.mode = "multi"
        args.profile = "BALANCE"

    per_tps = max(1, args.tps // args.processes)
    result_q = mp.Queue()
    worker_args = (args.mode, args.target, per_tps, args.duration, args.msisdn,
                   args.notify_text, args.profile, args.think_min, args.think_max,
                   args.menu_config, args.max_inflight, args.warmup, result_q)
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
    print("USSD HTTP Push load test")
    print("  target     : %s" % args.target)
    print("  mode       : %s" % args.mode)
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
