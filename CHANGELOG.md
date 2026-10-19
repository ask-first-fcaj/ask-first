# Changelog

Theo [Keep a Changelog](https://keepachangelog.com/vi/1.1.0/); commit theo [Conventional Commits](https://www.conventionalcommits.org/vi/v1.0.0/).
Chỉ ghi thay đổi người dùng hoặc team thấy được.

## [Unreleased]

### Changed
- Rút kế hoạch từ 12 xuống **6 tuần** (19/10–29/11/2026, v3): `docs/implementation-plan.md`, `docs/huong-dan-backend.md`, `docs/huong-dan-ai.md`, `docs/system-architecture.md`, `docs/research-concept.md`, `README.md`.
- Cắt scope MVP còn text + ảnh (OCR) + người thân + RAG cơ bản; **hoãn** voice/ASR, VLM fallback, multi-turn, Phase 2, Mini App; **bỏ pilot**, thay bằng test nội bộ ở W6.

### Added
- Khung repo: `docs/`, `backend/`, `ai/`, `contracts/`, `infra/`.
- Tài liệu: kiến trúc (`docs/system-architecture.md`), kế hoạch (`docs/implementation-plan.md`), hướng dẫn cho team Backend và AI.
- `CODEOWNERS`, template PR, workflow CI khung (3 OS, job `contract`).
