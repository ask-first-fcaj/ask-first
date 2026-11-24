"""Dựng artifact `vietocr` (AI-26): xuất VietOCR `vgg_seq2seq` sang 3 file ONNX + `vocab.json` cho `local_models/vietocr.py`.

Cần torch + vietocr, KHÔNG nằm trong dependency của `ai/` (Lambda chỉ chạy file ONNX). Dựng env riêng (Python 3.12):

    uv venv .venv-vietocr --python 3.12
    uv pip install --python .venv-vietocr torch==2.15.0 torchvision==0.29.2 --index-url https://download.pytorch.org/whl/cpu
    uv pip install --python .venv-vietocr --no-deps vietocr==0.3.13      # deps của vietocr (imgaug…) không cần để xuất
    uv pip install --python .venv-vietocr einops gdown pyyaml pillow onnx
    .venv-vietocr/Scripts/python scripts/export_vietocr_onnx.py OUT_DIR

vietocr tự tải trọng số `vgg_seq2seq.pth` (https://vocr.vn/data/vietocr/vgg_seq2seq.pth); script dừng nếu sha256 khác
`WEIGHTS_SHA256`. File ONNX không dựng lại byte-y-hệt được qua các bản torch khác nhau: bản trên S3 (sha256 ở
`config/artifacts.yaml`) là bản chuẩn.

So với `vietocr.tool.translate`: bỏ `assert` và dropout (không xuất được), đổi trục âm sang trục dương (ONNX Transpose),
và tính sẵn phần attention chỉ phụ thuộc encoder (W_e·enc + b) ở `encoder.onnx` để mỗi bước giải mã nhẹ hơn. Script kiểm
từng phần khớp torch (sai số ≤ 1e-4) trước khi ghi.

Không lượng tử hoá int8: `cnn.onnx` int8 tĩnh (QDQ) đọc đúng trên máy dev nhưng trên Lambda x86_64 tụt từ 82% xuống 49% từ
có dấu đúng (`docs/results/ocr-vietocr.md`).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import tempfile
from pathlib import Path

WEIGHTS_SHA256 = "0921503a41375a0584268e23ef3d414ea478a8fe8777865c7745d38f2d0bc5db"


def build(out: Path) -> None:
    import torch
    from PIL import Image
    from torch import nn
    from torch.nn import functional as F

    if not hasattr(Image, "ANTIALIAS"):   # vietocr gọi tên cũ, Pillow >= 10 đã bỏ
        Image.ANTIALIAS = Image.LANCZOS
    from vietocr.tool.config import Cfg
    from vietocr.tool.predictor import Predictor

    cfg = Cfg.load_config_from_name("vgg_seq2seq")
    cfg["device"] = "cpu"
    cfg["cnn"]["pretrained"] = False
    model = Predictor(cfg).model.eval()
    weights = Path(tempfile.gettempdir()) / cfg["weights"].split("/")[-1]   # chỗ vietocr.tool.utils.download lưu file
    got = hashlib.sha256(weights.read_bytes()).hexdigest()
    if got != WEIGHTS_SHA256:
        raise SystemExit(f"{weights}: sha256 {got} khác {WEIGHTS_SHA256}")
    s2s = model.transformer

    class Cnn(nn.Module):
        def __init__(self):
            super().__init__()
            self.m = model.cnn.model

        def forward(self, x):
            conv = self.m.last_conv_1x1(self.m.features(x))       # N x C x H x W
            return conv.transpose(2, 3).flatten(2).permute(2, 0, 1)  # (W*H) x N x C

    class Encoder(nn.Module):
        def __init__(self):
            super().__init__()
            self.enc, self.att = s2s.encoder, s2s.decoder.attention.attn

        def forward(self, src):
            outputs, hidden = self.enc.rnn(src)
            hidden = torch.tanh(self.enc.fc(torch.cat((hidden[-2], hidden[-1]), dim=1)))
            eo = outputs.permute(1, 0, 2)                            # N x T x 2H
            keys = F.linear(eo, self.att.weight[:, hidden.shape[1]:], self.att.bias)
            return hidden, eo, keys

    class Decoder(nn.Module):
        def __init__(self):
            super().__init__()
            self.d = s2s.decoder

        def forward(self, tgt, hidden, eo, keys):
            emb = self.d.embedding(tgt.unsqueeze(0))
            q = F.linear(hidden, self.d.attention.attn.weight[:, :hidden.shape[1]]).unsqueeze(1)
            a = F.softmax(self.d.attention.v(torch.tanh(keys + q)).squeeze(2), dim=1).unsqueeze(1)
            weighted = torch.bmm(a, eo).permute(1, 0, 2)
            output, hid = self.d.rnn(torch.cat((emb, weighted), dim=2), hidden.unsqueeze(0))
            logits = self.d.fc_out(torch.cat((output.squeeze(0), weighted.squeeze(0), emb.squeeze(0)), dim=1))
            return logits, hid.squeeze(0)

    out.mkdir(parents=True, exist_ok=True)
    img = torch.rand(2, 3, 32, 160)
    with torch.no_grad():
        src = model.cnn(img)
        tgt = torch.ones(2, dtype=torch.long)
        h0, e0 = s2s.forward_encoder(src)
        ref, ref_h, _ = s2s.decoder(tgt, h0, e0)
        hidden, eo, keys = Encoder().eval()(src)
        got, got_h = Decoder().eval()(tgt, hidden, eo, keys)
        diff = max(float((Cnn()(img) - src).abs().max()), float((ref - got).abs().max()), float((ref_h - got_h).abs().max()))
        if diff > 1e-4:
            raise SystemExit(f"bản viết lại lệch torch: {diff}")
        kw = {"opset_version": 17, "dynamo": False}
        torch.onnx.export(Cnn().eval(), (img,), out / "cnn.onnx", input_names=["img"], output_names=["src"],
                          dynamic_axes={"img": {0: "n", 3: "w"}, "src": {0: "t", 1: "n"}}, **kw)
        torch.onnx.export(Encoder().eval(), (src,), out / "encoder.onnx", input_names=["src"],
                          output_names=["hidden", "eo", "keys"],
                          dynamic_axes={"src": {0: "t", 1: "n"}, "hidden": {0: "n"}, "eo": {0: "n", 1: "t"},
                                        "keys": {0: "n", 1: "t"}}, **kw)
        torch.onnx.export(Decoder().eval(), (tgt, hidden, eo, keys), out / "decoder.onnx",
                          input_names=["tgt", "hidden", "eo", "keys"], output_names=["logits", "hidden_out"],
                          dynamic_axes={"tgt": {0: "n"}, "hidden": {0: "n"}, "eo": {0: "n", 1: "t"},
                                        "keys": {0: "n", 1: "t"}, "logits": {0: "n"}, "hidden_out": {0: "n"}}, **kw)
    vocab = {"chars": cfg["vocab"], **{k: cfg["dataset"][k] for k in ("image_height", "image_min_width", "image_max_width")}}
    (out / "vocab.json").write_text(json.dumps(vocab, ensure_ascii=False), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    a = ap.parse_args()
    build(a.out)
    for f in sorted(a.out.iterdir()):
        print(f"{f.name}: {f.stat().st_size // 1024} KB, sha256 {hashlib.sha256(f.read_bytes()).hexdigest()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
