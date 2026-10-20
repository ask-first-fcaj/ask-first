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
- `docs/ai-viec-nghien-cuu-phu.md`: hướng dẫn 3 việc nghiên cứu phụ cho AI2 (bộ dữ liệu OTP, bộ test mask PII, script đo độ dễ đọc), kèm quy tắc dữ liệu và tiêu chí xong.
- `docs/features.md`: tổng quan tính năng (FR1–FR15, FR17) cho cả team: người dùng làm gì, bot phải làm gì, xong khi nào, task nào, thứ tự làm (chuỗi mốc, Blocked by, Ưu tiên P0/P1/P2).
- Board "Ask First: Sprint Board": issue cha cho từng FR và 3 nhóm việc (label `type: feature`), issue cho toàn bộ task MVP W1–W6 gắn làm sub-issue, quan hệ "Blocked by" theo cột "Cần trước" của PLAN, field **Ưu tiên**, view 🧩 Features, 👤 Việc của tôi, 🗓 Roadmap; Sprint Kanban và Danh Sách Nhiệm Vụ ẩn issue feature.
- Khung repo: `docs/`, `backend/`, `ai/`, `contracts/`, `infra/`.
- Tài liệu: kiến trúc (`docs/system-architecture.md`), kế hoạch (`docs/implementation-plan.md`), hướng dẫn cho team Backend và AI.
- `CODEOWNERS`, template PR, workflow CI khung (3 OS, job `contract`).
