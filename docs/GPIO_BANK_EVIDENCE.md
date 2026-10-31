<!-- SPDX-License-Identifier: Apache-2.0 -->
# GPIO logical-index evidence (DTS is not application use)

The last generated Zephyr `zephyr.dts` can contain GPIO consumers inherited
from the **board defaults**. A board's `led0` on MSPM0 GPIO B bit 22, for
example, is a **DTS declaration**, not proof that an application configured
or toggled PB22. Source-text matches have similarly weak semantics.

The Visual Hardware Studio now leaves **all GPIO cells neutral by default**.
Optional "Board DTS declarations" and "Unverified C patterns" layers can be
switched on independently to inspect `D` and `C?` annotations on neutral
cells. No occupied/active colouring is inferred. The GPIO Evidence table keeps
exact DTS consumer paths/flags and source file/line provenance without
claiming runtime use. Missing or stale builds are indicated.

The register bit indexes are not package pads: without an authoritative
vendor part/package mapping, TI MSPM0 displays up to 32 logical indexes
per bank, not proof of 32 physically bonded pins. A DTS `gpios` phandle
does not prove direction, runtime level, compiler reachability, live
configuration, a conflict-free design, or physical bonding.

Only numeric, unambiguous GPIO specifiers with verified controller
phandle/labels are decoded. Unresolved and mixed/invalid properties do not
result in fabricated controller assignments. User edits require reviewed
overlay changes and an exact-target rebuild, not direct editing of generated
DTS.

## Release verification

The native SWT regression tests require both evidence overlays to be off by
default and verify that the board-provided PB22/led0 name is not presented as
application usage. The headless tests verify phandle parsing and unresolved
controller handling. Firmware CI, installed Eclipse smoke and physical target
validation are required separately before a commercial release.
