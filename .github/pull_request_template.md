## Việc này làm gì
<!-- Mã việc (vd BE-22, AI-05) và 1–2 câu mô tả. -->

Liên quan: <!-- BE-xx / AI-xx / INT-xx -->

## Cách kiểm
<!-- Lệnh đã chạy, kết quả; hoặc cách test tay. Chưa kiểm cái gì thì ghi "not verified". -->

## Definition of Done
- [ ] Code + test; lệnh của phía mình pass (`./mvnw verify` hoặc `uv run ruff check . && uv run pytest`)
- [ ] Đạt điều kiện "Xong khi" của việc
- [ ] Đã deploy lên `dev` (nếu là việc BE hoặc INT)
- [ ] Cập nhật tài liệu liên quan (README, ADR, `CHANGELOG.md`)
- [ ] Chạy được trên cả Windows, macOS, Linux (CI xanh cả 3 OS)

## Nếu đụng `contracts/` hoặc `ai/src/askfirst/domain/`
- [ ] Đã sinh lại schema (`uv run python -m askfirst.domain.export`) và commit
- [ ] Đã sửa `StubPipeline`, fixtures, test contract ở CẢ HAI phía
- [ ] Có 1 người BE và 1 người AI duyệt

## Nếu đụng `prompts/`, `kb/`, `rules.yaml`, `ai/config/models.yaml`
- [ ] Eval gate pass (recall ≥ 90%, báo nhầm ≤ 10%)

## An toàn dữ liệu
- [ ] Không log nội dung người dùng, prompt, `masked_text`, URL tải file Telegram, token
- [ ] Không commit secret (`.env`, token, key)
