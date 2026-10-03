#!/usr/bin/env python3
"""Export a trained human-policy-v2 JSON artifact to a compact Java-readable binary."""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path
from typing import Any

MAGIC = 0x48505632  # "HPV2"
VERSION = 1


def flatten(values: Any) -> list[float]:
    """Flatten JSON numeric arrays while preserving row-major order."""
    if isinstance(values, list):
        out: list[float] = []
        for value in values:
            out.extend(flatten(value))
        return out
    if isinstance(values, (int, float)) and not isinstance(values, bool):
        return [float(values)]
    raise SystemExit(f"Model array contains non-numeric value: {type(values).__name__}")


def validate_matrix(values: Any, rows: int, cols: int, name: str) -> None:
    if not isinstance(values, list) or len(values) != rows:
        raise SystemExit(f"{name}: expected {rows} rows, got {len(values) if isinstance(values, list) else 'non-list'}")
    for row_index, row in enumerate(values):
        if not isinstance(row, list) or len(row) != cols:
            raise SystemExit(
                f"{name}[{row_index}]: expected {cols} columns, "
                f"got {len(row) if isinstance(row, list) else 'non-list'}"
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    model = json.loads(Path(args.input).read_text(encoding="utf-8"))
    if model.get("source_schema_version") != 2:
        raise SystemExit(
            f"Expected source_schema_version=2, got {model.get('source_schema_version')}"
        )

    architecture = model.get("architecture")
    if architecture != [96, 64, 64, 6]:
        raise SystemExit(f"Expected architecture [96,64,64,6], got {architecture}")

    validate_matrix(model.get("w1"), 96, 64, "w1")
    validate_matrix(model.get("w2"), 64, 64, "w2")
    validate_matrix(model.get("w3"), 64, 6, "w3")

    expected_lengths = {
        "input_mean": 96,
        "input_std": 96,
        "b1": 64,
        "b2": 64,
        "b3": 6,
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)

    arrays = [
        ("input_mean", expected_lengths["input_mean"]),
        ("input_std", expected_lengths["input_std"]),
        ("w1", 96 * 64),
        ("b1", expected_lengths["b1"]),
        ("w2", 64 * 64),
        ("b2", expected_lengths["b2"]),
        ("w3", 64 * 6),
        ("b3", expected_lengths["b3"]),
    ]

    with output.open("wb") as handle:
        handle.write(struct.pack(">IIiiii", MAGIC, VERSION, 96, 64, 64, 6))
        for name, expected in arrays:
            values = flatten(model.get(name))
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
