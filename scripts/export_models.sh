#!/usr/bin/env bash
# Export Llama 3.2 1B Instruct to ExecuTorch .pte (XNNPACK, INT4) two ways, for the report:
#   (a) SpinQuant INT4 checkpoint  -> llama_xnnpack_spinquant.yaml   (better accuracy)
#   (b) BF16 checkpoint, quantized here with 8da4w (group 128, embeddings 4,32) -> llama_xnnpack.yaml
# Run inside WSL2 Ubuntu. Both repos are gated: accept the Llama 3.2 licence on Hugging Face
# and run `hf auth login` (or `huggingface-cli login`) first.
#
# WSL memory: exporting a 1B model peaks around 8-10 GB. Put this in %UserProfile%\.wslconfig
# and run `wsl --shutdown` before exporting:
#   [wsl2]
#   memory=12GB
#   swap=8GB
set -euo pipefail

ET_VERSION="1.5.1"          # must match org.pytorch:executorch-android:1.5.1 in the app
CONTEXT=1024                # app's BuildConfig.MODEL_CONTEXT; the export default is only 128!
WORK="${WORK:-$HOME/tutor-export}"
OUT="$WORK/out"
METADATA='{"get_bos_id":128000,"get_eos_ids":[128009,128001]}'

mkdir -p "$WORK" "$OUT"
cd "$WORK"

TORCH_VERSION="2.14.0"      # torch_pin.py at executorch v1.5.1; executorch does not declare torch as a dependency

# uv gives us a Python 3.12 venv without sudo (Ubuntu 26.04 ships 3.14 without python3-venv).
export PATH="$HOME/.local/bin:$PATH"
if ! command -v uv >/dev/null; then
  mkdir -p "$HOME/.local/bin" "$WORK/dl"
  curl -fsSL -o "$WORK/dl/uv.tar.gz" https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-unknown-linux-gnu.tar.gz
  tar -xzf "$WORK/dl/uv.tar.gz" -C "$WORK/dl"
  cp "$WORK/dl/uv-x86_64-unknown-linux-gnu/uv" "$HOME/.local/bin/"
fi
[ -d .venv ] || uv venv -p 3.12 .venv
# shellcheck disable=SC1091
source .venv/bin/activate
# CPU-only torch: the default Linux wheel pulls ~3 GB of CUDA we don't need for export.
uv pip install --index-url https://download.pytorch.org/whl/cpu "torch==${TORCH_VERSION}"
uv pip install "executorch==${ET_VERSION}" "huggingface_hub[cli]"

HF=hf; command -v hf >/dev/null || HF=huggingface-cli

echo "== Downloading checkpoints"
$HF download meta-llama/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8 --local-dir spinquant
$HF download meta-llama/Llama-3.2-1B-Instruct --include "original/*" --local-dir bf16

# executorch is a namespace package (__file__ is None), so search its __path__ entries.
CFG="${CFG:-$(python - <<'PY'
import os, executorch
for root in executorch.__path__:
    for dirpath, _, files in os.walk(root):
        if "llama_xnnpack_spinquant.yaml" in files:
            print(dirpath); raise SystemExit
PY
)}"
if [ -z "$CFG" ]; then
  echo "Could not find llama_xnnpack_spinquant.yaml inside the executorch wheel." >&2
  echo "Clone https://github.com/pytorch/executorch at tag v${ET_VERSION} and set CFG=<repo>/examples/models/llama/config" >&2
  exit 1
fi
echo "== Using configs in $CFG"

echo "== (a) SpinQuant INT4 -> tutor_1b_spinquant.pte"
python -m executorch.extension.llm.export.export_llm \
  --config "$CFG/llama_xnnpack_spinquant.yaml" \
  ++base.checkpoint="$WORK/spinquant/consolidated.00.pth" \
  ++base.params="$WORK/spinquant/params.json" \
  "++base.metadata='${METADATA}'" \
  ++export.max_seq_length=$CONTEXT \
  ++export.max_context_length=$CONTEXT \
  ++export.output_dir="$OUT" \
  ++export.output_name=tutor_1b_spinquant.pte

echo "== (b) BF16 + local 8da4w quantization -> tutor_1b_8da4w.pte"
python -m executorch.extension.llm.export.export_llm \
  --config "$CFG/llama_xnnpack.yaml" \
  ++base.checkpoint="$WORK/bf16/original/consolidated.00.pth" \
  ++base.params="$WORK/bf16/original/params.json" \
  "++base.metadata='${METADATA}'" \
  ++model.use_kv_cache=True \
  ++model.use_sdpa_with_kv_cache=True \
  ++model.dtype_override=fp32 \
  ++quantization.qmode=8da4w \
  ++quantization.group_size=128 \
  "++quantization.embedding_quantize='4,32'" \
  ++backend.xnnpack.enabled=True \
  ++backend.xnnpack.extended_ops=True \
  ++export.max_seq_length=$CONTEXT \
  ++export.max_context_length=$CONTEXT \
  ++export.output_dir="$OUT" \
  ++export.output_name=tutor_1b_8da4w.pte

cp "$WORK/spinquant/tokenizer.model" "$OUT/tokenizer.model"

# Some export_llm versions ignore output_dir and write to the CWD.
for f in tutor_1b_spinquant.pte tutor_1b_8da4w.pte; do
  [ -f "$OUT/$f" ] || { [ -f "$WORK/$f" ] && mv "$WORK/$f" "$OUT/"; }
done

echo "== Done"
ls -lh "$OUT"
echo "Copy to Windows with:  cp $OUT/* /mnt/d/Projects/OfflineTutor/model-export/out/"
