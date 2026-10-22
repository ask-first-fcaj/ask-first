# Kết quả Spike OCR (AI-20, Gate G2)

> Sinh tự động bởi `eval/ocr_benchmark.py`. Ngày chạy: 2026-10-22. Người chạy: AI2. **Bản nháp, AI1 chưa duyệt.**

## 1. Kết luận và đề xuất

**Chưa có engine nào đạt ngưỡng G2 (chép đúng URL, nghiêm ngặt, ≥ 90%).** Cao nhất là PaddleOCR + sắp dòng theo cột (ảnh ghép 2 khung) với 28/32 URL. Sau khi sửa lỗi đầu URL (`ttps://.` → `https://`) bằng regex: RapidOCR (PP-OCRv6 small, ONNX) 25→29/32; RapidOCR + sắp dòng theo cột (ảnh ghép 2 khung) 27→31/32; RapidOCR + phóng ×2 ảnh rộng < 800 px 24→28/32; RapidOCR rec Latinh (PP-OCRv5 mobile) 19→21/32; RapidOCR rec Latinh + phóng ×2 18→20/32; PaddleOCR (det + rec mặc định, PP-OCRv6 medium) 26→29/32; PaddleOCR + sắp dòng theo cột (ảnh ghép 2 khung) 28→31/32. Qua ngưỡng 90% sau sửa: RapidOCR (PP-OCRv6 small, ONNX), RapidOCR + sắp dòng theo cột (ảnh ghép 2 khung), PaddleOCR (det + rec mặc định, PP-OCRv6 medium), PaddleOCR + sắp dòng theo cột (ảnh ghép 2 khung). **Lưu ý:** các số này lạc quan, vì quy tắc regex và quy tắc sắp dòng theo cột được viết sau khi xem lỗi trên chính 50 ảnh này, chưa có tập kiểm tra giữ riêng, và `oi-015` (URL bị mờ đuôi) đã bị loại khỏi mẫu số (số trước khi loại ở mục 6). Cách sắp dòng theo cột dùng cùng một hàm cho RapidOCR và Paddle nên hai con này so được với nhau; Tesseract và VietOCR chưa được sắp theo cột. Xem mục 6.

Chưa nên chốt ADR-003 chỉ bằng bảng này: các số dùng regex mới thử một kiểu lỗi, và 50 ảnh chưa đủ để phân biệt các engine sát nhau. Cần thử thêm (VLM fallback hoặc tiền xử lý ảnh) rồi đo lại.

Việc chờ quyết định: AI1 cùng AI2 chốt ADR-003. Đọc kỹ mục 6 trước khi tin số liệu.

### Quyết định đề xuất: ứng viên cho ADR-003

**Trạng thái: ỨNG VIÊN, chưa phải ADR đã chốt.** AI2 đề xuất; AI1 chưa duyệt; **chưa có kiểm tra độc lập** (xem điều kiện bên dưới).

**Hướng chọn:** RapidOCR (PP-OCRv6 small, ONNX, bộ đọc mặc định) + sắp dòng theo cột (`order_by_columns`) + sửa đầu URL bằng regex (`repair_urls`).

- Số đo hiện có: URL nghiêm ngặt 27/32 (84%, CI 68–93%); sau regex 31/32 (97%, CI 84–99%); CER 12.9%; p95 3.05 giây trên máy dev.
- Paddle với cùng cách sắp cột: 31/32 (97%, CI 84–99%) sau regex, CER 12.7%, p95 60 giây. **Hòa về độ chính xác** với RapidOCR.
- **Lý do chọn RapidOCR thay vì Paddle:** độ chính xác ngang nhau, nhưng RapidOCR nhẹ hơn khoảng 5 lần (khoảng 105 MB so với 520 MB trong `.venv`) và nhanh hơn trên máy dev. Image `ai` bị giới hạn ≤ 2 GB (BE-06).
- **Không chọn:** Tesseract và Paddle + VietOCR (chép đúng URL thấp, xem bảng); phóng ×2 và bộ đọc Latinh (không cải thiện, thậm chí tệ hơn).

**Rủi ro đã biết:**

- Quy tắc sắp cột, sửa URL (`repair_urls`) và sửa SĐT (`repair_phones`) đều **viết sau khi thấy lỗi, chưa kiểm tra độc lập**; số đo có thể lạc quan. Riêng `repair_phones` viết sau khi thấy lỗi `0`↔`O` ở ảnh ngoài bộ 50 (`eval/support/053.png`), nên không đổi số nào trong bảng này.
- Mức đạt phụ thuộc regex: nếu không sửa đầu URL thì RapidOCR + cột chỉ đạt 27/32 (84%, CI 68–93%), chưa qua 90%.
- Mẫu nhỏ (32 URL), khoảng tin cậy rộng.

**Điều kiện trước khi chốt thật (chưa làm mục nào):**

1. **Kiểm tra độc lập:** thêm 10–20 ảnh mới, không dùng để chỉnh regex hay quy tắc cột, chạy đúng một lần với ngưỡng chép đúng URL ≥ 90%.
2. **AI1 xác nhận quy ước URL bị mờ** (`oi-015`). Nếu không đồng ý thì số URL đổi (xem mục 6).
3. **Đo trên Lambda thật** (BE-06, AI-26): cold start, RAM đỉnh, p95 so với mục tiêu 3 giây ở 3–4 GB. Số trong báo cáo đo trên máy dev 18 luồng, không so trực tiếp được. Đã thử chạy trong Docker giả lập Lambda (3008 MB RAM, khoảng 1,7 vCPU, 2 lõi) với RapidOCR + sắp dòng theo cột: chạy được, khoảng 2 giây mỗi ảnh khi đặt số luồng onnxruntime bằng số lõi, nhưng khoảng 10 giây nếu để onnxruntime tự chọn luồng. Chỉ thử 4 ảnh, chưa tính p95, chưa phải Lambda thật.
4. **Đưa quy tắc cột và regex vào code pipeline** kèm test (AI-26), giữ cả URL gốc và URL đã sửa như ARCH yêu cầu.

**Nếu kiểm tra độc lập dưới 90%:** không coi kết quả hiện tại là đủ, phải xem lại (tiền xử lý ảnh, hoặc VLM fallback AI-27 đang hoãn).

## 2. Cách chạy lại

```bash
uv venv .venv --python 3.12
uv pip install --python .venv/Scripts/python.exe rapidocr onnxruntime paddlepaddle paddleocr vietocr torch torchvision "opencv-contrib-python==4.10.0.84" "setuptools<81"
# Tesseract: cài UB-Mannheim, đặt vie.traineddata (tessdata_fast) vào ~/tessdata
.venv/Scripts/python.exe eval/ocr_benchmark.py
```

| Mục | Giá trị |
|---|---|
| Commit code | `547c5ab` (+ thay đổi chưa commit) |
| Bộ dữ liệu | `eval/datasets/ocr_images.jsonl`, 50 dòng, sha256 `e81311b50d4909cc` |
| Python | 3.12.14 |
| Hệ điều hành | Windows-11-10.0.26200-SP0 |
| CPU | Intel64 Family 6 Model 170 Stepping 4, GenuineIntel (18 luồng) |
| Phiên bản | rapidocr 3.10.0, onnxruntime 1.31.0, paddleocr 3.7.0, paddlepaddle 3.3.1, vietocr 0.3.12, torch 2.14.1, tesseract v5.4.0.20240606 |

## 3. Bảng số đo

Mọi tỷ lệ ghi dạng `x/n` kèm khoảng tin cậy Wilson 95%. CER là tổng số ký tự sai chia tổng số ký tự đáp án (gộp mọi ảnh), khoảng tin cậy lấy bằng bootstrap 2000 lần theo ảnh.

| Engine | Chép đúng URL, **nghiêm ngặt** (mục tiêu ≥ 90%) | Sau sửa đầu URL bằng regex | Phụ: đúng tên miền + đường dẫn (bỏ `https://`) | Từ có dấu đọc đúng | SĐT đúng | SĐT sau sửa `0`/`O` | CER | CER bỏ dấu |
|---|---|---|---|---|---|---|---|---|
| RapidOCR (PP-OCRv6 small, ONNX) | 25/32 (78%, CI 61–89%) | 29/32 (91%, CI 76–97%) | 29/32 (91%, CI 76–97%) | 862/2327 (37%, CI 35–39%) | 16/16 (100%, CI 81–100%) | 16/16 (100%, CI 81–100%) | 26.8% (CI 17.9–35.8%) | 24.8% |
| RapidOCR + sắp dòng theo cột (ảnh ghép 2 khung) | 27/32 (84%, CI 68–93%) | 31/32 (97%, CI 84–99%) | 31/32 (97%, CI 84–99%) | 862/2327 (37%, CI 35–39%) | 16/16 (100%, CI 81–100%) | 16/16 (100%, CI 81–100%) | 12.9% (CI 10.5–15.5%) | 10.9% |
| RapidOCR + phóng ×2 ảnh rộng < 800 px | 24/32 (75%, CI 58–87%) | 28/32 (88%, CI 72–95%) | 28/32 (88%, CI 72–95%) | 865/2327 (37%, CI 35–39%) | 16/16 (100%, CI 81–100%) | 16/16 (100%, CI 81–100%) | 26.9% (CI 17.9–35.8%) | 24.9% |
| RapidOCR rec Latinh (PP-OCRv5 mobile) | 19/32 (59%, CI 42–74%) | 21/32 (66%, CI 48–80%) | 25/32 (78%, CI 61–89%) | 697/2327 (30%, CI 28–32%) | 16/16 (100%, CI 81–100%) | 16/16 (100%, CI 81–100%) | 28.8% (CI 19.9–37.7%) | 22.5% |
| RapidOCR rec Latinh + phóng ×2 | 18/32 (56%, CI 39–72%) | 20/32 (62%, CI 45–77%) | 24/32 (75%, CI 58–87%) | 698/2327 (30%, CI 28–32%) | 16/16 (100%, CI 81–100%) | 16/16 (100%, CI 81–100%) | 28.8% (CI 19.7–37.8%) | 22.5% |
| Tesseract 5.4 `vie` (tessdata_fast) | 10/32 (31%, CI 18–49%) | 10/32 (31%, CI 18–49%) | 14/32 (44%, CI 28–61%) | 1667/2327 (72%, CI 70–73%) | 12/16 (75%, CI 51–90%) | 12/16 (75%, CI 51–90%) | 39.2% (CI 30.8–49.0%) | 38.4% |
| Tesseract 5.4 `vie+eng` (tessdata_fast) | 15/32 (47%, CI 31–64%) | 15/32 (47%, CI 31–64%) | 16/32 (50%, CI 34–66%) | 1572/2327 (68%, CI 66–69%) | 12/16 (75%, CI 51–90%) | 12/16 (75%, CI 51–90%) | 39.0% (CI 30.5–48.7%) | 37.8% |
| PaddleOCR (det + rec mặc định, PP-OCRv6 medium) | 26/32 (81%, CI 65–91%) | 29/32 (91%, CI 76–97%) | 29/32 (91%, CI 76–97%) | 866/2327 (37%, CI 35–39%) | 16/16 (100%, CI 81–100%) | 16/16 (100%, CI 81–100%) | 27.4% (CI 18.3–36.5%) | 25.0% |
| PaddleOCR + sắp dòng theo cột (ảnh ghép 2 khung) | 28/32 (88%, CI 72–95%) | 31/32 (97%, CI 84–99%) | 31/32 (97%, CI 84–99%) | 866/2327 (37%, CI 35–39%) | 16/16 (100%, CI 81–100%) | 16/16 (100%, CI 81–100%) | 12.7% (CI 10.4–15.5%) | 10.1% |
| Paddle det + VietOCR (vgg_transformer) | 9/32 (28%, CI 16–45%) | 9/32 (28%, CI 16–45%) | 18/32 (56%, CI 39–72%) | 1979/2327 (85%, CI 84–86%) | 12/16 (75%, CI 51–90%) | 12/16 (75%, CI 51–90%) | 34.8% (CI 24.2–45.3%) | 33.5% |

CER trên riêng các ảnh **không có vùng `[mờ]`**, và CER trung vị từng ảnh:

| Engine | CER 34 ảnh không `[mờ]` | CER trung vị theo ảnh |
|---|---|---|
| RapidOCR (PP-OCRv6 small, ONNX) | 26.3% | 12.8% |
| RapidOCR + sắp dòng theo cột (ảnh ghép 2 khung) | 11.3% | 12.5% |
| RapidOCR + phóng ×2 ảnh rộng < 800 px | 26.6% | 12.8% |
| RapidOCR rec Latinh (PP-OCRv5 mobile) | 28.6% | 15.2% |
| RapidOCR rec Latinh + phóng ×2 | 28.8% | 15.2% |
| Tesseract 5.4 `vie` (tessdata_fast) | 34.2% | 25.7% |
| Tesseract 5.4 `vie+eng` (tessdata_fast) | 34.1% | 25.5% |
| PaddleOCR (det + rec mặc định, PP-OCRv6 medium) | 26.9% | 13.6% |
| PaddleOCR + sắp dòng theo cột (ảnh ghép 2 khung) | 11.2% | 12.1% |
| Paddle det + VietOCR (vgg_transformer) | 30.6% | 13.7% |

Độ trễ trên **CPU máy dev** (giây mỗi ảnh, đã làm nóng 1 lượt, không tính thời gian nạp model):

| Engine | Nạp model | p50 | p95 | Trung bình |
|---|---|---|---|---|
| RapidOCR (PP-OCRv6 small, ONNX) | 0.7s | 1.84 | 3.01 | 1.93 |
| RapidOCR + sắp dòng theo cột (ảnh ghép 2 khung) | 0.6s | 1.91 | 3.05 | 1.99 |
| RapidOCR + phóng ×2 ảnh rộng < 800 px | 0.6s | 1.96 | 2.92 | 2.05 |
| RapidOCR rec Latinh (PP-OCRv5 mobile) | 0.0s | 2.15 | 3.23 | 2.19 |
| RapidOCR rec Latinh + phóng ×2 | 0.0s | 2.28 | 3.10 | 2.32 |
| Tesseract 5.4 `vie` (tessdata_fast) | 0.0s | 0.23 | 0.70 | 0.29 |
| Tesseract 5.4 `vie+eng` (tessdata_fast) | 0.0s | 0.33 | 0.99 | 0.42 |
| PaddleOCR (det + rec mặc định, PP-OCRv6 medium) | 5.1s | 25.54 | 62.49 | 29.44 |
| PaddleOCR + sắp dòng theo cột (ảnh ghép 2 khung) | 4.7s | 23.95 | 60.09 | 27.60 |
| Paddle det + VietOCR (vgg_transformer) | 3.4s | 12.12 | 26.04 | 13.60 |

Mục tiêu p95 ≤ 3 giây (AI-26) là trên **Lambda 3–4 GB**; số trên đây là máy dev, **không so trực tiếp** được.

## 4. License

| Thành phần | License | Nguồn |
|---|---|---|
| RapidOCR, PaddleOCR, VietOCR | Apache-2.0 | ARCH §6.3 [L4], [L5] |
| Tesseract | Apache-2.0 | Chưa đối chiếu lại repo gốc: **not verified** |
| Trọng số model (PP-OCRv6, VietOCR `vgg_transformer`, `tessdata_fast`) | **not verified** | Chưa đọc license riêng của từng file trọng số |

## 5. Ví dụ lỗi

### RapidOCR (PP-OCRv6 small, ONNX)

URL chép sai: 7/32 (hiện tối đa 10).

| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |
|---|---|---|
| oi-006 | `https://vietcombank.vn-ms.top` | `ttps://.vietcombank.vn-ms.topde` |
| oi-008 | `https://dichvucongs.top/vn` | `ttps://.dichvucongs.top/vn` |
| oi-022 | `https://251970.help/vietnampost.vn` | `https://251970.help/Vuilòngxemchitit` |
| oi-022 | `https://1526701.cc/dichvycong.gov/vn` | `https://1526701.cc/Nuliênktkhôngmđưc,v` |
| oi-025 | `https://dichvucongg.top/vn` | `ttps://.dichvucongg.top/vn` |
| oi-037 | `vpbank.vn-tp.xyz` | `ypbank.vn-tp.xyzde` |
| oi-048 | `https://techcombank.vn-iy.life` | `ttps://.techcombank.vn-iy.lifede` |

5 ảnh CER cao nhất: oi-046 (74%), oi-022 (70%), oi-023 (70%), oi-049 (67%), oi-034 (60%)

### RapidOCR + sắp dòng theo cột (ảnh ghép 2 khung)

URL chép sai: 5/32 (hiện tối đa 10).

| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |
|---|---|---|
| oi-006 | `https://vietcombank.vn-ms.top` | `ttps://.vietcombank.vn-ms.topde` |
| oi-008 | `https://dichvucongs.top/vn` | `ttps://.dichvucongs.top/vn` |
| oi-025 | `https://dichvucongg.top/vn` | `ttps://.dichvucongg.top/vn` |
| oi-037 | `vpbank.vn-tp.xyz` | `ypbank.vn-tp.xyzde` |
| oi-048 | `https://techcombank.vn-iy.life` | `ttps://.techcombank.vn-iy.lifede` |

5 ảnh CER cao nhất: oi-028 (52%), oi-007 (46%), oi-024 (36%), oi-020 (33%), oi-027 (28%)

### RapidOCR + phóng ×2 ảnh rộng < 800 px

URL chép sai: 8/32 (hiện tối đa 10).

| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |
|---|---|---|
| oi-004 | `dichvucong-vn.com` | `dichvucongvn.comTin` |
| oi-006 | `https://vietcombank.vn-ms.top` | `ttps://.vietcombank.vn-ms.topde` |
| oi-008 | `https://dichvucongs.top/vn` | `ttps://.dichvucongs.top/vn` |
| oi-022 | `https://251970.help/vietnampost.vn` | `https://251970.help/Vuilòngxemchitit` |
| oi-022 | `https://1526701.cc/dichvycong.gov/vn` | `https://1526701.cc/Nuliênktkhôngmđưc,v` |
| oi-025 | `https://dichvucongg.top/vn` | `ttps://.dichvucongg.top/vn` |
| oi-037 | `vpbank.vn-tp.xyz` | `ypbank.vn-tp.xyzde` |
| oi-048 | `https://techcombank.vn-iy.life` | `ttps://.techcombank.vn-iy.lifede` |

5 ảnh CER cao nhất: oi-046 (74%), oi-022 (70%), oi-023 (70%), oi-049 (67%), oi-034 (60%)

### RapidOCR rec Latinh (PP-OCRv5 mobile)

URL chép sai: 13/32 (hiện tối đa 10).

| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |
|---|---|---|
| oi-004 | `dichvucong-vn.com` | `lichvucong-vn.comTi` |
| oi-006 | `https://vietcombank.vn-ms.top` | `https:/.vietcombank.vn-ms.topde` |
| oi-008 | `https://dichvucongs.top/vn` | `ttps://.dichvucongs.top/vn` |
| oi-013 | `https://902577.cc/dichvycong.gov/vn` | `https:/|002577.cc/dichvycong.gov/vnTr` |
| oi-014 | `https://redonred.net/?rcd=17524` | `:https:/redonred.net/?rcd=17524Ne` |
| oi-022 | `https://251970.help/vietnampost.vn` | `https://251970.help/Vuilòngxemchitié` |
| oi-022 | `https://1526701.cc/dichvycong.gov/vn` | `https://1526701.cc/Néuliênkétkhôngmòdu` |
| oi-025 | `https://dichvucongg.top/vn` | `ttps:/l.dichvucongg.top/vn` |
| oi-030 | `https://v-acb.com` | `https:/ly-acb.comde` |
| oi-037 | `vpbank.vn-tp.xyz` | `ypbank.vn-tp.xyzde` |

5 ảnh CER cao nhất: oi-046 (75%), oi-023 (74%), oi-022 (72%), oi-049 (70%), oi-034 (62%)

### RapidOCR rec Latinh + phóng ×2

URL chép sai: 14/32 (hiện tối đa 10).

| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |
|---|---|---|
| oi-004 | `dichvucong-vn.com` | `eichvucongvn.comTin` |
| oi-006 | `https://vietcombank.vn-ms.top` | `https:/.vietcombank.vn-ms.topde` |
| oi-008 | `https://dichvucongs.top/vn` | `ttps://.dichvucongs.top/vn` |
| oi-013 | `https://902577.cc/dichvycong.gov/vn` | `https:/|002577.cc/dichvycong.gov/vnTr` |
| oi-014 | `https://redonred.net/?rcd=17524` | `:https:/redonred.net/?rcd=17524Ne` |
| oi-018 | `https://ebank.hlug1k.com` | `https://ebank.hluglk.comdé` |
| oi-022 | `https://251970.help/vietnampost.vn` | `https://251970.help/Vuilòngxemchitié` |
| oi-022 | `https://1526701.cc/dichvycong.gov/vn` | `https://1526701.cc/Néuliênkétkhôngmòdu` |
| oi-025 | `https://dichvucongg.top/vn` | `ttps:/l.dichvucongg.top/vn` |
| oi-030 | `https://v-acb.com` | `https:/ly-acb.comde` |

5 ảnh CER cao nhất: oi-046 (75%), oi-023 (74%), oi-022 (72%), oi-049 (70%), oi-034 (62%)

### Tesseract 5.4 `vie` (tessdata_fast)

URL chép sai: 22/32 (hiện tối đa 10).

| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |
|---|---|---|
| oi-004 | `dichvucong-vn.com` | `(không thấy đoạn nào giống)` |
| oi-006 | `https://msb.vn-cvs.top` | `https:/fmsb.vn-cvs.topde` |
| oi-007 | `http://www.vietinbankvpps.cc` | `ptpiJluyuvwvietinbanksdexacmna` |
| oi-007 | `ebanking.vietinbank.vn` | `piJluyuvwvietinbanksdexa` |
| oi-008 | `https://dichvucongs.top/vn` | `httbs://dichvucongs.top/vn` |
| oi-010 | `https://i-vietcombank.com` | `https:/Ji-vietcombank.com` |
| oi-011 | `https://acb-online.cc` | `etPfDS;/acb.on;ñì9.cc9c` |
| oi-013 | `https://902577.cc/dichvycong.gov/vn` | `https://202577.ccjdichvýcong.gov/vnTr` |
| oi-016 | `dichvucong-govvn.com` | `(không thấy đoạn nào giống)` |
| oi-018 | `https://ebank.hlug1k.com` | `ghifps//ebanamđểxem.` |

5 ảnh CER cao nhất: oi-021 (100%), oi-024 (100%), oi-025 (100%), oi-027 (100%), oi-035 (100%)

### Tesseract 5.4 `vie+eng` (tessdata_fast)

URL chép sai: 17/32 (hiện tối đa 10).

| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |
|---|---|---|
| oi-004 | `dichvucong-vn.com` | `(không thấy đoạn nào giống)` |
| oi-007 | `http://www.vietinbankvpps.cc` | `iknoeDahtt:vietinbanksdexacymn` |
| oi-007 | `ebanking.vietinbank.vn` | `noeDahtt:vietinbanksdexa` |
| oi-011 | `https://acb-online.cc` | `https:Vach-onjjNe.cc9ca` |
| oi-016 | `dichvucong-govvn.com` | `(không thấy đoạn nào giống)` |
| oi-018 | `https://ebank.hlug1k.com` | `httes://ebanomđểxem.` |
| oi-019 | `https://i-sacombank.com` | `hhttis:/i:sacombank.comde` |
| oi-022 | `https://251970.help/vietnampost.vn` | `https;J/251970.help/VietnampostvnNếu` |
| oi-022 | `https://1526701.cc/dichvycong.gov/vn` | `ại:https;626701.cc/dichvycong.gov/vn.T` |
| oi-025 | `https://dichvucongg.top/vn` | `(không thấy đoạn nào giống)` |

5 ảnh CER cao nhất: oi-021 (100%), oi-024 (100%), oi-025 (100%), oi-027 (100%), oi-035 (100%)

### PaddleOCR (det + rec mặc định, PP-OCRv6 medium)

URL chép sai: 6/32 (hiện tối đa 10).

| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |
|---|---|---|
| oi-008 | `https://dichvucongs.top/vn` | `ttps://.dichvucongs.top/vn` |
| oi-009 | `https://ghtkx.cfd/vn` | `ttps://.ghtkx.cfd/vnđc` |
| oi-018 | `https://ebank.hlug1k.com` | `https://ebank.hlug1kcomdéx` |
| oi-022 | `https://251970.help/vietnampost.vn` | `https://251970.help/Vuilòngxemchitit` |
| oi-022 | `https://1526701.cc/dichvycong.gov/vn` | `https://1526701.cc/Nulinktkhôngmđuc,vu` |
| oi-048 | `https://techcombank.vn-iy.life` | `ttps://.techcombank.vn-iy.lifede` |

5 ảnh CER cao nhất: oi-046 (75%), oi-023 (74%), oi-022 (72%), oi-049 (68%), oi-034 (61%)

### PaddleOCR + sắp dòng theo cột (ảnh ghép 2 khung)

URL chép sai: 4/32 (hiện tối đa 10).

| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |
|---|---|---|
| oi-008 | `https://dichvucongs.top/vn` | `ttps://.dichvucongs.top/vn` |
| oi-009 | `https://ghtkx.cfd/vn` | `ttps://.ghtkx.cfd/vnđc` |
| oi-018 | `https://ebank.hlug1k.com` | `https://ebank.hlug1kcomdéx` |
| oi-048 | `https://techcombank.vn-iy.life` | `ttps://.techcombank.vn-iy.lifede` |

5 ảnh CER cao nhất: oi-007 (60%), oi-020 (49%), oi-028 (37%), oi-027 (28%), oi-024 (28%)

### Paddle det + VietOCR (vgg_transformer)

URL chép sai: 23/32 (hiện tối đa 10).

| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |
|---|---|---|
| oi-004 | `dichvucong-vn.com` | `ehchvucongvn.comTin` |
| oi-006 | `https://vietcombank.vn-ms.top` | `https:/lvietcombank.vn-ms.topde` |
| oi-007 | `http://www.vietinbankvpps.cc` | `http/lonhucngayhomnayLandaOKch` |
| oi-007 | `ebanking.vietinbank.vn` | `Chankingviec177/7181015u` |
| oi-008 | `https://dichvucongs.top/vn` | `https:/ldichvucongs.top/vn` |
| oi-009 | `https://ghtkx.cfd/vn` | `https:/lghtkx.cfd/vnđể` |
| oi-011 | `https://acb-online.cc` | `https:/lacb-online-cokh` |
| oi-013 | `https://902577.cc/dichvycong.gov/vn` | `https:/1902577.cc/dichvycong.gov/vnTr` |
| oi-014 | `https://redonred.net/?rcd=17524` | `https://redonred-net/2rcd?17524NE` |
| oi-018 | `https://ebank.hlug1k.com` | `htips://ebankihug1kcomđểxe` |

5 ảnh CER cao nhất: oi-027 (134%), oi-007 (96%), oi-035 (90%), oi-028 (89%), oi-021 (87%)

## 6. Chưa kiểm và hạn chế

- **Cỡ mẫu nhỏ.** 50 ảnh, trong đó chỉ có 32 URL; chênh vài URL không đủ kết luận ai hơn ai. Xem khoảng tin cậy.
- **Vùng `[mờ]`:** 16/50 ảnh có vùng bị làm mờ trong ảnh. Đáp án đã bỏ `[mờ]`, nhưng engine đôi khi vẫn đọc ra chữ trong vùng mờ và bị tính là chèn thừa. Ảnh hưởng như nhau với mọi engine; số CER ở bảng "không `[mờ]`" sạch hơn.
- **Thứ tự dòng:** đáp án gõ tay theo thứ tự đọc của người; engine có thể ra thứ tự khác ở ảnh nhiều khung, làm CER cao dù chữ đúng.
- **Cách tính URL:** khớp chính xác (phân biệt hoa thường), bỏ mọi khoảng trắng và dấu `/` cuối trước khi so, để không phạt việc engine ngắt dòng giữa URL. Chỉ tính URL trong nhãn `urls`; URL bị mờ không có trong nhãn.
- **Cột "sau sửa đầu URL bằng regex" lạc quan:** hai quy tắc (`ttps:` → `https:`, bỏ dấu `.` thừa sau `://`) được viết **sau khi xem lỗi trên chính 50 ảnh này**, nên chưa phải số đo trên dữ liệu chưa thấy. Chưa thử sửa lỗi kiểu `https:/l` của Tesseract/VietOCR vì quy tắc đó dễ sửa nhầm.
- **Nhãn `oi-015` đã đổi sau lần đo đầu:** đuôi URL sau `.xyz/` bị làm mờ nên `urls` chuyển từ 1 URL sang rỗng, theo cùng quy ước với `oi-020` và `oi-039` (URL bị mờ không dùng để đo exact-match). Việc này **đưa mẫu số từ 33 xuống 32** và làm RapidOCR, Paddle mặc định đổi từ 29/33 (88%) sang 29/32 (91%) ở cột sau regex. **Hai con tự nó qua ngưỡng 90% nhờ việc loại 1 URL, không nhờ OCR đọc tốt hơn**; số trước khi đổi nhãn là 29/33. Không có đổi nhãn nào khác.
- **Sắp dòng theo cột áp dụng cho RapidOCR và Paddle (cùng một hàm `order_by_columns`), chưa áp dụng cho Tesseract và VietOCR:** nhiều ảnh trong bộ là ảnh ghép 2 khung chat, và thứ tự đọc là một nguồn lỗi lớn. Trên cùng 7 ảnh (`oi-006`, `014`, `022`, `023`, `034`, `046`, `049`), CER RapidOCR giảm từ 26,8% xuống 12,9% và Paddle từ 27,4% xuống 12,7%, không ảnh nào tệ đi. Số của Tesseract và VietOCR bị thiệt chưa rõ bao nhiêu nên không dùng bảng này để so chúng với hai con kia. Ngưỡng của quy tắc (dải trống ≥ 12 px trong 30–70% chiều rộng, ≥ 3 hộp mỗi bên) được chọn từ một ảnh (`oi-022`) và chưa chỉnh thêm; kết quả vẫn đo trên cùng 50 ảnh, chưa có tập kiểm tra giữ riêng.
- **Từ có dấu đọc đúng:** so tập từ (không xét thứ tự), bỏ từ chứa số, URL, email.
- **Độ trễ:** đo trên máy dev, một luồng chạy tuần tự, chưa đo Lambda, chưa đo cold start. Tesseract tính cả thời gian khởi động tiến trình.
- **Dung lượng cài đặt (đo trong `.venv` trên Windows, chưa phải image Lambda):** RapidOCR gồm `rapidocr` 32 MB + `onnxruntime` 42 MB + model 31 MB; PaddleOCR gồm `paddle` 377 MB + `paddlex` 11 MB + model 133 MB, nặng gấp khoảng 3 lần. Đây là lý do thực tế để ưu tiên RapidOCR khi giới hạn image `ai` ≤ 2 GB (BE-06); chưa kiểm Paddle có chạy được trên Lambda hay không.
- **Cấu hình chưa tối ưu:** mỗi engine chạy tham số mặc định (Tesseract `psm` mặc định; Paddle tắt mkldnn do lỗi oneDNN trên Windows, nên có thể chậm hơn mức thực). Đã thử phóng ×2 ảnh rộng < 800 px và bộ đọc Latinh PP-OCRv5 **chỉ với RapidOCR**: cả hai không cải thiện, nên chưa thử với Paddle (suy luận, chưa kiểm). Chưa thử tăng tương phản hay `tessdata_best`.
- **Paddle + VietOCR là bản tự ghép:** cắt theo hộp Paddle phát hiện, ghép hộp cùng hàng bằng toạ độ. Cách ghép khác có thể cho kết quả khác.
- **Dữ liệu:** ảnh từ báo/cảnh báo công khai, nhãn do AI1 và AI2 gõ tay, AI1 chưa review chéo nhãn của AI2.

## 7. Phụ lục: CER từng ảnh

| Ảnh | `[mờ]` | RapidOCR | RapidOCR + sắp dòng theo cột | RapidOCR + phóng ×2 ảnh rộng < 800 px | RapidOCR rec Latinh | RapidOCR rec Latinh + phóng ×2 | Tesseract 5.4 `vie` | Tesseract 5.4 `vie+eng` | PaddleOCR | PaddleOCR + sắp dòng theo cột | Paddle det + VietOCR |
|---|---|---|---|---|---|---|---|---|---|---|---|
| oi-001 | có | 13% | 13% | 13% | 15% | 15% | 22% | 21% | 12% | 12% | 58% |
| oi-002 |  | 12% | 12% | 11% | 18% | 15% | 31% | 28% | 13% | 13% | 4% |
| oi-003 | có | 13% | 13% | 13% | 15% | 15% | 19% | 20% | 13% | 13% | 4% |
| oi-004 |  | 20% | 20% | 20% | 26% | 26% | 85% | 84% | 18% | 18% | 16% |
| oi-005 |  | 11% | 11% | 12% | 14% | 14% | 5% | 5% | 11% | 11% | 3% |
| oi-006 |  | 45% | 1% | 45% | 45% | 45% | 46% | 46% | 45% | 1% | 50% |
| oi-007 | có | 46% | 46% | 46% | 46% | 46% | 70% | 70% | 60% | 60% | 96% |
| oi-008 |  | 10% | 10% | 10% | 13% | 14% | 2% | 1% | 11% | 11% | 4% |
| oi-009 |  | 12% | 12% | 12% | 15% | 15% | 6% | 6% | 14% | 14% | 5% |
| oi-010 |  | 1% | 1% | 1% | 3% | 3% | 5% | 4% | 3% | 3% | 5% |
| oi-011 |  | 1% | 1% | 1% | 2% | 2% | 68% | 67% | 2% | 2% | 69% |
| oi-012 |  | 13% | 13% | 13% | 17% | 17% | 17% | 18% | 17% | 17% | 10% |
| oi-013 |  | 8% | 8% | 8% | 11% | 11% | 31% | 31% | 8% | 8% | 3% |
| oi-014 |  | 48% | 8% | 48% | 49% | 49% | 5% | 5% | 48% | 8% | 46% |
| oi-015 | có | 20% | 20% | 20% | 22% | 22% | 85% | 84% | 20% | 20% | 40% |
| oi-016 | có | 3% | 3% | 3% | 3% | 3% | 71% | 68% | 4% | 4% | 2% |
| oi-017 | có | 19% | 19% | 19% | 23% | 21% | 24% | 22% | 17% | 17% | 17% |
| oi-018 |  | 15% | 15% | 16% | 17% | 19% | 25% | 23% | 15% | 15% | 12% |
| oi-019 | có | 2% | 2% | 3% | 2% | 2% | 21% | 19% | 3% | 3% | 5% |
| oi-020 |  | 33% | 33% | 44% | 37% | 48% | 92% | 90% | 49% | 49% | 78% |
| oi-021 |  | 19% | 19% | 19% | 18% | 18% | 100% | 100% | 18% | 18% | 87% |
| oi-022 |  | 70% | 16% | 70% | 72% | 72% | 26% | 27% | 72% | 12% | 74% |
| oi-023 |  | 70% | 16% | 70% | 74% | 74% | 74% | 74% | 74% | 15% | 75% |
| oi-024 | có | 36% | 36% | 30% | 36% | 30% | 100% | 100% | 28% | 28% | 82% |
| oi-025 |  | 8% | 8% | 8% | 10% | 11% | 100% | 100% | 8% | 8% | 10% |
| oi-026 |  | 12% | 12% | 14% | 16% | 17% | 11% | 10% | 12% | 12% | 5% |
| oi-027 | có | 28% | 28% | 16% | 32% | 20% | 100% | 100% | 28% | 28% | 134% |
| oi-028 | có | 52% | 52% | 50% | 50% | 50% | 92% | 92% | 37% | 37% | 89% |
| oi-029 |  | 15% | 15% | 13% | 18% | 17% | 15% | 18% | 15% | 15% | 15% |
| oi-030 |  | 2% | 2% | 2% | 4% | 4% | 3% | 5% | 3% | 3% | 3% |
| oi-031 |  | 16% | 16% | 14% | 18% | 17% | 8% | 8% | 14% | 14% | 2% |
| oi-032 |  | 10% | 10% | 10% | 14% | 14% | 4% | 6% | 10% | 10% | 2% |
| oi-033 |  | 10% | 10% | 10% | 13% | 13% | 7% | 8% | 10% | 10% | 4% |
| oi-034 | có | 60% | 15% | 60% | 62% | 62% | 61% | 60% | 61% | 15% | 58% |
| oi-035 |  | 22% | 22% | 22% | 14% | 14% | 100% | 100% | 19% | 19% | 90% |
| oi-036 |  | 18% | 18% | 18% | 22% | 22% | 100% | 100% | 19% | 19% | 75% |
| oi-037 |  | 5% | 5% | 5% | 4% | 5% | 10% | 9% | 5% | 5% | 5% |
| oi-038 | có | 9% | 9% | 9% | 12% | 11% | 10% | 10% | 10% | 10% | 7% |
| oi-039 | có | 9% | 9% | 9% | 10% | 10% | 25% | 24% | 8% | 8% | 21% |
| oi-040 |  | 5% | 5% | 5% | 7% | 7% | 54% | 55% | 6% | 6% | 10% |
| oi-041 |  | 13% | 13% | 13% | 15% | 15% | 17% | 17% | 10% | 10% | 2% |
| oi-042 |  | 2% | 2% | 2% | 2% | 2% | 76% | 75% | 2% | 2% | 59% |
| oi-043 |  | 26% | 26% | 26% | 28% | 28% | 56% | 57% | 24% | 24% | 21% |
| oi-044 |  | 10% | 10% | 10% | 9% | 9% | 1% | 1% | 10% | 10% | 2% |
| oi-045 | có | 13% | 13% | 13% | 14% | 14% | 100% | 100% | 17% | 17% | 85% |
| oi-046 | có | 74% | 28% | 74% | 75% | 75% | 66% | 66% | 75% | 22% | 66% |
| oi-047 | có | 3% | 3% | 3% | 4% | 4% | 22% | 22% | 4% | 4% | 9% |
| oi-048 |  | 3% | 3% | 3% | 2% | 3% | 3% | 3% | 3% | 3% | 2% |
| oi-049 |  | 67% | 9% | 67% | 70% | 70% | 63% | 64% | 68% | 10% | 66% |
| oi-050 |  | 13% | 13% | 13% | 15% | 15% | 6% | 7% | 13% | 13% | 3% |
