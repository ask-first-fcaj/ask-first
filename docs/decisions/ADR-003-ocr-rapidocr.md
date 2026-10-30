# ADR-003: Chọn RapidOCR cho OCR local (có điều kiện)

- **Trạng thái:** Chấp nhận **có điều kiện** (chưa qua Gate G2). Chưa phải kết luận cuối.
- **Ngày:** 2026-10-22
- **Người quyết định:** AI1 (chốt hướng); AI2 (chạy đo, xem `docs/results/ocr-spike.md`)
- **Task:** AI-20 (spike), AI-26 (OCR production). Issue #17.

## Bối cảnh

Stage Extract (ARCH §6.3) đọc ảnh chụp màn hình tin nhắn lừa đảo ngay trên Lambda `ai`, không gửi ảnh ra ngoài. Mục tiêu G2: chép đúng URL (khớp chính xác, nghiêm ngặt) **≥ 90%**; p95 ≤ 3 giây trên Lambda 3–4 GB; image `ai` ≤ 2 GB (BE-06).

Spike AI-20 so 10 cấu hình của 3 stack trên 50 ảnh (`eval/datasets/ocr_images.jsonl`, 32 URL đo được). Số liệu đầy đủ và hạn chế: `docs/results/ocr-spike.md`.

## Quyết định

Dùng **RapidOCR** (PP-OCRv6 small, ONNX, bộ đọc mặc định) làm OCR local, kèm hai bước hậu xử lý đã thử trong spike:

1. `order_by_columns`: sắp dòng theo cột cho ảnh ghép 2 khung chat.
2. `repair_urls`: sửa đầu URL (`ttps:` → `https:`, bỏ dấu `.` thừa sau `://`).

Pipeline giữ cả URL gốc lẫn URL đã sửa (ARCH yêu cầu).

## Lý do

| Cấu hình | URL nghiêm ngặt | Sau regex | CER | p95 (máy dev) | Dung lượng `.venv` |
|---|---|---|---|---|---|
| RapidOCR + cột | 27/32 (84%) | 31/32 (97%) | 12.9% | 3.05 s | ~105 MB |
| PaddleOCR + cột | 28/32 (88%) | 31/32 (97%) | 12.7% | 60.09 s | ~520 MB |
| Tesseract `vie` | 10/32 (31%) | 10/32 | 39.2% | 0.70 s | |
| Paddle det + VietOCR | 9/32 (28%) | 9/32 | 34.8% | 26.04 s | |

- RapidOCR và Paddle hòa về độ chính xác (khoảng tin cậy 84–99% cho cả hai), nhưng RapidOCR nhẹ hơn khoảng 5 lần và nhanh hơn nhiều, hợp giới hạn image ≤ 2 GB.
- Tesseract và Paddle + VietOCR chép URL quá thấp.
- Phóng ×2 ảnh rộng và bộ đọc Latinh PP-OCRv5 không cải thiện, nên không dùng.

## Điều kiện (mục 1 và 4 đã xong; mục 2 hoãn sang AI-26)

1. **Kiểm tra độc lập (xong, 2026-10-23, PR #99):** 16 ảnh mới (`eval/testocr/`, 18 URL, 20 SĐT), không sửa code hay regex, chạy đúng một lần. URL nghiêm ngặt 15/18 (83%, CI 61–94%); sau `repair_urls` 17/18 (94%, CI 74–99%), vượt ngưỡng 90%; SĐT 20/20; CER 7.2% (CI 5.9–8.7%). Chi tiết: `docs/results/ocr-spike.md` mục 8. Hạn chế: 18 URL là mẫu nhỏ (cận dưới CI 74%); không có regex thì chỉ 83%, dưới 90%; p50 trên tập này 5,35 s (máy dev), cao hơn 1,91 s của tập 50 ảnh; nhãn 16 ảnh chưa có AI1 duyệt. **Chạy lại trên CI (30/10/2026, PR #105, `ocr-spike.md` mục 9):** ra cùng số URL và CER trên Linux. Bộ 16 ảnh không có ảnh ghép 2 khung, nên `order_by_columns` chưa được kiểm độc lập. Nhãn 16 ảnh AI1 đã soát lại (2026-10-30), không sửa.
2. **Đo trên Lambda ở AI-26 (hoãn theo quyết định của team 2026-10-23, chưa làm):** cold start, RAM đỉnh, p95 so với 3 giây ở 3–4 GB. Số hiện tại là máy dev 18 luồng, không so trực tiếp được. Mới thử Docker giả lập (3008 MB, 2 lõi, 4 ảnh, khoảng 2 giây mỗi ảnh). Số đo thay thế trên GitHub Actions (Linux, khoá 2 lõi, onnxruntime 2 luồng, 30/10/2026): p95 1.63 s (50 ảnh) và 1.12 s (16 ảnh); RAM đỉnh 826 MB. Vẫn chưa phải Lambda: chưa có cold start, chưa tính tải model.
3. **Đưa luật cột và `repair_urls`/`repair_phones` vào code pipeline** kèm test (AI-26).
4. **Quy ước URL bị mờ (`oi-015`, `oi-020`, `oi-039`):** AI1 đã xác nhận: URL có phần bị mờ không đưa vào `urls` và không dùng để đo exact-match. Báo cáo ghi cả hai mẫu số (32 và 33).

## CER so với mục tiêu G2

Issue #17 ban đầu đặt CER ≤ 5%. Kết quả: 12.9% trên 50 ảnh, 7.2% trên 16 ảnh độc lập; cả hai đều trên 5%.

**Quyết định (AI1, 2026-10-23): hạ ngưỡng CER xuống ≤ 10%, tính trên tập độc lập.** Lý do: CER gộp cả vùng `[mờ]` và tin leetspeak, còn pipeline chỉ cần URL và SĐT đúng; chỉ số quyết định vẫn là URL ≥ 90%. Kết quả 7.2% (CI 5.9–8.7%) đạt ngưỡng mới.

Lưu ý: ngưỡng này đặt **sau khi đã thấy số đo**, không phải ngưỡng đặt trước. Số 12.9% trên 50 ảnh tuning vẫn trên 10%. Ngưỡng 10% chưa có căn cứ độc lập ngoài việc URL mới là chỉ số chính.

## Nếu điều kiện 1 hoặc 2 không đạt

Không coi G2 là đạt. Xem lại theo thứ tự: tiền xử lý ảnh, rồi VLM fallback (AI-27, đang hoãn), rồi mở lại ADR này.

## Chưa kiểm

- License code: `rapidocr` 3.10.0 là Apache-2.0, `onnxruntime` 1.31.0 là MIT, PaddleOCR là Apache-2.0 (nguồn ở `ocr-spike.md` mục 4, kiểm 30/10/2026).
- License file trọng số ONNX PP-OCRv6 mà RapidOCR tải từ ModelScope: **not verified** (trang license của model chưa đọc được). Team xác nhận ổn (2026-10-23) nhưng chưa có nguồn; AI-26 ghi vào `MODELS.md`.
- Hiệu năng trên Lambda thật: **not verified**.
- Ngưỡng sắp cột chỉ chỉnh theo 1 ảnh (`oi-022`).
