"""
TPS warmup ramp — default ON for all load generators.

Ramps through 1 → 100 → 500 → 1000 → 2000 → 3000 → 5000 → 7000 → 10000 TPS
over the first 60 seconds, capped at the configured target TPS.
Avoids slamming full target rate into USSD GW before JVM/SLEE/TCAP are ready.
"""

WARMUP_SECONDS = 60
WARMUP_STEPS = [1, 100, 500, 1000, 2000, 3000, 5000, 7000, 10000]


def add_warmup_arguments(parser):
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "--warmup",
        dest="warmup",
        action="store_true",
        default=True,
        help="ramp TPS over first %ds (default: on)" % WARMUP_SECONDS,
    )
    group.add_argument(
        "--no-warmup",
        dest="warmup",
        action="store_false",
        help="start at full --tps immediately",
    )


def build_step_list(target_tps: int) -> list:
    target = max(1, int(target_tps))
    steps = []
    for value in WARMUP_STEPS:
        if value <= target:
            steps.append(value)
        else:
            if not steps or steps[-1] != target:
                steps.append(target)
            break
    if not steps:
        steps = [1, target]
    elif steps[-1] != target and target > steps[-1]:
        steps.append(target)
    return steps


def target_tps_at(elapsed_sec: float, target_tps: int, warmup: bool = True) -> int:
    """Return allowed session/sec at elapsed seconds from test start."""
    target = max(1, int(target_tps))
    if not warmup or elapsed_sec >= WARMUP_SECONDS:
        return target
    steps = build_step_list(target)
    progress = max(0.0, min(1.0, elapsed_sec / float(WARMUP_SECONDS)))
    idx = int(progress * len(steps))
    if idx >= len(steps):
        idx = len(steps) - 1
    return max(1, steps[idx])


def warmup_summary(target_tps: int, warmup: bool = True) -> str:
    if not warmup:
        return "warmup off — full %d TPS from start" % target_tps
    steps = build_step_list(target_tps)
    return "warmup %ds: %s → %d TPS" % (WARMUP_SECONDS, " → ".join(str(s) for s in steps), target_tps)
