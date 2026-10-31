#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only, offline guided diagnostics for one saved Zephyr RAM CTF capture.

Evidence is provenance-checked. Neither a matching ELF checksum nor CTF file
integrity proves that a specific firmware image was flashed onto the MCU.
"""
from __future__ import annotations
import argparse
import hashlib
from html import escape
import json
from pathlib import Path

MAX_RECEIPT = 256 * 1024
MAX_TRACE = 64 * 1024 * 1024
MAX_METADATA = 2 * 1024 * 1024
MAX_FAULT = 4 * 1024 * 1024


def _file(path, cap):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError("Missing or symlinked investigation artifact: " + path.name)
    if path.stat().st_size > cap:
        raise ValueError("Investigation artifact exceeds safe size limit: " + path.name)
    with path.open("rb") as src:
        raw = src.read(cap + 1)
    if len(raw) > cap:
        raise ValueError("Investigation artifact exceeds bounded read limit: " + path.name)
    return raw


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def review_receipt(receipt, *, trace=None, metadata=None, build_elf=None,
                   build_config=None, fault_log=None):
    """Produce sorted evidence-backed findings without mutating any inputs."""
    findings = []
    def finding(code, severity, evidence, action):
        findings.append(dict(code=code, severity=severity, evidence=evidence, action=action))

    if not isinstance(receipt, dict):
        raise ValueError("Capture receipt must be a JSON object")
    if trace is None or metadata is None:
        finding("CAPTURE_BYTES_UNVERIFIED", "blocking", "Raw CTF bytes unavailable",
                "Open the saved capture directory containing ctf/channel0_0 and ctf/metadata.")
    else:
        for name, contents, field in (
                ("CTF stream", trace, "trace_sha256"),
                ("CTF metadata", metadata, "metadata_sha256")):
            if not isinstance(receipt.get(field), str) or receipt[field] != _sha(contents):
                finding("ARTIFACT_HASH_MISMATCH", "blocking", name + " differs from saved receipt",
                        "Preserve the original files and recapture; do not trust this analysis.")
        if receipt.get("trace_bytes") != len(trace):
            finding("TRACE_LENGTH_MISMATCH", "blocking", "Stream length differs from capture receipt",
                    "Recapture the full memory range and inspect read/transfer errors.")
    cap = receipt.get("buffer_capacity")
    if isinstance(cap, int) and cap > 0 and isinstance(receipt.get("trace_bytes"), int):
        if receipt["trace_bytes"] > cap:
            finding("TRACE_EXCEEDS_CAPACITY", "blocking", "Captured bytes exceed the declared RAM buffer",
                    "Check the capture backend layout and selected ELF before decoding.")
    if receipt.get("capture_full") is True:
        finding("TRACE_BUFFER_FULL", "warning", "Backend reported RAM tracing buffer full",
                "Increase CONFIG_RAM_TRACING_BUFFER_SIZE or shorten capture; lost event count is unknown.")
    elif receipt.get("capture_full") == "unknown":
        finding("BUFFER_FULL_UNKNOWN", "warning", "Backend fullness symbol could not be read",
                "Treat loss as unknown and inspect the backend used by this Zephyr version.")
    if receipt.get("transport_read_complete") is not True:
        finding("TRANSPORT_INCOMPLETE", "blocking", "Complete memory read not established",
                "Inspect the halted GDB memory ranges, then retry the capture.")

    analysis = receipt.get("ctf_analysis", {})
    if not isinstance(analysis, dict):
        analysis = {}
    state = analysis.get("ctf_decode", "not checked")
    if state != "validated":
        finding("CTF_NOT_VALIDATED", "blocking" if state == "invalid" else "warning",
                "CTF decoder: " + str(state),
                "Install Babeltrace 2 bt2 for this interpreter or open CTF in Trace Compass.")
    loss = analysis.get("discarded_events_reported", 0)
    dropped = analysis.get("discarded_packets_reported", 0)
    if (isinstance(loss, int) and loss > 0) or (isinstance(dropped, int) and dropped > 0) \
            or analysis.get("discard_count_unknown") is True:
        finding("CTF_REPORTED_LOSS", "blocking", "CTF decoder reported dropped packets/events",
                "CPU/timing metrics must not be used; obtain a complete capture.")
    cpu = analysis.get("cpu", {})
    if not isinstance(cpu, dict):
        cpu = {}
    cpu_status = cpu.get("status", "unavailable")
    if cpu_status != "measured":
        finding("CPU_METRICS_PARTIAL", "warning",
                "Exclusive CPU accounting: " + str(cpu_status) + " (" + str(cpu.get("reason", "not enough evidence")) + ")",
                "Supply the observed idle thread ID and loss-free single-stream CTF switch/ISR events.")
    elif cpu.get("cpu_busy_percent") is None:
        finding("CPU_BUSY_UNKNOWN", "warning", "CPU busy percentage is not established",
                "Identify the idle thread explicitly before claiming utilisation.")
    timing = analysis.get("timing", {})
    if not isinstance(timing, dict):
        timing = {}
    timing_state = timing.get("status", "unavailable")
    if timing_state != "observed":
        finding("TIMING_UNAVAILABLE", "warning",
                "Thread scheduling durations unavailable: " + str(timing.get("reason", "not decoded")),
                "Enable and validate thread switch CTF events; verify clock ordering and loss evidence.")
    else:
        exceed = sum(int(row.get("observed_bursts_exceeding_deadline") or 0)
                     for row in timing.get("threads", {}).values()
                     if isinstance(row, dict))
        if exceed:
            finding("OBSERVED_DEADLINE_EXCEEDANCE", "warning",
                    str(exceed) + " observed scheduling bursts exceeded user-defined thresholds",
                    "Inspect source/trace and declared thresholds; this is not a proof of WCET.")
    if receipt.get("firmware_matches_elf") != "verified":
        finding("FLASH_IDENTITY_UNKNOWN", "warning", "Capture does not authenticate flashed firmware",
                "Use a separately verified flashed-image readback; ELF path/hash alone is not enough.")

    selected_build = "not supplied"
    if build_elf is not None:
        selected_build = "hash-matched" if _sha(build_elf) == receipt.get("elf_sha256") else "hash-mismatch"
        if selected_build == "hash-mismatch":
            finding("SELECTED_ELF_MISMATCH", "blocking", "Selected build ELF hash differs from the capture receipt",
                    "Select the exact unstripped ELF used to make the capture.")
    if build_config is not None and receipt.get("build_config_sha256"):
        if _sha(build_config) != receipt["build_config_sha256"]:
            finding("CONFIG_SNAPSHOT_MISMATCH", "blocking", "Selected generated .config hash differs",
                    "Select the original build configuration; rebuild changes invalidate comparisons.")
    fault = None
    if fault_log is not None:
        text = fault_log.decode("utf-8", "strict")
        markers = text.count("ZEPHYR FATAL ERROR")
        fault = {"sha256": _sha(fault_log), "fatal_markers": markers,
                 "core_dump_marker": "#CD:BEGIN#" in text,
                 "matched_to_trace": "not verified"}
        if markers != 1:
            finding("FAULT_TEXT_AMBIGUOUS", "warning", str(markers) + " fatal summary markers",
                    "Use a single failure excerpt in Zephyr Fault Investigation.")
        finding("FAULT_CORRELATION_UNVERIFIED", "information",
                "Fault log and trace have no authenticated common boot/session clock",
                "Investigate PC/LR/SP and status registers in Zephyr Fault Investigation; confirm matching ELF.")
        if fault["core_dump_marker"]:
            finding("COREDUMP_REQUIRES_DECODER", "information", "Zephyr core-dump marker present",
                    "Use matching Zephyr scripts/coredump and GDB; text analysis cannot unwind core dumps.")

    blocking = [x["code"] for x in findings if x["severity"] == "blocking"]
    return {
        "schema_version": "zew-investigation-1",
        "status": "blocked" if blocking else "review-with-limits",
        "capture": {"trace_bytes": receipt.get("trace_bytes"), "buffer_capacity": cap,
                    "ctf_decode": state, "cpu_status": cpu_status,
                    "cpu_busy_percent": cpu.get("cpu_busy_percent"),
                    "thread_exclusive_ns": cpu.get("thread_exclusive_ns"),
                    "isr_ns": cpu.get("isr_ns"), "unknown_ns": cpu.get("unknown_ns"),
                    "observed_window_ns": cpu.get("observed_window_ns"),
                    "idle_ns": cpu.get("idle_ns"),
                    "selected_build_elf": selected_build,
                    "flashed_firmware": "not independently authenticated"},
        "fault": fault,
        "timing": timing,
        "events": analysis.get("observed_events", {}),
        "findings": findings,
        "limits": [
            "Capture identity, target timestamp accuracy and unreported loss are not independently established.",
            "Observed maxima are not proven WCET, deadlines, or CPU capacity on all cores.",
            "No MCU, probe, debug state, application source or build file was changed.",
        ],
    }


def render_html(report):
    """Portable no-JavaScript diagnostics; bars show observed composition only."""
    if not isinstance(report, dict) or report.get("schema_version") != "zew-investigation-1":
        raise ValueError("Invalid report for HTML rendering")
    data = report["capture"]
    findings = report.get("findings", [])
    def cell(value):
        return escape(str(value), quote=True)
    window = data.get("observed_window_ns")
    durations = data.get("thread_exclusive_ns")
    segments = []
    if isinstance(window, int) and window > 0 and isinstance(durations, dict):
        # Do not graph inconsistent or partial clocks as valid 100% occupancy.
        try:
            values = [(str(name), amount) for name, amount in durations.items()
                      if isinstance(amount, int) and amount >= 0]
            values.extend([("ISR (exclusive)", data.get("isr_ns")),
                           ("Unknown / unobserved", data.get("unknown_ns"))])
            if not all(isinstance(amount, int) and amount >= 0 for _, amount in values):
                values = []
            if sum(amount for _, amount in values) != window:
                values = []
            segments = values[:64]
        except (ValueError, TypeError):
            segments = []
    rows = []
    bars = []
    used = 0
    for label, amount in segments:
        percentage = 100 * amount / window
        if amount == 0:
            continue
        color = "#2563eb" if label not in ("ISR (exclusive)", "Unknown / unobserved") else (
            "#d97706" if label == "ISR (exclusive)" else "#64748b")
        bars.append('<span title="' + cell(label + ': ' + str(amount) + ' ns')
                    + '" style="width:' + f'{percentage:.5f}' + '%;background:' + color + '"></span>')
        rows.append('<tr><td>' + cell(label) + '</td><td>' + str(amount)
                    + ' ns</td><td>' + f'{percentage:.3f}' + '%</td></tr>')
        used += amount
    uart = report.get("uart") or {}
    uart_summary = ('<p>Saved UART lines: ' + cell(uart.get("lines"))
                    + ', raw bytes: ' + cell(uart.get("raw_bytes"))
                    + ', truncated: ' + cell(uart.get("truncated_lines"))
                    + '. Host receive time only; NOT synchronized with MCU trace time.</p>'
                    if uart else '<p>No saved UART application console supplied.</p>')
    entries = ''.join(
        '<li><strong>' + cell(item.get("severity", "unknown")).upper() + ' · '
        + cell(item.get("code", "UNKNOWN")) + '</strong>: '
        + cell(item.get("evidence", "")) + '<p>Next: '
        + cell(item.get("action", "")) + '</p></li>'
        for item in findings[:128] if isinstance(item, dict))
    timing = report.get("timing", {})
    timing_rows = []
    if isinstance(timing, dict) and timing.get("status") == "observed":
        for tid, sample in list(timing.get("threads", {}).items())[:64]:
            if not isinstance(sample, dict): continue
            timing_rows.append('<tr><td>' + cell(tid) + '</td><td>'
                + cell(sample.get("name", "")) + '</td><td>'
                + cell(sample.get("max_observed_burst_ns")) + '</td><td>'
                + cell(sample.get("period_range_jitter_ns")) + '</td><td>'
                + cell(sample.get("max_observed_ready_latency_ns")) + '</td><td>'
                + cell(sample.get("observed_bursts_exceeding_deadline")) + '</td></tr>')
    timing_table = ('<table><tr><th>Thread</th><th>Name</th><th>Max observed burst (ns)</th>'
                    '<th>Period range jitter (ns)</th><th>Max ready latency (ns)</th>'
                    '<th>Observed threshold exceedances</th></tr>'
                    + ''.join(timing_rows) + '</table>') if timing_rows else (
                    '<p>No qualified observed timing table; inspect CTF timing quality.</p>')
    busy = data.get("cpu_busy_percent")
    busy_text = "unknown (requires explicit idle identity and full interval coverage)" \
        if busy is None else cell(str(busy) + "% (conditional scheduler occupancy)")
    chart = ('<div class="bar">' + ''.join(bars) + '</div><table>'
             + ''.join(rows) + '</table>') if bars and used == window else (
             '<p>No defensible time-composition chart: decoder/clock/coverage evidence unavailable.</p>')
    return '''<!doctype html><html lang="en"><head><meta charset="utf-8">
<title>Zephyr RTOS offline investigation</title><style>
body{max-width:980px;margin:3em auto;padding:0 20px;font:16px/1.5 system-ui,sans-serif;color:#1e293b}
h1,h2{line-height:1.2}section{border-top:1px solid #cbd5e1;padding:16px 0}
.bar{display:flex;width:100%;height:36px;border-radius:7px;overflow:hidden;background:#e2e8f0}
.bar span{height:100%;min-width:0}
table{border-collapse:collapse;width:100%;margin-top:14px}
td{border-bottom:1px solid #e2e8f0;padding:7px}
li{margin:12px 0}li p{margin:4px 0 0 14px;color:#475569}
small{color:#64748b}
</style></head><body><h1>Zephyr RTOS — offline investigation</h1>
<p>Result: ''' + cell(report.get("status", "unknown")) + '''. No target was touched.</p>
<section><h2>Observed execution composition</h2><p>CPU busy: ''' + busy_text + '''</p>''' + chart + '''
<small>Single-stream CTF, observed nanoseconds only. Unknown is not idle.
ISR time is separated from interrupted threads. Do not infer complete trace capture or WCET.</small></section>
<section><h2>UART application messages</h2>''' + uart_summary + '''</section>
<section><h2>Thread timing and deadline observations</h2>''' + timing_table + '''
<small>Observed scheduling bursts include interrupts; their maximum is NOT WCET.
Ready latency requires a matched explicit ready event. No inferred blocked state.</small></section>
<section><h2>Guided findings</h2><ol>''' + entries + '''</ol></section>
<section><h2>Provenance and limits</h2><p>CTF decoder: ''' + cell(data.get("ctf_decode")) + '''.
Selected ELF: ''' + cell(data.get("selected_build_elf")) + '''.
Flashed firmware: NOT independently authenticated.</p>
<ul>''' + ''.join('<li>' + cell(line) + '</li>' for line in report.get("limits", [])) + '''
</ul></section></body></html>'''


def review_uart(folder):
    """Validate independently saved UART bytes; never infer target time/session."""
    folder = Path(folder)
    if folder.is_symlink() or not folder.is_dir():
        raise ValueError("Select a real saved UART session directory")
    receipt = json.loads(_file(folder / "receipt.json", MAX_RECEIPT).decode("utf-8"))
    raw = _file(folder / "raw.bin", MAX_TRACE)
    lines = _file(folder / "messages.jsonl", 16 * 1024 * 1024)
    if receipt.get("schema") != "zew-uart-1":
        raise ValueError("Unsupported UART capture receipt format")
    findings = []
    if receipt.get("sha256") != _sha(raw) or receipt.get("bytes") != len(raw):
        findings.append({"code": "UART_ARTIFACT_MISMATCH", "severity": "blocking",
                         "evidence": "UART bytes or checksum differ from saved receipt",
                         "action": "Do not trust this recording; recapture the application console."})
    count = 0
    invalid = 0
    truncated = 0
    for entry in lines.splitlines():
        if len(entry) > 32 * 1024:
            raise ValueError("UART message record exceeds bounded size")
        message = json.loads(entry)
        if message.get("target_timestamp") is not None:
            findings.append({"code": "UART_TARGET_TIME_UNVERIFIED", "severity": "blocking",
                             "evidence": "Unexpected target timestamp in host-only recording",
                             "action": "Use the original raw data; host receive time is not MCU time."})
            break
        if not message.get("host_received_utc") or not isinstance(message.get("line"), str):
            raise ValueError("Malformed UART message evidence")
        count += 1
        invalid += bool(message.get("contains_replacement"))
        truncated += bool(message.get("truncated"))
    if receipt.get("lines") != count:
        findings.append({"code": "UART_LINES_MISMATCH", "severity": "blocking",
                         "evidence": "Saved UART message count differs from receipt",
                         "action": "Check the original capture for partial/truncated output."})
    if receipt.get("message_index_complete") is False:
        findings.append({"code": "UART_MESSAGE_INDEX_PARTIAL", "severity": "warning",
                         "evidence": "Serial recorder stopped indexing messages at its size/line limit",
                         "action": "Preserve raw.bin; shorten capture or reduce console volume."})
    if truncated:
        findings.append({"code": "UART_TRUNCATED_LINES", "severity": "warning",
                         "evidence": str(truncated) + " UART messages were truncated",
                         "action": "Adjust application output rate and buffer/line limits before correlating logs."})
    if invalid:
        findings.append({"code": "UART_ENCODING_REPLACEMENTS", "severity": "warning",
                         "evidence": str(invalid) + " lines contained invalid UTF-8",
                         "action": "Verify console framing, baud and selected encoding; preserve the raw bytes."})
    findings.append({"code": "UART_CLOCK_UNCORRELATED", "severity": "information",
                     "evidence": "UART host-reception timestamps have no proven common target clock with CTF",
                     "action": "Provide explicit target-side timestamps and a tested synchronization mapping."})
    return {
        "schema": "zew-uart-1", "lines": count, "raw_bytes": len(raw),
        "raw_sha256": _sha(raw), "encoding_replacements": invalid,
        "truncated_lines": truncated, "host_time_only": True,
        "matched_to_trace": "not verified", "findings": findings,
    }


def review(path, *, build=None, fault_log=None, uart_session=None):
    folder = Path(path)
    if folder.is_symlink() or not folder.is_dir():
        raise ValueError("Select a real capture directory, not a symlink")
    receipt = json.loads(_file(folder / "capture.json", MAX_RECEIPT).decode("utf-8"))
    trace = _file(folder / "ctf" / "channel0_0", MAX_TRACE)
    metadata = _file(folder / "ctf" / "metadata", MAX_METADATA)
    elf = config = None
    if build is not None:
        build = Path(build)
        elf = _file(build / "zephyr" / "zephyr.elf", MAX_TRACE)
        config = _file(build / "zephyr" / ".config", MAX_METADATA)
    fatal = _file(fault_log, MAX_FAULT) if fault_log is not None else None
    report = review_receipt(receipt, trace=trace, metadata=metadata,
                            build_elf=elf, build_config=config, fault_log=fatal)
    if uart_session is not None:
        uart = review_uart(uart_session)
        report["uart"] = {key: value for key, value in uart.items() if key != "findings"}
        report["findings"].extend(uart["findings"])
        if any(item["severity"] == "blocking" for item in uart["findings"]):
            report["status"] = "blocked"
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--build", type=Path)
    parser.add_argument("--fault-log", type=Path)
    parser.add_argument("--uart-session", type=Path, help="Optional saved serial-* directory with receipt.json")
    args = parser.parse_args()
    try:
        result = review(args.capture, build=args.build, fault_log=args.fault_log,
                        uart_session=args.uart_session)
    except (OSError, ValueError, UnicodeError, json.JSONDecodeError) as error:
        print(json.dumps({"status": "blocked", "reason": str(error)}))
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 1 if result["status"] == "blocked" else 0


if __name__ == "__main__":
    raise SystemExit(main())
