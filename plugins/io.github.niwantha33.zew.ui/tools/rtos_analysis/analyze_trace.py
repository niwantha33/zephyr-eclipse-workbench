#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline CTF validation using Babeltrace 2; no decoder or CPU metrics are invented."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from cpu_analysis import account


def summarize(records, *, idle_thread_id=None):
    records = list(records)
    """Consume normalized Babeltrace records. Pair single-stream scheduled intervals only."""
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
        if kind in ('discarded_events', 'discarded_packets'):
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
    result['cpu'] = account(records, idle_thread_id=idle_thread_id)
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
    import bt2
    streams = {}
    for message in bt2.TraceCollectionMessageIterator(str(ctf)):
        if isinstance(message, bt2._DiscardedEventsMessageConst):
            yield {'kind': 'discarded_events', 'count': message.count}
        elif isinstance(message, bt2._DiscardedPacketsMessageConst):
            yield {'kind': 'discarded_packets', 'count': message.count}
        elif isinstance(message, bt2._EventMessageConst):
            event = message.event
            # stream.addr is the stable C object identity exposed by Babeltrace.
            stream = event.stream.addr
            streams.setdefault(stream, len(streams))
            fields = event.payload_field
            def field(name):
                return str(fields[name]) if fields is not None and name in fields else None
            try:
                timestamp = message.default_clock_snapshot.ns_from_origin
            except (AttributeError, ValueError):
                timestamp = None
            yield {'kind': 'event', 'stream': streams[stream], 'name': event.name,
                   'timestamp_ns': timestamp, 'thread_id': field('thread_id'), 'thread_name': field('name')}


def analyze(ctf, *, idle_thread_id=None):
    try:
        return summarize(decode(ctf), idle_thread_id=idle_thread_id)
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
    args = parser.parse_args()
    result = analyze(args.ctf, idle_thread_id=args.idle_thread_id)
    print(json.dumps(result, indent=2))
    return 1 if result['ctf_decode'] == 'invalid' else 0


if __name__ == '__main__':
    raise SystemExit(main())
