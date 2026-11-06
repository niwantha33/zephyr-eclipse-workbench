# Zephyr Contention Report (offline preview)

The **Zephyr Contention Report** view analyses **saved CTF semantic events**
only. It does not poll a device or label firmware as defective.

Open **Zephyr → Capture & Analyse → Zephyr Contention Report…**, select an
imported Zephyr project, and choose an existing saved CTF directory. The
read-only report contains *Kind*, *Object*, *Waiter*, *Holder*, *Duration (us)*
and *Explanation*.

The threshold field defaults to **1000 microseconds**. Changing the numeric
threshold triggers a new offline evaluation of the selected saved trace.

## Interpretation

**Priority inversion (Pattern A)** is reported only when all these events
are observed in order:

1. Thread L **successfully acquires** a particular mutex.
2. A distinct thread H **attempts** that same mutex and is explicitly
   **blocked**, while L has confirmed ownership.
3. While H is blocked and L still holds the mutex, an observed scheduler
   **switch-in** identifies a third thread M2 that runs. All three threads
   must have explicit numeric Zephyr priorities with
   **H priority < M2 priority < L priority** (smaller number = higher
   priority in Zephyr).
4. An observed thread **READY** or successful lock-return ends H's wait.
   The report measures the proven blocked span, not a guessed duration.

A matching wait produces **one inversion finding**, no matter how many times
M2 was switched in during that wait. It records H, L, M2, numeric
priorities and the matching mutex. The finding does **not** establish a
missed deadline or a defect.

**Long Wait (Pattern B)** requires an observed mutex/semaphore take attempt
and a successful take-return, or an explicit resource-blocking event with
an observed READY/successful return. The measured interval is clipped to
the two observed boundaries. A finding is emitted only if the duration is
**strictly greater** than the selected threshold; 1000.000 us exactly is
*not* above a 1000 us threshold.

For a blocked wait the time is **BLOCKED → READY / successful return**;
otherwise it is **take attempt → successful return**. The report's
explanation identifies the observed wait. The holder is named only if
a prior successful mutex take established ownership of the *same object*,
with no conflicting take or intervening release. Semaphore holder
identity is not inferred. If unproven, the Holder column stays blank and
the explanation says **holder unknown**.

## Evidence limitations

Stock Zephyr CTF commonly includes resource IDs rather than human-readable
mutex names and may not include numeric thread priorities. The view keeps
IDs explicitly labelled **object ID** instead of inventing a name. If
numeric priority evidence is entirely absent, Pattern A is **disabled**
and the view explains why. Long waits remain reportable.

The normalizer treats Zephyr's CTF \`mutex_lock_enter\` as an **attempt**
and \`mutex_lock_exit\` with **ret = 0** as an **acquisition**; a lock
attempt alone is not ownership evidence. Similarly, semaphore takes use
the enter/blocking/exit distinction. A missing, failed, interrupted,
truncated or otherwise unmatched event cannot be used to manufacture a
duration or a holder. A report without findings is **not** proof that
contention never happened.

The top-of-view disclaimer reads:

> Findings are inferred from trace events. A reported priority inversion means the pattern was observed in the event stream; it does not prove that a deadline was missed.

The report includes no CPU percentages or timeline visualization.
