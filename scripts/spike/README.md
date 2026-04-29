# BirdNET v2.4 model spike

Standalone scripts to fetch the BirdNET v2.4 TFLite model + labels and read its
tensor shapes. Output drives `docs/private/MODEL_SPIKE.md` and the constants
that downstream Tasks 4, 6, and 7 rely on.

## Setup

```bash
python3 -m venv .venv
.venv/bin/pip install -r scripts/spike/requirements.txt
```

`tensorflow` / `tflite-runtime` are NOT used: they have no wheel for Python
3.14 (Apple Silicon). The lightweight `tflite` flatbuffer parser is enough to
read tensor shapes; full inference comparison is deferred — see
`docs/private/MODEL_SPIKE.md` § "Deferred work".

## Run

Download model + labels (sourced from `woheller69/whoBIRD-TFlite` and
`woheller69/whoBIRD` since the upstream `birdnet-team/BirdNET-Analyzer` repo
distributes weights only via installer packages — see MODEL_SPIKE.md):

```bash
mkdir -p models
curl -L -o models/birdnet_v2_4.tflite \
  "https://github.com/woheller69/whoBIRD-TFlite/raw/master/BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite"
curl -L -o models/labels.txt \
  "https://raw.githubusercontent.com/woheller69/whoBIRD/master/app/src/main/assets/labels_en_uk.txt"
shasum -a 256 models/birdnet_v2_4.tflite models/labels.txt | tee models/SHA256SUMS
```

Inspect tensors:

```bash
.venv/bin/python scripts/spike/inspect_tflite.py models/birdnet_v2_4.tflite
```
