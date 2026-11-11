# ADR-000b: Class Java của contract BE↔AI viết tay, không dùng codegen

- Ngày: 08/11/2026
- Người viết: BE1
- Gate: không (BE-05b)
- Trạng thái: đã áp dụng trong code. **Chưa chạy spike jsonschema2pojo trong repo này**, nên mục "Phương án" không có số liệu cho phương án codegen.

## Bối cảnh

Plan BE-05b dự kiến plugin `jsonschema2pojo-maven-plugin` đọc `contracts/schema/*.json` và sinh class Java. Plan cũng ghi sẵn phương án dự phòng: nếu codegen không sinh đúng enum, `Instant`, trường tuỳ chọn và `Map<String,Integer>` thì viết tay `record` và giữ test round-trip với fixtures làm lưới an toàn.

Khi viết worker, repo đã có `InboundMessage`, `EntityRef`, `MediaRef` viết tay (BE-21) đúng contract. Python dùng `extra="forbid"`, nên Java chỉ được gửi đúng field của schema.

## Phương án

| Phương án | Tình trạng |
|---|---|
| A. jsonschema2pojo sinh vào `target/generated-sources` | Chưa thử trong repo này |
| B. Viết tay `record` trong `AiContract` (Jackson, `@JsonProperty` snake_case, `@JsonInclude(NON_NULL)`) | Đã làm, 12/12 fixtures round-trip đạt |

## Quyết định

Chọn B: `common/.../ai/AiContract.java` chứa toàn bộ record (Flags, AnalyzeContext, SessionContext, AnalyzeRequest, Signal, Citation, Verdict, Button, OutMessage, ReplyPlan, AlertResult, RenderContext, GuardianAlertContext, RenderRequest, RenderGuardianAlertsRequest, AnalyzeResult, RenderGuardianAlertsResult, AiError, AiResponse). `InboundMessage` dùng lại record có sẵn. Các giá trị enum (level, group, kind...) để `String` để ai thêm giá trị mới không làm worker hỏng khi đọc.

Lưới an toàn: `AiContractTest` đọc mọi `contracts/fixtures/<op>/*.json`, parse request và response vào record, rồi kiểm (1) Java không sinh field nào ngoài fixture, (2) mọi giá trị khác null của fixture xuất hiện đúng, (3) `AiResponse` có đúng một kết quả khớp `op` hoặc `error`. Test **không** tìm thấy `contracts/fixtures` thì fail, không skip.

## Hệ quả

- Sửa `models.py` thì phải sửa `AiContract` cùng PR (quy tắc 1 BE + 1 AI duyệt vẫn áp dụng). Fixtures mới là thứ phát hiện sự lệch.
- Chưa có test validate JSON do Java sinh ra với `contracts/schema/*.json` bằng một thư viện JSON Schema (plan có nhắc `SchemaValidationTest`). Hiện chỉ so với fixtures. Field bắt buộc mới thêm vào schema mà chưa có trong fixtures sẽ không bị bắt.
- Không cần plugin trong `pom.xml`, không phụ thuộc cách plugin xử lý `anyOf`/`nullable` của schema xuất bởi `_JavaFriendlySchema`.

## Cách đảo ngược

Thêm `jsonschema2pojo-maven-plugin`, sinh class vào `target/generated-sources`, xoá `AiContract` và sửa import trong `LambdaAiClient`, `AnalyzeProcessor`. `AiContractTest` vẫn dùng được để kiểm lớp sinh ra.
