# Zephyr Memory Profile — saved CTF evidence only

The **Zephyr Memory Profile** view reviews a **saved** Zephyr CTF directory
without target polling. Open **Zephyr → Capture & Analyse → Zephyr Memory
Profile…** and choose a captured `ctf` directory.

## Verified native CTF names

Verified against [Zephyr CTF metadata](https://github.com/zephyrproject-rtos/zephyr/blob/main/subsys/tracing/ctf/tsdl/metadata)
and [CTF hooks](https://github.com/zephyrproject-rtos/zephyr/blob/main/subsys/tracing/ctf/ctf_top.c):

| API | Actual CTF events | Relevant fields |
|---|---|---|
| `k_malloc` | `heap_sys_k_malloc_enter` / `heap_sys_k_malloc_exit` | `id` (heap); exit `ret` (returned pointer) |
| `k_free` | `heap_sys_k_free_enter` / `heap_sys_k_free_exit` | `id` (heap), `heap_ref` (pointer) |
| `k_mem_slab_alloc` | `mem_slab_alloc_enter` / `mem_slab_alloc_exit` | `id` (slab); exit `ret` (zero means success) |
| `k_mem_slab_free` | `mem_slab_free_enter` / `mem_slab_free_exit` | `id` (slab) |

**Not found as literal native CTF event names, so left unmapped:**
`k_malloc`, `k_free`, `k_mem_slab_alloc`, `k_mem_slab_free`,
`mem_alloc`, `mem_free`. The speculative `mem_alloc` and `mem_free`
raw-name aliases were removed. The semantic `MEM_ALLOC` / `MEM_FREE`
event kinds remain.

**Stock CTF has no allocation size in these events.** For malloc, the
successful returned pointer can identify an allocation and a subsequent
matching free; for slabs, only slab-level successful operations can be
counted. The emitted slab records have neither block pointer nor size.
Therefore **do not infer allocation sizes from IDs, pointers, a memory
slab declaration, or Kconfig**.

Exact outstanding-byte balances and peaks require actual size evidence in
a compatible trace (e.g., a genuine `size_bytes`, `allocation_size` or
`size` field on a successful allocation). The Workbench does not add these
fields to firmware. Where size or pairing evidence is missing, byte
figures display **unavailable**, never an assumed value. An observed
allocation count is not an absolute census of a heap whose lifetime
predates the recording.

The analyzer computes an observed count/byte **step function** over
recorded event timestamps. The Eclipse view provides a summary and a
pool/slab table (Alloc count, Free count, Peak bytes). Pool names are used
only when a `pool_name`, `heap_name` or `slab_name` exists; otherwise an
observed ID is explicitly labelled as such.

## Exact user-facing states

**No allocation events:**

> No memory allocation events found. Enable CONFIG_TRACING_HEAP or the equivalent memory tracing option in the Zephyr build to use this view.

The diagnostic name `CONFIG_TRACING_HEAP` may not be defined in every
Zephyr revision: verify actual tracing options in the generated `.config`.

**Complete, matched and fully sized trace ending with outstanding allocations:**

> Trace ends with N outstanding allocations (M bytes). This may indicate a leak or may simply mean the allocations are long-lived. The trace alone cannot distinguish these.

This is an **observed imbalance**, not a leak finding. An unmatched free
or duplicate live pointer prevents reliable balance reporting.

**Truncated/lossy trace** (the imbalance line is suppressed entirely):

> Trace is incomplete; outstanding allocation imbalance cannot be assessed.

The profiler neither estimates missing sizes nor extrapolates beyond
observed events. See the [Zephyr tracing guide](https://docs.zephyrproject.org/latest/services/tracing/index.html)
for obtaining a saved CTF capture and matching metadata.
