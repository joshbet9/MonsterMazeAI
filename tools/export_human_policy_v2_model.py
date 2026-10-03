#!/usr/bin/env python3
"""Export a trained human-policy-v2 JSON artifact to a compact Java-readable binary."""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

MAGIC = 0x48505632  # "HPV2"
VERSION = 1


def floats(values):
    return [float(value) for value in values]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    model = json.loads(Path(args.input).read_text(encoding="utf-8"))
    if model.get("source_schema_version") != 2:
        raise SystemExit(f"Expected source_schema_version=2, got {model.get('source_schema_version')}")
    architecture = model.get("architecture")
    if architecture != [96, 64, 64, 6]:
        raise SystemExit(f"Expected architecture [96,64,64,6], got {architecture}")

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)

    arrays = [
        ("input_mean", 96),
        ("input_std", 96),
        ("w1", 96 * 64),
        ("b1", 64),
        ("w2", 64 * 64),
        ("b2", 64),
        ("w3", 64 * 6),
        ("b3", 6),
    ]

    with output.open("wb") as handle:
        handle.write(struct.pack(">IIiiii", MAGIC, VERSION, 96, 64, 64, 6))
        for name, expected in arrays:
            values = floats(model[name])
            if len(values) != expected:
                raise SystemExit(f"{name}: expected {expected} values, got {len(values)}")
            handle.write(struct.pack(f">{expected}d", *values))

    print("========== HUMAN POLICY V2 MODEL EXPORT ==========")
    print(f"input={args.input}")
    print(f"output={output}")
    print("architecture=[96,64,64,6]")
    print(f"bytes={output.stat().st_size}")
    print("===============================================")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
