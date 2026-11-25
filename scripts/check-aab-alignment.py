"""Check 16 KiB PT_LOAD alignment for every 64-bit library in a signed AAB."""

import re
import struct
import sys
import zipfile


def check_library(name, data):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{name}: expected little-endian 64-bit ELF")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    load_segments = 0
    for index in range(count):
        kind, _, file_offset, address, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset + index * entry_size
        )
        if kind == 1:
            load_segments += 1
            if alignment < 0x4000 or (file_offset - address) % 0x4000 != 0:
                raise ValueError(f"{name}: incompatible page alignment {alignment}")
    if not load_segments:
        raise ValueError(f"{name}: no PT_LOAD segments")
    print(f"{name}: {load_segments} aligned load segments")


with zipfile.ZipFile(sys.argv[1]) as bundle:
    paths = [path for path in bundle.namelist() if re.fullmatch(
        r"base/lib/(arm64-v8a|x86_64)/[^/]+\.so", path
    )]
    for abi in ("arm64-v8a", "x86_64"):
        if f"base/lib/{abi}/libgojni.so" not in paths:
            raise ValueError(f"Missing Mihomo core for {abi}")
    for path in paths:
        check_library(path, bundle.read(path))
