"""Inspect a TFLite model — input/output tensor shapes and op count.

Uses the `tflite` flatbuffer parser (no runtime needed). Works on Python 3.14.
"""
from __future__ import annotations

import sys
from pathlib import Path

import tflite as tfl


def main(path: Path) -> None:
    buf = path.read_bytes()
    model = tfl.Model.GetRootAsModel(buf, 0)
    print(f"file: {path} ({len(buf):,} bytes)")
    print(f"version: {model.Version()}")
    print(f"description: {model.Description()}")
    print(f"subgraphs: {model.SubgraphsLength()}")
    sub = model.Subgraphs(0)
    print(f"  tensors: {sub.TensorsLength()}")
    print(f"  operators: {sub.OperatorsLength()}")

    print("\ninputs:")
    for i in range(sub.InputsLength()):
        t = sub.Tensors(sub.Inputs(i))
        shape = [t.Shape(j) for j in range(t.ShapeLength())]
        print(f"  [{i}] name={t.Name().decode()!r} shape={shape} type={t.Type()}")

    print("\noutputs:")
    for i in range(sub.OutputsLength()):
        t = sub.Tensors(sub.Outputs(i))
        shape = [t.Shape(j) for j in range(t.ShapeLength())]
        print(f"  [{i}] name={t.Name().decode()!r} shape={shape} type={t.Type()}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
