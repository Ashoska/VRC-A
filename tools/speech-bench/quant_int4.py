"""Parakeet encoder fp32 -> 4-bit weight-only linear layers (MatMulNBits) + int8 convs
(ConvInteger, like the shipped int8 build). Needs `pip install onnx onnx_ir` in the venv.

  quant_int4.py <fp32 encoder.onnx (+ its .weights beside it)> <out.onnx> <block size, e.g. 32>

Used for the 2026-10-11 memory test (results-2026-10-11-memory.txt): same accuracy as int8
on 200 English clips, but x86 ONNX Runtime re-packs 4-bit weights at ~0.75 B each, so it only
saves memory on ARM (KleidiAI packing, ~0.56 B), which needs an on-headset measurement."""
import sys, os, tempfile, onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
from onnxruntime.quantization.matmul_nbits_quantizer import MatMulNBitsQuantizer, DefaultWeightOnlyQuantConfig

src, out, block = sys.argv[1], sys.argv[2], int(sys.argv[3])
model = onnx.load(src)  # external weights (encoder.weights) load from the same folder
cfg = DefaultWeightOnlyQuantConfig(block_size=block, is_symmetric=True, accuracy_level=4)
q = MatMulNBitsQuantizer(model, algo_config=cfg)
q.process()
tmp = out + ".mm4.onnx"
q.model.save_model_to_file(tmp, use_external_data_format=True)
del q, model
# Remaining fp32 convs -> int8 (dynamic), as in sherpa-onnx's int8 export.
quantize_dynamic(tmp, out, op_types_to_quantize=["Conv"], weight_type=QuantType.QUInt8)
print("saved", out, os.path.getsize(out) // 1048576, "MB")
