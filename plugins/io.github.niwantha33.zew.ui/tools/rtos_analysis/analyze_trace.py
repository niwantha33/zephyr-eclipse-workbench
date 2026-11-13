#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline CTF validation using Babeltrace 2; no decoder or CPU metrics are invented."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from cpu_analysis import account
from timing_analysis import analyze_timing
from semantic_trace import SemanticTraceParser, EventKind, decode_ctf
from thread_event_log import events_table


def summarize(records, *, idle_thread_id=None, deadlines_ns=None):
    """Consume normalized Babeltrace records. Pair single-stream scheduled intervals only."""
    semantic = SemanticTraceParser().parse(records)
    if semantic.decoder_unavailable:
        return {'ctf_decode': 'not checked', 'reason': 'Babeltrace 2 Python module bt2 is unavailable. '
                'Install it for this Python interpreter or validate the CTF in Trace Compass.',
                'timing_scope': 'No timing metrics computed.', 'incomplete': True,
                'unknown_events': semantic.unknown_count}
    if semantic.incomplete and semantic.error:
        return {'ctf_decode': 'invalid', 'reason': semantic.error or 'Incomplete or malformed CTF input',
                'timing_scope': 'No timing metrics computed.', 'incomplete': True,
                'partial_semantic_events': len(semantic.events), 'unknown_events': semantic.unknown_count,
                'unknown_event_message': semantic.unknown_message}
    records = semantic.raw_records
    result = {'ctf_decode': 'validated', 'events': 0, 'streams': 0,
              'discarded_events_reported': 0, 'discarded_packets_reported': 0,
              'discard_count_unknown': False, 'warnings': [], 'threads': {},
              'timing_scope': 'Complete switch-in/out pairs only; includes ISR time. Not CPU utilisation.'}
    streams = set()
    active = None
    previous = None
    timing_valid = True
    first = last = None
    for record in records:
        kind = record['kind']
        if kind in ('discarded_events', 'discarded_packets', 'ram_buffer_full'):
            if kind == 'ram_buffer_full':
                result['buffer_full_reported'] = True
                result['warnings'].append(
                    'RAM tracing buffer reported full; lost event count unknown. '
                    'CPU/timing conclusions withheld.')
            else:
                count = record.get('count')
                if count is None:
                    result['discard_count_unknown'] = True
                else:
                    key = 'discarded_events_reported' if kind == 'discarded_events' else 'discarded_packets_reported'
                    result[key] += count
            timing_valid = False
            active = None
            continue
        result['events'] += 1
        streams.add(record['stream'])
        timestamp = record.get('timestamp_ns')
        if len(streams) > 1 or timestamp is None or (previous is not None and timestamp < previous):
            timing_valid = False
            active = None
        previous = timestamp
        if timestamp is not None:
            first = timestamp if first is None else min(first, timestamp)
            last = timestamp if last is None else max(last, timestamp)
        name = record['name'].rsplit(':', 1)[-1]
        thread_id = record.get('thread_id')
        if thread_id is None or name not in ('thread_create', 'thread_name_set', 'thread_switched_in', 'thread_switched_out'):
            continue
        thread = result['threads'].setdefault(str(thread_id), {'name': '', 'complete_intervals': 0, 'scheduled_ns': 0})
        if record.get('thread_name'):
            thread['name'] = record['thread_name']
        if not timing_valid:
            continue
        if name == 'thread_switched_in':
            if active is not None:
                result['warnings'].append('Switch-in without matching switch-out; incomplete interval excluded.')
            active = (str(thread_id), timestamp)
        elif name == 'thread_switched_out':
            if active is not None and active[0] == str(thread_id):
                thread['complete_intervals'] += 1
                thread['scheduled_ns'] += timestamp - active[1]
            else:
                result['warnings'].append('Switch-out without matching switch-in; incomplete interval excluded.')
            active = None
    result['streams'] = len(streams)
    # Preserve PR #190's observed-only event log alongside semantic normalization.
    # The parser's raw records retain decoder-reported loss and original names.
    result['thread_event_log'] = events_table(records)
    result['semantic_event_count'] = len(semantic.events)
    result['unknown_events'] = semantic.unknown_count
    result['unknown_event_message'] = semantic.unknown_message
    result['incomplete'] = semantic.incomplete
    result['semantic_kind_counts'] = {
        kind.value: sum(event.kind == kind for event in semantic.events)
        for kind in EventKind if any(event.kind == kind for event in semantic.events)
    }
    result['cpu'] = account(semantic, idle_thread_id=idle_thread_id)
    result['timing'] = analyze_timing(semantic, deadlines_ns=deadlines_ns)
    result['observed_events'] = {}
    for record in records:
        if record.get('kind') == 'event':
            name = str(record.get('name', '')).rsplit(':', 1)[-1]
            if len(result['observed_events']) < 256 or name in result['observed_events']:
                result['observed_events'][name] = result['observed_events'].get(name, 0) + 1
    result['first_timestamp_ns'] = first
    result['last_timestamp_ns'] = last
    if active is not None:
        result['warnings'].append('Capture ends inside a scheduled interval; trailing interval excluded.')
    if not result['events']:
        result['ctf_decode'] = 'invalid'
        result['warnings'].append('Decoder produced no events.')
    if not timing_valid:
        result['warnings'].append('Timing totals withheld: missing/reordered timestamps, multiple streams or reported loss.')
        for thread in result['threads'].values():
            thread['scheduled_ns'] = None
            thread['complete_intervals'] = None
    result['warnings'] = list(dict.fromkeys(result['warnings']))
    result['loss_detection_scope'] = 'Decoder-reported loss only; absence of reports does not prove no events were lost.'
    return result


def decode(ctf):
    """Compatibility entrypoint: the shared normalizer owns CTF decoding."""
    yield from decode_ctf(ctf)

def analyze(ctf, *, idle_thread_id=None, deadlines_ns=None, capture_full=False):
    try:
        records = decode(ctf)
        if capture_full is True:
            # While capture is staged, capture.json has not been written yet.
            # Its target-side full flag is passed explicitly, not inferred.
            from itertools import chain
            records = chain(records, ({'kind': 'ram_buffer_full', 'count': None},))
        return summarize(records, idle_thread_id=idle_thread_id, deadlines_ns=deadlines_ns)
    except ImportError:
        return {'ctf_decode': 'not checked', 'reason': 'Babeltrace 2 Python module bt2 is unavailable. '
                'Install it for this Python interpreter or validate the CTF in Trace Compass.',
                'timing_scope': 'No timing metrics computed.'}
    except Exception as error:
        return {'ctf_decode': 'invalid', 'reason': str(error), 'timing_scope': 'No timing metrics computed.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ctf', type=Path)
    parser.add_argument('--idle-thread-id', help='Explicit observed idle thread ID; never guessed')
    parser.add_argument('--deadline', action='append', default=[], metavar='THREAD_ID:NS',
                        help='Optional observed per-thread scheduling-burst deadline in nanoseconds')
    args = parser.parse_args()
    deadlines = {}
    for item in args.deadline:
        try:
            key, value = item.rsplit(':', 1)
            if not key or int(value) <= 0: raise ValueError()
            deadlines[key] = int(value)
        except ValueError:
            parser.error('Each --deadline must be THREAD_ID:POSITIVE_NS')
    result = analyze(args.ctf, idle_thread_id=args.idle_thread_id, deadlines_ns=deadlines)
    print(json.dumps(result, indent=2))
    return 1 if result['ctf_decode'] == 'invalid' else 0


if __name__ == '__main__':
    raise SystemExit(main())
