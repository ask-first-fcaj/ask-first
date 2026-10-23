# Changelog

Theo [Keep a Changelog](https://keepachangelog.com/vi/1.1.0/); commit theo [Conventional Commits](https://www.conventionalcommits.org/vi/v1.0.0/).
Chỉ ghi thay đổi người dùng hoặc team thấy được.

## [Unreleased]

### Changed
- Rút kế hoạch từ 12 xuống **6 tuần** (19/10–29/11/2026, v3): `docs/implementation-plan.md`, `docs/huong-dan-backend.md`, `docs/huong-dan-ai.md`, `docs/system-architecture.md`, `docs/research-concept.md`, `README.md`.
- Backend build bằng **Maven** thay cho Gradle: lệnh `./mvnw verify`, codegen `jsonschema2pojo-maven-plugin` vào `target/generated-sources`, CI job `backend` chạy `./mvnw -B verify` khi có `backend/mvnw`; cập nhật docs, PR template, `.gitignore` (`target/`).
- Chia lại việc AI theo độ khó (plan v3.1): AI1 nhận việc khó (OCR production AI-26, chống SSRF AI-24b), AI2 nhận việc dữ liệu/KB/test (AI-08, AI-09, AI-11); tách AI-24 thành AI-24a/AI-24b; thêm cách AI1 kèm AI2 (`docs/implementation-plan.md`, `docs/huong-dan-ai.md`).
- Làm rõ quy tắc duyệt `contracts/` và `ai/src/askfirst/domain/`: "1 BE + 1 AI" là luật team, vì CODEOWNERS của GitHub chỉ cần 1 owner bất kỳ approve (`README.md`, `.github/CODEOWNERS`, plan §1, ARCH §8.1).
- Cắt scope MVP còn text + ảnh (OCR) + người thân + RAG cơ bản; **hoãn** voice/ASR, VLM fallback, multi-turn, Phase 2, Mini App; **bỏ pilot**, thay bằng test nội bộ ở W6.

### Added
- SH-01 (#3, G0): hợp đồng BE↔AI v1.0 trong `ai/src/askfirst/domain/` (`models.py`, `export.py`), `contracts/schema/*.json` sinh tự động, `StubPipeline` + `build_pipeline("stub")`, fixtures `contracts/fixtures/<op>/<case>.json`, test `ai/tests/contract/`, khung `ai/config/models.yaml` và `artifacts.yaml`.
- Quy ước code bằng tiếng Anh (README): đổi tên trong contract v1.0 sang tiếng Anh. Mức: `DANGEROUS`/`SUSPICIOUS`/`UNSURE`/`LOW_RISK`; `nhom`→`group`, `kich_ban`→`scenario`, `KHAC`→`OTHER`, `xung_ho`→`address_form` (`elder`/`aunt`/`uncle`); `risky_actions` dùng `transfer_money`, `share_otp`, `share_password`, `install_app`, `share_screen`, `click_link`, `keep_secret`, `share_id_card`. Cập nhật tài liệu liên quan; schema và fixtures sinh lại.
- `docs/ai-viec-nghien-cuu-phu.md`: hướng dẫn 3 việc nghiên cứu phụ cho AI2 (bộ dữ liệu OTP, bộ test mask PII, script đo độ dễ đọc), kèm quy tắc dữ liệu và tiêu chí xong.
- `docs/decisions/ADR-003-ocr-rapidocr.md`: chọn RapidOCR + sắp dòng theo cột + sửa đầu URL cho OCR local, **có điều kiện** (kiểm độc lập ≥ 90% chép đúng URL, đo Lambda ở AI-26); chưa qua Gate G2 (AI-20, issue #17).
- `eval/validate.py` và `eval/test_validate.py`: `python -m eval.validate` kiểm bộ eval JSONL theo plan §3.8 (SH-03): schema, enum, `source_url` bắt buộc với `real_scam`, trùng `id`/trùng nội dung (bỏ dấu, bỏ dấu câu). Chỉ **cảnh báo** (không làm đỏ) khi thiếu số lượng so với mốc v0 (40/20/20/20, 25 mẫu mỗi người; giả định chia theo tỉ lệ ARCH §6.11), mẫu gần trùng, hoặc còn số điện thoại/email thật; `--target v1` đổi mốc, `--strict` biến cảnh báo thành lỗi. Theo schema tiếng Anh của contract v1.0 (`group`, `scenario`, `DANGEROUS`…, `click_link`…); `author` là tên GitHub. Chỉ dùng thư viện chuẩn. Chưa chạy trên Windows/Linux.
- `eval/datasets/{real_scam,benign_suspicious,benign_normal,hard}.ai1.jsonl`: 25 mẫu eval v0 của thiendt1608 (SH-03, #5): 10 `real_scam` viết lại từ cảnh báo chính thức kèm `source_url`, 5 `benign_suspicious`, 5 `benign_normal`, 5 `hard`. **Do agent LLM soạn và gán nhãn theo quyết định họp team 23/10/2026, người đã duyệt lại**; phần lớn mẫu dựng từ mô tả bài (không có nguyên văn tin nhắn), mẫu lành tính dựng theo định dạng SMS, `notes` ghi nguồn gốc từng mẫu. Chưa gán nhãn độc lập bởi người thứ hai.
- `eval/ocr_benchmark.py` và `docs/results/ocr-spike.md`: script so 3 stack OCR local (RapidOCR, Tesseract, PaddleOCR; 10 cấu hình) trên 50 ảnh `ocr_images` theo CER, đúng dấu, chép đúng URL, độ trễ (AI-20); RapidOCR + sắp dòng theo cột là ứng viên cho ADR-003, chưa kiểm tra độc lập.
- `eval/assets/ocr/` và `eval/datasets/ocr_images.jsonl`: bộ 50 ảnh OCR (oi-001–050) và nhãn.
- `eval/test_ocr_postprocess.py`: test cho `repair_urls` (nối URL xuống dòng, dấu `.` thừa, `ttps:`) và `repair_phones` (`O`→`0` trong SĐT), gồm ca âm; các luật viết sau khi thấy lỗi, chưa kiểm tra độc lập.
- Đã thử chạy RapidOCR + sắp dòng theo cột trong Docker giả lập Lambda `ai` (3008 MB, 2 lõi): chạy được, khoảng 2 giây mỗi ảnh khi đặt số luồng onnxruntime bằng số lõi (thử 4 ảnh); chưa đo p95 và chưa đo trên Lambda thật (ghi ở `docs/results/ocr-spike.md`).
- `docs/features.md`: tổng quan tính năng (FR1–FR15, FR17) cho cả team: người dùng làm gì, bot phải làm gì, xong khi nào, task nào, thứ tự làm (chuỗi mốc, Blocked by, Ưu tiên P0/P1/P2).
- Board "Ask First: Sprint Board": issue cha cho từng FR và 3 nhóm việc (label `type: feature`), issue cho toàn bộ task MVP W1–W6 gắn làm sub-issue, quan hệ "Blocked by" theo cột "Cần trước" của PLAN, field **Ưu tiên**, view 🧩 Features, 👤 Việc của tôi, 🗓 Roadmap; Sprint Kanban và Danh Sách Nhiệm Vụ ẩn issue feature.
- Khung repo: `docs/`, `backend/`, `ai/`, `contracts/`, `infra/`.
- Tài liệu: kiến trúc (`docs/system-architecture.md`), kế hoạch (`docs/implementation-plan.md`), hướng dẫn cho team Backend và AI.
- `CODEOWNERS`, template PR, workflow CI khung (3 OS, job `contract`).
