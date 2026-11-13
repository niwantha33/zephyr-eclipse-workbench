#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Project only observed CTF events to a bounded text table, without state inference."""

LIMIT = 128
DIRECT = {
    "thread_switched_in": "switch-in observed; scheduled",
    "thread_switched_out": "switch-out observed; ready/wait/sleep UNKNOWN",
    "thread_ready": "ready event observed; interval UNKNOWN",
    "thread_sched_ready": "scheduler ready event observed; interval UNKNOWN",
    "thread_sched_pend": "scheduler pending event observed; interval UNKNOWN",
    "thread_pend": "pending event observed; interval UNKNOWN",
    "thread_sleep": "sleep request observed; interval UNKNOWN",
    "thread_wakeup": "wakeup observed; next state UNKNOWN",
    "thread_create": "thread creation observed; state UNKNOWN",
    "thread_name_set": "thread name observed",
    "isr_enter": "ISR entry observed",
    "isr_exit": "ISR exit observed",
}

def plain(value, bound=96):
    if value is None:
        return ""
    return "".join(ch if 32 <= ord(ch) <= 126 else "?" for ch in str(value))[:bound]

def events_table(records, limit=LIMIT):
    if not isinstance(limit, int) or not 1 <= limit <= LIMIT:
        raise ValueError("Invalid event table row limit")
    rows, known_names, streams = [], {}, set()
    observed = omitted = 0
    last_time = None
    loss = bad_clock = False
    for rec in records:
        kind = rec.get("kind")
        if kind in ("discarded_events", "discarded_packets", "ram_buffer_full"):
            loss = True
            if len(rows) >= limit:
                omitted += 1
            else:
                rows.append({"timestamp_ns": None, "stream": "", "core": "", "thread_id": "",
                             "thread_name": "", "event": plain(kind),
                             "observed": ("RAM buffer full; lost event count UNKNOWN"
                                          if kind == "ram_buffer_full" else
                                          "reported loss: event sequence INCOMPLETE")})
            continue
        if kind != "event":
            continue
        observed += 1
        stream = rec.get("stream")
        streams.add(stream)
        ns = rec.get("timestamp_ns")
        if not isinstance(ns, int) or isinstance(ns, bool) or ns < 0:
            ns = None
            bad_clock = True
        elif last_time is not None and ns < last_time:
            bad_clock = True
        if ns is not None:
            last_time = ns
        name = plain(rec.get("name"))
        suffix = name.rsplit(":", 1)[-1]
        tid = plain(rec.get("thread_id"), 32)
        explicit = plain(rec.get("thread_name"))
        if tid and explicit and suffix in ("thread_create", "thread_name_set"):
            known_names[tid] = explicit
        if len(rows) >= limit:
            omitted += 1
            continue
        rows.append({"timestamp_ns": ns, "stream": plain(stream, 20),
                     "core": plain(rec.get("core_id"), 20), "thread_id": tid,
                     "thread_name": explicit or known_names.get(tid, ""),
                     "event": name, "observed": DIRECT.get(suffix, "event observed; state UNKNOWN")})
    quality = ("partial: reported CTF loss" if loss else
               "partial: missing/reordered timestamps" if bad_clock else
               "partial: multiple streams; global clock ordering UNKNOWN" if len(streams) > 1 else
               "observed events only; unreported loss and target clock accuracy UNKNOWN")
    return {"rows": rows, "quality": quality, "events_observed": observed,
            "rows_omitted": omitted, "row_cap": limit,
            "source": "normalized saved CTF stream (native Zephyr 4.4 or Babeltrace 2)",
            "source_locations": "UNKNOWN: no validated PC/ELF mapping",
            "state_intervals": "UNKNOWN absent explicit matched state evidence",
            "clock": "CTF nanoseconds if available; calibration unverified"}
