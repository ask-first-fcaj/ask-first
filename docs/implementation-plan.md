# Kế hoạch triển khai "Hỏi Trước" (Ask First)

> **Phiên bản:** v3.1 · **Ngày:** 19/10/2026 (v3.1: **chia lại việc AI1/AI2 theo độ khó**, xem "Thay đổi v3.1"; v3.0: **rút từ 12 xuống 6 tuần**, cắt scope MVP, bỏ pilot, gắn ngày thật, xem "Thay đổi v3" bên dưới; v2.1: chia lại việc 2 BE theo lát cắt dọc, §2.1; v2: backend chuyển sang **Java 21**, AI giữ **Python**; thêm Lambda `ai`, hợp đồng qua JSON Schema; xem "Thay đổi v2" bên dưới)
> **Dựa trên:** [system-architecture.md](system-architecture.md) v3.5 (gọi tắt là **ARCH**, ví dụ "ARCH §6.4"). Bối cảnh sản phẩm xem [research-concept.md](research-concept.md).
> **Khung thời gian:** **6 tuần** (bootcamp AWS FCAJ), từ **thứ Hai 19/10/2026** đến **Chủ nhật 29/11/2026**. W1 = 19–25/10 · W2 = 26/10–01/11 · W3 = 02–08/11 · W4 = 09–15/11 · W5 = 16–22/11 · W6 = 23–29/11. (Bản v1–v2.1 là 12 tuần, W1…W12.)
> **Team:** 4 người: **BE1**, **BE2** (Java), **AI1**, **AI2** (Python). FE làm theo kiểu vibe coding, ai rảnh tuần nào thì nhận.

**Thay đổi v3 (rút còn 6 tuần, 19/10/2026):**
- **Cắt scope MVP** còn: văn bản + ảnh (OCR local) + cảnh báo người thân, kèm `/nguoithan`, `/lichsu` và RAG cơ bản. **Hoãn** (làm nếu dư thời gian, đánh dấu ⏸): voice/ASR (AI-21, AI-28), VLM fallback (AI-27), multi-turn (BE-33, AI-12).
- **Bỏ pilot** (M5 cũ, 10–20 hộ, W8–W9) → **test nội bộ** (team + người nhà) ở W6. Sẽ **không** có số liệu người dùng thật ngoài team.
- **Hoãn hẳn** (ngoài 6 tuần): Phase 2 (§12) và Mini App (BE-32, FE-01…FE-07, INT-04).
- **Mốc dồn lại:** M2 → W4, M3 (chỉ RAG) và M4 FREEZE → W5, M5 test nội bộ và M6 demo → W6 (§4). W4–W5 là hai tuần nặng nhất (nén 2 tuần cũ thành 1); thứ tự cắt tiếp nếu trễ ở §9.
- Contract không đổi: `feature_voice` vẫn có trong `Flags` nhưng **tắt mặc định** trong MVP, webhook trả lời "chưa hỗ trợ voice". Image `ai` chưa cần ffmpeg/sherpa-onnx.

**Build backend dùng Maven (19/10/2026):** team BE chọn **Maven** (`pom.xml` + Maven Wrapper `mvnw`) thay cho Gradle. Lệnh chuẩn: `./mvnw verify` (Windows: `mvnw.cmd verify`); codegen bằng `jsonschema2pojo-maven-plugin` sinh vào `target/generated-sources`; test contract `./mvnw test -Dtest='**/contract/*'`; bot local `./mvnw exec:java@local-bot`.

**Thay đổi v3.1 (chia lại việc AI, 19/10/2026):** AI2 mới bắt đầu học AI nên việc **khó** (LLM, runtime Lambda, SSRF, OCR production, hiệu chỉnh ngưỡng) dồn về **AI1**; **AI2** nhận việc dữ liệu, KB, luật, viết test và các việc S rõ đầu ra, luôn có AI1 kèm.
- Chuyển AI1 → AI2: **AI-08** (Guardrails), **AI-09** (eval trong CI, cùng BE2), **AI-11** (LLM-as-judge). Chuyển AI2 → AI1: **AI-26** (OCR production; AI2 phụ phần `MODELS.md`, decode QR, regex sửa URL).
- **AI-24 tách đôi:** **AI-24a** (AI2: tách URL, allowlist, typosquat, punycode, `U_TEXT_LINK_MISMATCH`) và **AI-24b** (AI1: mở rộng shortener chống SSRF, RDAP, cache `URL#`).
- **AI-20**: AI2 dựng bộ ảnh và chạy đo, **AI1 cùng chốt ADR-003** (gate G2). **SH-08**: BE2 + AI2, AI1 duyệt câu chữ.
- **Cách kèm AI2:** mỗi việc AI2 bắt đầu bằng design note ≤ ½ trang để AI1 duyệt; pair 30 phút mỗi ngày; PR của AI2 nhỏ (≤ 300 dòng) và AI1 review mọi PR. Việc AI2 nằm trên đường găng của M1 (AI-22, AI-23) hạn **thứ Tư W3 (04/11)**; trễ thì AI1 nhận lại phần còn thiếu và đẩy AI-24b sang sau M4.

**Thay đổi v2 (theo ARCH v3.5):**
- `webhook`, `worker` (và `api` khi làm Mini App) viết bằng **Java 21** + Maven, gói zip, SnapStart. AI vẫn là **Python 3.12** nhưng chạy trong **Lambda riêng `ai`** (container image) thay vì là thư viện import trong worker.
- Interface `Pipeline` không còn gọi in-process từ BE. BE gọi `ai` bằng `InvokeFunction` với 3 `op` (`analyze`, `render_guardian_alerts`, `render`), JSON theo `contracts/schema/` sinh từ Pydantic (§3.3). `fingerprint` và cache `CACHE#` chuyển vào trong `analyze`.
- **Media do `ai` tự tải** (§3.6), BE chỉ truyền `file_id`. `ArtifactStore`, `KVCache` (S3, DynamoDB) do phía AI cài trong Lambda `ai`.
- Task bị ảnh hưởng: BE-02, BE-06, BE-08, BE-20…BE-27, AI-01, INT-01, G0, P2-04, P2-06; thêm **BE-05b** (codegen Java + `AiClient`) và **AI-03b** (runtime Lambda `ai`). Tổng khối lượng BE giảm nhẹ (không còn tải media, cache), AI tăng nhẹ (handler, tải media, S3/DynamoDB).

---

## 0. Cách đọc tài liệu này

- Mỗi việc có **mã** theo track: `SH-` (cả team), `BE-`, `AI-`, `FE-`, `INT-` (tích hợp), `P2-` (Phase 2).
- **Cỡ việc** (tính theo ngày làm tập trung, team tự quy đổi theo lịch học): **S** ≤ 1 ngày · **M** 2–3 ngày · **L** 4–5 ngày. Việc nào lớn hơn L phải tách nhỏ ra.
- Mỗi việc đều có cột **"Xong khi"**: điều kiện **kiểm được** (chạy test, xem số liệu, demo). Không đạt thì chưa được đánh dấu xong.
- **Gate (G1, G2…)** là các điểm phải ra quyết định. Qua gate rồi mới build tiếp phần phụ thuộc. Kết quả ghi vào `docs/decisions/` (mẫu ở §10).

---

## 1. Nguyên tắc để 3 track chạy song song

Mục tiêu: **AI không phải chờ BE.** (Từ ARCH v3.4, Mini App là MVP+ nên MVP không có track FE riêng; mọi tương tác nằm trong chat.) Cách làm:

1. **Contract-first.** Tuần 1 cả team chốt **hợp đồng dữ liệu** (§3): Pydantic model (xuất ra JSON Schema cho Java), format message SQS, format bộ eval, fixtures JSON cho từng `op`. Sau đó mỗi track code theo hợp đồng của mình.
2. **AI là một Lambda Python riêng, lõi là thư viện thuần.** Mọi code AI nằm trong `ai/src/askfirst/` (`pipeline/`, `local_models/`, `llm/`; phần chạm AWS gom ở `handler.py`, `media/`, `cache/`, `artifacts/`). Lõi `pipeline/` **không import** `handler`, `media`, `cache`, `artifacts`. AI chạy và test được trên laptop bằng CLI (`uv run python -m askfirst.cli analyze …`) và bằng bộ eval, **không cần AWS** (trừ lúc gọi Bedrock), và **không cần Java**.
3. **BE dùng `ai` bản stub cho tới khi AI xong.** Từ tuần 2, Lambda `ai` deploy với `PIPELINE_IMPL=stub` (trả kết quả giả theo từ khoá, §3.5), nên luồng Telegram → Java worker → `ai` → trả lời chạy được end-to-end từ sớm. Khi AI xong thì đổi `PIPELINE_IMPL=real`, Java không đổi gì. Test Java dùng `FakeAiClient` đọc `contracts/fixtures/`, không cần Python.
4. **(MVP+) Nếu làm Mini App:** FE dùng mock API sinh từ `contracts/openapi.yaml` (MSW hoặc json-server), lúc ghép API thật chỉ đổi base URL.
5. **Tích hợp theo lát cắt dọc (vertical slice).** Mỗi mốc ghép một loại input chạy thật từ đầu đến cuối: văn bản (W3) → ảnh + người thân (W4) → RAG (W5); voice ⏸ hoãn. Không ghép một lần vào cuối.
6. **Feature flag:** `PIPELINE_IMPL` là biến môi trường (§3.5). Các cờ bật tắt lúc chạy nằm ở DynamoDB `CONFIG#global/FLAGS` (ARCH §5.8): `llm_enabled`, `feature_image`, `feature_voice`, `feature_rag` được truyền cho AI qua `Flags` (§3.3); `feature_guardian` chỉ BE đọc. Tính năng chưa xong vẫn merge được nhưng tắt.
7. **CODEOWNERS:** theo bảng "Thư mục theo owner" ở §3.1. File trong `ai/src/askfirst/domain/`, `contracts/`, `ai/tests/contract/` và `backend/src/test/**/contract/` thì **phải có 1 người BE và 1 người AI cùng duyệt**. Lưu ý: GitHub chỉ cần **1** code owner bất kỳ approve, nên "1 BE + 1 AI" là **luật team**: người approve thứ nhất không merge mà chờ đủ 1 BE + 1 AI, người merge tự kiểm (GitHub docs: *"an approval from any of the owners is sufficient"*).

```
            W1        W2        W3        W4        W5        W6
BE   ── setup ─ webhook/SQS/stub ─ E2E text ─ ảnh + guardian ─ lệnh chat/obs/harden ─ prod/demo
AI   ── spikes ─ G1/G2 ─ rules+judge ─ URL+OCR+CI ─ RAG/calib/red-team ─ báo cáo cuối
FE                         policy page (trang tĩnh, MVP) · Mini App: ⏸ hoãn
           ▲G0 contracts ▲G1,G2   ▲M1 text   ▲M2 ảnh    ▲M3 RAG, M4 FREEZE ▲M5 test nội bộ, M6 demo
```

---

## 2. Vai trò

| Người | Vai trò chính | Sở hữu (ARCH) | Hỗ trợ |
|---|---|---|---|
| **BE1**: Lane A, luồng kiểm tra tin (Java) | Code **và** hạ tầng của đường chậm: `webhook` (phần nhận tin, dedup, enqueue), SQS/DLQ → `worker`, gom tin, `AiClient` + codegen từ JSON Schema, Dockerfile/SAM/IAM của Lambda `ai`, kill switch, multi-turn, **gửi cảnh báo người thân** (🔀), metric + dashboard | ARCH §4.5, §5.1, §5.2, §5.5, §5.8, §6.12, §10, §11 | Đóng gói model local vào image (cùng AI2), eval trong CI (cùng AI1); pair của BE2 |
| **BE2**: Lane B, người dùng, người thân, dữ liệu (Java) | Code **và** hạ tầng của đường nhanh và dữ liệu người dùng: Telegram adapter, nền DynamoDB + repos, onboarding/consent, rate limit, liên kết người thân, `/nguoithan`, `/lichsu`, feedback, xoá dữ liệu (🔀), **CI/CD (OIDC) + stage `prod`**, alarm + runbook cho cả hệ thống (🔀) | ARCH §2, §5.3, §5.4, §5.6, §5.7, §5.9, §7, §8.2, §9.1 | Copy tin nhắn bot (cùng AI1), nghiên cứu người dùng; pair của BE1 |
| **AI1**: LLM, Eval & phần khó (Python) | Bộ eval, Bedrock client, prompt Judge, Fuse, Respond, cascade, hiệu chỉnh ngưỡng, **`handler.py` của Lambda `ai`** (dispatcher theo `op`, cache `CACHE#`), xuất JSON Schema, **OCR production** (AI-26), **chống SSRF khi mở shortener** (AI-24b); **kèm AI2** (duyệt design note, review mọi PR) | ARCH §4.5, §6.3, §6.4 (SSRF), §6.6–§6.12 | Benchmark model, chất lượng tiếng Việt, cùng chốt G2 |
| **AI2**: Dữ liệu, KB & Perception cơ bản (Python, đang học, AI1 kèm) | Spike OCR (dựng bộ ảnh, chạy đo), mask PII, chuẩn hoá + luật, URL intel phần cơ bản (AI-24a), embedding/RAG, dữ liệu KB (taxonomy, allowlist, hotline), Guardrails, eval trong CI (cùng BE2), LLM-as-judge, red-team, **tải media từ Telegram** (`media/`), `ArtifactStore` S3 | ARCH §6.0, §6.3–§6.5, §9.2 | Bộ ảnh test, xác minh hotline, copy tin nhắn bot |
| **FE** | MVP: chỉ trang chính sách tĩnh (FE-00). Mini App là MVP+ (§6.4), ⏸ hoãn ngoài 6 tuần | ARCH §13 | — |

Ai cũng: viết mẫu cho bộ eval, phỏng vấn người dùng, review chéo PR, trực hệ thống trong thời gian test nội bộ (W6).

### 2.1 Hai BE làm chung thế nào (không chia "người làm hạ tầng" và "người làm code")

Bản v2 chia BE1 = platform/DevOps, BE2 = code tính năng nên lệch: BE2 khoảng 34,5 ngày công, phần lớn là code Java; BE1 khoảng 27 ngày, phần lớn là hạ tầng (cùng cách quy đổi ở bảng dưới); hai người ít khi phải đọc code của nhau. Bản này đổi sang:

1. **Chia theo lát cắt dọc, không theo tầng.** Mỗi lane là một nửa sản phẩm chạy được. Người sở hữu lane tự viết Java, SAM/IAM, test, metric và runbook cho phần mình. Việc hạ tầng chung chia đôi: BE1 lo SQS, SnapStart, image `ai`, dashboard; BE2 lo DynamoDB, CI/CD, stage `prod`, alarm.
2. **Owner + pair.** Người BE còn lại là pair của mọi việc BE: (a) đọc **design note ≤ 1 trang** trong issue trước khi code (15–30 phút: interface, item DynamoDB đụng tới, IAM cần thêm); (b) review mọi PR của việc đó; (c) làm thay khi owner bận. Việc 🔀 thì pair **bắt buộc** ngồi cùng ít nhất một buổi.
3. **W1 làm chung:** BE-01, BE-02, BE-03, BE-14 ngồi cùng nhau (pair programming), để từ ngày đầu cả hai cùng biết SAM, Maven, Testcontainers, cách deploy và rollback.
4. **Việc chéo (🔀)**, mỗi người 4 việc: BE1 làm BE-29 (cảnh báo người thân, dùng dữ liệu lane B) và BE-09a (rà bảo mật lane B); BE2 làm BE-30, BE-31 (đụng `CHECK#`, `SESSION#`, `donated/` của lane A), BE-08b (alarm cho cả hệ thống), BE-09b (rà bảo mật lane A và `ai`) và dẫn INT-03.
5. **Người trực tuần (xoay vòng từ W2):** tuần chẵn BE1, tuần lẻ BE2. Người trực deploy `dev` (từ W6 thêm `prod`), chạy smoke test 15 tin (§7), xem dashboard, xử lý DLQ, trả lời câu hỏi deploy của AI. Ai cũng phải deploy được một mình.
6. **Demo chéo thứ Sáu:** BE1 demo tính năng BE2 làm trong tuần và ngược lại (5 phút mỗi người). Không demo được thì coi như pair chưa review kỹ.
7. **Ranh giới code** (ít đụng file của nhau nhưng vẫn đọc được): `vn.askfirst.webhook`, `vn.askfirst.check` (worker, buffer, `AiClient`, gửi cảnh báo) do BE1; `vn.askfirst.telegram`, `vn.askfirst.user`, `vn.askfirst.guardian` do BE2; `vn.askfirst.store` thì BE2 viết nền, mỗi lane thêm repo của mình; `vn.askfirst.common` cần cả hai duyệt. Webhook (BE1) chỉ định tuyến; handler của lệnh và callback nằm ở package của BE2.

**Khối lượng ước tính** (quy đổi S = 1, M = 2,5, L = 4,5 ngày theo §0; chỉ là ước lượng để so hai người, không phải số đo):

| | BE1 | BE2 |
|---|---|---|
| Việc của lane (MVP, không tính BE-12 và BE-32) | 15 việc ≈ 29,5 ngày | 14 việc ≈ 28 ngày |
| Việc làm chung W1 (BE-01, BE-02, BE-03, BE-14) | ≈ 5,5 ngày (cả hai cùng ngồi) | ≈ 5,5 ngày (cả hai cùng ngồi) |
| Dẫn tích hợp và việc SH | INT-01, INT-02 | INT-03, SH-06 (S), FE-00 (S) ≈ +2 ngày |
| Việc nặng về hạ tầng | BE-05, BE-06, BE-07, BE-08a, BE-10 (≈ 12 ngày) | BE-04, BE-08b, BE-09b, BE-11 (≈ 7 ngày) |
| Việc nặng về code Java | BE-05b, BE-22, BE-24b, BE-27, BE-23, BE-13, BE-34, BE-29, BE-33 | BE-20, BE-21, BE-24, BE-25, BE-26, BE-35, BE-28, BE-30, BE-31, BE-36 |

**Checklist "ai cũng làm được"** (kiểm ở W5, trước M4). Mỗi BE tự làm một mình, không hỏi người kia: (1) `sam deploy` lên `dev` rồi rollback về version trước; (2) đổi `ai` về `PIPELINE_IMPL=stub` rồi bật lại `real`; (3) lấy một message trong DLQ, tìm `trace_id` trong log, xử lý xong; (4) thêm một lệnh chat đơn giản (ví dụ `/ping`) từ adapter đến trả lời, có test; (5) giải thích luồng §3.2 và bảng DynamoDB ARCH §7 cho AI1 hoặc AI2. Chưa đạt mục nào thì người kia kèm trong W7.

---

## 3. Hợp đồng dùng chung BE ↔ AI (contract v1.0, đã chốt)

> **Đọc phần này trước khi code.** Mọi thứ ở đây là bắt buộc. Muốn đổi thì mở PR sửa `ai/src/askfirst/domain/models.py` (rồi chạy `uv run python -m askfirst.domain.export` trong `ai/` để sinh lại `contracts/schema/`) hoặc file liên quan, **cần 1 người BE và 1 người AI duyệt**, và tăng `CONTRACT_VERSION` (đổi phá vỡ tương thích thì tăng số đầu, cập nhật `StubPipeline`, fixtures và test contract **cả hai phía** trong cùng PR).
> Code Python ở §3.3 và §3.9 **đã chạy thử** ngày 15/10/2026 với Python 3.12.12 + pydantic 2.14.1 (20 test pass: tạo đối tượng, JSON round-trip, wire của 3 `op`, các ca phải báo lỗi, xuất JSON Schema không còn `anyOf`/`null`) và qua `ruff 0.17` (line-length 140). Copy nguyên văn vào repo. **Chưa chạy:** codegen Java (jsonschema2pojo) trên schema này và mã giả Java ở §3.2; BE-05b phải xác nhận ở W1 ⚠️.

### 3.1 Quy ước kỹ thuật đã chốt

| Mục | Quy ước |
|---|---|
| Ngôn ngữ, thư viện | **BE:** Java 21 (LTS), Maven (`pom.xml` + Maven Wrapper `mvnw`), AWS SDK for Java v2, Jackson, Powertools for AWS Lambda (Java), JUnit 5 + AssertJ, Testcontainers (DynamoDB Local), Spotless (google-java-format) · **AI:** Python 3.12, pydantic 2, `uv` (quản lý môi trường), `ruff` (lint + format, line-length 140), `pytest` |
| Cấu trúc repo | Monorepo (ARCH §8.1): `backend/` (Java, package gốc `vn.askfirst`), `ai/` (Python, package `askfirst` ở `ai/src/askfirst/`), `contracts/` (schema + fixtures), `infra/`, `kb/`, `prompts/`, `eval/`, `scripts/`. Trong plan, `src/<x>/` của phía AI nghĩa là `ai/src/askfirst/<x>/` |
| Thư mục theo owner | **AI:** `ai/` (trừ `ai/Dockerfile`), `kb/`, `prompts/`, `eval/`, `ai/config/models.yaml`, `ai/config/artifacts.yaml` · **BE:** `backend/`, `infra/`, `scripts/`, `ai/Dockerfile` · **Chung (1 BE + 1 AI duyệt):** `ai/src/askfirst/domain/`, `contracts/`, `ai/tests/contract/`, `backend/src/test/**/contract/` |
| Luật import (Python) | `pipeline/`, `local_models/`, `llm/` **chỉ được import** `askfirst.domain` và thư viện ngoài; **không** import `handler`, `media/`, `cache/`, `artifacts/` (phần chạm AWS). CI kiểm bằng `ruff` rule `TID251` (banned-api) hoặc một test grep đơn giản |
| Class hợp đồng (Java) | **Sinh** từ `contracts/schema/*.json` bằng jsonschema2pojo vào `backend/target/generated-sources/` (không commit, không sửa tay), package `vn.askfirst.contract`. Jackson: `PropertyNamingStrategies.SNAKE_CASE`, `FAIL_ON_UNKNOWN_PROPERTIES=false`, `JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS=false` |
| ID | `user_id` = `"tg:<telegram_user_id>"`. `job_id` = `check_id` = **ULID** (26 ký tự) do webhook sinh khi đẩy job; `alert_id` cũng là ULID. `check_id` dùng luôn làm `trace_id` trong log, ở cả Java lẫn Python |
| Thời gian | Lưu và truyền **UTC**, ISO 8601 có `Z` (Java `Instant`, Python `datetime` có tz). Chỉ lúc render cho người đọc mới đổi sang giờ Việt Nam (`Asia/Ho_Chi_Minh`) |
| Log | Logger JSON (Powertools Java ở BE, Powertools Python ở `ai`), luôn có `trace_id`. **Cấm log:** request/response của `ai`, `Bundle`, mọi text của người dùng, `masked_text`, `Signal.evidence`, `explanation`, prompt, response của LLM, bytes media, URL tải file Telegram, token. Chỉ log mã, số, thời gian |
| Metric | **AI không tự đẩy metric nghiệp vụ.** AI trả số đo trong `Verdict.latency_ms` và `cost_usd_est`; worker (Java) đẩy toàn bộ metric EMF ở một chỗ (ARCH §11). `ai` chỉ có metric runtime mặc định của Lambda (duration, errors, throttles) |
| Text gửi Telegram | Luôn `parse_mode=HTML` và tắt link preview. Text từ `render` (AI) đã escape và defang sẵn, BE gửi nguyên văn. Text BE tự soạn (onboarding, lệnh) phải escape HTML cho mọi giá trị động (template engine escape mặc định, hoặc `HtmlUtils`/hàm `Html.escape` tự viết có test với `<`, `>`, `&`) |
| Template | `kb/templates/verdict/*.j2` và `kb/templates/guardian/*.j2` (Jinja2) do AI viết; `kb/templates/bot/*.mustache` (chào, đồng ý, lệnh, lỗi) do BE viết, render bằng JMustache (escape HTML mặc định với `{{x}}`) 💡. Câu chữ duyệt theo SH-08 |

### 3.2 Ranh giới `worker` (Java) ↔ `ai` (Python): ai làm bước nào

`worker` là code **Java** của BE; nó gọi Lambda `ai` (Python, của AI) bằng `InvokeFunction` đồng bộ với 3 `op` (ARCH §4.5). Đây là luồng chuẩn, BE cài theo đúng thứ tự này (BE-27, BE-23, BE-29):

```java
// backend/src/main/java/vn/askfirst/handler/WorkerHandler.java (BE). Mã giả, không phải code chạy được.
// AnalyzeRequest, AiResponse, Verdict…: class sinh từ contracts/schema (§3.3), không viết tay.
void handleAnalyze(AnalyzeJob job, Context lambdaCtx) {
    Buffer buf = store.getBuffer(job.chatId());
    if (buf == null || job.seq() < buf.maxSeq()) return;                 // còn tin mới hơn: job cuối xử lý cả cụm (ARCH §5.2)
    List<InboundMessage> items = buf.itemsAfter(buf.doneSeq());           // chỉ metadata + file_id, KHÔNG có bytes media
    Flags flags = store.getFlags();                                       // CONFIG#global/FLAGS, cache 30 giây
    var ctx = new AnalyzeContext(job.jobId(), job.userId(), flags,
                                 Math.min(85.0, lambdaCtx.getRemainingTimeInMillis() / 1000.0 - 20));

    AiResponse res = ai.call(new AnalyzeRequest(items, /*session*/ null, ctx));   // AI: tải media, cache, pipeline
    if (res.getError() != null) { handleAiError(job, buf, res.getError()); return; }   // retryable → throw (SQS retry); fatal → câu suy giảm
    Verdict verdict = res.getAnalyze().getVerdict();

    List<AlertResult> alerts = new ArrayList<>();
    List<Guardian> guardians = store.activeGuardians(job.userId());      // status=active và consent.purposes.guardian = true
    if (verdict.getShouldAlert() && !guardians.isEmpty() && store.lockAlert(job.userId(), verdict.getKichBan())) {  // ALERTLOCK 30'
        List<GuardianAlertContext> gctx = guardians.stream()
            .map(g -> new GuardianAlertContext(Ulid.next(), profile.displayName(), g.shareLevel(), Instant.now())).toList();
        List<OutMessage> outs = ai.call(new RenderGuardianAlertsRequest(verdict, gctx))   // AI, cùng thứ tự
                                  .getRenderGuardianAlerts().getAlerts();
        for (int i = 0; i < guardians.size(); i++) {                     // gửi song song cho mọi người thân (virtual threads)
            boolean ok = telegram.send(guardians.get(i).chatId(), outs.get(i)).isPresent();   // true khi có message_id
            alerts.add(new AlertResult(guardians.get(i).name(), ok ? "sent" : "failed"));
            store.putAlert(job.userId(), gctx.get(i).getAlertId(), guardians.get(i), ok);
        }
        if (alerts.stream().anyMatch(a -> a.getStatus().equals("sent"))) sqs.send(new FollowupJob(...), 900);
    }

    ReplyPlan plan = ai.call(new RenderRequest(verdict, new RenderContext(                    // AI
        job.jobId(), profile.xungHo(), !guardians.isEmpty(), alerts, !flags.getLlmEnabled()))).getRender();
    telegram.editOrSend(job.chatId(), buf.placeholderId(), plan.getMessages().get(0));       // sửa tin "⏳"
    plan.getMessages().stream().skip(1).forEach(m -> telegram.send(job.chatId(), m));

    store.putCheck(job.userId(), job.jobId(), items, verdict, alerts);   // §3.7, không có nội dung
    store.putSession(job.chatId(), job.jobId(), verdict);                // SESSION#: masked_text + followup_question, TTL 30 phút
    store.finishBuffer(job.chatId(), job.seq());                         // xoá có điều kiện, sai thì ghi done_seq (ARCH §5.2)
}
```

`AiClient.call(...)`: serialize bằng Jackson (`SNAKE_CASE`, `FAIL_ON_UNKNOWN_PROPERTIES=false`), `InvokeFunction` vào alias `live` của `ai`, read timeout 95 giây, **không retry trong SDK** (retry đi qua SQS). `FunctionError` hoặc response không parse được thì coi như `AiError(kind="retryable", code="invoke")`. Log `ai_invoke_ms{op}` và `trace_id`, không log body.

Việc **webhook** (BE, Java) làm trước khi có job, AI không cần biết: kiểm secret, dedup, consent (kể cả `voice`), rate limit, đầu vào không hỗ trợ (ARCH §5.7), giới hạn voice ≤ 120 giây và file ≤ 20 MB, cờ `feature_image`/`feature_voice` (tắt thì trả lời "chưa hỗ trợ" ngay), lệnh và callback.

**Việc AI tự đảm bảo trong `ai`** (`handler.py` + `analyze`): (1) tự tải media từ Telegram vào RAM theo §3.6, không log URL tải; (2) tự tính `fingerprint`, đọc/ghi `CACHE#` (bản `for_cache()`, TTL 24 giờ), trả `cache_hit`; (3) `flags.llm_enabled == False` thì không gọi Bedrock, trả `path="rules_only"`; (4) `session.turn == 2` thì `followup_question` phải là `None`; (5) **không bao giờ raise ra ngoài handler**: lỗi model/mạng tự suy giảm về `rules_only`; lỗi tải Telegram, throttle → `AiError("retryable")`; request sai schema hoặc `v` lạ → `AiError("fatal")`.

### 3.3 Domain model: `ai/src/askfirst/domain/models.py` (copy nguyên văn; nguồn của `contracts/schema/`)

```python
"""askfirst.domain.models: BE (Java) <-> AI (Python) contract, plan §3.2.

Single source of truth: `uv run python -m askfirst.domain.export` writes the WIRE_MODELS to contracts/schema/*.json, and Java generates its classes from them.
Changing this file needs 1 BE + 1 AI review.
"""
from __future__ import annotations

import json
import pathlib
from datetime import datetime
from typing import Any, Literal, Protocol

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.json_schema import GenerateJsonSchema

CONTRACT_VERSION = "1.0"

Level = Literal["DANGEROUS", "SUSPICIOUS", "UNSURE", "LOW_RISK"]
LEVEL_ORDER: dict[str, int] = {"LOW_RISK": 0, "UNSURE": 1, "SUSPICIOUS": 2, "DANGEROUS": 3}
Group = Literal["N0", "N1", "N2", "N3", "N4", "N5", "N6", "OTHER"]
RiskyAction = Literal[
    "transfer_money", "share_otp", "share_password", "install_app",
    "share_screen", "click_link", "keep_secret", "share_id_card",
]
Path = Literal["cache", "fast", "llm", "escalated", "rules_only"]
Outcome = Literal["verdict", "smalltalk", "unreadable"]
ForwardOrigin = Literal["user", "hidden_user", "chat", "channel"]
AddressForm = Literal["elder", "aunt", "uncle"]
Answer = Literal["yes", "no", "unknown"]


class _M(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


# ---------- Input: created by BE (Java), sent over the wire ----------

class EntityRef(_M):
    kind: Literal["url", "text_link"]
    offset: int                      # in UTF-16 code units, same as Telegram
    length: int
    url: str | None = None           # required when kind == "text_link" (the real URL, not the displayed text)

    @model_validator(mode="after")
    def _text_link_has_url(self) -> EntityRef:
        if self.kind == "text_link" and not self.url:
            raise ValueError("text_link requires url")
        return self


class MediaRef(_M):
    file_id: str
    file_unique_id: str
    mime: str | None = None
    size: int | None = None          # bytes
    duration: int | None = None      # seconds, voice/audio only
    file_name: str | None = None     # document only


class InboundMessage(_M):
    """Built by the adapter from one update. Kept temporarily in BUFFER# (≤ 10 minutes, deleted right after processing)."""
    channel: Literal["telegram", "zalo"]
    update_id: str
    chat_id: str
    user_id: str                     # "tg:<telegram_user_id>"
    message_id: str
    kind: Literal["text", "photo", "voice", "audio", "document"]
    text: str | None = None          # text or caption, truncated at 4,000 characters
    entities: list[EntityRef] = Field(default_factory=list)
    media: MediaRef | None = None
    forward_origin: ForwardOrigin | None = None
    media_group_id: str | None = None
    received_at: datetime            # UTC


# ---------- AI-internal: NOT sent over the wire (Lambda `ai` builds these from InboundMessage, plan §3.6) ----------

class TextPart(_M):
    text: str
    entities: list[EntityRef] = Field(default_factory=list)   # offset within this text


class ImageInput(_M):
    data: bytes
    file_unique_id: str
    mime: str


class AudioInput(_M):
    data: bytes
    file_unique_id: str
    mime: str                        # "audio/ogg" (Telegram voice), "audio/mpeg"…
    duration_s: int


class SessionContext(_M):
    """Follow-up turn (ARCH §6.12). BE reads it from SESSION# and sends it in AnalyzeRequest; AI merges it into the Judge input. Goes over the wire."""
    prev_check_id: str
    masked_text: str                 # = Verdict.masked_text of the previous turn
    question: str                    # = Verdict.followup_question of the previous turn
    answer: Answer
    turn: int = Field(ge=1, le=2)


class Bundle(_M):
    """Pipeline input: the whole batch of messages (ARCH §5.2), with media already downloaded into the RAM of Lambda `ai`."""
    texts: list[TextPart] = Field(default_factory=list)
    images: list[ImageInput] = Field(default_factory=list)
    audios: list[AudioInput] = Field(default_factory=list)
    forward_origin: ForwardOrigin | None = None   # from the first message that has a forward
    session: SessionContext | None = None


class Flags(_M):
    """BE reads these from DynamoDB CONFIG#global/FLAGS (ARCH §5.8), caches them for 30 seconds, and sends them in AnalyzeContext."""
    llm_enabled: bool = True
    feature_image: bool = True
    feature_voice: bool = True
    feature_rag: bool = True


class AnalyzeContext(_M):
    check_id: str                    # ULID generated by BE (= job_id); also the trace_id in logs
    user_id: str
    flags: Flags
    deadline_s: float                # seconds BE can still wait (≤ the `ai` timeout); the pipeline must return before deadline_s - 5


# ---------- Output of analyze: created by AI, received by BE over the wire ----------

class Signal(_M):
    code: str                        # code from kb/rules.yaml, e.g. "R_OTP_REQUEST", "U_TYPOSQUAT"
    weight: int
    hard: bool = False
    evidence: str | None = None      # masked excerpt, ≤ 120 characters; MUST NOT be logged


class Citation(_M):
    title: str
    url: str
    publisher: str
    date: str                        # "YYYY-MM-DD"


class Verdict(_M):
    outcome: Outcome                 # smalltalk → level LOW_RISK; unreadable → level UNSURE
    level: Level
    score: int = Field(ge=0, le=100)
    group: Group
    scenario: str                    # taxonomy code, e.g. "N1.4"; "OTHER" if nothing matches
    signals: list[Signal] = Field(default_factory=list)
    risky_actions: list[RiskyAction] = Field(default_factory=list)
    should_alert: bool               # level == DANGEROUS and risky_actions is non-empty; BE must still check guardians, consent, ALERTLOCK
    explanation: str = ""            # post-checked, NOT HTML-escaped (render does the escaping)
    citations: list[Citation] = Field(default_factory=list)
    followup_question: str | None = None
    masked_text: str = ""            # masked input; ONLY for SESSION# (30 minutes) and donated/ with user consent; MUST NOT be logged
    path: Path
    model_ids: list[str] = Field(default_factory=list)
    versions: dict[str, str] = Field(default_factory=dict)   # keys: prompt, rules, kb, local_models, contract
    latency_ms: dict[str, int] = Field(default_factory=dict) # per stage, e.g. {"ocr": 1800, "judge": 2400}
    cost_usd_est: float = 0.0

    def for_cache(self) -> Verdict:
        """Copy to store in CACHE# (24 hours): drops everything that contains text taken from the input."""
        return self.model_copy(update={
            "masked_text": "", "explanation": "", "followup_question": None,
            "signals": [s.model_copy(update={"evidence": None}) for s in self.signals],
        })


# ---------- Output of render: created by AI, sent by BE ----------

ButtonKind = Literal[
    "checklist",        # BE: callback "cl"           → sends the /bilua checklist
    "call_guardian",    # BE: callback "cg:<check_id>" → sendContact for the guardian
    "alert_guardian",   # BE: callback "ag:<check_id>" → the elder alerts the guardian themself (level SUSPICIOUS)
    "report_wrong",     # BE: callback "fb:wrong:<check_id>"
    "donate",           # BE: callback "dn:<check_id>" → asks for consent, then stores masked_text in donated/
    "answer_yes", "answer_no", "answer_unknown",   # BE: callback "fu:<check_id>:y|n|u"
    "ack",              # BE: callback "ack:<alert_id>" (guardian alert message)
]


class Button(_M):
    kind: ButtonKind
    label: str                       # display text, written by AI


class OutMessage(_M):
    text_html: str                   # HTML-escaped and URL-defanged; BE sends with parse_mode=HTML and always disables link preview
    buttons: list[list[Button]] = Field(default_factory=list)   # list of button rows
    silent: bool = False             # True → disable_notification


class ReplyPlan(_M):
    messages: list[OutMessage] = Field(min_length=1)   # [0] = the conclusion, edited into the "⏳" message; the rest are sent as new messages


class AlertResult(_M):
    guardian_name: str
    status: Literal["sent", "failed"]   # "sent" only when Telegram returned a message_id


class RenderContext(_M):
    check_id: str
    address_form: AddressForm
    has_guardian: bool               # at least one active guardian
    alerts: list[AlertResult] = Field(default_factory=list)   # empty if no alert was sent
    maintenance: bool = False        # = not flags.llm_enabled


class GuardianAlertContext(_M):
    alert_id: str                    # ULID
    elder_name: str                  # display name on Telegram, not escaped
    share_level: Literal[1, 2, 3]
    at: datetime                     # UTC; render shows Vietnam time


# ---------- Wire: Lambda `ai` (the Java worker calls it with InvokeFunction, ARCH §4.5) ----------

class AnalyzeRequest(_M):
    v: str = CONTRACT_VERSION
    op: Literal["analyze"] = "analyze"
    items: list[InboundMessage] = Field(default_factory=list)   # metadata + file_id only; `ai` downloads the media itself
    session: SessionContext | None = None                       # "answer" job: items empty, session present
    ctx: AnalyzeContext


class AnalyzeResult(_M):
    verdict: Verdict
    cache_hit: bool


class RenderGuardianAlertsRequest(_M):
    v: str = CONTRACT_VERSION
    op: Literal["render_guardian_alerts"] = "render_guardian_alerts"
    verdict: Verdict
    guardians: list[GuardianAlertContext] = Field(min_length=1)


class RenderGuardianAlertsResult(_M):
    alerts: list[OutMessage]                                    # same order as request.guardians


class RenderRequest(_M):
    v: str = CONTRACT_VERSION
    op: Literal["render"] = "render"
    verdict: Verdict
    ctx: RenderContext


class AiError(_M):
    kind: Literal["retryable", "fatal"]   # retryable: BE lets SQS retry; fatal: bad input/bug, BE sends the degraded reply, no retry
    code: str                             # e.g. "bad_request", "unsupported_version", "telegram_download", "deadline"


class AiResponse(_M):
    """Every op returns exactly one of the result fields, or error. No union, to keep the Java codegen simple."""
    v: str = CONTRACT_VERSION
    op: Literal["analyze", "render_guardian_alerts", "render"]
    analyze: AnalyzeResult | None = None
    render_guardian_alerts: RenderGuardianAlertsResult | None = None
    render: ReplyPlan | None = None
    error: AiError | None = None

    @model_validator(mode="after")
    def _exactly_one(self) -> AiResponse:
        filled = [k for k in ("analyze", "render_guardian_alerts", "render", "error") if getattr(self, k) is not None]
        if len(filled) != 1 or (filled[0] != "error" and filled[0] != self.op):
            raise ValueError(f"op={self.op} must have exactly one result with the same name, or error; got {filled}")
        return self


WIRE_MODELS: tuple[type[BaseModel], ...] = (
    AnalyzeRequest, RenderGuardianAlertsRequest, RenderRequest, AiResponse,
    InboundMessage,                                             # BE also stores it as JSON in BUFFER#
)


class _JavaFriendlySchema(GenerateJsonSchema):
    """`X | None` → just X (the field is not in `required`), so the Java codegen emits a nullable field instead of anyOf."""

    def nullable_schema(self, schema: Any) -> dict[str, Any]:
        return self.generate_inner(schema["schema"])


def export_json_schemas(out_dir: pathlib.Path) -> list[pathlib.Path]:
    """Called from askfirst.domain.export: writes contracts/schema/<Model>.json. CI reruns it, then `git diff --exit-code contracts/`."""
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for model in WIRE_MODELS:
        schema = model.model_json_schema(schema_generator=_JavaFriendlySchema, ref_template="#/definitions/{model}")
        if "$defs" in schema:
            schema["definitions"] = schema.pop("$defs")
        schema["$id"] = f"askfirst/{CONTRACT_VERSION}/{model.__name__}.json"
        path = out_dir / f"{model.__name__}.json"
        path.write_text(json.dumps(schema, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        written.append(path)
    return written


# ---------- Python-internal interfaces (not sent over the wire) ----------

class KVCache(Protocol):
    """Lambda `ai` implements it with DynamoDB (URL#…, CACHE#…); the CLI and eval use an in-RAM version."""
    def get(self, key: str) -> dict | None: ...
    def put(self, key: str, value: dict, ttl_s: int) -> None: ...


class ArtifactStore(Protocol):
    """Returns the local directory holding an artifact (model weights, RAG index) per config/artifacts.yaml.
    Lambda `ai` implements it with S3: on first call it downloads s3://…/<prefix>/ to /tmp/artifacts/<name>/<version>/ and checks the sha256.
    The CLI and eval read the local directory directly."""
    def path(self, name: str) -> pathlib.Path: ...


class Pipeline(Protocol):
    """Lambda `ai` (handler.py) calls these methods by `op`; the CLI and eval call them directly. Java never sees this interface."""

    def fingerprint(self, bundle: Bundle) -> str:
        """Cache key (ARCH §6.2): sha256 of the normalised text + every file_unique_id + URLs from entities."""
        ...

    def analyze(self, bundle: Bundle, ctx: AnalyzeContext) -> Verdict:
        """Never raises on model/network errors (degrades to rules_only on its own). An exception means a bug → SQS retry."""
        ...

    def render(self, verdict: Verdict, ctx: RenderContext) -> ReplyPlan:
        """Pure function, no network calls. A Verdict taken from cache (empty explanation) uses a template keyed by signal code."""
        ...

    def render_guardian_alert(self, verdict: Verdict, ctx: GuardianAlertContext) -> OutMessage:
        """Content depends on share_level (ARCH §5.5); always has an "ack" button."""
        ...
```

`ai/src/askfirst/domain/export.py` (lệnh sinh schema, chạy được trên Windows/macOS/Linux):

```python
"""Sinh contracts/schema/*.json. Chạy trong thư mục ai/: `uv run python -m askfirst.domain.export [out_dir]`."""
import pathlib
import sys

from askfirst.domain.models import export_json_schemas

if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "../contracts/schema")
    for path in export_json_schemas(out):
        print(path)
```

### 3.4 Message SQS (queue `jobs`; owner BE1, chỉ Java dùng nên **không** nằm trong `contracts/schema`, nhưng ghi ở đây để cả team đọc được)

Mọi message có `v` (version của schema) và `type`; Java dùng `record` + Jackson `@JsonTypeInfo(property="type")`. Worker từ chối message có `v` lạ (log lỗi, không retry).

| `type` | Ai gửi, khi nào | Trường | `DelaySeconds` |
|---|---|---|---|
| `analyze` | Webhook, mỗi tin cần phân tích | `{"v":1,"type":"analyze","job_id":"01J…","chat_id":"…","user_id":"tg:…","seq":3,"enqueued_at":"2026-10-14T07:05:00Z"}` | 3 |
| `answer` | Webhook, khi người dùng bấm [Có] [Không] [Không nhớ] của câu hỏi thêm | `{"v":1,"type":"answer","job_id":"01J…","chat_id":"…","user_id":"tg:…","prev_check_id":"01J…","answer":"yes","placeholder_message_id":"…","enqueued_at":"…"}` | 0 |
| `followup` | Worker, sau khi đã gửi cảnh báo người thân | `{"v":1,"type":"followup","job_id":"01J…","chat_id":"…","elder_uid":"tg:…","check_id":"01J…","alert_ids":["01J…"],"enqueued_at":"…"}` | 900 |

- `answer`: worker đọc `SESSION#<chat_id>`, gửi `AnalyzeRequest(items=[], session=SessionContext(...))` cho `ai`, rồi chạy đúng luồng §3.2 với `placeholder_message_id` thay cho `buf.placeholder_id`. Session hết hạn thì trả lời "Bác gửi lại tin giúp cháu nhé".
- `followup`: không gọi AI. Chưa ai bấm ACK thì hỏi người cao tuổi và nhắc người thân một lần, gửi im lặng (ARCH §5.5).

### 3.5 Pipeline factory, cấu hình và `StubPipeline` (owner AI1)

```python
# ai/src/askfirst/pipeline/__init__.py (AI)
def build_pipeline(impl: str, config_dir: pathlib.Path, artifacts: ArtifactStore, url_cache: KVCache) -> Pipeline:
    """impl = "stub" | "real". Called ONCE at cold start (module-level variable); the object is reused across invocations."""

# ai/src/askfirst/handler.py (AI1): entry point of Lambda `ai`
PIPELINE = build_pipeline(os.environ["PIPELINE_IMPL"], CONFIG_DIR, S3ArtifactStore(...), DynamoKV(prefix="URL#"))

def lambda_handler(event: dict, context) -> dict:
    """Never raises: every branch returns AiResponse.model_dump(mode="json") (§3.2)."""
    # op = event.get("op"); unknown v → AiError("fatal","unsupported_version")
    # analyze: validate AnalyzeRequest → media.telegram.build_bundle(items, flags) (§3.6) → fingerprint → CACHE# → analyze
    # render_guardian_alerts / render: validate → call PIPELINE.render_* (pure functions, no network)
```

| Biến môi trường của Lambda `ai` | Giá trị | Ai đọc |
|---|---|---|
| `PIPELINE_IMPL` | `stub` (mặc định tới INT-01) hoặc `real` | `handler.py`, truyền vào `build_pipeline` |
| `CONFIG_DIR` | `/var/task/config` (nằm trong image) | AI |
| `ARTIFACT_BUCKET` | tên bucket S3 | `S3ArtifactStore` (AI2) |
| `TABLE_NAME` | `askfirst` | `DynamoKV` cho `CACHE#`, `URL#` (AI1) |
| `BOT_TOKEN_PARAM` | tên tham số SSM chứa bot token | `media/telegram.py` (AI2); đọc lười ở lần gọi đầu, cache trong RAM |

| Biến môi trường của `worker` (Java) | Giá trị |
|---|---|
| `AI_FUNCTION_ARN` | ARN alias `live` của Lambda `ai` |
| `TABLE_NAME`, `JOBS_QUEUE_URL`, `BOT_TOKEN_PARAM` | như tên |

**`ai/config/models.yaml`** (AI1; model ID **copy từ console**, không đoán):

```yaml
bedrock_region: ap-southeast-2
judge:  {model_id: "<copy từ console>", max_tokens: 600, temperature: 0, thinking: false, timeout_s: 8}
backup: {model_id: "<gpt-oss-120b, copy từ console>", max_tokens: 800, temperature: 0, timeout_s: 12}
vlm:    {model_id: "<chốt ở ADR-006>", max_tokens: 600, timeout_s: 10}
guardrail: {id: "<guardrail id>", version: "1"}
pricing_usd_per_1m:            # dùng để tính Verdict.cost_usd_est; cập nhật theo trang giá Bedrock
  "<judge model_id>":  {in: 0.1545, out: 0.618}
```

**`ai/config/artifacts.yaml`** (AI2 ghi version và checksum; AI2 viết `scripts/upload_artifacts.py` để đẩy lên S3, BE1 cấp quyền):

```yaml
- name: ocr                     # artifacts.path("ocr") trả thư mục chứa các file dưới đây
  version: ppocrv5-vi-1
  s3_prefix: artifacts/ocr/ppocrv5-vi-1/
  license: Apache-2.0
  files: {det.onnx: "<sha256>", rec.onnx: "<sha256>"}
- name: asr
  version: zipformer-vi-int8-2025-04-20
  s3_prefix: artifacts/asr/zipformer-vi-int8-2025-04-20/
  license: "<kiểm ở AI-21>"
  files: {encoder.int8.onnx: "<sha256>", decoder.int8.onnx: "<sha256>", joiner.int8.onnx: "<sha256>", tokens.txt: "<sha256>"}
- name: embedder
  version: e5-small-int8-1
  s3_prefix: artifacts/embedder/e5-small-int8-1/
  license: "<kiểm>"
  files: {model.onnx: "<sha256>", tokenizer.json: "<sha256>"}
- name: kb_index                # do scripts/build_index.py tạo
  version: kb-2026.10.14
  s3_prefix: artifacts/kb_index/kb-2026.10.14/
  license: internal
  files: {index.npy: "<sha256>", meta.json: "<sha256>"}
```

`ArtifactStore` bản S3 (AI2, trong `ai/src/askfirst/artifacts/s3.py`): tải **lười** (lần đầu `path(name)` được gọi), lưu ở `/tmp/artifacts/<name>/<version>/`, kiểm sha256 từng file, sai checksum thì raise. AI chỉ gọi `artifacts.path(...)` ngay trước khi cần model (lazy load, ARCH §10).

**`StubPipeline`** (AI1 viết trong W1, cùng PR contract; BE1 deploy Lambda `ai` với `PIPELINE_IMPL=stub` ở W2 để BE chạy end-to-end). Bản stub **không tải media** (chỉ đếm số ảnh/voice trong `items`), nên chạy được khi chưa có quyền SSM. Hành vi cố định, test được:

| Input | `Verdict` trả về |
|---|---|
| Không có text, ảnh, voice | `outcome="unreadable"`, `level="UNSURE"` |
| Chỉ có text và text (lowercase) thuộc {"cảm ơn", "alo", "ok"} | `outcome="smalltalk"`, `level="LOW_RISK"` |
| Text chứa "otp" | `DANGEROUS`, score 90, `group="N1"`, `scenario="N1.1"`, `risky_actions=["share_otp"]`, `should_alert=True` |
| Text chứa "http" hoặc "link" | `SUSPICIOUS`, score 60, `scenario="N1.4"`, `risky_actions=["click_link"]` |
| Text chứa "hỏi thêm" | `UNSURE`, score 30, `followup_question="Người gọi có bảo bác giữ bí mật không ạ?"` (trừ khi `session.turn == 2`) |
| Có ảnh hoặc voice, không khớp dòng nào ở trên | `UNSURE`, score 30 |
| Còn lại | `LOW_RISK`, score 10, `group="N0"`, `scenario="N0"` |

Mọi trường hợp: `path="fast"`, `versions={"contract": CONTRACT_VERSION, "pipeline": "stub"}`, `masked_text` = text gốc (stub không mask, **chỉ dùng ở bot `dev`**). `render` của stub trả 1 tin `"[STUB] <level> <scenario>"` kèm nút `report_wrong`; `render_guardian_alert` trả `"[STUB] cảnh báo <elder_name>"` kèm nút `ack`. Output của stub cho mỗi dòng bảng trên được chụp thành `contracts/fixtures/<op>/<case>.json` (request + response) để `FakeAiClient` phía Java dùng lại.

### 3.6 Dựng `Bundle` từ các `InboundMessage` (AI2, trong Lambda `ai`)

- Java (BE2, `TelegramAdapter`) tạo `InboundMessage`: với `photo` chọn `PhotoSize` **lớn nhất** làm `media`; `document` giữ `mime`, `file_name`. Java **không tải** file nào.
- `ai` (`media/telegram.py`) duyệt các tin theo thứ tự trong `items` (đã theo `seq`). Tin có `text` thì thêm `TextPart(text, entities)`, giữ nguyên `entities` của chính tin đó.
- `photo`: `getFile(media.file_id)` rồi tải. `document` có `mime` dạng `image/*`: tải và coi như ảnh. Voice/audio: tải nguyên file, `duration_s` lấy từ `media.duration`. Tải song song tối đa 4 file, timeout 10 giây mỗi file; lỗi tải → `AiError("retryable","telegram_download")`.
- Media tải **vào RAM của Lambda `ai`**, không ghi đĩa, không ghi S3, không trả về Java. URL tải (`https://api.telegram.org/file/bot<token>/…`) chứa token: **không log, không đưa vào exception message**. `ImageInput.mime`/`AudioInput.mime` lấy từ Telegram, thiếu thì đoán theo đuôi tệp.
- `forward_origin` = của tin đầu tiên có forward.
- `feature_image=False` thì bỏ ảnh; `feature_voice=False` thì bỏ voice (webhook đáng lẽ đã chặn, đây là lớp thứ hai).

### 3.7 `Verdict` → bản ghi `CHECK#` (BE, Java, ARCH §7)

| Trường `CHECK` | Lấy từ | Ghi chú |
|---|---|---|
| `input_kind` | `items[].kind`: `text` / `image` / `voice` / `mixed` | BE tự suy ra từ `InboundMessage` (không cần `Bundle`) |
| `outcome`, `level`, `score`, `group`, `scenario`, `path`, `model_ids`, `cost_usd_est` | `Verdict` cùng tên | |
| `signal_codes` | `[s.code for s in verdict.signals]` | **Không** lưu `evidence` |
| `prompt_version`, `rules_version`, `kb_version`, `local_models_version` | `verdict.versions[...]` | |
| `latency_ms` | `verdict.latency_ms` + `{"e2e": …}` do BE đo | |
| `notified` | `[a.status for a in alerts]` | |
| `feedback` | callback `fb:wrong` sau này | |

**Không bao giờ ghi vào `CHECK`, `CACHE` hay log:** `masked_text`, `explanation`, `evidence`, `followup_question`, `citations` kèm nội dung. `masked_text` chỉ được lưu ở `SESSION#` (30 phút, để hỏi thêm và để "Đóng góp mẫu" nếu người dùng bấm trong 30 phút) và ở `donated/<hash(uid)>/` khi người dùng đồng ý `donate`.

### 3.8 Bộ eval: `eval/datasets/*.jsonl` (owner AI1)

Mỗi dòng một mẫu:

```json
{"id":"rs-001","split":"real_scam",
 "input":{"text":"Quý khách có biên bản phạt nguội…","entities":[],"image":null,"audio":null,"forward_origin":"hidden_user"},
 "label":{"is_scam":true,"group":"N1","scenario":"N1.4","min_level":"SUSPICIOUS","max_level":null,
          "must_signals":["U_TYPOSQUAT"],"risky_actions":["click_link"]},
 "source_url":"https://…","author":"AI2","notes":"","provenance":"rewritten"}
```

| Trường | Quy ước |
|---|---|
| `split` | `real_scam` \| `benign_suspicious` \| `benign_normal` \| `hard` \| `ocr_images` \| `voice` \| `synthetic` (ARCH §6.11) |
| `input.image` / `input.audio` | Đường dẫn tương đối tới `eval/assets/…`, hoặc `null`. Có thể vừa có `text` vừa có ảnh |
| `input.entities` | Cùng dạng `EntityRef` (§3.3), dùng cho ca `text_link` |
| `label.min_level` | Bắt buộc khi `is_scam=true` |
| `label.max_level` | Bắt buộc khi `is_scam=false`; thường là `"UNSURE"` |
| `source_url` | Bắt buộc với mẫu viết lại từ cảnh báo chính thức |
| `label.scenario` | Mã kịch bản research-concept §2.2 (`N0`, `N1.1`…`N6`, `OTHER`); `group` = phần trước dấu chấm. Cách gán: `eval/LABELING.md` |
| `provenance` | `rewritten` \| `ai_drafted` \| `constructed` \| `real_anonymized`; bắt buộc với 4 split `real_scam`/`benign_*`/`hard`. Mẫu `ai_drafted` cần người thứ hai duyệt nhãn (`eval/LABELING.md` mục 10) |

`eval/run_eval.py` đổi mỗi dòng thành `Bundle` (đọc file ảnh/voice thành bytes), gọi `pipeline.analyze()` với `Flags()` mặc định, **không cần BE**. Định nghĩa chỉ số (dùng thống nhất ở G1, M4, CI):

- **Bắt được** = `LEVEL_ORDER[level] >= LEVEL_ORDER["SUSPICIOUS"]` (mức `SUSPICIOUS` hoặc `DANGEROUS`).
- **Recall** = tỷ lệ mẫu `is_scam=true` bị bắt được. `UNSURE` **không** tính là bắt được, báo cáo riêng thành "tỷ lệ chưa chắc".
- **Báo nhầm** = tỷ lệ mẫu `benign_suspicious` bị bắt được.
- **Đạt nhãn mức** = `level ≥ min_level` (mẫu lừa đảo) hoặc `level ≤ max_level` (mẫu lành tính).
- `must_signals` thiếu thì ghi vào báo cáo, không tính là sai kết luận.

### 3.9 Test contract (chung, chạy mỗi PR)

**Phía Python:** `ai/tests/contract/test_models.py`

```python
import json
import pathlib
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from askfirst.domain import models as m

NOW = datetime(2026, 10, 14, 7, 5, tzinfo=UTC)
ULID = "01J" + "0" * 23


def _verdict(**kw) -> m.Verdict:
    base = {"outcome": "verdict", "level": "DANGEROUS", "score": 90, "group": "N1", "scenario": "N1.4",
            "signals": [m.Signal(code="U_TEXT_LINK_MISMATCH", weight=40, hard=True, evidence="Bấm vào đây")],
            "risky_actions": ["click_link"], "should_alert": True, "explanation": "x", "masked_text": "Bấm vào đây",
            "path": "fast", "versions": {"contract": m.CONTRACT_VERSION}}
    return m.Verdict(**(base | kw))


def test_inbound_message_json_round_trip() -> None:   # BUFFER# stores it as JSON
    msg = m.InboundMessage(
        channel="telegram", update_id="1", chat_id="42", user_id="tg:42", message_id="7", kind="text",
        text="Bấm vào đây", entities=[m.EntityRef(kind="text_link", offset=0, length=3, url="https://dichvucong-vn.com")],
        received_at=NOW)
    assert m.InboundMessage.model_validate_json(msg.model_dump_json()) == msg


def test_text_link_requires_url() -> None:
    with pytest.raises(ValidationError):
        m.EntityRef(kind="text_link", offset=0, length=1)


def test_for_cache_strips_user_text() -> None:
    v = _verdict(followup_question="Có ai bảo giữ bí mật không?")
    c = v.for_cache()
    assert c.masked_text == "" and c.explanation == "" and c.followup_question is None
    assert all(s.evidence is None for s in c.signals)
    assert v.signals[0].evidence == "Bấm vào đây"          # the original is untouched
    assert m.Verdict.model_validate_json(c.model_dump_json()) == c


@pytest.mark.parametrize("bad", [
    lambda: _verdict(level="SAFE"),                       # there is no "safe" level
    lambda: _verdict(score=101),
    lambda: _verdict(risky_actions=["chuyen_khoan"]),        # invalid enum value
    lambda: m.ReplyPlan(messages=[]),                        # needs at least 1 message
    lambda: m.Flags(llm_enabled=True, typo=1),               # unknown fields are rejected
    lambda: m.SessionContext(prev_check_id=ULID, masked_text="x", question="?", answer="yes", turn=3),
])
def test_invalid_values_are_rejected(bad) -> None:
    with pytest.raises(ValidationError):
        bad()


def test_contexts_and_reply_plan_build() -> None:
    bundle = m.Bundle(texts=[m.TextPart(text="alo")],
                      images=[m.ImageInput(data=b"\x89PNG", file_unique_id="AQAD", mime="image/png")])
    ctx = m.AnalyzeContext(check_id=ULID, user_id="tg:42", flags=m.Flags(), deadline_s=85)
    plan = m.ReplyPlan(messages=[m.OutMessage(
        text_html="<b>x</b>", buttons=[[m.Button(kind="report_wrong", label="Bot báo sai")]])])
    rctx = m.RenderContext(check_id=ULID, address_form="elder", has_guardian=True,
                           alerts=[m.AlertResult(guardian_name="Nam", status="sent")])
    gctx = m.GuardianAlertContext(alert_id=ULID, elder_name="Lan", share_level=1, at=NOW)
    assert bundle.images[0].data and ctx.flags.llm_enabled and plan.messages and rctx.alerts and gctx.share_level == 1


def test_json_schema_generates() -> None:
    for model in (m.Bundle, m.Verdict, m.ReplyPlan, m.AnalyzeContext, m.RenderContext, m.GuardianAlertContext):
        assert model.model_json_schema()


def _msg() -> m.InboundMessage:
    return m.InboundMessage(
        channel="telegram", update_id="1", chat_id="42", user_id="tg:42", message_id="7", kind="photo",
        media=m.MediaRef(file_id="AgAC", file_unique_id="AQAD", mime="image/jpeg", size=120_000), received_at=NOW)


def test_wire_round_trip_all_ops() -> None:   # exactly what Java sends/receives
    ctx = m.AnalyzeContext(check_id=ULID, user_id="tg:42", flags=m.Flags(llm_enabled=False), deadline_s=85)
    reqs = [
        m.AnalyzeRequest(items=[_msg()], ctx=ctx),
        m.RenderGuardianAlertsRequest(verdict=_verdict(), guardians=[
            m.GuardianAlertContext(alert_id=ULID, elder_name="Lan", share_level=2, at=NOW)]),
        m.RenderRequest(verdict=_verdict(), ctx=m.RenderContext(check_id=ULID, address_form="aunt", has_guardian=False)),
    ]
    for req in reqs:
        assert type(req).model_validate_json(req.model_dump_json()) == req
        assert json.loads(req.model_dump_json())["v"] == m.CONTRACT_VERSION
    plan = m.ReplyPlan(messages=[m.OutMessage(text_html="x")])
    resps = [
        m.AiResponse(op="analyze", analyze=m.AnalyzeResult(verdict=_verdict(), cache_hit=False)),
        m.AiResponse(op="render_guardian_alerts", render_guardian_alerts=m.RenderGuardianAlertsResult(alerts=plan.messages)),
        m.AiResponse(op="render", render=plan),
        m.AiResponse(op="analyze", error=m.AiError(kind="retryable", code="telegram_download")),
    ]
    for r in resps:
        assert m.AiResponse.model_validate_json(r.model_dump_json()) == r


def test_wire_request_has_no_media_bytes() -> None:   # media only lives in the RAM of `ai` (ARCH §4.5)
    raw = m.AnalyzeRequest(items=[_msg()], ctx=m.AnalyzeContext(
        check_id=ULID, user_id="tg:42", flags=m.Flags(), deadline_s=85)).model_dump_json()
    assert "data" not in json.loads(raw)["items"][0]


@pytest.mark.parametrize("bad", [
    lambda: m.AiResponse(op="render"),                                                   # no result
    lambda: m.AiResponse(op="render", analyze=m.AnalyzeResult(verdict=_verdict(), cache_hit=True)),   # wrong op
    lambda: m.AiResponse(op="render", render=m.ReplyPlan(messages=[m.OutMessage(text_html="x")]),
                         error=m.AiError(kind="fatal", code="bad_request")),            # both a result and an error
    lambda: m.RenderGuardianAlertsRequest(verdict=_verdict(), guardians=[]),
    lambda: m.AnalyzeRequest.model_validate({"op": "analyse", "ctx": {}}),             # misspelled op
])
def test_invalid_wire_rejected(bad) -> None:
    with pytest.raises(ValidationError):
        bad()


def test_exported_schema_is_java_friendly(tmp_path: pathlib.Path) -> None:
    paths = m.export_json_schemas(tmp_path)
    assert {p.name for p in paths} == {f"{x.__name__}.json" for x in m.WIRE_MODELS}
    for p in paths:
        text = p.read_text(encoding="utf-8")
        assert '"anyOf"' not in text and '"$defs"' not in text and '"null"' not in text
        assert "#/definitions/" in text or p.name == "InboundMessage.json" or "definitions" not in text


def test_callback_data_fits_telegram_limit() -> None:  # callback_data ≤ 64 byte
    for data in (f"fb:wrong:{ULID}", f"fu:{ULID}:u", f"cg:{ULID}", f"ack:{ULID}", f"dn:{ULID}"):
        assert len(data.encode()) <= 64
```

**Phía Java:** `backend/src/test/java/vn/askfirst/contract/` (BE1 viết ở BE-05b)

- `FixturesRoundTripTest`: với mọi file `contracts/fixtures/**/*.json`, deserialize request/response vào class sinh ra rồi serialize lại; so **cây JSON** (`JsonNode.equals`, bỏ qua thứ tự khoá) với file gốc. Bắt lỗi đặt tên `snake_case`, `Instant`, enum, `null`.
- `SchemaValidationTest`: request Java tự dựng (như trong `WorkerHandler`) phải hợp lệ theo `contracts/schema/*.json` (dùng `networknt/json-schema-validator`). Request có trường lạ là đỏ, vì `ai` dùng `extra="forbid"`.
- `ContractVersionTest`: `CONTRACT_VERSION` Java đọc từ `$id` của schema, khớp `v` trong fixtures.

**CI (`ci.yml`, job `contract`):** `cd ai && uv run python -m askfirst.domain.export` → `git diff --exit-code contracts/schema` (quên sinh lại schema là đỏ) → `uv run pytest tests/contract` → `cd backend && ./mvnw test -Dtest='**/contract/*'`. Job này chạy khi PR đụng `ai/src/askfirst/domain/`, `contracts/` hoặc `backend/`.

### 3.10 Schema KB: `kb/*.yaml` (owner AI2)

Mỗi mục có nguồn thì có `source_url` và `verified_at` (`YYYY-MM-DD`). CI báo đỏ nếu một mục trong `hotlines.yaml` có `verified_at` cũ hơn 30 ngày.

```yaml
# kb/scenarios.yaml: scenario card (ARCH §6.5)
- code: N1.4
  group: N1
  title: Giả mạo Cổng Dịch vụ công / phạt nguội
  description: Tin nhắn báo vi phạm giao thông, kèm link trang giả để lấy thông tin thẻ và OTP.
  example_phrases: ["biên bản phạt nguội chưa xử lý", "nộp phạt trực tuyến tại"]
  red_flags: ["Tên miền không phải .gov.vn", "Số tiền phạt nhỏ để dễ trả"]
  advice: ["Không bấm link", "Tra cứu phạt nguội tại trang chính thức"]   # không ghi số điện thoại ở đây
  sources: [{url: "https://…", publisher: "Công an tỉnh …", date: "2026-04-10"}]
  verified_at: 2026-10-14

# kb/rules.yaml (ARCH §6.4); patterns chạy trên bản đã chuẩn hoá: lowercase, KHÔNG dấu, đã mask
version: rules-2026.10.14
hard_floor: 80
rules:
  - code: R_OTP_REQUEST
    weight: 45
    hard: true
    patterns: ['(cung cap|doc|gui|nhap)\W+(\w+\W+){0,3}(ma )?(otp|xac thuc|xac nhan|\[otp_\d+\])']
    explain: Người lạ xin mã OTP là dấu hiệu lừa đảo, ngân hàng không bao giờ xin mã này.
combos:
  - all: [R_AUTHORITY]
    any: [R_MONEY_TRANSFER, R_SECRECY, R_SAFE_ACCOUNT]
    floor: 85

# kb/allowlist.yaml: so khớp eTLD+1 hoặc hậu tố
domains:
  - {domain: dichvucong.gov.vn, owner: "Cổng Dịch vụ công quốc gia", source_url: "https://…", verified_at: 2026-10-14}
suffixes: [gov.vn]

# kb/brands.yaml: dùng cho typosquat và U_TEXT_LINK_MISMATCH
- {name: "Cổng Dịch vụ công", tokens: [dichvucong, dvc], official_domains: [dichvucong.gov.vn]}

# kb/hotlines.yaml: chỉ mục đã xác minh mới được đưa ra người dùng
- {id: police_113, name: "Công an (khẩn cấp)", kind: phone, value: "113", source_url: "https://…", verified_at: 2026-10-14}
```

> Pattern trong `rules.yaml` ở trên chỉ là **ví dụ về định dạng**; AI2 viết pattern thật ở AI-23 và mỗi luật phải có test dương và test âm.

### 3.11 API Mini App: `contracts/openapi.yaml` (owner BE2), **MVP+, không thuộc gate G0**

Chỉ viết khi quyết định làm Mini App. Đủ các endpoint ở ARCH §13, có ví dụ response cho từng endpoint để FE mock được.

---

## 4. Mốc và Gate

> Lịch v3 (6 tuần). Cột "Cuối tuần" ghi thứ Sáu của tuần đó (W1 23/10 · W2 30/10 · W3 06/11 · W4 13/11 · W5 20/11 · W6 27/11/2026).

| Mốc / Gate | Cuối tuần | Nội dung | Tiêu chí qua |
|---|---|---|---|
| **G0: Contracts** | W1 (23/10) | `ai/src/askfirst/domain/models.py` + `export.py` (§3.3), `contracts/schema/*.json` đã sinh, `ai/tests/contract/test_models.py` (§3.9), `StubPipeline` + fixtures trong `contracts/fixtures/` (§3.5), `ai/config/models.yaml` và `ai/config/artifacts.yaml` khung (§3.5) đã merge vào `main` | Job `contract` trong CI xanh: `uv run pytest tests/contract` (Python) **và** `./mvnw test -Dtest='**/contract/*'` (Java đọc fixtures, validate schema); 1 BE + 1 AI đã duyệt; AI chạy được `build_pipeline("stub", …)` từ máy dev. Codegen Java (jsonschema2pojo) có thể xong muộn hơn ở BE-05b, không chặn G0 |
| **G1: Chốt LLM** | W2 (30/10) | Xác nhận Qwen3 32B (ARCH §6.9) | Trên 100 mẫu với prompt v0: JSON hợp lệ ≥ 98%, p95 ≤ 5 giây, recall sơ bộ ≥ 85%, biết cách tắt thinking. Không đạt thì làm theo phương án dự phòng ở §9 |
| **G2: Chốt OCR** | W2 (30/10) | Chọn stack OCR (ARCH §6.0, §6.3). Phần ASR ⏸ hoãn cùng AI-21 | OCR: chép đúng URL ≥ 90%, CER ≤ 10% trên tập ảnh độc lập (hạ từ 5%, ADR-003) |
| **M1: Text E2E** | W3 (06/11) | Chuyển tiếp tin "phạt nguội" có link giả → bot trả kết luận thật (luật + Qwen3) | Demo trên bot `dev`; 20 mẫu văn bản đúng ≥ 85% |
| **M2: Ảnh + người thân** | W4 (13/11) | Ảnh chụp màn hình → OCR local → kết luận; điện thoại của "con" nhận cảnh báo | p95 ảnh ≤ 15 giây (warm); luồng double opt-in chạy đủ |
| **M3: RAG** | W5 (20/11) | RAG: kết luận kèm trích nguồn cảnh báo (voice ⏸ hoãn) | Recall@4 scenario card ≥ 90% trên `real_scam`; truy vấn < 100 ms |
| **M4: MVP FREEZE** | W5 (20/11) | Đủ các FR mức MVP ở ARCH §1.2 (gồm `/nguoithan`, `/lichsu` trong chat; **không** gồm voice, multi-turn), eval chạy trong CI | Recall ≥ 90%, báo nhầm ≤ 10% trên 300 mẫu; không còn bug mức P0 hay P1 |
| **M5: Test nội bộ** | W6 (đến 25/11) | Team và người nhà dùng thử (thay cho pilot 10–20 hộ cũ); UAT theo §8 | Danh sách lỗi và phản hồi đã xử lý; **chưa có** số liệu người dùng thật ngoài team |
| **M6: Thuyết trình** | W6 (27/11) | Slide, demo, báo cáo eval và chi phí thật | Rehearsal 2 lần |

Sau M4 **chỉ sửa bug, chỉnh prompt hoặc ngưỡng** (qua eval gate) và làm việc hạ tầng/runbook (BE-08b, BE-10, BE-11). Tính năng mới (voice, VLM, multi-turn, Phase 2, Mini App) để dành cho sau 6 tuần.

---

## 5. Lịch tổng theo tuần và theo người

> Lịch v3 (6 tuần). Ngày: W1 19–25/10 · W2 26/10–01/11 · W3 02–08/11 · W4 09–15/11 · W5 16–22/11 · W6 23–29/11/2026. Hoãn: voice/ASR (AI-21, AI-28), VLM (AI-27), multi-turn (BE-33, AI-12), Phase 2, Mini App.

| Tuần | BE1 (Lane A: luồng kiểm tra) | BE2 (Lane B: người dùng & dữ liệu) | AI1 (LLM, Eval & phần khó) | AI2 (Dữ liệu, KB & việc dễ; AI1 kèm) |
|---|---|---|---|---|
| **W1** (19–25/10) | **Làm chung:** BE-01, BE-02, BE-03, BE-14 · BE-05, BE-05b (spike codegen) | **Làm chung:** BE-01, BE-02, BE-03, BE-14 · BE-20, BE-21 (bắt đầu), SH-06 | AI-01, AI-02 | AI-20 (bắt đầu: dựng bộ ảnh, cài 3 OCR), SH-02 |
| **W2** (26/10–01/11) | BE-05b, BE-22, BE-07, BE-24b · trực tuần | BE-21, BE-24 (nền trước), **FE-00** | AI-03 → **G1**, AI-03b (`handler.py`), AI-04 (bắt đầu), cùng chốt **G2** | AI-20 → **G2 (OCR)** cùng AI1, AI-03b (`media/`, `S3ArtifactStore`), AI-22, SH-05, AI-25 (bắt đầu) |
| **W3** (02–08/11) | BE-07, BE-27, BE-23, BE-06 (bắt đầu), **INT-01** | BE-04, BE-25, BE-26 · trực tuần | AI-04, AI-05, AI-06 | AI-22 (xong), AI-23, AI-25, AI-24a (bắt đầu), SH-08 |
| **W4** (09–15/11) | BE-06, BE-34, BE-29 🔀, BE-08a (bắt đầu), **INT-02** · trực tuần | BE-28, BE-35 | AI-07, AI-26, AI-24b (bắt đầu) | AI-24a, AI-08, AI-29 (bắt đầu), AI-09 (bắt đầu, cùng BE2), phụ AI-26 |
| **W5** (16–22/11) | BE-13, BE-08a, BE-09a 🔀, **INT-03** 🔀 | BE-30 🔀, BE-31 🔀, BE-36, BE-09b 🔀 · trực tuần | AI-24b, AI-10, AI-13 | AI-29, AI-09, AI-11, AI-14 (bắt đầu) |
| **W6** (23–29/11) | BE-10, BE-12 (trực test nội bộ), slide kiến trúc và chi phí | BE-08b 🔀, BE-11, UAT (điều phối), BE-12, video demo | Báo cáo eval cuối, slide AI và eval | AI-14 (xong), slide dữ liệu và taxonomy |

**Cảnh báo tải (v3):** W4–W5 nén hai tuần cũ thành một: BE1 W4 và BE2 W5 mỗi người ≈ 7 ngày công trong tuần 5 ngày. Nếu trễ, cắt theo thứ tự ở §9 (sau những việc đã hoãn): AI-11 → BE-10 → phần "người thân" của BE-36 → rút gọn AI-14.

**Thay đổi theo ARCH v3.2–v3.4:** thêm **BE-13** (kill switch bằng script + SNS alarm) ở W5 bên BE1 (lịch v3), **BE-35** (đầu vào không hỗ trợ, `.apk`) ở W4 bên BE2, **BE-36** (`/nguoithan` đầy đủ + `/lichsu`) ở W5 bên BE2 (lịch v3) thay cho BE-32. Mini App (FE-01…FE-07, BE-32, INT-04) chuyển sang MVP+, bớt khoảng 9–13 ngày công ở W4–W7 (cộng cỡ việc FE-01…FE-07 và BE-32 theo quy ước S/M ở §0, trừ đi BE-36). Các việc BE-23, BE-25, BE-29, BE-31, AI-06, AI-07, AI-22, AI-24 đã được chỉnh phạm vi trong §6 (sửa gom tin, consent theo mục đích, cảnh báo song song + ACK, defang URL + escape HTML, một model dự phòng, mask OTP, `entities`).

**Chia lại việc BE (v2.1):** BE1/BE2 không còn là "Platform" và "Bot & Product" mà là hai lát cắt dọc, mỗi người làm cả code lẫn hạ tầng của lane mình (§2.1). BE-08 tách thành BE-08a/BE-08b, BE-09 thành BE-09a/BE-09b (mỗi người rà lane của người kia), BE-24 tách thêm BE-24b; thêm BE-14 (spike học chung W1); BE-04 dời sang W3, BE-22 và BE-29 chuyển sang BE1, BE-03 làm cặp.

**Thay đổi theo ARCH v3.5 (backend Java):** thêm **BE-05b** (codegen + `AiClient`, BE1 W1–W2) và **AI-03b** (runtime Lambda `ai`, AI1 + AI2 W2). BE-27 giờ phụ thuộc AI-03b vì worker gọi `ai` bản stub thay cho `build_pipeline` in-process.

**Tải AI (v3.1):** AI1 ≈ 5–6 ngày công mỗi tuần W1–W5 (nặng nhất W3–W4); AI2 ≈ 4–5 ngày công, chừa thời gian học. AI1 kèm AI2 ≈ 30 phút mỗi ngày.

Việc chung chạy xuyên suốt: SH-03 (mỗi người 25 mẫu eval ở W1–W2, thêm 50 mẫu ở W3–W4), SH-04 (mỗi người phỏng vấn 2–3 người ở W1–W2).

---

## 6. Chi tiết công việc theo track

### 6.1 SHARED: cả team

| Mã | Việc | Owner | Tuần | Cỡ | Xong khi |
|---|---|---|---|---|---|
| SH-01 | Kickoff: cả team đọc ARCH và §3; **AI1** copy `models.py`, `export.py` + test contract từ §3.3 và §3.9 vào `ai/`, sinh `contracts/schema/`, viết `StubPipeline` theo bảng §3.5 và ghi fixtures; **BE2** duyệt và viết `TelegramAdapter.parseUpdate` (Java) tạo đúng `InboundMessage` hợp lệ theo schema | Cả team (AI1, BE2 chính) | W1 | M | Gate G0 qua |
| SH-02 | Hướng dẫn gán nhãn: mỗi kịch bản N0–N6 có định nghĩa, 2 ví dụ dương, 1 ví dụ dễ nhầm (`eval/LABELING.md`) | AI2 (chính), AI1 | W1 | S | 4 người gán thử cùng 10 mẫu, đồng ý ≥ 8/10 |
| SH-03 | Bộ eval: **v0 = 100 mẫu** (mỗi người 25 mẫu, W1–W2) → **v1 = 300 mẫu** (W5) theo cơ cấu ARCH §6.11. Mẫu lừa đảo **viết lại từ cảnh báo chính thức, kèm URL nguồn**; **không** sinh bằng LLM | Cả team | W1–W4 | M mỗi người | `python -m eval.validate` pass (đúng schema, không trùng, đủ số lượng từng split) |
| SH-04 | Phỏng vấn người dùng: 5 người cao tuổi, 5 con cháu (câu hỏi ở research-concept §9). Ghi chú ở `research/interviews/` (đã ẩn danh) | Cả team | W1–W2 | S mỗi người | 10 bản ghi chú + 1 trang tổng hợp insight |
| SH-05 | Xác minh hotline và kênh trình báo (113, công an xã, hotline các ngân hàng lớn, kênh tố giác VNeID…) **chỉ từ trang chính thức** | AI2 + BE2 | W2–W3 | S | Mọi mục trong `kb/hotlines.yaml` có `source_url` và `verified_at` |
| SH-06 | Test truy cập Telegram trên Viettel, VNPT, FPT, MobiFone (4G và cáp quang) | BE2 | W1 | S | Bảng kết quả trong README, có ngày test |
| SH-07 | Chính sách quyền riêng tư và disclaimer (tiếng Việt, ngắn, dễ đọc) theo ARCH §9.1; nhờ mentor đọc lại | BE2 + AI1 | W2 | S | File `docs/privacy-policy.md` có version và đã gắn vào FE-00 |
| SH-08 | Copy tin nhắn của bot: lời chào, đồng ý, 4 mức kết quả, checklist "30 phút đầu", cảnh báo người thân, nhắc lại 15 phút | BE2 + AI2 (AI1 duyệt câu chữ) | W3 | S | 2 người lớn tuổi (người nhà) đọc thử và hiểu đúng |

### 6.2 BACKEND

> Hai BE chia theo **lát cắt dọc** (§2.1), không chia "người làm hạ tầng" và "người làm code". Mỗi việc có owner; người BE còn lại là **pair** (đọc design note, review mọi PR, làm thay khi owner bận). 🔀 = việc chéo: owner phải đọc và đụng vào code của lane kia.

#### Làm chung (W1, ngồi cùng nhau)

| Mã | Việc | Owner | Tuần | Cỡ | Cần trước | Xong khi |
|---|---|---|---|---|---|---|
| BE-01 | Thiết lập tài khoản AWS: MFA, IAM Identity Center hoặc user riêng cho từng người, **AWS Budgets 10/25/50 USD**, CloudTrail, region `ap-southeast-1`; hỏi ban tổ chức về credit | BE1 chính, BE2 cùng làm | W1 | S | — | 4 người đăng nhập được; email Budgets test nhận được |
| BE-02 | Khung repo theo ARCH §8.1: `backend/` (Java 21, Maven Wrapper `mvnw` (commit kèm quyền chạy: `git update-index --chmod=+x backend/mvnw`), Spotless, JUnit 5), `ai/` (Python 3.12, `uv`, `ruff`, `pytest`), `contracts/`, pre-commit, `CHANGELOG.md`, conventional commits, CODEOWNERS, template PR. Lệnh chuẩn chạy được trên Windows/macOS/Linux: `./mvnw verify` (Windows: `mvnw.cmd verify`) và `uv run ruff check . && uv run pytest` | BE2 chính, BE1 cùng làm | W1 | S | — | Hai lệnh trên chạy được trên máy sạch; CI chạy cả hai trên `ubuntu-latest`, `windows-latest`, `macos-latest` |
| BE-03 | SAM skeleton: `webhook` (hello), DynamoDB `askfirst` (PK/SK, GSI1, TTL, PITR), SQS `jobs` + `jobs-dlq`, S3 bucket (Block Public Access, prefix `models/`, `kb/`, `donated/` + lifecycle), SSM params; stage `dev`. Hai người cùng chạy `sam deploy` lần đầu và cùng viết `infra/README.md` (cách deploy, rollback) | BE2 chính (DynamoDB, S3, SSM), BE1 (`webhook`, SQS) | W1–W2 | M | BE-01 | `sam deploy` tạo đủ tài nguyên; `curl` Function URL trả 200 |
| BE-14 | **Spike Java + AWS học chung**: mỗi người dẫn 1 spike, người kia ngồi cùng: (1) BE1: Java `worker` (SnapStart) gọi `ai` stub bằng `InvokeFunction`, đo overhead và cold start cộng dồn; (2) BE2: DynamoDB Enhanced Client + Testcontainers (DynamoDB Local) chạy được trên cả Windows/macOS/Linux (hoặc ghi rõ phải chạy trên CI). Thứ Sáu W1 mỗi người trình bày spike của **người kia** 10 phút | BE1 + BE2 | W1 | S mỗi người | BE-01 | Có số đo đưa vào ADR-007 (bản nháp); `backend/README.md` có mục "chạy test DynamoDB local"; mỗi người tự chạy lại được spike của người kia |
| BE-12 | Trực trong test nội bộ (M5): xoay vòng mỗi ngày một người (cả 2 BE và 2 AI, BE trực kèm AI những ngày đầu); xem dashboard, xử lý DLQ theo runbook BE-08b | BE1 + BE2 (xoay vòng cùng cả team) | W6 | — | M4 | Có log sự cố mỗi ngày |

#### BE1: Lane A, luồng kiểm tra tin (webhook đường chậm → SQS → `worker` → `ai`)

Code Java **và** hạ tầng của lane A: SQS/DLQ, SnapStart, IAM và image của `ai`, metric và dashboard. Pair: BE2.

| Mã | Việc | Tuần | Cỡ | Cần trước | Xong khi |
|---|---|---|---|---|---|
| BE-05 | **Bedrock ở `ap-southeast-2`**: bật model access (Qwen3 32B, gpt-oss-120b, Gemma 3 12B, Nova Lite, Qwen3 VL 235B để thử); **xin tăng quota ngay**; ghi model ID copy từ console vào `ai/config/models.yaml` | W1 | S | BE-01 | AI1 gọi được Converse tới Qwen3 32B từ máy dev |
| BE-05b | **Codegen + `AiClient` (Java)**: plugin jsonschema2pojo đọc `contracts/schema/`, sinh class vào `target/generated-sources` (Jackson, `SNAKE_CASE`); `AiClient` (`InvokeFunction`, timeout 95 giây, không retry SDK, map `FunctionError` → `retryable`); `FakeAiClient` đọc `contracts/fixtures/`; test contract phía Java (§3.9). **Spike W1:** xác nhận jsonschema2pojo sinh đúng enum, `Instant`, trường tuỳ chọn, `Map<String,Integer>` từ schema ở §3.3; không ổn thì viết tay `record` + giữ `FixturesRoundTripTest` làm lưới an toàn (ghi ADR-000b) | W1–W2 | M | G0 | `./mvnw test -Dtest='**/contract/*'` xanh với fixtures của `StubPipeline`; ADR-000b có kết luận codegen |
| BE-22 | **Webhook handler (Java)**: kiểm secret bằng `MessageDigest.isEqual` (constant-time, không dùng `String.equals`), dedup `update_id` (conditional put, TTL 24 giờ), chỉ nhận chat private, đường nhanh (lệnh, callback) hoặc đường chậm (gửi "⏳" rồi enqueue); luôn trả 200 nhanh; secret đọc lười sau SnapStart restore | W2 | M | BE-03, BE-21 (dùng fixtures của BE-21 khi adapter chưa xong) | Test: secret sai trả 401; gửi trùng update thì chỉ xử lý 1 lần; p95 handler < 500 ms (warm) |
| BE-07 | Đường ống SQS → worker (Java): event source mapping (batch 1, **max concurrency 5**, ReportBatchItemFailures), visibility **720 giây** (6 × timeout 120 giây của worker), `maxReceiveCount=5`, alarm DLQ; xử lý DLQ: gửi người dùng câu "Bot đang bận"; bật **SnapStart** cho `webhook`/`worker` (published version + alias) | W2–W3 | M | BE-03 | Test: job lỗi 5 lần thì vào DLQ, alarm bắn, người dùng nhận tin báo bận; đo cold start Java có/không SnapStart, ghi ADR |
| BE-24b | **Repos lane A** (Java, trên nền `store/` của BE-24): `CHECK#`, `SESSION#`, `BUFFER#`, `DEDUP#`, `ALERT#`, `ALERTLOCK#`, `CONFIG#global/FLAGS` (ARCH §7) | W2 | S | BE-24 (phần nền) | Unit test CRUD + TTL + conditional write pass, dùng chung cấu hình Testcontainers của BE-24 |
| BE-27 | **Worker orchestration (Java) + `ai` bản stub**: cài đúng luồng mã giả ở **§3.2** (`analyze` → cảnh báo người thân qua `render_guardian_alerts` → `render` → sửa tin "⏳" → `CHECK` theo §3.7 → `SESSION#` → kết thúc buffer), xử lý `AiError` (retryable → SQS retry, fatal → câu suy giảm), xử lý job `answer` và `followup` (§3.4). Deploy Lambda `ai` với `PIPELINE_IMPL=stub` (cùng AI1) | W2–W3 | M | BE-07, BE-24, BE-24b, BE-05b, AI-03b | Với `ai` stub, gửi tin vào bot `dev` thì nhận kết quả giả trong < 5 giây; unit test worker dùng `FakeAiClient` phủ cả nhánh lỗi |
| BE-23 | **Gom tin** (ARCH §5.2): `BUFFER#chat` cập nhật nguyên tử, `seq`, "⏳" **chỉ cho tin đầu cụm**, `DelaySeconds=3`, bỏ job cũ, **xoá buffer có điều kiện** (`max_seq == seq`, sai thì ghi `done_seq`) | W3 | M | BE-27 | Test: gửi 3 tin trong 2 giây thì có đúng 1 tin "⏳" và 1 câu trả lời; album 4 ảnh thì 1 câu trả lời; tin đến đúng lúc worker đang xử lý vẫn được trả lời |
| BE-13 | **Kill switch** (ARCH §5.8): cờ ở DynamoDB `CONFIG#global/FLAGS` (worker Java cache 30 giây, gửi cho `ai` trong `AnalyzeContext.flags`), `scripts/flags.py llm off\|on`; **SNS topic nhận alarm** gửi email (SAM) | W5 | S | BE-03, BE-07 | Test: chạy `flags.py llm off` thì trong ≤ 30 giây `ai` không gọi Bedrock (`path=rules_only`); alarm DLQ test gửi được email |
| BE-34 | Chặn media trước khi tạo job (Java, ở webhook): kiểm `duration ≤ 120` giây và `size ≤ 20 MB` từ metadata của update; gom album theo `media_group_id`. Việc tải thật nằm ở `ai` (AI-03b). Voice ⏸ hoãn: trong MVP webhook trả lời "chưa hỗ trợ voice" như khi `feature_voice=false`, vẫn giữ kiểm `duration` để bật lại sau | W4 | S | BE-21 | Ảnh > 20 MB bị từ chối kèm câu giải thích; voice (mọi độ dài) nhận câu "chưa hỗ trợ"; không có job nào được enqueue |
| BE-06 | **Container image cho Lambda `ai`** (`ai/Dockerfile`): base Lambda Python 3.12, onnxruntime, stack OCR đã chốt ở G2 (ffmpeg tĩnh và sherpa-onnx chỉ cần khi làm voice, ⏸ hoãn); SAM cho function `ai` (alias `live`, resource policy chỉ cho role `worker`, IAM theo ARCH §8.3: DynamoDB `LeadingKeys` `CACHE#`/`URL#`, SSM bot token); thử **arm64**, không được thì x86_64. Weights do `S3ArtifactStore` (AI2) tải vào `/tmp` | W3–W4 | L | G2, AI-26 | Image ≤ 2 GB; đo **cold start** của `ai` và cold start cộng dồn `worker`+`ai`, ghi vào ADR; memory 3–4 GB chạy được (kiểm quota memory của tài khoản mới); build image chạy trên GitHub Actions, không build trên máy cá nhân |
| BE-29 | 🔀 **Cảnh báo người thân + nhắc lại** (ARCH §5.5): gửi khi `notify_guardian=true`, nội dung theo `share_level` (3 mức), **chỉ nói "đã báo" khi có `message_id`** (lỗi 403/400 thì nói thật), gửi **song song cho mọi người thân `active`**, nút [Tôi đã liên hệ], job `followup` delay 900 giây **nhắc lại một lần** nếu chưa ai ACK (nhắc người thân gửi im lặng), chống trùng cùng kịch bản trong 30 phút (`ALERTLOCK#`), nút "Gọi cho con" (`sendContact`). Đọc dữ liệu người thân qua repos của BE2 (`GUARDIAN#`, `share_level`); BE2 là pair bắt buộc | W4 | M | BE-28, INT-01 | Demo: tin nguy hiểm thì điện thoại "con" rung trong ≤ 5 giây sau kết quả; 15 phút sau có tin nhắc; test "người thân đã chặn bot" thì người cao tuổi **không** thấy dòng "đã báo"; mọi URL trong tin cảnh báo đã defang |
| BE-33 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** Multi-turn (ARCH §6.12): lưu `SESSION#` bản đã mask, nút [Có] [Không] [Không nhớ], tối đa 2 lượt | Hoãn | M | AI-12 | Kịch bản "có người gọi bảo bác…" hỏi thêm được 1 câu rồi kết luận |
| BE-08a | Observability, phần đo: Powertools **Java** (Logging, Metrics EMF) ở `webhook`/`worker`, Powertools **Python** Logger ở `ai` (cùng AI1), cùng khoá `trace_id`; metrics theo ARCH §11 (thêm `ai_invoke_ms{op}`, `contract_error_total`); CloudWatch dashboard | W4–W5 | M | INT-02 | Dashboard hiện số thật từ `dev`; BE2 đọc dashboard và tự tìm được 1 request chậm theo `trace_id` |
| BE-09a | 🔀 **Rà bảo mật lane B** (người không viết code đó rà): IAM của `webhook`, secret webhook, chặn khi chưa đồng ý, `callback_data` không chứa dữ liệu cá nhân, test "log không chứa nội dung" phía webhook và luồng người thân, runbook xoay vòng bot token | W5 | S | — | Checklist ARCH §9.3 phần lane B đủ; issue cho mọi lỗi tìm được |
| BE-10 | Đo tải nhẹ + cold start: 50 tin liên tục và 10 tin đồng thời; script **làm nóng trước demo** | W6 | S | INT-03 | Có bảng p50/p95 theo stage; script warm-up chạy được |

#### BE2: Lane B, người dùng, người thân và dữ liệu (Telegram adapter, đường nhanh, DynamoDB, CI/CD)

Code Java **và** hạ tầng của lane B: nền DynamoDB, CI/CD, stage `prod`, alarm và runbook. Pair: BE1.

| Mã | Việc | Tuần | Cỡ | Cần trước | Xong khi |
|---|---|---|---|---|---|
| BE-20 | Tạo 2 bot (`dev`, `prod`) qua BotFather; `scripts/set_commands.py`; `scripts/set_webhook.py` (secret token, `allowed_updates`, `drop_pending_updates`) theo ARCH §8.2; **chế độ polling cho dev local**: `./mvnw exec:java@local-bot` (trong `backend/`) gọi `getUpdates` rồi đưa từng update qua đúng `WebhookHandler` | W1 | S | — | Gõ `/start` vào bot `dev` thì log in ra trên máy |
| BE-21 | **Telegram adapter (Java)** (ARCH §4.4): `parseUpdate` cho text, photo (chọn `PhotoSize` lớn nhất), album, voice, audio, document, forward, callback, `my_chat_member` → `InboundMessage`/`CommandEvent`/`CallbackEvent`; `sendText`, `editText`, `sendContact`, `typing` (`java.net.http.HttpClient` + Jackson); xử lý lỗi 429 (`retry_after`). **Không có `downloadMedia`** (media do `ai` tải, §3.6). **Contract test** bằng payload thật đã ghi lại (`backend/src/test/resources/telegram/*.json`); `InboundMessage` sinh ra phải hợp lệ theo `contracts/schema/InboundMessage.json` | W1–W2 | M | G0, BE-05b | ≥ 12 fixture pass; coverage adapter ≥ 80% (JaCoCo) |
| BE-24 | **DynamoDB nền + repos lane B** (Java, `store/`, DynamoDB Enhanced Client): lớp nền dùng chung (mapper, conditional write, TTL, cấu hình **DynamoDB Local qua Testcontainers** cho cả hai lane, cần Docker; máy không có Docker thì chạy trên CI); repos `USER#…/PROFILE`, `GUARDIAN#`, `WARD_OF#`, `INVITE#`, `RATE#`, `AUDIT#`. `CACHE#`/`URL#` do `ai` sở hữu, repos lane A ở BE-24b | W2 | M | G0 | Unit test CRUD + TTL + conditional write pass; phần nền merge trong 2 ngày đầu W2 để BE1 làm BE-24b |
| BE-04 | CI/CD GitHub Actions dùng **OIDC** (không lưu access key): mỗi PR chạy `./mvnw verify` (Java) + `uv run ruff check . && uv run pytest` (Python) + job `contract` (§3.9) trên ma trận `ubuntu-latest`/`windows-latest`/`macos-latest`; build image `ai` và zip Java trên Actions; deploy `dev` khi merge vào `main`; deploy `prod` thủ công có duyệt. Trước khi có BE-04, cả hai deploy `dev` bằng `sam deploy` tay theo `infra/README.md` | W3 | M | BE-03 | Merge một PR thì `dev` tự cập nhật; CI xanh trên cả 3 OS |
| BE-25 | **Onboarding và đồng ý** (ARCH §5.3): lời chào, tóm tắt chính sách, nút đồng ý, lưu `consent_version` + `policy_sha256` + `AUDIT`; chưa đồng ý thì **không xử lý nội dung**; **đồng ý theo 4 mục đích** (`analyze`, `guardian`, `voice`, `donate`; hỏi ngay trong luồng cần nó), `/caidat` (chỉ xưng hô), **đồng ý lại khi đổi phiên bản chính sách**; bàn phím cố định 3 nút; `/huongdan`, `/bilua` (checklist từ KB) (ARCH §5.3, §2.5) | W3 | M | BE-22, SH-07 | Test: chưa đồng ý thì không có job nào được enqueue; đổi `policy_sha256` thì bị chặn tới khi đồng ý lại; chưa đồng ý `voice` thì voice bị từ chối |
| BE-26 | Rate limit theo user (30/giờ, 100/ngày) bằng `RATE#` + `ADD` nguyên tử | W3 | S | BE-24 | Test: tin thứ 31 trong giờ nhận câu nhắc nhẹ |
| BE-35 | **Đầu vào không hỗ trợ và `.apk`** (ARCH §5.7): video/sticker/vị trí/danh bạ/document không phải ảnh thì không tải và hướng dẫn thay thế; `.apk` thì không tải, trả cảnh báo riêng; cắt text ở 4.000 ký tự. Xử lý ngay ở webhook, không qua SQS | W4 | S | BE-21 | Test từng loại; `.apk` không bao giờ bị tải về |
| BE-28 | **Liên kết người thân double opt-in** (ARCH §5.4): `/nguoithan`, `INVITE#` dùng một lần, deep link `g_<code>`, người thân đồng ý, `request_contact`, người cao tuổi xác nhận + chọn mức chia sẻ, huỷ liên kết, `my_chat_member` → `unreachable` | W4 | L | BE-25 | Kịch bản test tay 6 bước pass trên 2 tài khoản Telegram thật; mã hết hạn hoặc đã dùng thì bị từ chối |
| BE-30 | 🔀 Feedback `fb:wrong` + câu hỏi phụ + "Đóng góp mẫu" (đồng ý riêng, bản đã mask lưu `donated/`); đọc `SESSION#` của lane A, BE1 là pair bắt buộc | W5 | S | BE-27 | Feedback hiện trong `CHECK.feedback` |
| BE-31 | 🔀 `/xoadulieu`, `/rutdongy` (toàn bộ hoặc theo mục đích), `AUDIT` (ARCH §5.6); xoá mẫu đóng góp theo prefix `donated/<hash(uid)>/`; hướng dẫn người dùng tự xoá chat Telegram; xoá cả dữ liệu lane A (`CHECK#`, `ALERT#`, `SESSION#`, `BUFFER#`), BE1 là pair bắt buộc | W5 | M | BE-24, BE-24b | Test: sau khi xoá, query `USER#uid` chỉ còn bản ghi audit tối thiểu và prefix `donated/` của user trống |
| BE-36 | **Quản lý trong chat** (ARCH §5.9): `/nguoithan` theo vai trò (người cao tuổi: danh sách + [Đổi mức] [Tạm dừng/Bật lại] [Gỡ] [Mời]; người thân: danh sách người mình bảo vệ + [Ngừng nhận cảnh báo]); `/lichsu` 10 lượt gần nhất, chỉ metadata, nút [Xem thêm] mang `ts` trong `callback_data`; chạy ở đường nhanh của webhook | W5 | M | BE-28, BE-24, BE-24b | Test: người thân gõ `/lichsu` không thấy lịch sử của người cao tuổi; `callback_data` ≤ 64 byte, không chứa dữ liệu cá nhân; 2 người lớn tuổi (người nhà) dùng thử đổi mức và xem lịch sử mà không phải hỏi |
| BE-08b | 🔀 Alarm + runbook cho **cả hệ thống**: alarm (DLQ, lỗi `worker`/`ai`, throttle `ai`, p95 > 20 giây, Bedrock throttle, chi phí ước tính mỗi ngày) gửi qua SNS topic của BE-13; mỗi alarm có 2–3 dòng runbook "bắn thì làm gì" | W6 | S | BE-08a, BE-13 | Alarm test bắn được; BE2 tự xử lý 1 alarm của lane A theo runbook mà không cần hỏi BE1 |
| BE-09b | 🔀 **Rà bảo mật lane A + `ai`**: IAM `worker`/`ai` least-privilege (đúng ARN model ở `ap-southeast-2`, `ai` chỉ ghi `CACHE#`/`URL#`, chỉ `worker` invoke được `ai`), SnapStart không chụp secret vào snapshot, log Java và Python không lọt nội dung hay URL tải file có token, quét dependency (OWASP Dependency-Check/Dependabot cho Maven, `pip-audit` cho `ai/`), chạy `/sec` hoặc security review | W5 | S | — | Checklist ARCH §9.3 phần lane A đủ; test "log không chứa nội dung" pass ở cả hai phía |
| BE-11 | Stage `prod`: bot riêng, stack riêng, PITR, runbook sự cố một trang | W6 | M | BE-04 | Bot `prod` trả lời được; runbook có người thứ hai đọc và làm theo được |
| BE-32 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** *(MVP+)* **API cho Mini App** (`λ api`, Java + SnapStart): kiểm `initData` (HMAC-SHA-256 bằng `javax.crypto.Mac`, so sánh `MessageDigest.isEqual`, `auth_date` ≤ 1 giờ, có test vector), đủ endpoint trong `openapi.yaml`, CORS chỉ cho origin của FE | Hoãn | M | BE-24, contracts | Contract test khớp OpenAPI; `initData` giả bị từ chối |

### 6.3 AI

#### AI1: LLM, Eval & phần khó (thiendt1608)

| Mã | Việc | Tuần | Cỡ | Cần trước | Xong khi |
|---|---|---|---|---|---|
| AI-01 | **Eval harness** (`eval/run_eval.py`): đọc JSONL, chạy `Pipeline` (hoặc từng stage) **in-process bằng Python, không qua Lambda**, tính recall, tỷ lệ báo nhầm trên `benign_suspicious`, F1 theo nhóm, JSON hợp lệ, p50/p95, chi phí, tỷ lệ trích dẫn bị loại; xuất `eval/report.md` và JSON để so giữa các lần chạy; cache kết quả LLM theo hash để chạy lại không tốn tiền | W1–W2 | M | G0 | Chạy được với `StubPipeline` và 10 mẫu giả |
| AI-02 | **Bedrock client** (`llm/bedrock.py`) gọi `ap-southeast-2`: Converse, retry có backoff và jitter, timeout, đếm token và tính chi phí, đổi model bằng config. **Spike** (ghi vào ADR): (1) Qwen3 32B chạy qua Converse hay phải dùng `bedrock-mantle`; (2) structured outputs có hỗ trợ không; (3) **cách tắt thinking**; (4) độ trễ thực tế | W1 | M | BE-05 | ADR-001 trả lời đủ 4 câu |
| AI-03 | **Benchmark xác nhận Qwen3 32B** (ARCH §6.9): prompt v0, 100 mẫu (SH-03 v0); so Qwen3 32B (tắt/bật thinking), gpt-oss-120b, Gemma 3 12B và Claude Haiku 5.5 làm mốc; chấm tay tiếng Việt 20 mẫu | W2 | M | AI-01, AI-02, SH-03 v0 | **Gate G1** có quyết định trong ADR-002 |
| AI-03b | **Runtime Lambda `ai`** (§3.2, §3.5): `handler.py` nhận `AnalyzeRequest`/`RenderGuardianAlertsRequest`/`RenderRequest`, validate bằng Pydantic, **không bao giờ raise** (mọi lỗi → `AiResponse.error`, `retryable` hoặc `fatal`); cache `CACHE#` (fingerprint, `DynamoKV`), đọc bot token từ SSM để tải media (cùng AI2: `media/telegram.py` dựng `Bundle` theo §3.6, `S3ArtifactStore`); log chỉ có `trace_id` + metadata. Deploy với `PIPELINE_IMPL=stub` cùng BE1 (BE-06 bản tối thiểu: image chỉ có Python + Pydantic) | W2 | M | G0, BE-03 | Gọi `aws lambda invoke` với fixtures trong `contracts/fixtures/` trả đúng `AiResponse`; test: request sai schema trả `fatal`, Bedrock throttle trả `retryable`; test "log không chứa nội dung, không chứa URL có token" pass |
| AI-04 | **Judge prompt v1** + output schema (ARCH §6.6) + **post-validation**: trích dẫn phải là chuỗi con, mã kịch bản phải có trong taxonomy, cắt hotline/URL khỏi giải thích, lọc từ cấm, phát hiện lẫn tiếng Anh | W2–W3 | M | G1 | Unit test post-validation; JSON hợp lệ ≥ 98% trên bộ v0 |
| AI-05 | **Fuse** (ARCH §6.7): điểm, mức, sàn cứng, giảm điểm khi là N0, chế độ chỉ chạy luật, `notify_guardian`, logic **fast path** | W3 | S | G0 | Unit test bao mọi nhánh (≥ 15 case) |
| AI-06 | **Respond** (ARCH §6.8): `render()` và `render_guardian_alert()` theo §3.3 (3 mức chia sẻ, nút `ack`); template Jinja2 cho 4 mức và cho `outcome` smalltalk/unreadable, N0 dạng OTP thật, giải thích fast path, verdict lấy từ cache (explanation rỗng) thì ghép từ template theo mã dấu hiệu, trích nguồn cảnh báo, disclaimer, dòng trạng thái người thân theo `RenderContext.alerts`; trả `ReplyPlan` (nút dạng trừu tượng); **defang URL** (`hxxps://…[.]…` trong `<code>`, tắt link preview, chỉ URL allowlist mới là nút URL), chia tin > 4.096 ký tự, kết luận ≤ 600 ký tự (ARCH §6.8) | W3 | M | AI-05, SH-08 | Snapshot test cho 4 mức; mọi tin ≤ 4096 ký tự; **test duyệt mọi template (kết quả và cảnh báo người thân): fail nếu có URL nghi ngờ chưa defang hoặc chuỗi input chưa `html.escape`** |
| INT-01 (AI) | Đóng gói `RealPipeline` cho **văn bản** (normalize → mask → rules → judge → fuse → respond) trong image `ai`, bàn giao cho BE1 bật `PIPELINE_IMPL=real` | W3 | S | AI-04, AI-05, AI-22, AI-23, AI-03b | Xem INT-01 ở §7 |
| AI-07 | **Cascade và fallback** (ARCH §6.9): **một** model dự phòng (gpt-oss-120b) dùng chung cho leo thang (không chắc, mâu thuẫn với luật, JSON hỏng) và khi lỗi/throttle; lỗi tiếp thì chế độ chỉ chạy luật; tôn trọng cờ `LLM_ENABLED` | W4 | M | AI-04 | Test giả lập throttle thì vẫn trả được `Verdict` với `path=rules_only` |
| AI-26 | **OCR production** (`local_models/ocr.py`; **AI2 phụ**: `MODELS.md`, decode QR, regex sửa URL): stack đã chốt ở G2, ONNX int8, lazy load, sửa URL bằng regex (giữ cả bản gốc và bản đã sửa), `mean_conf`, **decode QR local**; `MODELS.md` (license, checksum) | W4 | M | G2 | 50 ảnh trong `ocr_images`: chép đúng URL ≥ 90%; p95 ≤ 3 giây trên Lambda 3–4 GB (đo cùng BE1) |
| AI-24b | **URL intel, phần mạng** (ARCH §6.4): mở rộng shortener **chống SSRF** (chặn IP private, loopback, link-local, metadata; kiểm lại sau mỗi redirect), RDAP tuỳ chọn, cache `URL#` (`DynamoKV`) | W4–W5 | M | AI-24a | Test SSRF: URL trỏ `169.254.169.254` hoặc `127.0.0.1` (kể cả qua redirect) đều bị chặn |
| AI-10 | **Hiệu chỉnh ngưỡng** 75/50/25 trên bộ v1 (300 mẫu); vẽ đường đánh đổi recall và báo nhầm | W5 | M | SH-03 v1, INT-02 | ADR-005 ghi ngưỡng đã chọn kèm số liệu |
| AI-13 | **Báo cáo eval cho MVP freeze**: số liệu trên dữ liệu thật và dữ liệu tổng hợp **tách riêng**, kèm chi phí mỗi lượt | W5 | S | AI-10 | Gate M4 |
| AI-12 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** Xử lý `followup_question` trong prompt và ghép câu trả lời ở lượt sau | Hoãn | S | AI-04 | 5 mẫu mơ hồ đều hỏi lại đúng 1 câu |

#### AI2: Dữ liệu, KB & việc dễ, có AI1 kèm (minhvhq)

> AI2 đang học: mỗi việc bắt đầu bằng design note ≤ ½ trang (AI1 duyệt), PR ≤ 300 dòng, AI1 review mọi PR. Kẹt quá nửa ngày thì hỏi AI1 ngay, không ngồi một mình.

| Mã | Việc | Tuần | Cỡ | Cần trước | Xong khi |
|---|---|---|---|---|---|
| AI-20 | **Spike OCR** (ARCH §6.3; **AI2** dựng bộ ảnh, cài và chạy 3 stack, ghi số đo; **AI1** cùng phân tích và chốt ADR-003): dựng 50 ảnh chụp màn hình thật (SMS, Zalo, Messenger, web giả; đã che thông tin) **cùng cả team**; so (a) RapidOCR/PP-OCRv5, (b) Paddle det + VietOCR, (c) Tesseract `vie` theo CER, tỷ lệ đúng dấu, **tỷ lệ chép đúng URL**, độ trễ CPU; kiểm license | W1–W2 | M | — | **Gate G2 (OCR)**: ADR-003 |
| AI-22 | **Mask PII** (ARCH §9.2): SĐT, STK gần từ khoá ngân hàng, CCCD, **số viết bằng chữ**; **mã OTP/PIN và email** (v3.2); token `[SĐT_1]`, `[STK_1 • …]`, `[OTP_1]`; không mask URL. Viết như **test bảo mật** | W2–W3 (hạn 04/11) | M | G0 (AI1 review như test bảo mật) | ≥ 40 case test; không mẫu nào trong bộ eval lọt số hay mã OTP vào prompt; luật `R_OTP_*` chạy đúng trên văn bản đã mask |
| AI-23 | **Chuẩn hoá văn bản + rules engine v1** (ARCH §6.4): NFC, bỏ zero-width, bản không dấu, gộp chữ bị tách, leetspeak; nạp `rules.yaml`, trọng số, sàn cứng, tổ hợp | W3 (hạn 04/11) | M | AI-22 (AI1 dựng khung interface trước) | Mỗi luật có ≥ 2 test dương, 1 test âm; chạy < 10 ms/tin |
| AI-25 | **Dữ liệu KB v1**: `scenarios.yaml` (khoảng 20 card N0–N6 theo research-concept §2.2), `allowlist.yaml`, `brands.yaml`, `hotlines.yaml` (từ SH-05), template checklist | W2–W3 | M | SH-02, SH-05 | Validator schema pass; mỗi mục có nguồn |
| AI-24a | **URL intel, phần cơ bản** (ARCH §6.4): tách URL, so eTLD+1 với allowlist, **gộp URL từ text + `entities` (`url`, `text_link`) và tín hiệu `U_TEXT_LINK_MISMATCH`**, typosquat (Levenshtein so với brands), punycode/homoglyph. **Không gọi mạng** | W3–W4 | M | AI-23 | 10 domain giả trong cảnh báo chính thức bị gắn cờ; domain trong allowlist không bị gắn cờ |
| AI-08 | **Guardrails**: tạo guardrail (input: chỉ prompt attack; output: denied topics), gọi `ApplyGuardrail`, phát hiện injection thì **cộng điểm** (ARCH §6.10); test bằng câu tiếng Việt | W4 | S | AI-02 (AI1 duyệt) | 10 mẫu injection đều bị gắn cờ; không chặn nhầm tin lừa đảo thường |
| AI-29 | **Embedding + Retriever** (ARCH §6.5): e5-small ONNX int8, `build_index.py`, top-4 scenario cards (luôn kèm N0), top-3 cảnh báo; **corpus 100 cảnh báo chính thức** (tóm tắt team tự viết + URL + ngày) | W4–W5 | M | AI-25 (AI1 kèm phần nạp model ONNX) | Recall@4 của scenario card ≥ 90% trên `real_scam`; truy vấn < 100 ms |
| AI-09 | **Eval trong CI** (cùng BE2, người làm CI ở BE-04): workflow chạy khi đổi `prompts/`, `kb/`, `rules.yaml` hoặc `ai/config/models.yaml`; comment bảng so sánh vào PR; **chặn merge** nếu recall < 90% hoặc báo nhầm > 10% (bật chặn từ M4) | W4–W5 | M | AI-01, BE-04 | Một PR làm hỏng prompt bị CI đánh đỏ |
| AI-11 | **LLM-as-judge** chấm phần giải thích (Claude Sonnet 5.5, rubric ở ARCH §6.11) + chấm tay 20 mẫu | W5 | S | AI-06, AI-02 (AI1 duyệt rubric) | Báo cáo chất lượng giải thích trong `eval/report.md` |
| AI-14 | **Red-team**: viết không dấu, emoji, tách link, prompt injection trong ảnh, tin "ngân hàng nhắc cảnh giác" thật; bổ sung vào split `hard` | W5–W6 | S | INT-03 | ≥ 30 mẫu `hard` mới; bug tìm được đã có issue |
| AI-21 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** **Spike ASR**: 20+ voice (Bắc, Trung, Nam; có giọng người lớn tuổi nếu xin được, có đồng ý); so Zipformer-vi int8, Zipformer-vi 30M, PhoWhisper-small theo WER, RTF; **kiểm license repo gốc của Zipformer-vi** | Hoãn | M | — | **Gate G2 (ASR)**: ADR-004 |
| AI-27 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** **Fallback đọc ảnh bằng VLM** (ARCH §6.3): điều kiện kích hoạt (conf thấp, ít chữ, ảnh giống giấy tờ), **làm mờ vùng có số** trước khi gửi, VLM chỉ **mô tả** (JSON), không phán. Chọn model trên 20 ảnh khó: Gemma 3 12B, Nova Lite hay Qwen3 VL 235B | Hoãn | M | AI-26, AI-02 | ADR-006 chọn VLM; 20 ảnh khó cải thiện recall so với chỉ dùng OCR |
| AI-28 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** **ASR production** (`local_models/asr.py`): ffmpeg sang PCM 16 kHz, VAD, sherpa-onnx, lazy load; transcript chạy qua AI-22 để mask số viết bằng chữ | Hoãn | M | G2, BE-06 | Voice 60 giây xử lý ≤ 10 giây trên Lambda; WER đúng như số đo lúc spike |

### 6.4 FE (vibe coding)

> **ARCH v3.4:** chỉ **FE-00** (trang chính sách tĩnh) là MVP. **FE-01…FE-07 là MVP+**: ⏸ **hoãn ngoài phạm vi 6 tuần** (v3); chỉ làm nếu M4 đạt sớm và còn thời gian, khi đó cần thêm BE-32 và INT-04.
>
> Gợi ý: dùng Vite + React (hoặc Next.js static export) + `telegram-web-app.js`. Màu lấy theo `themeParams` của Telegram. **Chữ to, tương phản cao, nút lớn** vì người dùng lớn tuổi. Code theo mock từ `contracts/openapi.yaml` trước, ghép API thật sau.

| Mã | Việc | Người làm | Tuần | Cỡ | Cần trước | Xong khi |
|---|---|---|---|---|---|---|
| FE-00 | **Trang chính sách quyền riêng tư** (static, HTTPS) để gắn link vào lời chào bot | BE2 | W2 | S | SH-07 | Có URL công khai; hiển thị tốt trên điện thoại |
| FE-01 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** Scaffold Mini App + mock API (MSW) + gửi header `Authorization: tma <initData>` + deploy static hosting + gắn nút menu `web_app` cho bot `dev` | (chưa phân) | Hoãn | M | contracts | Mở được Mini App trong Telegram, đọc được dữ liệu mock |
| FE-02 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** Màn hình **"Người thân của tôi"**: danh sách, mức chia sẻ, huỷ liên kết, "Mời người thân" (hiện deep link + nút chia sẻ) | (chưa phân) | Hoãn | M | FE-01 | Chạy được với mock |
| FE-03 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** Màn hình **"Lịch sử kiểm tra"** (chỉ metadata, phân trang) | (chưa phân) | Hoãn | S | FE-01 | Chạy được với mock |
| FE-04 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** Màn hình **"Dữ liệu của tôi"**: xem chính sách, rút đồng ý, xoá dữ liệu (hỏi xác nhận 2 bước) | (chưa phân) | Hoãn | S | FE-01 | Chạy được với mock |
| FE-05 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** Màn hình cho **người thân**: danh sách người mình bảo vệ và cảnh báo gần đây | (chưa phân) | Hoãn | S | FE-01 | Chạy được với mock |
| FE-06 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** **Ghép API thật** (BE-32) trên `dev`, rồi trên `prod` | (chưa phân) | Hoãn | M | BE-32 | Mọi màn hình chạy với API thật; `initData` hết hạn thì hiện thông báo |
| FE-07 | ⏸ **Hoãn (ngoài phạm vi 6 tuần).** **Rà khả năng tiếp cận** cho người lớn tuổi: cỡ chữ ≥ 18 px, tương phản, câu chữ tiếng Việt đơn giản; 2 người lớn tuổi dùng thử | (chưa phân) | Hoãn | S | FE-02…05 | Không ai phải hỏi "bấm vào đâu" ở các thao tác chính |

---

## 7. Điểm tích hợp (INT): nơi 2 track gặp nhau

| Mã | Tuần | Ghép gì | Owner | Checklist bàn giao |
|---|---|---|---|---|
| **INT-01** | W3 | `RealPipeline` cho **văn bản** trong Lambda `ai` (bật `PIPELINE_IMPL=real` cho text); worker Java giữ nguyên | BE1 + AI1 | (1) Pipeline pass eval ≥ 85% trên phần văn bản của bộ v0; (2) IAM của **`ai`** gọi được Bedrock ở `ap-southeast-2` (worker Java không có quyền Bedrock); (3) log cả `worker` lẫn `ai` có cùng `trace_id`, **không có nội dung**; (4) đo p95 trên Lambda, tách `ai_invoke_ms{op}` khỏi phần Java; (5) đổi lại về stub bằng env `PIPELINE_IMPL` của `ai` → **M1** |
| **INT-02** | W4 | Image `ai` đầy đủ + **OCR local** + cache theo `file_unique_id` (trong `ai`); VLM fallback ⏸ hoãn (AI-27) | BE1 + AI1 (AI2 phụ) | (1) Weights trên S3 có checksum; (2) cold start của `ai` và cold start cộng dồn `worker`+`ai` đã đo; (3) p95 ảnh ≤ 15 giây khi warm; (4) ảnh chỉ nằm trong `/tmp` của `ai`, không đi qua payload Java và không ra khỏi Lambda → **M2** (cùng BE-29) |
| **INT-03** | W5 | **RAG** trong `ai` (index trên S3, `ai` nạp lúc cold start); ASR local ⏸ hoãn (AI-28) | BE2 + AI2, AI1 kèm (BE1 pair) 🔀 | (1) Truy vấn RAG < 100 ms, kết luận trích đúng nguồn cảnh báo (deadline gọi `ai` vẫn < 85 giây); (2) index có version; (3) bật flag `FEATURE_RAG` (worker đọc cờ, gửi qua `AnalyzeContext.flags`; `FEATURE_VOICE` giữ tắt) → **M3** |
| **INT-04** *(MVP+)* | Hoãn | Mini App ↔ API thật | BE2 + người làm FE | Contract test + test tay đủ màn hình (không còn là điều kiện của M4) |

**Quy tắc ghép:** ghép vào `dev` trước. Chạy **bộ smoke test 15 tin** (`scripts/smoke.py` gửi tin mẫu vào bot `dev` qua một tài khoản test, rồi kiểm câu trả lời). Đạt thì mới bật flag. Có lỗi thì tắt flag, không revert cả nhánh.

---

## 8. Chiến lược test

| Loại | Phạm vi | Chạy khi | Owner |
|---|---|---|---|
| Unit | Python: rules, mask, fuse, URL intel, post-validation, handler `ai`. Java: adapter, repos, webhook, worker orchestration (với `FakeAiClient`) | Mỗi PR | Người viết code |
| Contract | Schema `contracts/schema` sinh lại không đổi; fixtures `contracts/fixtures/` đọc được ở **cả Python lẫn Java** (§3.9); payload Telegram (fixtures Java); schema SQS (thêm OpenAPI ↔ handler `api` nếu làm Mini App) | Mỗi PR | BE2 + AI1 |
| Bảo mật | Mask không lọt số; log (Java và Python) không chứa nội dung hay URL tải file có token; SSRF; secret webhook sai; URL đã defang và input đã escape; người thân không xem được lịch sử người cao tuổi; chỉ `worker` invoke được `ai` (thêm `initData` giả nếu làm Mini App) | Mỗi PR | AI2 + BE1 + BE2 (BE-09a/BE-09b rà chéo lane của nhau) |
| Eval | 300 mẫu (ARCH §6.11) | PR đổi prompt, KB, rules hoặc model; hằng tuần trên `main` | AI1 |
| Smoke E2E | 15 tin qua bot `dev` thật | Sau mỗi lần deploy `dev`; trước khi bật flag | Người trực tuần (§2.1) |
| Hiệu năng | p50/p95 theo stage, cold start (SnapStart Java, image `ai`, cộng dồn), overhead `InvokeFunction` | W4, W5 | BE1 + AI1 |
| UAT | Kịch bản bấm tay: onboarding, kiểm tin (4 loại input), liên kết người thân, cảnh báo, `/nguoithan`, `/lichsu`, xoá dữ liệu | Trước M2, M4 và trước demo | BE2 (điều phối), cả team |

**Cấp độ bug:** P0 = sai kết luận ở tin lừa đảo rõ ràng, lộ dữ liệu, bot chết · P1 = luồng chính hỏng · P2 = lỗi hiển thị hay copy. Không freeze khi còn P0 hoặc P1.

---

## 9. Rủi ro và phương án dự phòng (gắn với việc cụ thể)

| Rủi ro | Phát hiện ở | Phương án dự phòng |
|---|---|---|
| Qwen3 32B không đạt G1 | AI-03 | Đổi Judge chính sang gpt-oss-120b (cùng giá); vẫn không đạt thì Claude Haiku 5.5. Chỉ đổi `ai/config/models.yaml` (redeploy image `ai`, không đụng Java) |
| Qwen3 không chạy qua Converse | AI-02 | Viết adapter `bedrock-mantle` trong `llm/` (thêm khoảng 1 ngày) |
| Không tắt được thinking của Qwen3 | AI-02 | Chấp nhận độ trễ cao hơn, hoặc giới hạn `max_tokens`, hoặc đổi model |
| OCR local chép sai URL nhiều | AI-20 | Đổi stack OCR; hoặc bật lại **VLM cho mọi ảnh có URL** (AI-27, đã làm mờ số; ⏸ hoãn nên tốn thêm thời gian) |
| ASR local WER kém hoặc license không dùng được (⏸ voice hoãn, chỉ áp dụng nếu làm lại) | AI-21 | PhoWhisper (BSD-3); cùng lắm là Amazon Transcribe `vi-VN` (ARCH §3.2) |
| Cold start của image `ai` quá lâu (> 15 giây), cộng với cold start Java | BE-06, BE-07 | SnapStart cho Java; bake weights vào image; model nhỏ hơn; tách function riêng cho voice; làm nóng `ai` trước demo (BE-10); provisioned concurrency chỉ trong ngày demo |
| Quota Bedrock thấp hoặc chậm duyệt | BE-05 | Xin từ W1; dự phòng gọi `ap-southeast-3` (Jakarta có Qwen3 32B in-region, ARCH §6.9) |
| Tài khoản mới bị giới hạn memory của Lambda | BE-06 | Xin tăng quota; dùng model int8 nhỏ hơn |
| Contract Java ↔ Python lệch nhau (đổi `models.py` mà quên Java) | Job `contract` (§3.9) | CI bắt buộc sinh lại schema + `git diff --exit-code`; fixtures dùng chung; `AiResponse.v` khác số đầu thì Java trả câu suy giảm và đếm `contract_error_total`; đổi phá vỡ phải sửa cả hai phía trong một PR |
| jsonschema2pojo sinh class không dùng được (enum, `Instant`, `Map`) | BE-05b (spike W1) | Viết tay `record` Java cho ~15 model wire, giữ `FixturesRoundTripTest` + `SchemaValidationTest` làm lưới an toàn (ADR-000b) |
| Payload `InvokeFunction` vượt giới hạn hoặc `ai` timeout | INT-02, INT-03 | Media không bao giờ đi qua payload (`ai` tự tải); cắt text 4.000 ký tự; deadline `min(85, remaining-20)` giây, `AiClient` timeout 95 giây; hết giờ thì SQS retry rồi DLQ |
| Thiếu mẫu eval thật | SH-03 | Ưu tiên chất lượng hơn số lượng: 200 mẫu thật tốt hơn 300 mẫu tạp; báo cáo dữ liệu tổng hợp riêng |
| Một người bận (thi cử…) | Họp hằng tuần | BE: mọi việc đã có pair, W1 làm chung, xoay vòng người trực tuần và checklist "ai cũng làm được" (§2.1) nên người kia làm thay được. AI: **cặp AI1–AI2 review code cho nhau** |
| Trễ tiến độ trước M4 | Retro W4 | Multi-turn (BE-33/AI-12), VLM fallback (AI-27) và voice (AI-21/AI-28) **đã hoãn từ đầu** (v3). Nếu vẫn trễ, cắt tiếp theo thứ tự: AI-11 (LLM-as-judge) → BE-10 (đo tải) → phần người thân trong `/nguoithan` (BE-36) → rút gọn AI-14 (red-team) |

---

## 10. Cách làm việc

**Nhịp tuần:**
- **Thứ Hai (30 phút):** chọn việc trong tuần từ §5, cập nhật board (GitHub Projects).
- **Thứ Tư (15 phút):** đồng bộ chéo BE–AI: có contract nào cần đổi không, có gì đang chặn không.
- **Thứ Sáu (45 phút):** demo trên bot `dev` + đọc `eval/report.md` + retro ngắn.

**Git:** nhánh ngắn (`feat/BE-22-webhook`), PR nhỏ (≤ 400 dòng nếu được), **review chéo**: người BE duyệt PR của AI có đụng `domain/`, và ngược lại. Commit theo conventional commits (`feat:`, `fix:`, `test:`…) và cập nhật `CHANGELOG.md`.

**Definition of Done cho mọi việc:**
1. Code + test; lệnh của phía mình pass: `./mvnw verify` (Java, Windows dùng `mvnw.cmd verify`) hoặc `uv run ruff check . && uv run pytest` (Python); đụng `domain/` hay `contracts/` thì job `contract` xanh.
2. Đạt điều kiện "Xong khi" của việc đó.
3. Đã deploy lên `dev` (với việc BE hoặc INT), hoặc chạy được bằng CLI/eval (với việc AI).
4. Cập nhật tài liệu liên quan (README, `MODELS.md`, ADR, `CHANGELOG.md`).
5. Đã có 1 người khác review.

**Mẫu ADR** (`docs/decisions/ADR-00x-<tên>.md`):

```markdown
# ADR-00x: <quyết định>
- Ngày / Người viết / Gate:
- Bối cảnh: (vì sao phải quyết định)
- Phương án đã thử: (bảng số liệu: chất lượng, độ trễ, chi phí, license)
- Quyết định:
- Hệ quả: (việc nào bị ảnh hưởng, việc nào được mở khoá)
- Cách đảo ngược: (nếu sai thì đổi lại thế nào)
```

ADR dự kiến: 001 Bedrock/Qwen3 spike · 002 chốt LLM (G1) · 003 OCR (G2) · 004 ASR (G2) · 005 ngưỡng · 006 VLM fallback · 007 cold start / đóng gói · 000b codegen Java từ JSON Schema.

---

## 11. Test nội bộ (W6, thay cho pilot)

> v3: **bỏ pilot** 10–20 hộ (W8–W9 cũ). Thay bằng test nội bộ ở W6 (23–29/11/2026): chỉ team và người nhà. Vì vậy **không có** số liệu "người dùng thật" để đưa lên slide; số liệu chỉ lấy từ eval và từ buổi test nội bộ, ghi rõ nguồn.

| Việc | Owner | Xong khi |
|---|---|---|
| Mời 5–10 người nhà, ký đồng ý tham gia | BE2 (chính), cả team | Danh sách người đã đồng ý |
| Buổi cài đặt: con cài cho bố mẹ theo luồng onboarding | Cả team | Mọi hộ đã liên kết ít nhất 1 người thân |
| "Bài tập" **có báo trước** (tin mẫu để tập chuyển tiếp). **Không gửi tin lừa đảo giả mà không báo trước** | AI1 | Ít nhất 1 bài tập đã gửi |
| Ghi nhận: số lượt kiểm tra, báo sai do người dùng phản hồi, tỷ lệ "đã liên hệ người thân" (research-concept §9, chỉ mang tính tham khảo ở quy mô này) | AI1 + BE1 | Bảng số liệu test nội bộ |
| Review feedback → thêm vào split `hard` → chỉnh prompt hoặc luật (qua eval gate) | AI1 + AI2 | Mỗi thay đổi đều có số liệu trước/sau |

---

## 12. Phase 2 (⏸ hoãn ngoài phạm vi 6 tuần)

Theo ARCH §16. **Không làm trong 6 tuần (v3).** Giữ lại làm hướng phát triển sau bootcamp; nếu sau này làm mà không đủ thời gian, **dừng ở P2-05** cũng đã có câu chuyện tốt: bot tự cập nhật được chiêu mới.

| Mã | Việc | Owner | Cỡ | Xong khi |
|---|---|---|---|---|
| P2-01 | Khảo sát 5–10 nguồn: RSS hay HTML, `robots.txt`, điều khoản sử dụng | BE2 | S | Bảng nguồn được phép crawl |
| P2-02 | `λ crawler` (Python, ARCH §16) + EventBridge Scheduler (6 giờ/lần) + lưu `raw/` + trích nội dung bài | BE2 + AI2 | M | Mỗi ngày có danh sách bài mới, không lỗi |
| P2-03 | Phễu dedup tầng 0–2 (hash, MinHash, lọc liên quan) | AI2 | M | Bài báo đăng lại cùng một cảnh báo được gộp đúng ≥ 90% |
| P2-04 | Queue `kb-ingest` + `λ kb-processor` (Python, **dùng chung image với `ai`**, handler khác) + bảng `askfirst-kb` | BE1 + AI2 | M | Bài từ crawler chạy hết pipeline |
| P2-05 | Prompt **trích xuất variant card** + **merge judge** + bộ eval 50 cặp bài | AI1 | L | Tỷ lệ gộp sai < 10% |
| P2-06 | Publisher + index có version + con trỏ `KB#CURRENT` + **hot reload ở `ai`** (`ai` đọc `KB#CURRENT`, cache 5 phút) + eval gate + `U_KNOWN_SCAM_DOMAIN` | AI1 + BE1 | M | Chiêu mới được duyệt thì bot nhận ra trong vài giờ, không cần deploy |
| P2-07 | **Duyệt KB bằng script** `scripts/review_kb.py` chạy local (hiện diff, nhập duyệt/sửa/từ chối) vì Mini App là MVP+ (ARCH §16.2); nếu đã có Mini App thì làm màn hình duyệt | BE2 | S | Admin duyệt được 1 card end-to-end |
| P2-08 | *(sau bootcamp, ARCH §16.10 mục 2d)* `λ trending` + `STAT#` gộp từ `CHECK` + tab "Chiêu lừa mới" | BE2 | M | Tab hiện các variant đã duyệt, sắp theo độ hot |

---

## 13. Checklist trước ngày demo (W6, 27/11/2026)

- [ ] Bot `prod` đã **làm nóng** (script BE-10) 10 phút trước giờ demo.
- [ ] Có **video dự phòng** quay sẵn toàn bộ kịch bản demo (phòng mạng hội trường yếu).
- [ ] Kịch bản 4 phút theo research-concept §11: phạt nguội có link giả → ảnh chụp tin nhắn giả "công an" (voice ⏸ hoãn) → OTP thật (chứng minh ít báo nhầm) → kiến trúc + số liệu.
- [ ] Số liệu trên slide **lấy từ `eval/report.md` và Cost Explorer**, ghi rõ nguồn và ngày; tách dữ liệu thật và dữ liệu tổng hợp.
- [ ] Trả lời sẵn các câu hỏi khó: vì sao không dùng Step Functions/OpenSearch/GPU (ARCH §3.2, §6.0); vì sao Telegram mà không phải Zalo (ARCH §2.4); bot sai thì sao; dữ liệu cá nhân xử lý thế nào (ARCH §9).
- [ ] AWS Budgets không có cảnh báo bất thường; tắt provisioned concurrency sau demo (nếu có bật).
