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
| Tesseract `vie` | 10/32 | không đo | | 0.70 s | |
| Paddle det + VietOCR | 9/32 | 18/32 | | 26.04 s | |

- RapidOCR và Paddle hòa về độ chính xác (khoảng tin cậy 84–99% cho cả hai), nhưng RapidOCR nhẹ hơn khoảng 5 lần và nhanh hơn nhiều, hợp giới hạn image ≤ 2 GB.
- Tesseract và Paddle + VietOCR chép URL quá thấp.
- Phóng ×2 ảnh rộng và bộ đọc Latinh PP-OCRv5 không cải thiện, nên không dùng.

## Điều kiện (chưa làm mục nào trừ mục 4)

1. **Kiểm tra độc lập:** thêm 10–20 ảnh mới, không dùng để chỉnh luật cột hay regex, chạy đúng **một lần**. Cần chép đúng URL ≥ 90%. Số hiện tại đo trên chính 50 ảnh đã dùng để chỉnh luật nên có thể lạc quan; không có regex thì chỉ 84%.
2. **Đo trên Lambda ở AI-26:** cold start, RAM đỉnh, p95 so với 3 giây ở 3–4 GB. Số hiện tại là máy dev 18 luồng, không so trực tiếp được. Mới thử Docker giả lập (3008 MB, 2 lõi, 4 ảnh, khoảng 2 giây mỗi ảnh).
3. **Đưa luật cột và `repair_urls`/`repair_phones` vào code pipeline** kèm test (AI-26).
4. **Quy ước URL bị mờ (`oi-015`, `oi-020`, `oi-039`):** AI1 đã xác nhận: URL có phần bị mờ không đưa vào `urls` và không dùng để đo exact-match. Báo cáo ghi cả hai mẫu số (32 và 33).

## Nếu điều kiện 1 hoặc 2 không đạt

Không coi G2 là đạt. Xem lại theo thứ tự: tiền xử lý ảnh, rồi VLM fallback (AI-27, đang hoãn), rồi mở lại ADR này.

## Chưa kiểm

- License trọng số model (PP-OCRv6, `tessdata_fast`) và Tesseract: **not verified**.
- Hiệu năng trên Lambda thật: **not verified**.
- Ngưỡng sắp cột chỉ chỉnh theo 1 ảnh (`oi-022`).
