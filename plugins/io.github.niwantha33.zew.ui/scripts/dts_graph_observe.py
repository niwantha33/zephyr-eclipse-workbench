#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only, exact-build DTS topology observation for the native Eclipse graph.

Protocol ZEW-DTSGRAPH-1: ASCII tab-separated records, with all payloads base64
UTF-8 encoded. S(state, sha256, reason), N(path, name, status, compatible,
labels, line), E(source, target, relation, property, detail). A relation is
shown only if upstream dtlib or edtlib resolved its exact target node.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import re
import sys
from pathlib import Path

MAX_DTS_BYTES = 4 * 1024 * 1024
MAX_CACHE_BYTES = 8 * 1024 * 1024
MAX_NODES = 3500
MAX_EDGES = 6000
MAX_RECORD_BYTES = 3_000_000
FORBIDDEN = (b"/include/", b"/incbin/")
TI_PIN_DEFINITIONS = Path("modules/hal/ti/dts/ti/mspm0/g/mspm0g1x0x_g3x0x-pinctrl.dtsi")
TI_BUS_PIN = re.compile(
    r"\b((?:i2c|spi|uart|canfd)\d+)_([a-z0-9_]+)_(p[ab]\d{1,2})\s*:\s*"
    r"[a-z0-9_]+\s*\{\s*pinmux\s*=\s*"
    r"<MSP_PINMUX\(\s*(\d+)\s*,\s*MSPM0_PIN_FUNCTION_(\d+)\s*\)>\s*;")
PIN_HEADERS = {
    "st,stm32-pinctrl": "stm32-pinctrl.h",
    "nordic,nrf-pinctrl": "nrf-pinctrl.h",
    "espressif,esp32-pinctrl": "esp-pinctrl-common.h",
}


def emit(tag: str, *values: object) -> None:
    print("\t".join([tag, *(base64.b64encode(str(v).encode("utf-8")).decode("ascii")
                           for v in values)]))


def checked(path: Path, limit: int) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ValueError("missing-or-linked-input")
    before = path.stat()
    if before.st_size > limit:
        raise ValueError("input-size-limit")
    data = path.read_bytes()
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError("input-changed")
    if len(data) > limit or b"\0" in data:
        raise ValueError("invalid-input-bytes")
    data.decode("utf-8")
    return data


def ti_bus_pins(zephyr, root):
    """Index exact bus mux values from the workspace's TI HAL pin declarations."""
    compatible = root.props.get("compatible")
    if compatible is None or "ti,mspm0g3507" not in compatible.to_strings():
        return {}
    try:
        definitions = checked(zephyr.parent / TI_PIN_DEFINITIONS, 1024 * 1024).decode("utf-8")
        header = checked(zephyr / "include/zephyr/dt-bindings/pinctrl/mspm0-pinctrl.h",
                         64 * 1024).decode("utf-8")
    except (OSError, ValueError):
        return {}
    # Accept only the encoding defined by this checkout's Zephyr binding.
    encoding = re.sub(r"\s+", "", header)
    if "#defineMSP_PINMUX(pincm,function)(((pincm-1)<<0x10)|function)" not in encoding:
        return {}
    pins = {}
    for controller, signal, pad, register, function in TI_BUS_PIN.findall(definitions):
        register, function = int(register), int(function)
        if 1 <= register <= 256 and 2 <= function <= 15:
            value = ((register - 1) << 16) | function
            pins.setdefault((controller, value), set()).add((signal.upper(), pad.upper()))
    return pins


def ti_bus_pin(pins, paths, source, target):
    if not pins:
        return None
    owner = paths.get(target.path.rsplit("/", 1)[0])
    prop = target.props.get("pinmux")
    compatible = owner.props.get("compatible") if owner else None
    if prop is None or compatible is None or "ti,mspm0-pinctrl" not in compatible.to_strings():
        return None
    if prop.type.name != "NUM":
        return None
    value = prop.to_num()
    matches = {match for label in source.labels or []
               for match in pins.get((label, value), ())}
    if len(matches) == 1:
        signal, pad = next(iter(matches))
        return signal, pad, value
    return None


def pin_definitions(zephyr, header):
    path = zephyr / "include/zephyr/dt-bindings/pinctrl" / header
    try:
        source = checked(path, 64 * 1024).decode("utf-8")
    except (OSError, ValueError):
        return {}
    return {key: int(value, 16 if value.lower().startswith("0x") else 10)
            for key, value in re.findall(
        r"(?m)^#define[ \t]+(\w+)[ \t]+(0[xX][0-9a-fA-F]+|[0-9]+)[uUlL]*\b", source)}


def vendor_pin_value(compatible, fields, value):
    """Use the selected checkout's defined fields; keep unresolved signal names explicit."""
    def get(shift, mask):
        offset, bits = fields[shift], fields[mask]
        if not 0 <= offset < 32 or not 1 <= bits <= 0xffff:
            raise ValueError("unexpected pinctrl field")
        return (value >> offset) & bits

    try:
        if compatible == "st,stm32-pinctrl":
            port = get("STM32_PORT_SHIFT", "STM32_PORT_MASK")
            pin = get("STM32_LINE_SHIFT", "STM32_LINE_MASK")
            mode = get("STM32_MODE_SHIFT", "STM32_MODE_MASK")
            if value > 0x3fff or port not in {*range(17), 25} or pin > 15 or mode > 17:
                return None
            signal = f"AF{mode}" if mode < 16 else ("Analog" if mode == 16 else "GPIO")
            return signal, f"P{chr(65 + port)}{pin}"
        if compatible == "nordic,nrf-pinctrl":
            pin = get("NRF_PIN_POS", "NRF_PIN_MSK")
            function = get("NRF_FUN_POS", "NRF_FUN_MSK")
            if pin == fields["NRF_PIN_MSK"]:
                return None
            names = [name.removeprefix("NRF_FUN_") for name, number in fields.items()
                     if name.startswith("NRF_FUN_") and name not in {"NRF_FUN_POS", "NRF_FUN_MSK"}
                     and number == function]
            signal = names[0] if len(names) == 1 else f"Function {function}"
            return signal, f"P{pin // 32}.{pin % 32:02d}"
        if compatible == "espressif,esp32-pinctrl":
            if value > 0xffffff:  # Other encodings include flags outside the GPIO-matrix cell.
                return None
            pin = get("ESP32_PIN_NUM_SHIFT", "ESP32_PIN_NUM_MASK")
            incoming = get("ESP32_PIN_SIGI_SHIFT", "ESP32_PIN_SIGI_MASK")
            outgoing = get("ESP32_PIN_SIGO_SHIFT", "ESP32_PIN_SIGO_MASK")
            def signal_name(direction, index):
                names = [name[4:] for name, number in fields.items()
                         if name.startswith("ESP_") and name.endswith("_" + direction)
                         and number == index]
                return names[0] if len(names) == 1 else f"{direction}{index}"

            signals = [signal_name("IN", incoming)] if incoming != fields["ESP32_PIN_SIGI_MASK"] else []
            if outgoing != fields["ESP32_PIN_SIGO_MASK"]:
                signals.append(signal_name("OUT", outgoing))
            return "/".join(signals) or "GPIO matrix", f"GPIO{pin}"
    except (KeyError, ValueError):
        return None
    return None


def vendor_bus_pins(headers, paths, children, source, target):
    parent = paths.get(target.path.rsplit("/", 1)[0])
    compatible_prop = parent.props.get("compatible") if parent else None
    if compatible_prop is None:
        return
    compatible = next((entry for entry in compatible_prop.to_strings() if entry in headers), None)
    if compatible is None:
        return
    property_name = "psels" if compatible == "nordic,nrf-pinctrl" else "pinmux"
    for group in (target, *children.get(target.path, ())):
        prop = group.props.get(property_name)
        if prop is None or prop.type.name not in {"NUM", "NUMS"}:
            continue
        for value in prop.to_nums()[:64]:
            result = vendor_pin_value(compatible, headers[compatible], value)
            if result:
                signal, pad = result
                if compatible == "st,stm32-pinctrl":
                    designations = {label[len(owner) + 1:-(len(pad) + 1)].upper()
                                    for owner in source.labels or [] for label in group.labels or []
                                    if label.startswith(owner + "_") and label.endswith("_" + pad.lower())}
                    if len(designations) == 1 and "" not in designations:
                        signal = f"{next(iter(designations))} ({signal})"
                yield signal, pad, value


def reference_cells(prop_name, index, cells):
    """Present only named specifier cells resolved by the selected edtlib.

    io-channels is not necessarily an ADC. A channel or PWM period is never
    a GPIO number, and does not establish an association with a mux pad.
    """
    kind = "phandle-array"
    if prop_name == "gpios" or prop_name.endswith("-gpios"):
        kind, names = "gpio", ("pin",)
    elif prop_name == "io-channels" or prop_name.endswith("-io-channels"):
        kind, names = "io-channel", ("input", "channel")
    elif prop_name == "pwms" or prop_name.endswith("-pwms"):
        kind, names = "pwm", ("channel", "period", "flags")
    elif prop_name.startswith("clocks"):
        kind, names = "clock", ()
    elif prop_name.startswith("interrupt"):
        kind, names = "interrupt", ()
    elif prop_name.startswith("resets"):
        kind, names = "reset", ()
    else:
        names = ()
    detail = "[" + str(index) + "]"
    if isinstance(cells, dict):
        for name in names:
            value = cells.get(name)
            if type(value) is int and 0 <= value <= 0xffffffff:
                detail += " " + name + "=" + str(value)
    return kind, detail


def board_matches(config, expected):
    """Compare the full requested board to Zephyr's pre-split cache identity."""
    if not expected or expected != expected.strip():
        return False
    entries = {}
    for key in ("CACHED_BOARD", "BOARD_TARGET", "BOARD"):
        values = [value.strip() for value in re.findall(
            rf"(?m)^{key}:[A-Z_]+=(.*)$", config)]
        if len(values) > 1 or (values and not values[0]):
            return False
        if values:
            entries[key] = values[0]
    # Zephyr 4.4 records CACHED_BOARD before boards.cmake separates
    # revision and qualifiers. BOARD_TARGET is a compatibility fallback.
    # Do not accept a base-name fallback for an explicitly qualified board.
    full = entries.get("CACHED_BOARD") or entries.get("BOARD_TARGET")
    if full is not None:
        return full == expected
    return entries.get("BOARD") == expected


def observed(zephyr: Path, application: Path, build: Path, expected_board=None):
    zephyr = zephyr.resolve(strict=True)
    application = application.resolve(strict=True)
    if not zephyr.is_dir() or not application.is_dir():
        raise ValueError("selected-path-not-directory")
    cache = build / "CMakeCache.txt"
    generated = build / "zephyr/zephyr.dts"
    if not cache.is_file() or not generated.is_file():
        return ("no-build", "", "Select and build the exact board/profile first.", [], [])
    config = checked(cache, MAX_CACHE_BYTES).decode("utf-8")
    # CMakeCache.txt uses CRLF on Windows; trim the captured record terminator.
    # Retain interior spaces in paths such as "C:/Sandbox/Zephyr Setup".
    roots = [value.strip() for value in
             re.findall(r"(?m)^ZEPHYR_BASE:[A-Z_]+=(.*)$", config)]
    if len(roots) != 1 or Path(roots[0]).resolve(strict=False) != zephyr:
        return ("mismatch", "", "Selected build and Zephyr checkout disagree.", [], [])
    # A verified Zephyr checkout is insufficient when this build belongs to
    # another application. Compare source-dir cache evidence when supplied.
    for key in ("APPLICATION_SOURCE_DIR", "CMAKE_HOME_DIRECTORY"):
        values = [value.strip() for value in re.findall(
            rf"(?m)^{key}:[A-Z_]+=(.*)$", config)]
        if len(values) > 1 or (values and
                Path(values[0]).resolve(strict=False) != application):
            return ("mismatch", "",
                    "Selected build and application source disagree.", [], [])
    if expected_board is not None and not board_matches(config, expected_board):
        return ("mismatch", "",
                "Selected board/SoC/core target does not match verifiable build cache identity. Rebuild the selected target.", [], [])
    raw = checked(generated, MAX_DTS_BYTES)
    digest = hashlib.sha256(raw).hexdigest()
    if any(token in raw for token in FORBIDDEN):
        return ("unavailable", digest, "Generated DTS requests external parser reads.", [], [])
    library = zephyr / "scripts/dts/python-devicetree/src"
    if not (library / "devicetree/dtlib.py").is_file():
        return ("unavailable", digest, "Selected Zephyr checkout lacks dtlib.", [], [])
    sys.path.insert(0, str(library))
    try:
        from devicetree import dtlib
        dt = dtlib.DT(str(generated))
        nodes = list(dt.node_iter())
        if len(nodes) > MAX_NODES:
            return ("unavailable", digest, "Devicetree exceeds graph node safety limit.", [], [])
        by_path = {node.path: node for node in nodes}
        children = {}
        for path, node in by_path.items():
            children.setdefault(path.rsplit("/", 1)[0], []).append(node)
        ti_pins = ti_bus_pins(zephyr, by_path["/"])
        headers = {compatible: pin_definitions(zephyr, header)
                   for compatible, header in PIN_HEADERS.items()}
        root_compatible = by_path["/"].props.get("compatible")
        if root_compatible is not None:
            for compatible in root_compatible.to_strings():
                chip = re.fullmatch(r"espressif,(esp32(?:[csh]\d)?)", compatible)
                if chip:
                    headers["espressif,esp32-pinctrl"].update(
                        pin_definitions(zephyr, chip[1] + "-gpio-sigmap.h"))
                    break
        nrows = []
        edges = []
        seen_edges = set()

        def link(source, target, kind, prop, detail=""):
            if source not in by_path or target not in by_path:
                return
            if len(prop) > 120 or len(detail) > 180:
                return
            key = (source, target, kind, prop, detail)
            if key not in seen_edges:
                if len(edges) >= MAX_EDGES:
                    raise ValueError("graph-edge-limit")
                seen_edges.add(key)
                edges.append(key)

        for node in nodes:
            props = node.props
            status = props["status"].to_string() if "status" in props else "okay (default)"
            compatible = (", ".join(props["compatible"].to_strings())
                          if "compatible" in props else "")
            nrows.append((node.path, node.name, status[:80], compatible[:240],
                          ",".join(node.labels or [])[:160], int(node.lineno)))
            # dtlib knows the target identity for single/multiple phandles.
            # Never guess a reference from numeric or mixed cells.
            for prop_name, prop in props.items():
                kind = prop.type.name
                try:
                    if kind == "PHANDLE":
                        link(node.path, prop.to_node().path, "phandle", prop_name)
                    elif kind == "PHANDLES":
                        for index, target in enumerate(prop.to_nodes()):
                            link(node.path, target.path, "phandle", prop_name, str(index))
                    elif kind == "PATH":
                        link(node.path, prop.to_path().path, "path", prop_name)
                except (ValueError, KeyError, AttributeError):
                    continue

        state = "hierarchy-only"
        reason = "Observed generated DTS hierarchy; binding-aware links unavailable."
        try:
            from devicetree import edtlib
            roots = [zephyr / "dts/bindings", application / "dts/bindings"]
            roots = [str(root) for root in roots if root.is_dir() and not root.is_symlink()]
            if not roots:
                raise ValueError("binding-roots-unavailable")
            edt = edtlib.EDT(str(generated), roots)
            for node in edt.nodes:
                if node.path not in by_path:
                    continue
                for prop_name, prop in node.props.items():
                    if prop.type != "phandle-array":
                        continue
                    for index, spec in enumerate(prop.val or []):
                        if spec is None or getattr(spec, "controller", None) is None:
                            continue
                        controller = spec.controller.path
                        kind, detail = reference_cells(prop_name, index, getattr(spec, "data", None))
                        link(node.path, controller, kind, prop_name, detail)
                for state_record in getattr(node, "pinctrls", []):
                    for config in getattr(state_record, "conf_nodes", []):
                        state_name = str(state_record.name)[:80]
                        link(node.path, config.path, "pinctrl", "pinctrl",
                             state_name)
                        if config.path not in by_path:
                            continue
                        pin = ti_bus_pin(ti_pins, by_path, by_path[node.path], by_path[config.path])
                        if pin:
                            signal, pad, mux = pin
                            link(node.path, config.path, "pinmux", signal,
                                 f"{state_name} {pad} {mux:#x}")
                        for signal, pad, mux in vendor_bus_pins(
                                headers, by_path, children, by_path[node.path], by_path[config.path]):
                            link(node.path, config.path, "pinmux", signal, f"{state_name} {pad} {mux:#x}")
            state = "built"
            reason = "Generated DTS + selected-checkout edtlib references; runtime unverified."
        except (ImportError, ValueError, OSError, AttributeError, TypeError):
            # A binding failure must never invent a GPIO/clock relationship.
            reason = "Generated hierarchy available; edtlib references unavailable."
        if hashlib.sha256(checked(generated, MAX_DTS_BYTES)).hexdigest() != digest:
            return ("unavailable", "", "Generated DTS changed during inspection.", [], [])
        if len(nrows) + len(edges) > 8500:
            raise ValueError("graph-record-limit")
        return (state, digest, reason, nrows, edges)
    finally:
        sys.path.pop(0)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zephyr", required=True, type=Path)
    parser.add_argument("--application", required=True, type=Path)
    parser.add_argument("--build", required=True, type=Path)
    parser.add_argument("--board", help="Exact selected board including revision and qualifiers")
    args = parser.parse_args()
    print("ZEW-DTSGRAPH-1")
    try:
        state, digest, reason, nodes, edges = observed(
            args.zephyr, args.application, args.build, args.board)
        # No partial success report: format and size are bounded before emit.
        records = [("S", state, digest, reason)]
        records += [("N", *row) for row in nodes]
        records += [("E", *row) for row in edges]
        budget = sum(len("\t".join(map(str, row))) * 2 + 16 for row in records)
        if budget > MAX_RECORD_BYTES:
            records = [("S", "unavailable", "", "Graph protocol safety limit exceeded.")]
        for row in records:
            emit(*row)
    except Exception as error:
        # Never publish exception messages or source content from user files.
        emit("S", "unavailable", "", "Selected Devicetree could not be interpreted: "
             + type(error).__name__)


if __name__ == "__main__":
    main()
