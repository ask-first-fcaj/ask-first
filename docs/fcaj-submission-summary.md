# Ask First ("Hỏi Trước") — Telegram bot giúp người cao tuổi kiểm tra tin nhắn nghi lừa đảo

Nhóm 4 người (2 backend Java 21, 2 AI Python 3.12) · FCAJ, 19/10/2026 – 29/11/2026 · Repo: https://github.com/ask-first-fcaj/ask-first

## Problem Statement

Lừa đảo trực tuyến ở Việt Nam có quy mô lớn và người cao tuổi là đối tượng bị nhắm nhiều:

- Bộ Công an ước tính thiệt hại do lừa đảo trực tuyến năm 2025 là hơn 8.000 tỷ đồng (VOV: https://vov.vn/phap-luat/thiet-hai-tu-lua-dao-truc-tuyen-len-toi-8000-ty-dong-trong-nam-2025-post1286479.vov).
- Khảo sát 2025 của Hiệp hội An ninh mạng Quốc gia (NCA): mạo danh công an là hình thức phổ biến nhất, chỉ 32,12% nạn nhân trình báo (Báo Hải Phòng: https://baohaiphong.vn/cac-hinh-thuc-lua-dao-truc-tuyen-nao-pho-bien-nhat-nam-2025-532711.html).
- Công an Thái Nguyên: nạn nhân kịch bản giả danh công an "chủ yếu là người cao tuổi, người sinh sống ở vùng sâu, vùng xa" (Bộ Công an: https://www.bocongan.gov.vn/bai-viet/kip-thoi-ngan-chan-vu-viec-gia-danh-luc-luong-cong-an-lua-dao-chiem-doat-tai-san-1782987242).

**Khoảng trống:** ngân hàng cảnh báo ở bước chuyển tiền, nhưng đòn thao túng xảy ra sớm hơn, khi kẻ gian cô lập nạn nhân ("không được trao đổi với ai"). Trong các vụ được ngăn kịp luôn có một người thứ ba xen vào trước khi chuyển tiền. Người cao tuổi chưa có công cụ đơn giản để chuyển tiếp ảnh chụp hay link và nhận lời giải thích dễ hiểu, trong khi con cháu thường không ở bên cạnh.

**Người dùng:** người cao tuổi (chính) cần chuyển tiếp/chụp màn hình tin đáng ngờ và nhận câu trả lời ngắn, rõ, không phải cài app; người thân (phụ) cần được báo khi người cao tuổi đã liên kết nhận tin rủi ro cao.

## Proposed Solution

Bot Telegram **Hỏi Trước** đóng vai "người thứ ba". Người dùng chuyển tiếp tin, link hoặc ảnh chụp màn hình; bot trả lời bằng tiếng Việt gồm:

- một trong 4 mức rủi ro `DANGEROUS`, `SUSPICIOUS`, `UNSURE`, `LOW_RISK` (cố ý không có mức "an toàn");
- kịch bản lừa đảo mà tin giống, dấu hiệu trích từ chính tin, việc cần làm ngay, tổng đài chính thức đã xác minh và nguồn cảnh báo chính thức (RAG);
- cảnh báo người thân đã liên kết (double opt-in, có sự đồng ý của người cao tuổi) khi rủi ro cao, nhắc lại sau 15 phút nếu chưa ai bấm "Tôi đã liên hệ".

**Kiến trúc AWS (serverless, không VPC/NAT):**

| Dịch vụ | Vai trò |
|---|---|
| AWS Lambda `webhook`, `worker`, `worker-dlq` (Java 21) | Nhận update Telegram qua Function URL, điều phối lượt kiểm tra, gửi trả lời và cảnh báo người thân |
| AWS Lambda `ai` (Python 3.12, container image, 3008 MB) | Pipeline AI local-first: OCR (RapidOCR PP-OCRv6 small, đọc lại bằng VietOCR), embedding e5-small, che PII, luật, kiểm URL, RAG |
| Amazon SQS + DLQ | Hàng đợi giữa `webhook` và `worker`; `DelaySeconds` để gom tin (3 giây) và nhắc lại sau 15 phút |
| Amazon DynamoDB (on-demand, single-table, TTL) | Người dùng, đồng ý, liên kết người thân, metadata lượt kiểm tra, dedup, cache, rate limit, feature flag |
| Amazon Bedrock (ap-southeast-2, account riêng): Qwen3 32B, dự phòng gpt-oss-120b | Bước judge duy nhất gọi LLM, chỉ với văn bản đã che PII; Lambda `ai` assume role (STS) sang account Bedrock |
| Amazon Bedrock Guardrails | Filter `PROMPT_ATTACK` chặn prompt injection trong tin chuyển tiếp |
| Amazon S3, Amazon ECR | Trọng số model local, index RAG; image của Lambda `ai` |
| SSM Parameter Store (SecureString), KMS, IAM, GitHub Actions OIDC | Secret ngoài code, role least-privilege, CI/CD không lưu access key |
| CloudWatch, CloudTrail (event history 90 ngày), AWS Budgets, SNS | Log JSON chung `trace_id`, alarm DLQ gửi email qua SNS, audit, cảnh báo chi phí |

**Nguyên tắc:** workflow có kiểm soát (không phải agent): `Normalize → Extract → Mask PII → Signals → Retrieve → Guard → Judge → Fuse → Respond`. Tầng luật chạy được khi không có LLM nên bot luôn trả lời. Không lưu nội dung gốc, chỉ lưu kết luận, mã dấu hiệu và hash, TTL 30 ngày; ảnh chỉ nằm trong RAM của Lambda `ai`.

## End-to-End Workflow

Số bước khớp với sơ đồ kiến trúc (file `.xml` nộp kèm).

1. **Gửi tin:** người cao tuổi chuyển tiếp tin, link hoặc ảnh cho bot trên Telegram.
2. **Webhook:** Telegram POST update tới Function URL của Lambda `webhook`, kèm `secret_token` (so sánh constant-time).
3. **Gom tin:** `webhook` dedup `update_id`, kiểm đồng ý, gom tin vào `BUFFER#` trong DynamoDB, gửi "⏳ đang kiểm tra…". Telegram nhận phản hồi trong dưới 1 giây.
4. **Xếp job:** `webhook` đẩy job vào SQS với delay 3 giây để gom nhiều tin gửi liền nhau thành một lượt.
5. **Poll:** Lambda `worker` nhận job từ SQS (maximum concurrency 5 để giữ quota Bedrock), đọc buffer và cờ kill switch.
6. **Phân tích:** `worker` invoke đồng bộ Lambda `ai` với `op=analyze`, chỉ gửi metadata và `file_id`, không gửi bytes ảnh.
7. **Tải ảnh:** `ai` tải ảnh từ Telegram thẳng vào RAM.
8. **Load model:** `ai` nạp trọng số OCR, embedding và index RAG từ S3 (ở pha INIT), chạy OCR, che số điện thoại, số tài khoản, CCCD, OTP, email, áp luật.
9. **Cache:** `ai` tra cache kết quả trong DynamoDB (role của `ai` chỉ được đụng khoá `CACHE#` / `URL#`).
10. **ApplyGuardrail:** văn bản đã che đi qua Bedrock Guardrails (`PROMPT_ATTACK`); phát hiện injection thì mức tối thiểu là `UNSURE`.
11. **Đánh giá:** `ai` assume role (STS) sang account Bedrock rồi gọi judge Qwen3 32B; độ tin cậy thấp hoặc lỗi thì chuyển sang gpt-oss-120b; LLM hỏng hẳn thì chỉ chạy luật. Điểm luật và điểm LLM được fuse thành mức rủi ro.
12. **Tra link:** link rút gọn được mở thử để lấy đích thật (chặn SSRF: chỉ http/https, chỉ IP công khai), kết quả cache `URL#` 24 giờ trong DynamoDB.
13. **Trả kết quả:** `ai` render câu trả lời tiếng Việt từ template và trả về `worker`.
14. **Cảnh báo:** nếu mức là `DANGEROUS` kèm hành động nguy hiểm, `worker` báo song song cho mọi người thân đã liên kết; trạng thái "đã báo" chỉ ghi khi Telegram trả `message_id`.
15. **Kết quả:** `worker` sửa tin "⏳" thành câu trả lời cuối cho người cao tuổi.
16. **Lưu kết quả:** `worker` ghi metadata `CHECK#` vào DynamoDB (TTL 30 ngày), không lưu nội dung gốc.
17. **Hỏi lại sau 15 phút:** job nhắc lại qua SQS delay nếu chưa người thân nào xác nhận "Tôi đã liên hệ".

**Lỗi và vận hành:** job lỗi 5 lần vào SQS DLQ; `worker-dlq` đọc DLQ và báo "bận" cho người dùng; CloudWatch Alarm trên DLQ gửi email cho team qua SNS. GitHub Actions deploy qua OIDC. AWS Budgets 50 USD/tháng gửi email khi chi phí vượt 10, 25, 40 và 50 USD.

## Additional (Optional)

**Kết quả đo (eval cuối 24/11/2026, 134 mẫu có nhãn):**

| Tiêu chí | Mục tiêu | Kết quả |
|---|---|---|
| Recall tin lừa đảo | ≥ 90% | 100% trên 90 mẫu lừa đảo (3 lượt) |
| Báo nhầm tin lành tính trông đáng ngờ | ≤ 10% | 5% (1/20) |
| Độ trễ văn bản (warm) | p95 ≤ 15 giây | p95 3,5 giây |
| Độ trễ ảnh đầu-cuối | p95 ≤ 15 giây | p95 17,0 giây (vượt mục tiêu; AI lead chấp nhận tới khoảng 30 giây) |
| Cold start init Lambda `ai` | — | p50 6,2 giây |

**Hạn chế đã công khai:** bộ eval nhỏ và chủ yếu tự soạn, 0 mẫu thật đã ẩn danh (mục tiêu ban đầu 300); không pilot với người dùng thật ngoài team; tỉ lệ 25% lượt đi fast path chưa kiểm chứng.

**Bảo mật và quyền riêng tư:** đồng ý chủ động có lưu lại, lệnh `/rutdongy` và `/xoadulieu`; che PII trước mọi lời gọi ra ngoài; log không chứa nội dung tin. Thiết kế đối chiếu Luật Bảo vệ dữ liệu cá nhân số 91/2025/QH15 và Nghị định 356/2025/NĐ-CP (ánh xạ thiết kế, không phải tư vấn pháp lý).

**Cố ý không dùng:** API Gateway (Function URL là đủ), Step Functions (pipeline tuyến tính dưới 30 giây), OpenSearch/vector DB (corpus nhỏ, cosine trong RAM, RAG p95 45 ms), EC2/SageMaker GPU, VPC/NAT (tránh khoảng 32 USD/tháng cố định).

**Scope đã cắt để vừa 6 tuần:** voice/ASR, VLM fallback cho ảnh khó, hỏi nhiều lượt, Telegram Mini App, pilot 10–20 hộ gia đình.

## Cost

Giả định **3.000 lượt kiểm tra/tháng** (văn bản và ảnh). Dịch vụ ngoài Bedrock tính bằng AWS Pricing Calculator (24/11/2026, `ap-southeast-1`, "Without Free Tier"): https://calculator.aws/#/estimate?id=073f413f6ac370de48bf3bff0ba10a7b870ae807. Bedrock tính tay theo số token đo trên pipeline thật (Calculator không có Qwen3 ở Sydney).

| Dịch vụ | Cách tính | Không Free Tier (USD/tháng) | Có always-free (USD/tháng) |
|---|---|---|---|
| Bedrock LLM (Qwen3 32B + gpt-oss-120b) | 2.250 lượt gọi × 0,000792 USD (đo thật, gồm leo thang) | 1,78 | 1,78 |
| Bedrock Guardrails | 2.250 × 0,15 USD / 1.000 text unit | 0,34 | 0,34 |
| Lambda `ai` (3008 MB) | Calculator | 0,73 | 0 |
| Lambda `worker` (512 MB) | Calculator | 0,13 | 0 |
| Lambda `webhook` (512 MB) | Calculator | 0,03 | 0 |
| SQS (jobs + DLQ) | Calculator | 0,28 | 0 |
| DynamoDB on-demand | Calculator | 0,29 | < 0,05 |
| S3 | Calculator | 0,03 | 0,03 |
| ECR | Calculator | 0,20 | 0,20 |
| CloudWatch | Calculator | 2,35 | 0 |
| SNS, SSM Parameter Store (standard) | Không đưa vào Calculator | ≈ 0 | 0 |
| **Tổng** | | **≈ 6,16** | **≈ 2,4** |

- Chi phí token LLM mỗi lượt ≈ 0,000792 USD, thấp hơn mục tiêu 0,005 USD/lượt.
- 2.250 lượt gọi LLM giả định 25% lượt đi fast path hoặc cache (chưa kiểm chứng); nếu mọi lượt đều gọi LLM thì Bedrock ≈ 2,7 USD/tháng.
- Bảng chưa gồm thuế (hoá đơn thật có thêm ≈ 10%).

**Đối chiếu chi phí thật (01–25/11/2026, chủ yếu là traffic eval và test của nhóm):** tài khoản dev ≈ 0,09 USD (AWS Budgets, không gồm Bedrock); tài khoản chạy Bedrock 3,93 USD theo Cost Explorer (3,57 USD Bedrock + 0,36 USD thuế).

**Tối ưu chi phí:** không có chi phí cố định (không VPC/NAT, API Gateway, OpenSearch, GPU); AI local-first nên OCR, embedding, luật không tốn token; fast path và cache; kill switch `CONFIG#global/FLAGS` tắt judge LLM hoặc nhận ảnh không cần deploy; rate limit theo người dùng; S3 tự xoá `donated/` sau 30 ngày.
