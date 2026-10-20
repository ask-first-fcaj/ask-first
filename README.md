# Hỏi Trước (Ask First)

Bot Telegram giúp người cao tuổi kiểm tra tin nhắn, link, ảnh chụp màn hình, voice đáng ngờ **trước khi** làm theo. Người dùng chuyển tiếp nội dung cho bot; trong khoảng 15 giây bot trả lời bằng tiếng Việt dễ hiểu (4 mức: `DANGEROUS`, `SUSPICIOUS`, `UNSURE`, `LOW_RISK`, không có mức "an toàn"). Nguy cơ cao thì bot báo người thân đã đồng ý trước.

Dự án của team FCAJ (bootcamp AWS). Khung **6 tuần** (19/10–29/11/2026), team 4 người: BE1, BE2 (Java 21), AI1, AI2 (Python 3.12). Serverless trên AWS `ap-southeast-1` (Bedrock ở `ap-southeast-2`).

## Bắt đầu từ đâu

| Bạn là | Đọc |
|---|---|
| Backend (BE1, BE2) | [docs/huong-dan-backend.md](docs/huong-dan-backend.md) |
| AI (AI1, AI2) | [docs/huong-dan-ai.md](docs/huong-dan-ai.md) |
| Cần tra cứu sâu | [docs/system-architecture.md](docs/system-architecture.md) (ARCH), [docs/implementation-plan.md](docs/implementation-plan.md) (PLAN) |
| Cần bối cảnh sản phẩm, pháp lý | [docs/research-concept.md](docs/research-concept.md) |
| Cần tổng quan tính năng và thứ tự làm | [docs/features.md](docs/features.md) + [board](https://github.com/orgs/ask-first-fcaj/projects/1) (view 🧩 Features, 👤 Việc của tôi) |

Hai file `huong-dan-*` là bản dành cho người đọc; ARCH và PLAN là bản tra cứu đầy đủ. Mâu thuẫn thì ARCH/PLAN đúng, hãy báo để sửa.

## Cấu trúc repo

```
docs/        tài liệu, ADR ở docs/decisions/
backend/     Java 21, Maven (Lambda webhook, worker)           [BE]
ai/          Python 3.12, uv (Lambda ai)                       [AI]
contracts/   JSON Schema + fixtures (ranh giới BE <-> AI)      [BE + AI]
infra/       AWS SAM template                                  [BE]
kb/ prompts/ eval/ scripts/   (tạo khi tới việc tương ứng)
```

Hiện repo mới có khung và tài liệu; code sẽ thêm theo lịch trong PLAN (mục 5).

## Quy ước làm việc

- **Code viết bằng tiếng Anh**: tên biến, hàm, class, tên trường và giá trị enum trong JSON/contract, comment, docstring, thông báo lỗi và log. Không trộn tiếng Việt vào code. Chỉ nội dung hiển thị cho người dùng (tin nhắn bot, nút bấm, lệnh Telegram như `/bilua`) và dữ liệu mẫu tiếng Việt trong test/fixtures/eval được giữ tiếng Việt. Tài liệu trong `docs/` viết tiếng Việt, nhưng mọi tên code trích trong đó phải đúng như trong code (tiếng Anh).
- Nhánh ngắn (`feat/BE-22-webhook`), PR nhỏ (≤ 400 dòng nếu được), không push thẳng vào `main`.
- Conventional commits (`feat:`, `fix:`, `test:`, `docs:`...); thay đổi nhìn thấy được thì cập nhật [CHANGELOG.md](CHANGELOG.md).
- PR phải có ít nhất 1 người duyệt và CI xanh mới merge. `CODEOWNERS` tự gắn người duyệt. Sửa `contracts/` hoặc `ai/src/askfirst/domain/` cần **1 BE + 1 AI** approve: đây là **luật team**, GitHub chỉ ép 1 code owner bất kỳ, nên chưa đủ 1 BE + 1 AI thì **không bấm merge**.
- Mọi lệnh phải chạy được trên **Windows, macOS, Linux** (CI chạy cả 3).
- Không build nặng trên máy cá nhân (image `ai`, eval 300 mẫu): chạy trên GitHub Actions.
- **Không commit secret** (`.env`, bot token, key). Bot token và webhook secret để SSM Parameter Store; AWS deploy bằng OIDC, không lưu access key.
- Không log nội dung người dùng, `masked_text`, URL tải file Telegram, token.

## Lệnh thường dùng (khi code đã có)

```bash
# Backend
cd backend && ./mvnw verify            # Windows: mvnw.cmd verify

# AI
cd ai && uv run ruff check . && uv run pytest

# Sinh lại hợp đồng BE <-> AI (sau khi sửa ai/src/askfirst/domain/models.py)
cd ai && uv run python -m askfirst.domain.export
```

## CI

Workflow [.github/workflows/ci.yml](.github/workflows/ci.yml): job `backend` và `ai` chạy trên Ubuntu, Windows, macOS; job `contract` kiểm schema sinh lại không đổi. Job tổng hợp `ci-ok` là check bắt buộc của `main`. Khi `backend/` hoặc `ai/` chưa có code thật, các job tương ứng tự bỏ qua bước build.
