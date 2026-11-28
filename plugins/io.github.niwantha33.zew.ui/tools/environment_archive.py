#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Local, private, same-path recovery of a self-contained managed setup.

No network, execution of archived code, source rewriting or overwrite.
An archive is byte evidence, not authentication or a clean-machine build.
"""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import stat
import shutil
import struct
import sys
import tempfile
import zipfile

SCHEMA = 1
MAX_FILES = 250_000
MAX_TOTAL = 64 * 1024**3
MAX_META = 64 * 1024**2
MAX_CENTRAL = 128 * 1024**2
MAX_ARCHIVE = MAX_TOTAL + MAX_CENTRAL + MAX_META + 1024**3
BLOCK = 1024 * 1024
READY = ".zew-ready"
PROPERTIES = "environment.properties"
CORE = ("python", "workspace", "tools.sdk", "tools.cmake", "tools.ninja", "tools.git")
HEX = re.compile(r"^[0-9a-f]{64}$")
RESERVED = re.compile(r"^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\.|$)", re.I)


class ArchiveError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ArchiveError(message)


def safe_name(name):
    require(isinstance(name, str) and 0 < len(name) <= 2048, "Invalid archive path")
    for part in name.split("/"):
        require(0 < len(part) <= 255 and part not in (".", "..")
                and not part.endswith((" ", ".")) and not RESERVED.match(part)
                and not any(c in '\\:*?"<>|' or ord(c) < 32 or ord(c) == 127 for c in part),
                "Unsafe archive path: " + name[:100])
    return name


def regular(path, directory=False):
    info = path.lstat()
    require(not stat.S_ISLNK(info.st_mode)
            and not (getattr(info, "st_file_attributes", 0) & 0x400)
            and (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)),
            "Unsupported link, junction or special file: " + str(path))
    return info


def properties(root):
    """Only accept the simple escaped format emitted by managed setup.py."""
    result = {}
    path = root / PROPERTIES
    require(regular(path).st_size <= 1024**2, "Oversized setup properties")
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        require(sep and re.fullmatch(r"[A-Za-z0-9._-]+", key) and key not in result,
                "Unsupported or duplicate setup property")
        require(re.fullmatch(r"(?:[^\\]|\\[\\:=nr])*", value) is not None,
                "Unsupported setup property escaping")
        result[key] = re.sub(r"\\([\\:=nr])", lambda m: {"n": "\n", "r": "\r"}.get(m[1], m[1]), value)
    return result


def require_isolated(root, ready=True):
    regular(root, True)
    regular(root / ".zew-managed")
    if ready:
        regular(root / READY)
    values = properties(root)
    for key in CORE:
        require(key in values and Path(values[key]).is_absolute(), "Missing absolute setup path: " + key)
        path = Path(values[key])
        actual = path.resolve(strict=True)
        try:
            actual.relative_to(root)
        except ValueError:
            raise ArchiveError("External dependency " + key + "; isolate it before export") from None
        regular(path, key in ("workspace", "tools.sdk"))
    regular(Path(values["workspace"]) / ".west/config")
    regular(Path(values["workspace"]) / "zephyr/VERSION")
    regular(Path(values["tools.sdk"]) / "sdk_version")


def inventory(root):
    files, directories = {}, []
    folded = set()
    total = 0
    def remember(name):
        safe_name(name)
        require(name.casefold() not in folded, "Case-colliding archive path: " + name)
        folded.add(name.casefold())
        require(len(folded) <= MAX_FILES, "Too many archive entries")
    def fail(error):
        raise error
    for parent, dirs, names in os.walk(root, followlinks=False, onerror=fail):
        base = Path(parent)
        regular(base, True)
        for name in sorted(dirs):
            path = base / name
            regular(path, True)
            relative = path.relative_to(root).as_posix()
            remember(relative)
            directories.append(relative)
        for name in sorted(names):
            path = base / name
            info = regular(path)
            relative = path.relative_to(root).as_posix()
            remember(relative)
            total += info.st_size
            require(total <= MAX_TOTAL, "Archive exceeds total byte limit")
            files[relative] = (info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_ino, info.st_mode)
    return files, sorted(directories)


def digest_stream(source, target=None, expected=None, limit=MAX_TOTAL):
    digest = hashlib.sha256()
    size = 0
    for block in iter(lambda: source.read(BLOCK), b""):
        size += len(block)
        require(size <= (limit if expected is None else expected), "File exceeds declared size")
        digest.update(block)
        if target is not None:
            target.write(block)
    require(expected is None or size == expected, "File size changed or archive truncated")
    return digest.hexdigest(), size


def file_digest(path):
    before = regular(path)
    require(before.st_size <= MAX_ARCHIVE, "File exceeds digest limit")
    with path.open("rb") as source:
        digest, _ = digest_stream(source, expected=before.st_size, limit=MAX_ARCHIVE)
    after = regular(path)
    require((before.st_size, before.st_mtime_ns, before.st_ctime_ns, before.st_ino)
            == (after.st_size, after.st_mtime_ns, after.st_ctime_ns, after.st_ino),
            "File changed while hashing: " + str(path))
    return digest


def export_archive(root, output):
    root = root.resolve(strict=True)
    output = output.resolve(strict=False)
    require(root.parent != root and not output.is_relative_to(root), "Archive must be outside setup")
    require_isolated(root)
    files, directories = inventory(root)
    records = {}
    # Exclusive creation protects an existing archive. An interrupted export
    # lacks the complete manifest and is never accepted by inspect/restore.
    with output.open("xb") as raw:
        try:
            with zipfile.ZipFile(raw, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=3) as z:
                for name, before in sorted(files.items()):
                    path = root / name
                    regular(path)
                    with path.open("rb") as source, z.open("payload/" + name, "w", force_zip64=True) as target:
                        sha, size = digest_stream(source, target, before[0])
                    after = regular(path)
                    require(before == (after.st_size, after.st_mtime_ns, after.st_ctime_ns,
                                       after.st_ino, after.st_mode), "Setup changed during export: " + name)
                    records[name] = {"sha256": sha, "size": size, "executable": before[4] & 0o111}
                require(inventory(root) == (files, directories), "Setup changed during export")
                metadata = {"schema": SCHEMA, "root": str(root), "platform": sys.platform,
                            "machine": platform.machine(), "directories": directories, "files": records}
                data = json.dumps(metadata, sort_keys=True, separators=(",", ":")).encode("utf-8")
                require(len(data) <= MAX_META, "Archive manifest exceeds limit")
                z.writestr("manifest.json", data)
        except BaseException:
            raw.close()
            output.unlink(missing_ok=True)
            raise
    return {"status": "exported", "archive_sha256": file_digest(output), "files": len(records),
            "original_root": str(root), "qualification": "byte archive; build not tested"}


def archive_stamp(info):
    return info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_ino, info.st_dev


def open_identity(info):
    # On CPython 3.12+ Windows path stat still exposes creation time in
    # st_ctime, but fstat exposes metadata change time. Compare the shared
    # birthtime field instead at the path-to-descriptor boundary. Retain the
    # full descriptor stamp (including change time) for subsequent checks.
    time_ns = (getattr(info, "st_birthtime_ns", info.st_ctime_ns)
               if os.name == "nt" else info.st_ctime_ns)
    return info.st_size, info.st_mtime_ns, time_ns, info.st_ino, info.st_dev


def unchanged_archive(stream, stamp):
    require(archive_stamp(os.fstat(stream.fileno())) == stamp,
            "Archive changed during verification or restoration")


@contextmanager
def bounded_zip(path, expected=None):
    """Bind the retained digest, central-directory bounds and extraction to one open file."""
    before = regular(path)
    with path.open("rb") as stream:
        opened = os.fstat(stream.fileno())
        require(stat.S_ISREG(opened.st_mode)
                and not (getattr(opened, "st_file_attributes", 0) & 0x400)
                and open_identity(opened) == open_identity(before),
                "Archive changed before opening")
        stamp = archive_stamp(opened)
        size = stamp[0]
        require(22 <= size <= MAX_ARCHIVE, "Archive size outside limits")
        if expected is not None:
            require(isinstance(expected, str) and HEX.fullmatch(expected), "Invalid retained archive SHA-256")
            actual, _ = digest_stream(stream, expected=size, limit=MAX_ARCHIVE)
            require(actual == expected, "Archive digest mismatch")
            unchanged_archive(stream, stamp)
        start = max(0, size - 65557)
        stream.seek(start)
        tail = stream.read()
        pos = tail.rfind(b"PK\x05\x06")
        require(pos >= 0 and len(tail) - pos >= 22, "Missing ZIP end record")
        _, disk, cd_disk, count_disk, count, cd_size, cd_offset, comment = struct.unpack_from("<4s4H2LH", tail, pos)
        require(pos + 22 + comment == len(tail) and not disk and not cd_disk, "Unsupported split or trailing ZIP")
        end = start + pos
        if count == 65535 or cd_size == 0xffffffff or cd_offset == 0xffffffff:
            require(end >= 20, "Missing ZIP64 locator")
            stream.seek(end - 20)
            signature, disk, offset, disks = struct.unpack("<4sLQL", stream.read(20))
            require(signature == b"PK\x06\x07" and disk == 0 and disks == 1
                    and offset + 56 <= end - 20, "Invalid ZIP64 locator")
            stream.seek(offset)
            signature, length, _, _, disk, cd_disk, count_disk, count, cd_size, cd_offset = struct.unpack(
                "<4sQ2H2L4Q", stream.read(56))
            require(signature == b"PK\x06\x06" and length == 44 and not disk and not cd_disk,
                    "Invalid ZIP64 end record")
            end = offset
        require(count == count_disk and count <= MAX_FILES + 1 and cd_size <= MAX_CENTRAL
                and cd_offset + cd_size == end, "ZIP central directory exceeds limits or is invalid")
        with zipfile.ZipFile(stream, "r") as z:
            yield z, stream, stamp
            unchanged_archive(stream, stamp)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate manifest key")
        result[key] = value
    return result


def manifest(z):
    infos = z.infolist()
    require(len(infos) <= MAX_FILES + 1, "Too many ZIP entries")
    names = {i.filename: i for i in infos}
    require(len(names) == len(infos) and "manifest.json" in names, "Missing or duplicate ZIP entries")
    for info in infos:
        require(not info.is_dir() and not info.flag_bits & 1
                and stat.S_IFMT(info.external_attr >> 16) in (0, stat.S_IFREG)
                and info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                "Unsupported ZIP member type")
    require(names["manifest.json"].file_size <= MAX_META, "Oversized archive manifest")
    with z.open("manifest.json") as source:
        data = source.read(MAX_META + 1)
    require(len(data) <= MAX_META, "Oversized archive manifest")
    m = json.loads(data, object_pairs_hook=unique_pairs)
    require(isinstance(m, dict) and set(m) == {"schema", "root", "platform", "machine", "directories", "files"}
            and type(m["schema"]) is int and m["schema"] == SCHEMA, "Unsupported manifest schema")
    require(all(isinstance(m[k], str) and 0 < len(m[k]) <= 4096 for k in ("root", "platform", "machine")),
            "Invalid archive origin")
    dirs, files = m["directories"], m["files"]
    require(isinstance(dirs, list) and isinstance(files, dict)
            and len(dirs) + len(files) <= MAX_FILES, "Invalid archive inventory")
    paths = [safe_name(n) for n in dirs] + [safe_name(n) for n in files]
    require(len({n.casefold() for n in paths}) == len(paths), "Duplicate or case-colliding paths")
    directories = set(dirs)
    for name in paths:
        parent = name.rpartition("/")[0]
        require(not parent or parent in directories, "Missing parent directory: " + name)
    require({READY, PROPERTIES, ".zew-managed"} <= files.keys(), "Missing managed setup markers")
    require(set(names) == {"manifest.json"} | {"payload/" + n for n in files}, "Unexpected or missing payload")
    total = 0
    for name, row in files.items():
        require(isinstance(row, dict) and set(row) == {"sha256", "size", "executable"}
                and isinstance(row["sha256"], str) and HEX.fullmatch(row["sha256"])
                and type(row["size"]) is int and 0 <= row["size"] <= MAX_TOTAL
                and type(row["executable"]) is int and row["executable"] & ~0o111 == 0,
                "Invalid payload record")
        require(names["payload/" + name].file_size == row["size"], "Payload size mismatch: " + name)
        total += row["size"]
        require(total <= MAX_TOTAL, "Archive exceeds extraction limit")
    return m


def check_payload(z, name, row, target=None):
    with z.open("payload/" + name) as source:
        sha, _ = digest_stream(source, target, row["size"])
    require(sha == row["sha256"], "SHA-256 mismatch: " + name)


def archive_action(action, archive, destination=None, expected=None):
    if action == "restore":
        require(expected is not None, "Restore requires the separately retained archive SHA-256")
    with bounded_zip(archive, expected) as (z, stream, stamp):
        m = manifest(z)
        if action != "inspect":
            for name, row in sorted(m["files"].items()):
                check_payload(z, name, row)
        if action == "restore":
            require(destination is not None, "Select original empty setup location")
            destination = destination.resolve(strict=False)
            require(m["platform"] == sys.platform and m["machine"] == platform.machine(),
                    "Original host OS and architecture required")
            require(destination.is_absolute() and destination.parent != destination
                    and str(destination) == m["root"], "Same original absolute setup path required")
            require(not destination.exists() and not destination.is_symlink(),
                    "Restore target already exists; never overwrite")
            regular(destination.parent, True)
            require(shutil.disk_usage(destination.parent).free >= sum(r["size"] for r in m["files"].values()),
                    "Insufficient free disk space for archive restore")
            unchanged_archive(stream, stamp)
            # Reserve destination exclusively. On any failure it remains an
            # incomplete, non-ready directory for manual inspection; never
            # recursively delete files the user may have added.
            destination.mkdir(mode=0o700)
            for name in sorted(m["directories"], key=lambda n: (n.count("/"), n)):
                (destination / name).mkdir()
            for name, row in sorted(m["files"].items()):
                if name == READY:
                    continue
                path = destination / name
                with path.open("xb") as target:
                    check_payload(z, name, row, target)
                    target.flush()
                    os.fsync(target.fileno())
                path.chmod(0o600 | row["executable"])
            require_isolated(destination, ready=False)
            # Publish the historic ready marker only after all other files
            # are verified, and only after its own bytes are verified.
            row = m["files"][READY]
            require(row["size"] <= 1024**2, "Invalid ready marker")
            with z.open("payload/" + READY) as source:
                ready = source.read(row["size"] + 1)
            require(len(ready) == row["size"] and hashlib.sha256(ready).hexdigest() == row["sha256"],
                    "Ready marker digest mismatch")
            marker = None
            try:
                with tempfile.NamedTemporaryFile(dir=destination, prefix=".zew-ready-",
                                                 delete=False) as target:
                    marker = Path(target.name)
                    target.write(ready)
                    target.flush()
                    os.fsync(target.fileno())
                unchanged_archive(stream, stamp)
                require(not (destination / READY).exists(), "Unexpected ready marker")
                os.replace(marker, destination / READY)
            finally:
                if marker is not None:
                    marker.unlink(missing_ok=True)
    return {"status": {"inspect": "metadata-only", "verify": "payload-verified",
                       "restore": "restored-bytes"}[action],
            "original_root": m["root"], "platform": m["platform"], "machine": m["machine"],
            "files": len(m["files"]), "bytes": sum(r["size"] for r in m["files"].values()),
            "retained_digest_matched": expected is not None,
            "qualification": "not authenticated; attach and run pristine firmware qualification"}


def compare_firmware(original, rebuilt):
    first, second = file_digest(original), file_digest(rebuilt)
    return {"status": "match" if first == second else "different",
            "original_sha256": first, "rebuilt_sha256": second,
            "qualification": "file byte comparison only; not flashed identity"}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    commands = p.add_subparsers(dest="action", required=True)
    export = commands.add_parser("export")
    export.add_argument("--root", type=Path, required=True)
    export.add_argument("--archive", type=Path, required=True)
    export.add_argument("--private-copy", action="store_true",
                        help="I have reviewed secrets and rights for a private archive of the entire setup")
    for name in ("inspect", "verify", "restore"):
        sub = commands.add_parser(name)
        sub.add_argument("--archive", type=Path, required=True)
        sub.add_argument("--expected-sha256")
        if name == "restore":
            sub.add_argument("--destination", type=Path, required=True)
            sub.add_argument("--trusted-archive", action="store_true")
    compare = commands.add_parser("compare-firmware")
    compare.add_argument("--original", type=Path, required=True)
    compare.add_argument("--rebuilt", type=Path, required=True)
    args = p.parse_args(argv)
    if args.action == "export":
        require(args.private_copy, "Review local secrets and redistribution rights; explicitly select --private-copy")
        return export_archive(args.root, args.archive)
    if args.action == "compare-firmware":
        return compare_firmware(args.original, args.rebuilt)
    if args.action == "restore":
        require(args.trusted_archive, "Restore requires an explicitly trusted private archive")
    return archive_action(args.action, args.archive, getattr(args, "destination", None), args.expected_sha256)


if __name__ == "__main__":
    try:
        result = main()
        print(json.dumps(result, sort_keys=True))
        sys.exit(1 if result["status"] == "different" else 0)
    except (ArchiveError, OSError, ValueError, TypeError, KeyError, RuntimeError, RecursionError,
            struct.error, zipfile.BadZipFile) as error:
        print(json.dumps({"status": "error", "message": str(error)}, sort_keys=True))
        sys.exit(2)
