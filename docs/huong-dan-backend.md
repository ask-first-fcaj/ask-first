# Hướng dẫn cho team Backend: "Hỏi Trước" (Ask First)

> **Dành cho:** BE1 và BE2 — Sinh viên năm 3 đã nắm kiến thức nền (OOP, CTDL&GT, SQL cơ bản, RESTful API với Java/Spring Boot hoặc .NET).
> **Mục tiêu:** Giúp bạn tự tin xây dựng hệ thống **Serverless Event-Driven trên AWS** bằng **Java 21** mà không bị ngợp trước các công nghệ mới (Lambda, SQS, DynamoDB Single-table, Telegram Bot API).
> **Phiên bản:** v2.0 (Bản nâng cấp thân thiện cho sinh viên) · 17/10/2026.
> **Tài liệu gốc tra cứu:** `system-architecture.md` v3.5 (**ARCH**) và `implementation-plan.md` v2.1 (**PLAN**).
> **Quy ước:** ✅ đã kiểm chứng trong ARCH · ⚠️ cần team tự kiểm nghiệm · 💡 lời khuyên thực chiến cho sinh viên.

---

## Mục lục

0. [Cầu nối kiến thức: Từ Web API trường học sang AWS Serverless](#0-cầu-nối-kiến-thức-từ-web-api-trường-học-sang-aws-serverless)
1. [Bức tranh tổng thể trong 5 phút](#1-bức-tranh-tổng-thể-trong-5-phút)
2. [Một tin nhắn đi qua hệ thống như thế nào (Kể chuyện)](#2-một-tin-nhắn-đi-qua-hệ-thống-như-thế-nào-kể-chuyện)
3. [Kiến trúc: Các khối và ranh giới trách nhiệm](#3-kiến-trúc-các-khối-và-ranh-giới-trách-nhiệm)
4. [Các dịch vụ AWS giải thích từ gốc (Kèm bảng đối chiếu)](#4-các-dịch-vụ-aws-giải-thích-từ-gốc-kèm-bảng-đối-chiếu)
5. [Telegram Bot API: Những thứ cốt lõi phải biết](#5-telegram-bot-api-những-thứ-cốt-lõi-phải-biết)
6. [Dữ liệu: DynamoDB Single-Table Design từ góc nhìn SQL](#6-dữ-liệu-dynamodb-single-table-design-từ-góc-nhìn-sql)
7. [Các bài toán khó của BE và Code Mẫu thực chiến](#7-các-bài-toán-khó-của-be-và-code-mẫu-thực-chiến)
8. [Hợp đồng giữa Backend và AI (Contracts & Codegen)](#8-hợp-đồng-giữa-backend-và-ai-contracts--codegen)
9. [Lộ trình 7 ngày đầu để bắt nhịp (Dành cho SV năm 3)](#9-lộ-trình-7-ngày-đầu-để-bắt-nhịp-dành-cho-sv-năm-3)
10. [Phân công công việc: Hai Lane A và B](#10-phân-công-công-việc-hai-lane-a-và-b)
11. [Việc làm chung ở Tuần 1 (Pair Programming)](#11-việc-làm-chung-ở-tuần-1-pair-programming)
12. [Chi tiết công việc BE1 (Lane A: Luồng kiểm tra tin)](#12-chi-tiết-công-việc-be1-lane-a-luồng-kiểm-tra-tin)
13. [Chi tiết công việc BE2 (Lane B: Người dùng, Người thân & Dữ liệu)](#13-chi-tiết-công-việc-be2-lane-b-người-dùng-người-thân--dữ-liệu)
14. [Lịch trình 6 tuần](#14-lịch-trình-6-tuần)
15. [Điểm ghép nối với AI (INT) và Các mốc Gate](#15-điểm-ghép-nối-với-ai-int-và-các-mốc-gate)
16. [Cách làm việc, Test local không tốn tiền và Definition of Done](#16-cách-làm-việc-test-local-không-tốn-tiền-và-definition-of-done)
17. [Bảo mật & Quyền riêng tư: Luật bất di bất dịch](#17-bảo-mật--quyền-riêng-tư-luật-bất-di-bất-dịch)
18. [Checklist các bẫy thường gặp và cách gỡ](#18-checklist-các-bẫy-thường-gặp-và-cách-gỡ)
19. [Những điểm chưa chắc chắn (⚠️) cần kiểm chứng](#19-những-điểm-chưa-chắc-chắn--cần-kiểm-chứng)
20. [Phụ lục: Thuật ngữ & Bảng tra mã việc](#20-phụ-lục-thuật-ngữ--bảng-tra-mã-việc)

---

## 0. Cầu nối kiến thức: Từ Web API trường học sang AWS Serverless

Nếu bạn từng làm đồ án môn học với **Spring Boot** hoặc **ASP.NET Core**, bảng này sẽ giúp bạn định vị lại tư duy ngay lập tức:

| Khái niệm ở trường (Monolith / REST) | Thực tế ở Project này (Serverless / Event-driven) | Tại sao lại đổi như vậy? |
|---|---|---|
| **Server chạy 24/7** (Tomcat / Kestrel lắng nghe port 8080/5000) | **AWS Lambda**: Hàm chỉ chạy khi có request, chạy xong thì tắt (Stateless). | Tiết kiệm tiền! Bot có lúc cả tiếng không ai nhắn, thuê server 24/7 tốn tiền vô ích. |
| **Spring Controller đồng bộ** (`@PostMapping`, chờ DB/AI rồi `return ResponseEntity`) | **Chia 2 nửa**: Webhook trả về `200 OK` ngay lập tức (<1s), đẩy việc nặng vào **SQS Queue** để Worker xử lý sau. | Telegram đòi hỏi phản hồi trong <1s. Nếu bắt user chờ AI đọc ảnh/voice (10s), Telegram sẽ coi là lỗi và bắn lại tin nhắn liên tục. |
| **Cơ sở dữ liệu RDBMS** (SQL Server, MySQL, Postgres, khóa ngoại, JOIN, 3NF) | **DynamoDB Single-Table**: Toàn bộ hệ thống chỉ dùng đúng **1 bảng NoSQL**. | Tốc độ đọc/ghi đơn giản luôn đạt 5–10ms bất kể dữ liệu lớn cỡ nào; hỗ trợ tự xóa dữ liệu (TTL) theo luật bảo mật. |
| **Spring Data JPA / Hibernate / Entity Framework** (`findById`, `save`, `@ManyToOne`) | **AWS SDK v2 Enhanced Client**: Không có ORM ma thuật, tự kiểm soát truy vấn qua Partition Key (`PK`) và Sort Key (`SK`). | Tránh giấu logic ngầm, code nhẹ hơn, khởi động nhanh hơn, tránh tốn RAM. |
| **Lưu file trên ổ cứng máy tính** (`uploads/avatar.png`) | **Amazon S3**: Lưu trữ đối tượng đám mây (Cloud Object Storage). | Server Lambda không có ổ cứng cố định. Mọi dữ liệu bền vững phải đẩy lên S3 hoặc DynamoDB. |
| **Spring Security / JWT Cookie** | **Telegram Secret Token** trên header HTTP + **IAM Least Privilege**. | User tương tác qua chat Telegram, không có màn hình login/password thông thường. |

💡 **Lời khuyên:** Đừng cố nhồi Spring Boot vào Lambda ở dự án này. Spring khởi động mất 8–15 giây (Cold Start), gói file nặng 50MB+. Ta dùng **Java 21 thuần + AWS SDK v2 + Jackson**, gói file chỉ khoảng 10–15MB, khởi động cực nhanh!

---

## 1. Bức tranh tổng thể trong 5 phút

### 1.1 Sản phẩm là gì
**"Hỏi Trước" (Ask First)** là bot Telegram bảo vệ người cao tuổi trước các thủ đoạn lừa đảo công nghệ cao (phạt nguội giả, tài khoản ngân hàng bị khóa, trúng thưởng, cài app độc hại...).
* **Người cao tuổi:** Gặp tin nhắn, hình ảnh, đường link hoặc voice lạ → bấm chuyển tiếp (forward) cho Bot.
* **Hệ thống:** Trong vòng 15 giây, phân tích và trả lời bằng tiếng Việt gần gũi: *"Đây có phải lừa đảo không? Dấu hiệu là gì? Bác cần làm gì ngay bây giờ?"*.
* **Người thân (con cháu):** Nếu phát hiện mức độ **NGUY HIỂM CAO**, bot lập tức gửi tin nhắn cảnh báo tới tài khoản Telegram của con cháu để can thiệp kịp thời trước khi tiền bị chuyển đi.

### 1.2 Ranh giới: Bạn làm gì và Team AI làm gì?

```
┌──────────────────────────────────────────┐         ┌──────────────────────────────────────────┐
│          BACKEND TEAM (Các bạn)          │         │              AI TEAM (Python)            │
│                 Java 21                  │         │               Python 3.12                │
│                                          │         │                                          │
│ • Tiếp nhận webhook từ Telegram          │ JSON    │ • Đọc chữ từ ảnh chụp màn hình (OCR)     │
│ • Chống spam, lọc trùng, gom tin liên tục│ Schema  │ • Nghe và chuyển âm thanh voice (ASR)    │
│ • Lưu trữ DB (DynamoDB), quản lý user    │ ──────> │ • Che số tài khoản, số điện thoại (Mask) │
│ • Quản lý liên kết người thân (Guardian) │ <────── │ • Chấm luật dấu hiệu lừa đảo             │
│ • Xếp hàng (SQS), gửi tin kết quả        │         │ • Hỏi AI Model (LLM Bedrock)             │
│ • Gửi cảnh báo khẩn cấp cho con cháu     │         │ • Soạn nội dung giải thích dễ hiểu       │
└──────────────────────────────────────────┘         └──────────────────────────────────────────┘
```

👉 **Hai bên chỉ nói chuyện qua một cổng duy nhất:** Lambda `worker` của Backend gọi Lambda `ai` của Team AI thông qua lệnh `InvokeFunction`. Đầu vào là JSON, đầu ra là JSON. 
* Bạn **không cần** biết AI dùng model gì, prompt viết ra sao.
* AI **không cần** biết Telegram hay DynamoDB hoạt động thế nào.

### 1.3 Ba nguyên tắc sống còn của Backend
1. **Webhook trả lời dưới 1 giây:** Nhận tin từ Telegram → Gửi icon `⏳ Bác chờ chút...` → Đẩy việc vào SQS → Trả `200 OK` ngay lập tức.
2. **Không lưu nội dung gốc lâu dài:** Ảnh và voice của người dùng chỉ chạy trong RAM của AI Lambda để phân tích, **không bao giờ** đi qua code Java hay lưu vào Database. Dữ liệu lưu lại chỉ là kết quả phân tích (Verdict) và tự động xóa sau 30 ngày (TTL).
3. **Mọi thao tác phải Idempotent (Chạy nhiều lần kết quả vẫn như một):** Mạng chập chờn, SQS bắn lại tin, Telegram gọi webhook 2 lần... hệ thống vẫn không được gửi tin nhắn trùng cho người dùng hoặc người thân.

---

## 2. Một tin nhắn đi qua hệ thống như thế nào (Kể chuyện)

Hãy hình dung tình huống cụ thể: **Bà Lan (70 tuổi)** nhận SMS: *"Quý khách có biên bản phạt nguội 50.000đ, nộp tại dichvucong-vn.com"*. Bà chuyển tiếp tin này vào Bot. Con trai bà (**anh Nam**) đã liên kết tài khoản từ trước.

```
[Bà Lan] ──(1. Forward tin)──> [Telegram Server]
                                      │
                                      ▼ (2. POST Webhook <1s)
                             ┌──────────────────┐
                             │  Lambda Webhook  │ ──(Ghi DEDUP & BUFFER)──> [DynamoDB]
                             │     (Java 21)    │ ──(3. Gửi "⏳ Chờ chút")──> [Bà Lan]
                             └────────┬─────────┘
                                      │ (4. Đẩy job trễ 3s)
                                      ▼
                               [ SQS jobs Queue ]
                                      │
                                      ▼ (5. Kích hoạt sau 3s)
                             ┌──────────────────┐
                             │  Lambda Worker   │ <──(Lấy gom tin)─────── [DynamoDB]
                             │     (Java 21)    │
                             └────────┬─────────┘
                                      │ (6. InvokeFunction JSON)
                                      ▼
                             ┌──────────────────┐
                             │    Lambda AI     │ ──(7. Gọi Bedrock/OCR)──> [AWS Bedrock]
                             │     (Python)     │ ──(Trả về Verdict)────┐
                             └──────────────────┘                       │
                                                                        ▼
                                      ┌─────────────────────────────────┴────────┐
                                      │ Lambda Worker tiếp nhận kết quả:         │
                                      │ 8. Thấy NGUY HIỂM -> Báo khẩn cấp        │
                                      │ 9. Sửa tin "⏳" thành kết luận rõ ràng   │
                                      └───────┬──────────────────────────┬───────┘
                                              │                          │
                               (Gửi cảnh báo) ▼                          ▼ (Sửa tin kết quả)
                                         [Anh Nam]                   [Bà Lan]
```

* **Bước 1 (Nhận tin):** Telegram gọi Lambda `webhook`. Webhook kiểm tra secret token, kiểm tra trùng lặp (`DEDUP#`), ghi tin vào vùng đệm (`BUFFER#`), gửi tin nhắn tạm `⏳ Bác chờ chút...` cho bà Lan, rồi ném 1 message vào SQS với độ trễ 3 giây (`DelaySeconds=3`). Trả `200 OK` cho Telegram trong chưa đầy 500ms.
* **Bước 2 (Gom tin):** Tại sao cần trễ 3 giây? Vì người già hay nhắn 2–3 tin liền nhau (VD: gửi ảnh xong gửi thêm dòng chữ "Xem hộ tôi"). Trễ 3 giây để gom các tin này lại phân tích 1 thể, không bị gọi AI 3 lần.
* **Bước 3 (Phân tích):** SQS kích hoạt Lambda `worker`. Worker lấy các tin trong đệm, gọi Lambda `ai`. AI phân tích link `dichvucong-vn.com` là giả mạo cổng dịch vụ công thật, trả kết quả: Mức độ `NGUY_HIEM`.
* **Bước 4 (Cảnh báo người thân trước):** Thấy nguy hiểm, Worker kiểm tra bà Lan có người thân là anh Nam. Worker gửi tin nhắn cho anh Nam: *"Phát hiện bà Lan vừa nhận tin nhắn phạt nguội giả mạo, hãy liên lạc với bà ngay"*. Khi Telegram xác nhận đã gửi cho Nam thành công, Worker mới ghi nhận.
* **Bước 5 (Trả lời bà Lan):** Worker sửa trực tiếp tin nhắn `⏳` thành: *"Bác tuyệt đối không bấm vào link lạ. Đây là tin nhắn lừa đảo mạo danh công an. Cháu đã báo cho con trai Nam của bác rồi nhé!"*. Kèm nút bấm lớn `📞 Gọi cho con trai`.

---

## 3. Kiến trúc: Các khối và ranh giới trách nhiệm

### 3.1 Bảng phân nhiệm 3 Lambda Functions

| Đặc tính | `webhook` (BE) | `worker` (BE) | `ai` (AI Team) |
|---|---|---|---|
| **Môi trường chạy** | Java 21 (AWS Lambda) | Java 21 (AWS Lambda) | Python 3.12 (Container Image) |
| **Cách kích hoạt** | Telegram gọi qua **Function URL** | **Amazon SQS** kích hoạt tự động | Lambda `worker` gọi trực tiếp qua SDK |
| **Thời gian chạy** | **Cực nhanh (< 1 giây)** | Trung bình (5 – 30 giây) | Nặng (5 – 25 giây) |
| **Timeout cấu hình**| 5 giây | 120 giây | 90 giây |
| **Trách nhiệm chính**| • Kiểm tra tính hợp lệ request<br>• Chống gửi trùng (`DEDUP`)<br>• Kiểm tra điều khoản & hạn mức<br>• Gom tin nhắn liên tiếp<br>• Phản hồi lệnh cơ bản (`/start`, `/huongdan`) | • Nhận job từ SQS<br>• Gọi Lambda AI lấy kết quả<br>• Gửi cảnh báo khẩn cấp cho con cháu<br>• Cập nhật tin nhắn cho người cao tuổi<br>• Lưu lịch sử phân tích (chỉ metadata) | • Tải ảnh/voice từ Telegram về RAM<br>• Chạy OCR bóc chữ từ ảnh<br>• Chạy Whisper bóc giọng nói<br>• Che thông tin nhạy cảm (SĐT, STK)<br>• Gọi LLM Bedrock phân tích kịch bản |

### 3.2 Những thứ Backend KHÔNG làm
1. **Không tải file ảnh/voice:** Worker chỉ nhận `file_id` từ Telegram và chuyển tiếp chuỗi ID đó cho AI. Lambda AI sẽ tự tải file về bộ nhớ RAM của nó. Nhờ đó, Lambda Worker Java giữ được dung lượng mạng nhẹ và không sợ vượt trần kích thước gọi hàm (giới hạn 6MB của AWS Invoke).
2. **Không tự suy luận logic lừa đảo:** Backend không tự viết `if (text.contains("chuyen tien"))`. Toàn bộ logic phán đoán do AI Team chịu trách nhiệm.

---

## 4. Các dịch vụ AWS giải thích từ gốc (Kèm bảng đối chiếu)

Dự án chỉ dùng **5 dịch vụ AWS cốt lõi**. Dưới đây là giải thích bình dân học vụ nhất:

```
                  ┌───────────────────────────────────────────────┐
                  │                 AWS CLOUD                     │
                  │                                               │
Telegram ──HTTPS─>│ [Lambda webhook] ──Ghi job──> [ SQS Queue ]   │
                  │         │                           │         │
                  │       Đọc/Ghi                   Kích hoạt     │
                  │         │                           │         │
                  │         ▼                           ▼         │
                  │   [(DynamoDB)] <───────────── [Lambda worker] │
                  │     askfirst                        │         │
                  │                                Invoke sync    │
                  │                                     │         │
                  │                                     ▼         │
                  │                              [ Lambda ai ]    │
                  │                                     │         │
                  │             Gọi LLM API             ▼         │
                  │   [Bedrock Sydney] <────────────────┘         │
                  └───────────────────────────────────────────────┘
```

### 4.1 AWS Lambda (Function as a Service)
* **Hiểu đơn giản:** Thay vì viết cả ứng dụng Spring Boot chạy liên tục trên server, bạn chỉ viết 1 hàm Java kế thừa `RequestHandler<Request, Response>`. AWS sẽ tự bật máy ảo, chạy xong hàm thì tự giải phóng.
* **Khái niệm Cold Start:** Lần đầu tiên chạy (hoặc lâu không có ai gọi), AWS phải khởi động máy ảo Java, mất 1–3 giây. Những lần gọi tiếp theo (Warm Start) chỉ mất vài mili-giây.
* **SnapStart là gì?** Tính năng của AWS cho Java: AWS sẽ chạy khởi tạo ứng dụng trước, chụp lại toàn bộ bộ nhớ RAM của JVM lưu thành một "bản chụp" (snapshot). Khi có request, nó chỉ việc bung RAM ra chạy, Cold Start từ 3 giây giảm còn ~200ms!
  * ⚠️ **Bẫy của SnapStart:** Vì bộ nhớ RAM bị chụp lại từ trước, **tuyệt đối không được mở kết nối Database hoặc đọc mật khẩu bí mật (Secret) ở hàm khởi tạo constructor**. Phải đọc khi hàm xử lý bắt đầu chạy (Lazy load).

### 4.2 Amazon SQS (Hàng đợi tin nhắn)
* **Hiểu đơn giản:** Giống như cái thùng thư. Webhook ném thư vào thùng rồi quay đi làm việc khác. Worker đứng chờ ở đầu kia thùng thư, có thư rơi xuống thì nhặt lên đọc.
* **Visibility Timeout:** Khi Worker A nhặt 1 tin nhắn ra đọc, tin nhắn đó sẽ tạm thời "tàng hình" trong 720 giây để các Worker khác không nhặt trùng. Nếu Worker A xử lý thành công, nó gửi lệnh xóa tin. Nếu Worker A bị sập giữa chừng, hết 720 giây tin nhắn sẽ hiện hình trở lại để Worker khác thử lại.
* **DLQ (Dead Letter Queue - Thùng thư rác):** Nếu 1 tin nhắn bị lỗi, Worker thử đi thử lại 5 lần (`maxReceiveCount=5`) vẫn lỗi, tin nhắn sẽ bị ném vào DLQ để không làm nghẽn hệ thống.

### 4.3 Amazon DynamoDB (Cơ sở dữ liệu NoSQL tốc độ cao)
* **Hiểu đơn giản:** Một bảng băm khổng lồ (Distributed Hash Map). Bạn tìm kiếm dữ liệu dựa vào khóa chính `PK` và `SK`.
* **Ưu điểm vượt trội:** Tốc độ đọc ghi cực ổn định (5–10ms), có tính năng **TTL** (hẹn giờ tự xóa bản ghi, cực kỳ phù hợp với quy định bảo mật), và tính năng **Conditional Write** (chỉ ghi nếu chưa tồn tại – bí kíp chống trùng lặp).

### 4.4 Amazon Bedrock & S3
* **Bedrock:** Dịch vụ API gọi các mô hình trí tuệ nhân tạo (LLM). Đặt ở region Sydney (`ap-southeast-2`). Team AI sẽ dùng, BE chỉ cần hỗ trợ xin cấp quyền truy cập lúc đầu.
* **S3 (Simple Storage Service):** Ổ cứng lưu file trên mây, dùng để chứa trọng số model của AI và các file mẫu người dùng tự nguyện đóng góp (`donated/`).

---

## 5. Telegram Bot API: Những thứ cốt lõi phải biết

Giao tiếp với Telegram thực chất là **gửi các HTTP Request thông thường** (GET/POST) tới đường dẫn `https://api.telegram.org/bot<TOKEN>/<METHOD>`. Bạn không cần cài thư viện bên ngoài, dùng ngay `HttpClient` có sẵn của Java 21!

### 5.1 Các API bắt buộc dùng trong dự án

```java
// Ví dụ tư duy gọi Telegram API bằng Java 21 HttpClient chuẩn:
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString("""
        {
            "chat_id": 123456789,
            "text": "⏳ Bác chờ chút, hệ thống đang kiểm tra...",
            "parse_mode": "HTML"
        }
        """))
    .build();
```

* `setWebhook`: Báo cho Telegram biết URL của Lambda Webhook để khi có user nhắn tin, Telegram tự đẩy dữ liệu sang cho mình. Phải đặt `secret_token` để chống kẻ xấu gọi trộm vào Webhook.
* `sendMessage` & `editMessageText`: Gửi tin nhắn mới hoặc sửa lại tin nhắn cũ. Ta dùng `editMessageText` để biến tin nhắn `⏳` thành kết quả phân tích mà không làm phiền người dùng bằng nhiều tin nhắn rác.
* `InlineKeyboardMarkup`: Các nút bấm gắn liền dưới tin nhắn (VD: nút `[📋 Việc cần làm]`, `[📞 Gọi cho con]`). Khi bấm nút, Telegram gửi về một `callback_query`.
* `answerCallbackQuery`: **RẤT QUAN TRỌNG!** Mỗi khi user bấm vào nút Inline, bạn **bắt buộc** phải gọi API này ngay lập tức, nếu không nút bấm trên điện thoại user sẽ quay vòng xoay chờ vô tận.
* `sendContact`: Gửi số điện thoại dưới dạng danh bạ. Vì Telegram không hỗ trợ nút bấm gọi điện thoại trực tiếp (`tel:0912345678`), ta dùng lệnh này để gửi danh thiếp của con cho người cao tuổi chỉ việc chạm là gọi được.

---

## 6. Dữ liệu: DynamoDB Single-Table Design từ góc nhìn SQL

Đây là phần **dễ gây bối rối nhất** cho sinh viên đã quen với SQL. Hãy đọc kỹ phần này!

### 6.1 Tại sao lại nhét tất cả vào MỘT BẢNG DUY NHẤT?
Trong SQL, bạn tạo bảng `Users`, `Checks`, `Alerts`, `Guardians`. Khi cần lấy thông tin, bạn dùng `JOIN`.
Nhưng ở hệ thống chịu tải cao, phép `JOIN` trong database rất chậm và tốn tài nguyên. DynamoDB giải quyết bằng cách: **Gom tất cả các thực thể liên quan vào chung 1 bảng**, phân biệt nhau bằng tiền tố ở 2 cột khóa chính:
* `PK` (Partition Key - Khóa phân vùng): Xác định dữ liệu nằm ở máy chủ nào.
* `SK` (Sort Key - Khóa sắp xếp): Dùng để phân loại thực thể và sắp xếp dữ liệu.

### 6.2 Bảng tra cứu thiết kế Single-Table trong dự án `askfirst`

| Mục đích lưu trữ | Giá trị `PK` | Giá trị `SK` | Dữ liệu bên trong gồm những gì? | Thời gian tự hủy (TTL) |
|---|---|---|---|---|
| **Thông tin người dùng** | `USER#{uid}` | `PROFILE` | Cài đặt xưng hô, trạng thái đồng ý điều khoản, ngày tạo | Vĩnh viễn |
| **Liên kết người thân** | `USER#{uid}` | `GUARDIAN#{gid}` | Trạng thái (đang bật/tắt), mức độ chia sẻ (1, 2, 3), chat_id con | Vĩnh viễn |
| **Kết quả kiểm tra tin** | `USER#{uid}` | `CHECK#{timestamp}#{id}` | Mức độ nguy hiểm, tên kịch bản, điểm số, thời gian chạy. **CẤM LƯU CHỮ GỐC** | 30 ngày |
| **Chống xử lý trùng lặp** | `DEDUP#telegram#{update_id}`| `META` | Để trống (Chỉ cần sự tồn tại của khóa để chặn trùng) | 24 giờ |
| **Hộp đệm gom tin** | `BUFFER#{chat_id}` | `META` | Danh sách các tin nhắn vừa gửi trong 3s, số thứ tự `seq` | 10 phút |
| **Khóa chống spam báo động**| `ALERTLOCK#{uid}#{kich_ban}`| `META` | Đánh dấu vừa báo kịch bản này rồi, không báo lại nữa | 30 phút |
| **Mã mời người thân** | `INVITE#{code}` | `META` | ID người mời, thời gian hết hạn | 24 giờ |
| **Giới hạn lượt dùng** | `RATE#{uid}#{ngày_giờ}` | `COUNTER` | Số lượt đã kiểm tra trong giờ/ngày (Cộng dồn nguyên tử) | 2 giờ / 2 ngày |

### 6.3 Làm sao để Query dữ liệu?

* **Trường hợp 1: Muốn lấy Profile của bà Lan (Telegram ID: `112233`)**
  * SQL: `SELECT * FROM Users WHERE id = '112233'`
  * DynamoDB: `GetItem(PK = "USER#tg:112233", SK = "PROFILE")`
* **Trường hợp 2: Muốn lấy danh sách tất cả người thân của bà Lan**
  * SQL: `SELECT * FROM Guardians WHERE user_id = '112233'`
  * DynamoDB: `Query(PK = "USER#tg:112233", SK begins_with "GUARDIAN#")` (Cực kỳ nhanh vì các dòng này nằm sát nhau trên ổ cứng vật lý).
* **Trường hợp 3: Muốn lấy 10 lần kiểm tra tin gần nhất của bà Lan**
  * SQL: `SELECT * FROM Checks WHERE user_id = '112233' ORDER BY created_at DESC LIMIT 10`
  * DynamoDB: `Query(PK = "USER#tg:112233", SK begins_with "CHECK#", ScanIndexForward = false, Limit = 10)`

---

## 7. Các bài toán khó của BE và Code Mẫu thực chiến

Phần này cung cấp sẵn tư duy và đoạn code mẫu để các bạn sinh viên không phải tự mò mẫm cú pháp AWS SDK v2 phức tạp.

### 7.1 Bài toán 1: Gom tin (Debounce) khi người già gửi nhiều tin liên tiếp

**Hiện tượng:** Bà Lan chuyển tiếp 1 ảnh lúc 0.0s, gửi thêm tin nhắn chữ lúc 0.8s, rồi gửi tiếp chữ "Xem giúp tôi" lúc 1.5s.
**Yêu cầu:** Không được tạo ra 3 câu trả lời riêng rẽ gây loạn màn hình chat. Chỉ gửi đúng 1 tin `⏳`, và trả về 1 kết quả duy nhất cho cả cụm 3 tin.

```
0.0s: Tin 1 tới  ──> Ghi vào Buffer (seq=1) ──> Gửi tin "⏳" ──> Đẩy SQS Job 1 (Chạy lúc 3.0s)
0.8s: Tin 2 tới  ──> Ghi vào Buffer (seq=2) ──> KHÔNG gửi "⏳" ──> Đẩy SQS Job 2 (Chạy lúc 3.8s)
1.5s: Tin 3 tới  ──> Ghi vào Buffer (seq=3) ──> KHÔNG gửi "⏳" ──> Đẩy SQS Job 3 (Chạy lúc 4.5s)

3.0s: Job 1 chạy ──> Thấy max_seq hiện tại là 3 (> 1) ──> Bỏ qua (Tự kết thúc, không làm gì)
3.8s: Job 2 chạy ──> Thấy max_seq hiện tại là 3 (> 2) ──> Bỏ qua
4.5s: Job 3 chạy ──> Thấy seq == max_seq (3 == 3)   ──> GOM CẢ 3 TIN ĐI PHÂN TÍCH 1 LẦN!
```

**Code Java mẫu: Dùng Atomic Update trong DynamoDB để tăng số thứ tự `seq` an toàn:**

```java
public int appendMessageToBuffer(String chatId, InboundMessage message) {
    // Lệnh UpdateItem của AWS SDK v2 kết hợp atomic counter
    UpdateItemRequest request = UpdateItemRequest.builder()
        .tableName("askfirst")
        .key(Map.of(
            "PK", AttributeValue.fromS("BUFFER#" + chatId),
            "SK", AttributeValue.fromS("META")
        ))
        // Tăng max_seq thêm 1, nếu chưa có thì gán giá trị khởi tạo là 0
        .updateExpression("ADD max_seq :inc SET items = list_append(if_not_exists(items, :empty_list), :new_item), ttl_ts = :ttl")
        .expressionAttributeValues(Map.of(
            ":inc", AttributeValue.fromN("1"),
            ":empty_list", AttributeValue.fromL(List.of()),
            ":new_item", AttributeValue.fromL(List.of(AttributeValue.fromS(serializeJson(message)))),
            ":ttl", AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond() + 600)) // 10 phút
        ))
        .returnValues(ReturnValue.ALL_NEW)
        .build();

    UpdateItemResponse response = dynamoDbClient.updateItem(request);
    return Integer.parseInt(response.attributes().get("max_seq").n());
}
```

### 7.2 Bài toán 2: Chống xử lý trùng lặp bằng Conditional Put (Idempotency)

Khi mạng chập chờn, Telegram có thể gửi lại cùng một `update_id`. Ta dùng `attribute_not_exists(PK)` để đảm bảo: chỉ ghi thành công nếu chưa từng thấy ID này.

```java
public boolean tryAcquireDedupLock(String updateId) {
    try {
        PutItemRequest request = PutItemRequest.builder()
            .tableName("askfirst")
            .item(Map.of(
                "PK", AttributeValue.fromS("DEDUP#telegram#" + updateId),
                "SK", AttributeValue.fromS("META"),
                "ttl_ts", AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond() + 86400)) // 24 giờ
            ))
            // ĐIỀU KIỆN QUYẾT ĐỊNH: Chỉ ghi nếu PK chưa tồn tại trong bảng!
            .conditionExpression("attribute_not_exists(PK)")
            .build();

        dynamoDbClient.putItem(request);
        return true; // Ghi thành công -> Tin nhắn mới toanh
    } catch (ConditionalCheckFailedException e) {
        return false; // Điều kiện thất bại -> Tin này đã nhận rồi, BỎ QUA NGAY!
    }
}
```

### 7.3 Bài toán 3: So sánh Token bảo mật chống Timing Attack

Trong Java, bạn hay dùng `tokenA.equals(tokenB)`. **Điều này cực kỳ nguy hiểm trong bảo mật!** Vì hàm `equals` sẽ trả về `false` ngay ở ký tự sai đầu tiên. Kẻ trộm có thể bấm giờ từng mili-giây để dò dần từng ký tự một (Timing Attack).
* **Giải pháp chuẩn công nghiệp:** Dùng `MessageDigest.isEqual` (so sánh tốn thời gian cố định, không ngắt sớm).

```java
public boolean verifyTelegramSecret(String receivedSecret, String configuredSecret) {
    if (receivedSecret == null || configuredSecret == null) {
        return false;
    }
    byte[] a = receivedSecret.getBytes(StandardCharsets.UTF_8);
    byte[] b = configuredSecret.getBytes(StandardCharsets.UTF_8);
    
    // So sánh constant-time an toàn tuyệt đối
    return MessageDigest.isEqual(a, b);
}
```

---

## 8. Hợp đồng giữa Backend và AI (Contracts & Codegen)

Để 2 team Java và Python không cãi nhau về định dạng dữ liệu, dự án áp dụng mô hình: **Schema là chân lý duy nhất (Single Source of Truth)**.

```
AI Team viết file Python Model (Pydantic)
               │
               ▼ (Chạy lệnh tự động sinh file JSON)
     contracts/schema/*.json (JSON Schema chuẩn)
               │
               ▼ (jsonschema2pojo-maven-plugin đọc file JSON)
backend/target/generated-sources/.../vn/askfirst/contract/ (Tự sinh các class Java Record)
```

💡 **Ý nghĩa thực tế đối với bạn:**
* Bạn **không bao giờ phải tự gõ các DTO Java** cho kết quả của AI.
* Khi chạy `./mvnw generate-sources` (hoặc `./mvnw verify`), Maven sẽ tự sinh ra các file như `Verdict.java`, `InboundMessage.java`, `OutMessage.java`.
* **Tuyệt đối không sửa tay các file trong thư mục `generated`!** Muốn sửa trường nào, 2 team ngồi lại sửa file gốc `models.py` của AI rồi build lại.

### 8.1 Chạy thử nghiệm với `FakeAiClient` (Không cần chờ team AI)
Nếu tuần đầu team AI chưa làm xong model, bạn vẫn kiểm thử được toàn bộ hệ thống bằng `FakeAiClient`:
* Khi test, `FakeAiClient` sẽ đọc sẵn các file JSON mẫu trong thư mục `contracts/fixtures/analyze/`.
* Chứa chữ "otp", "chuyển khoản" → Trả về kết quả mẫu `NGUY_HIEM`.
* Chứa chữ "alo", "chào bot" → Trả lời xã giao `smalltalk`.
* Nhờ cơ chế này, **Backend hoàn toàn tự lập và kiểm thử trơn tru ngay từ tuần thứ 2!**

---

## 9. Lộ trình 7 ngày đầu để bắt nhịp (Dành cho SV năm 3)

Đừng cố gắng đọc hết tài liệu trong một ngày. Hãy chia nhỏ theo lộ trình 7 ngày đầu tiên:

```
[Ngày 1: Setup & Cảm nhận] ──> [Ngày 2: Thử Bot Telegram] ──> [Ngày 3: Thử DynamoDB Local]
                                                                        │
[Ngày 6-7: Viết Spike chung] <── [Ngày 5: Gọi thử SQS]   <── [Ngày 4: Làm quen Java 21 Record]
```

* **Ngày 1 (Cài đặt máy tính):**
  * Cài JDK 21 (khuyên dùng Amazon Corretto 21 hoặc Eclipse Temurin 21).
  * Cài Docker Desktop (để chạy database test local).
  * Cài AWS CLI và AWS SAM CLI. Đăng nhập tài khoản AWS do Lead cấp.
* **Ngày 2 (Tạo Bot thử nghiệm):**
  * Lên Telegram chat với `@BotFather`, gõ `/newbot` để tạo 1 con bot riêng cho bạn thử nghiệm.
  * Viết một file Java nhỏ dùng `HttpClient` gọi thử `getMe` và `sendMessage` gửi chữ "Hello" về tài khoản của mình.
* **Ngày 3 (Thực hành DynamoDB không tốn tiền):**
  * Dùng Testcontainers hoặc Docker chạy ảnh `amazon/dynamodb-local`.
  * Thử tạo bảng có `PK` và `SK`. Viết hàm `putItem` và `getItem` đơn giản.
* **Ngày 4 (Học tính năng Java 21):**
  * Đọc hiểu `record` (thay thế class getter/setter rườm rà).
  * Đọc hiểu Pattern Matching với `switch` trên `sealed interface`.
* **Ngày 5 (Gửi nhận thử SQS):**
  * Đăng nhập AWS Console, tạo 1 Queue SQS tên là `test-queue`.
  * Viết code Java gửi 1 message JSON lên queue và đọc về.
* **Ngày 6 & 7 (Cùng làm việc chung BE-01, BE-02, BE-14):**
  * Ngồi pair programming với bạn còn lại, thiết lập khung repo và deploy thử nghiệm.

---

## 10. Phân công công việc: Hai Lane A và B

Thay vì chia "người làm database, người làm API" kiểu cũ, dự án chia theo **Lát cắt dọc (Vertical Slices)**: Mỗi bạn sở hữu trọn vẹn một luồng tính năng từ đầu đến cuối (tự viết Java, cấu hình AWS, viết test và quản lý dashboard).

```
┌───────────────────────────────────────────────┐  ┌───────────────────────────────────────────────┐
│           LANE A (Phụ trách: BE1)             │  │           LANE B (Phụ trách: BE2)             │
│            "LUỒNG KIỂM TRA TIN"               │  │       "NGƯỜI DÙNG & DỮ LIỆU CỐT LÕI"          │
│                                               │  │                                               │
│ • Webhook phân loại tin cần kiểm tra          │  │ • Tạo & cấu hình Telegram Bot (Dev/Prod)      │
│ • Xếp hàng SQS và xử lý hàng đợi (Worker)     │  │ • Bộ chuyển đổi dữ liệu Telegram Adapter      │
│ • Bộ gom tin nhắn liên tiếp (Debounce)        │  │ • Cấu trúc nền tảng DynamoDB Single-Table     │
│ • Gọi Lambda AI (AiClient, FakeAiClient)      │  │ • Luồng đăng ký, hỏi ý kiến (Consent)         │
│ • Gửi cảnh báo khẩn cấp tới con cháu          │  │ • Quản lý liên kết người thân (Mời/Hủy)       │
│ • Đo lường hiệu năng, SnapStart, Cold Start   │  │ • Giới hạn tần suất gọi bot (Rate Limit)      │
│ • Thiết lập Kill Switch tắt AI khi gặp sự cố  │  │ • Xóa dữ liệu cá nhân theo yêu cầu pháp lý    │
└───────────────────────────────────────────────┘  └───────────────────────────────────────────────┘
```

🤝 **Cơ chế Pair Programming (Kèm cặp nhau):**
* Bạn làm Lane A thì bạn Lane B là **người review code bắt buộc**, và ngược lại.
* Thứ Sáu hàng tuần: BE1 demo tính năng của BE2 làm, BE2 demo tính năng của BE1 làm. Đảm bảo nếu một bạn bị ốm hoặc bận thi cử, bạn kia vẫn nắm rõ 100% dự án.

---

## 11. Việc làm chung ở Tuần 1 (Pair Programming)

Tuần 1 là tuần quan trọng nhất để xây móng. Hai bạn ngồi cạnh nhau hoặc share màn hình làm chung:

### BE-01. Thiết lập tài khoản AWS an toàn (BE1 chính, BE2 phụ)
* Bật xác thực 2 lớp (MFA) cho tài khoản root, sau đó cất mật khẩu root đi.
* Tạo 2 tài khoản IAM riêng cho 2 bạn. **Tuyệt đối không dùng chung tài khoản.**
* **Cực kỳ quan trọng:** Cài đặt **AWS Budgets cảnh báo ở các mốc 10$, 25$, 50$** về email. Tránh trường hợp viết code lặp vô tận làm phát sinh hóa đơn tiền triệu.

### BE-02. Tạo khung dự án chuẩn (BE2 chính, BE1 phụ)
* Khởi tạo dự án Maven (`pom.xml` + Maven Wrapper `mvnw`) với cấu trúc:
  * `backend/`: Chứa mã nguồn Java 21. Cài đặt plugin Spotless (tự format code) và JUnit 5.
  * `contracts/`: Chứa các file JSON Schema chia sẻ với team AI.
* Đảm bảo câu lệnh `./mvnw verify` chạy thành công trên máy của cả hai bạn.

### BE-03. Viết file SAM Template hạ tầng đầu tiên (Cả hai cùng làm)
* Tạo file `infra/template.yaml` khai báo:
  * 1 bảng DynamoDB `askfirst`.
  * 1 hàng đợi SQS `jobs` kèm hàng đợi phụ `jobs-dlq`.
  * 1 Lambda `webhook` đơn giản in ra chữ "Hello World".
* Cả 2 bạn cùng gõ lệnh `sam build` và `sam deploy` lên môi trường `dev` để chắc chắn máy mình deploy được.

### BE-14. Bài tập thực hành chung (Spike)
* **BE1 làm:** Viết 1 Lambda Worker nhỏ, bật tính năng SnapStart, thử đo xem thời gian chạy lần đầu mất bao nhiêu giây.
* **BE2 làm:** Viết 1 test Java dùng Testcontainers khởi động DynamoDB Local, thêm 1 bản ghi và đọc ra thành công.

---

## 12. Chi tiết công việc BE1 (Lane A: Luồng kiểm tra tin)

### Giai đoạn 1: Khung kết nối cơ bản (W1 – W3)
* **BE-05 (W1): Bật quyền truy cập Bedrock ở Sydney (`ap-southeast-2`):** Đăng nhập console AWS, gửi yêu cầu kích hoạt các model AI (Qwen, Nova Lite) để team AI có tài nguyên dùng.
* **BE-05b (W1–W2): Tạo class Java từ Schema (`AiClient`):** Thiết lập `jsonschema2pojo-maven-plugin` trong `pom.xml`. Viết class `AiClient` gọi Lambda AI và class `FakeAiClient` trả về dữ liệu mẫu để test.
* **BE-22 (W2): Xây dựng Lambda Webhook Handler:** 
  * Tiếp nhận request POST từ Telegram Function URL.
  * So sánh Secret Token bằng `MessageDigest.isEqual`.
  * Chống trùng lặp bằng `DEDUP#<update_id>`.
  * Phân luồng: Tin nhắn text/ảnh thì gửi tin `⏳` rồi ném vào SQS; lệnh như `/start` thì chuyển sang nhánh của BE2.
* **BE-07 (W2–W3): Nối SQS vào Worker Lambda:** Cấu hình để SQS kích hoạt Lambda Worker. Đặt thời gian ẩn tin nhắn `VisibilityTimeout = 720` giây. Bật SnapStart cho Worker.
* **BE-24b (W2): Tạo các Repository cho Lane A:** Viết code thao tác database cho các bảng `CHECK#`, `BUFFER#`, `DEDUP#`, `ALERT#`.
* **BE-27 (W2–W3): Xử lý cốt lõi trong Worker:** Đọc tin nhắn từ SQS → Gọi AI lấy kết luận → Gọi AI lấy mẫu tin cảnh báo → Sửa tin nhắn tạm `⏳` thành kết quả.
* **BE-23 (W3): Thuật toán Gom tin liên tiếp (Debounce):** Cài đặt logic gom tin trễ 3 giây như đã hướng dẫn ở [Mục 7.1](#71-bài-toán-1-gom-tin-debounce-khi-người-già-gửi-nhiều-tin-liên-tiếp).
* **INT-01 (W3): Ghép nối đợt 1:** Kết hợp với Team AI để kiểm tra luồng tin nhắn văn bản từ Telegram chạy mượt mà từ đầu đến cuối.

### Giai đoạn 2 & 3: Cảnh báo & Hoàn thiện (W4 – W6)
* **BE-06 (W3–W4): Hỗ trợ đóng gói Docker cho Lambda AI:** Viết Dockerfile tối ưu dung lượng dưới 2GB để chứa các thư viện OCR của AI. *(Voice/Whisper ⏸ Hoãn (ngoài phạm vi 6 tuần) nên image không cần ffmpeg/sherpa-onnx, nhẹ hơn.)*
* **BE-13 (W5): Cài đặt Kill Switch:** Viết script bật/tắt cờ `llm_enabled` trong DynamoDB. Khi tắt cờ, hệ thống chỉ chạy bộ luật cứng, không gọi AI tốn tiền.
* **BE-34 (W4): Từ chối file quá nặng:** Kiểm tra nếu người dùng gửi voice dài hơn 2 phút hoặc file nặng hơn 20MB thì từ chối lịch sự ngay tại Webhook, không đẩy vào SQS. *(Phần voice: `feature_voice` tắt mặc định trong MVP, webhook trả lời "chưa hỗ trợ voice"; xử lý voice ⏸ Hoãn (ngoài phạm vi 6 tuần).)*
* **BE-29 (W4 - Việc chéo 🔀): Gửi cảnh báo cho con cháu:** Khi AI báo mức `NGUY_HIEM`, lấy danh sách người thân từ database và gửi tin nhắn cảnh báo song song cho tất cả người thân đang kích hoạt. Đặt lịch nhắc lại sau 15 phút nếu người thân chưa bấm nút xác nhận.
* **BE-08a (W4–W5): Giám sát hệ thống (Observability):** Cài đặt AWS Lambda Powertools để ghi log có mã định danh `trace_id`. Tạo CloudWatch Dashboard theo dõi số lượng tin nhắn, độ trễ và chi phí.
* **BE-09a (W5 - Rà soát chéo 🔀):** Đọc và kiểm tra bảo mật toàn bộ code của BE2 trước khi phát hành.

---

## 13. Chi tiết công việc BE2 (Lane B: Người dùng, Người thân & Dữ liệu)

### Giai đoạn 1: Nền tảng người dùng (W1 – W3)
* **BE-20 (W1): Tạo Bot & Chế độ Dev Local không cần Deploy:**
  * Dùng `@BotFather` tạo 2 bot: `askfirst_dev_bot` (để dev) và `askfirst_bot` (để chạy thật).
  * Cấu hình `exec-maven-plugin` (execution `local-bot`, chạy bằng `./mvnw exec:java@local-bot`) dùng kỹ thuật Polling (liên tục kéo tin nhắn từ Telegram về máy tính cá nhân để debug mà không cần deploy lên AWS mỗi khi sửa code).
* **BE-21 (W1–W2): Bộ chuyển đổi Telegram Adapter:** Viết class phân tích gói tin JSON từ Telegram thành các đối tượng Java sạch sẽ: `InboundMessage` (tin nhắn), `CallbackEvent` (bấm nút), `CommandEvent` (gõ lệnh).
* **BE-24 (W2): Xây dựng khung tầng lưu trữ DynamoDB:** Viết các class cơ sở kết nối DynamoDB, cấu hình Testcontainers chạy database local cho cả 2 bạn cùng viết test.
* **BE-04 (W3): Thiết lập CI/CD bằng GitHub Actions:** Cấu hình để mỗi khi tạo Pull Request, GitHub tự động chạy test kiểm tra trên cả 3 hệ điều hành (Ubuntu, Windows, macOS). Tự động deploy lên AWS khi merge code.
* **BE-25 (W3): Luồng Onboarding & Hỏi ý kiến (Consent):** Khi người già gõ `/start`, bot gửi lời chào rõ ràng: *"Cháu là trợ lý Hỏi Trước. Cháu KHÔNG BAO GIỜ hỏi mật khẩu hay yêu cầu chuyển tiền..."* kèm nút bấm `[Tôi đồng ý với chính sách bảo mật]`. Nếu chưa bấm đồng ý, tuyệt đối không phân tích tin nhắn.
* **BE-26 (W3): Giới hạn tần suất dùng (Rate Limit):** Dùng lệnh tăng nguyên tử trong DynamoDB để giới hạn mỗi người tối đa 30 lần kiểm tra/giờ và 100 lần/ngày, tránh bị kẻ xấu spam phá hoại hệ thống.

### Giai đoạn 2 & 3: Tính năng Người thân & Pháp lý (W4 – W6)
* **BE-35 (W4): Chặn file lạ và mã độc `.apk`:** Nếu người dùng gửi file cài đặt ứng dụng Android (`.apk`), Webhook từ chối ngay lập tức và đưa ra cảnh báo: *"Tuyệt đối không cài file này vào điện thoại, đây là hình thức chiếm đoạt máy!"*.
* **BE-28 (W4): Luồng liên kết người thân an toàn 2 chiều (Double Opt-in):**
  1. Người cao tuổi gõ `/nguoithan` → Bot sinh 1 đường link mời ngẫu nhiên có hạn 24 giờ (VD: `t.me/askfirst_bot?start=g_AbCd1234`).
  2. Con cháu mở link trên máy mình → Bot hỏi xác nhận muốn nhận cảnh báo không.
  3. Bot gửi thông báo hỏi lại người cao tuổi: *"Anh Nam muốn kết nối bảo vệ bác, bác có đồng ý không?"*.
  4. Người cao tuổi bấm [Đồng ý] → Mới chính thức kích hoạt liên kết.
* **BE-30 (W5 - Việc chéo 🔀): Tiếp nhận phản hồi:** Xử lý khi người dùng bấm nút `[👎 Bot báo sai]`, lưu phản hồi để cải tiến AI.
* **BE-31 (W5 - Việc chéo 🔀): Tính năng Xóa dữ liệu & Rút lại đồng ý:** Cài đặt lệnh `/xoadulieu` (xóa sạch toàn bộ thông tin cá nhân và lịch sử kiểm tra) và `/rutdongy` đúng theo quy định của Luật Bảo vệ dữ liệu cá nhân.
* **BE-36 (W5): Menu quản lý trong chat:** Cài đặt lệnh `/lichsu` (xem lại 10 lần kiểm tra gần nhất) và `/nguoithan` (xem danh sách con cháu đang liên kết).
* **BE-08b (W6 - Việc chéo 🔀): Cài đặt cảnh báo sự cố (CloudWatch Alarm):** Thiết lập gửi email cho cả team khi hệ thống có tin nhắn rơi vào thùng rác DLQ hoặc lỗi quá 5%.
* **BE-09b (W5 - Rà soát chéo 🔀):** Kiểm tra lại toàn bộ phân quyền IAM và độ an toàn trong code của BE1.

---

## 14. Lịch trình 6 tuần

W1 bắt đầu **thứ Hai 19/10/2026**; làm việc T2–T6, Thứ Sáu là ngày chốt tuần và demo chéo.

```
TUẦN 1 ──> TUẦN 2-3 ──> TUẦN 4 ──> TUẦN 5 ──> TUẦN 6
  │           │           │          │          │
Khởi tạo    Kết nối     Ghép nối   Hoàn thiện  Test nội bộ
 & Học     nền tảng     ảnh & con  & Đóng băng  & Thuyết
 cơ bản    với AI       cháu       (M4 Freeze)  trình
```

| Tuần | Ngày | Thứ Sáu (chốt tuần) |
|---|---|---|
| W1 | 19/10 – 25/10/2026 | 23/10 |
| W2 | 26/10 – 01/11/2026 | 30/10 |
| W3 | 02/11 – 08/11/2026 | 06/11 |
| W4 | 09/11 – 15/11/2026 | 13/11 |
| W5 | 16/11 – 22/11/2026 | 20/11 |
| W6 | 23/11 – 29/11/2026 | 27/11 |

**Phạm vi MVP trong 6 tuần:** text + ảnh (OCR) + cảnh báo người thân (kèm `/nguoithan`, `/lichsu`, RAG cơ bản).

**⏸ Hoãn (ngoài phạm vi 6 tuần), làm nếu dư thời gian:** voice/ASR, VLM fallback, multi-turn (BE-33), Mini App (BE-32, `contracts/openapi.yaml`) và toàn bộ Phase 2. Các việc này vẫn được giữ trong tài liệu, không xóa.

* **W1 (23/10 – G0 Contracts):** Học kiến thức nền tảng, tạo tài khoản AWS, tạo khung repo, tạo bot Telegram test.
* **W2 (30/10 – G1 Chốt LLM, G2 Chốt OCR):** Viết Webhook, dựng DynamoDB, kết nối với AI bản giả lập (Stub).
* **W3 (06/11 – Cột mốc M1: Text E2E):** Hoàn thành xử lý tin nhắn chữ. Ghép nối thật với Team AI đợt 1.
* **W4 (13/11 – Cột mốc M2: Ảnh + người thân):** Xử lý chặn file độc hại `.apk`, làm tính năng kết nối người thân (Double Opt-in), gửi cảnh báo khẩn cấp cho con cháu (BE-29), ghép nối ảnh/OCR (INT-02, p95 ảnh ≤ 15 giây).
* **W5 (20/11 – Cột mốc M3: RAG và M4: MVP FREEZE):** Hoàn thành RAG (trích nguồn cảnh báo; không có voice). Đóng băng toàn bộ code mới, chỉ test lỗi, UAT bấm tay, rà soát bảo mật chéo (BE-09a/09b). Chỉ tiêu: recall ≥ 90%, báo nhầm ≤ 10% trên 300 mẫu, không còn bug P0/P1.
* **W6 (cuối 25/11 – Cột mốc M5: Test nội bộ; 27/11 – Cột mốc M6: Thuyết trình):** Test nội bộ trong team và người nhà (thay cho pilot với hộ gia đình; không có số liệu người dùng thật ngoài team). Hạ tầng/runbook (BE-08b, BE-10, BE-11), slide kiến trúc, biểu đồ chi phí và thuyết trình trước hội đồng (rehearsal 2 lần).

Sau M4 (W5) chỉ sửa bug, chỉnh prompt/ngưỡng (qua eval gate) và việc hạ tầng/runbook.

**Lịch theo tuần cho 2 bạn Backend:**

| Tuần | BE1 | BE2 |
|---|---|---|
| W1 | Làm chung BE-01/02/03/14 · BE-05, BE-05b | Làm chung BE-01/02/03/14 · BE-20, BE-21 (bắt đầu) |
| W2 | BE-05b, BE-22, BE-07, BE-24b, BE-27 · trực tuần | BE-21, BE-24 |
| W3 | BE-07, BE-27, BE-23, BE-06 (bắt đầu), INT-01 | BE-04, BE-25, BE-26 · trực tuần |
| W4 | BE-06, BE-34, BE-29 🔀, BE-08a (bắt đầu), INT-02 · trực tuần | BE-28, BE-35 |
| W5 | BE-13, BE-08a, BE-09a 🔀, INT-03 🔀 | BE-30 🔀, BE-31 🔀, BE-36, BE-09b 🔀 · trực tuần |
| W6 | BE-10, BE-12 (trực test nội bộ), slide kiến trúc + chi phí | BE-08b 🔀, BE-11, UAT (điều phối), BE-12, video demo |

**Cảnh báo tải:** W4–W5 dồn việc (nén 2 tuần cũ thành 1). Thứ tự cắt tiếp nếu trễ: AI-11 (LLM-as-judge) → BE-10 → phần "người thân" của BE-36 → rút gọn AI-14 (red-team).

---

## 15. Điểm ghép nối với AI (INT) và Các mốc Gate

Các buổi họp kỹ thuật chéo giữa 2 bạn Backend và 2 bạn AI:

| Điểm ghép | Tuần | Nội dung tích hợp kỹ thuật | Điều kiện để nghiệm thu (Checklist) |
|---|---|---|---|
| **INT-01** | W3 | Ghép nối xử lý **Văn bản (Text)** | 1. Bot trả lời đúng các mẫu tin phạt nguội giả.<br>2. Log của Java và Python đều có chung mã `trace_id`.<br>3. Trong log **không có bất kỳ đoạn chữ nào của người dùng** (bảo mật). |
| **INT-02** | W4 | Ghép nối xử lý **Hình ảnh (OCR)** | 1. Gửi ảnh chụp màn hình tin nhắn rác → Bot đọc được chữ và trả lời dưới 15 giây.<br>2. Ảnh chỉ lưu trong RAM của AI, không lọt qua payload của Java. |
| **INT-03** | W5 | Ghép nối **RAG** (trích nguồn cảnh báo) | Bot trích được nguồn cảnh báo từ RAG trong kết quả trả về. Không có voice trong phạm vi 6 tuần. |
| ~~INT-03 (voice)~~ | ⏸ Hoãn | Ghép nối **Giọng nói (Voice/ASR)** | ⏸ Hoãn (ngoài phạm vi 6 tuần). Giữ checklist cũ: gửi ghi âm 40 giây → phản hồi dưới 30 giây; người già chưa bấm đồng ý xử lý voice thì bot từ chối ngay. |

---

## 16. Cách làm việc, Test local không tốn tiền và Definition of Done

### 16.1 Làm sao để lập trình và test trên máy mà KHÔNG TỐN TIỀN AWS?
Là sinh viên, bạn không thể cứ mỗi lần sửa 1 dòng code lại deploy lên AWS để test vì vừa lâu vừa sợ tốn tiền. Hãy dùng **3 bí kíp dev local** này:

1. **Test nghiệp vụ gọi AI:** Dùng `FakeAiClient`. Nó tự đọc file JSON mẫu có sẵn trong máy bạn, không tốn 1 xu gọi AWS Bedrock.
2. **Test Database:** Dùng thư viện `Testcontainers` trong Java. Mỗi khi chạy test, nó tự bật 1 database DynamoDB ảo trong Docker máy bạn, chạy xong tự xóa sạch:
   ```java
   @Testcontainers
   class DynamoDbTest {
       @Container
       static GenericContainer<?> dynamo = new GenericContainer<>("amazon/dynamodb-local:latest")
           .withExposedPorts(8000);
       // Test thoải mái không cần tài khoản AWS!
   }
   ```
3. **Test nhắn tin Telegram:** Chạy lệnh `./mvnw exec:java@local-bot` (trong `backend/`). Bot sẽ chạy trực tiếp trên laptop của bạn qua cơ chế Polling. Bạn cầm điện thoại nhắn tin cho Bot là log trên máy tính nhảy ra ngay tức thì.

### 16.2 Định nghĩa Hoàn thành một công việc (Definition of Done)
Một tính năng chỉ được xem là "XONG" khi thỏa mãn 5 tiêu chí:
1. Chạy lệnh `./mvnw verify` trên máy tính cá nhân không báo bất kỳ lỗi nào.
2. Đã viết unit test kiểm tra cả trường hợp đúng lẫn trường hợp người dùng nhập sai/lỗi mạng.
3. Đã deploy thành công lên môi trường `dev` và nhắn tin thử bằng điện thoại thật thấy chạy đúng.
4. Không in bất kỳ thông tin cá nhân (SĐT, nội dung chat) nào ra màn hình log.
5. Được người đồng đội (Pair) đọc và bấm Approve trên Pull Request.

---

## 17. Bảo mật & Quyền riêng tư: Luật bất di bất dịch

Dự án xử lý dữ liệu của người dùng thật theo Nghị định 356/2025/NĐ-CP và Luật Bảo vệ dữ liệu cá nhân (BVDLCN). Bạn bắt buộc phải tuân theo 6 điều luật này:

```
     ┌────────────────────────────────────────────────────────────────────────┐
     │                     6 NGUYÊN TẮC BẢO MẬT BẮT BUỘC                      │
     ├────────────────────────────────────────────────────────────────────────┤
     │ 1. KHÔNG LƯU NỘI DUNG GỐC: Chỉ lưu kết luận (Verdict), mã dấu hiệu.    │
     │    Toàn bộ dữ liệu tự hủy sau 30 ngày qua cơ chế TTL của DynamoDB.     │
     │                                                                        │
     │ 2. KHÔNG LOG NỘI DUNG: Nghiêm cấm in text người dùng, ảnh, token ra    │
     │    CloudWatch log. Chỉ được log: mã lỗi, thời gian chạy, trace_id.     │
     │                                                                        │
     │ 3. ĐỒNG Ý RÕ RÀNG: Người dùng phải bấm [Tôi đồng ý] mới được xử lý tin.│
     │    Không được ngầm định coi như đã đồng ý.                             │
     │                                                                        │
     │ 4. QUYỀN ĐƯỢC LÃNG QUÊN: Khi gõ /xoadulieu, hệ thống phải lập tức xóa  │
     │    sạch mọi bản ghi liên quan đến người đó trong Database.             │
     │                                                                        │
     │ 5. BẢO VỆ CON CHÁU: Người thân KHÔNG ĐƯỢC PHÉP xem lén toàn bộ lịch sử │
     │    chat của cha mẹ. Con cháu chỉ nhận tin khi có nguy hiểm thật sự.    │
     │                                                                        │
     │ 6. VÔ HIỆU HÓA LINK ĐỘC (Defang URL): Mọi đường link lừa đảo khi gửi   │
     │    cho người dùng phải đổi dạng: hxxps://tenmien[.]com để tránh bấm nhầm│
     └────────────────────────────────────────────────────────────────────────┘
```

---

## 18. Checklist các bẫy thường gặp và cách gỡ

Lưu lại bảng này để tra cứu mỗi khi gặp lỗi:

| Hiện tượng lỗi | Nguyên nhân gốc rễ | Cách xử lý đúng |
|---|---|---|
| **Bot gửi lại 2-3 tin nhắn kết quả y hệt nhau** | Lambda Webhook xử lý quá lâu (>1s). Telegram tưởng bot chết nên tự động gửi lại request. | Đẩy ngay việc sang SQS, Webhook chỉ tiếp nhận và trả về mã `200 OK` ngay lập tức. |
| **Vòng xoay trên nút bấm Telegram quay mãi không tắt** | Quên gọi hàm phản hồi nút bấm. | Bất cứ khi nào nhận `callback_query`, phải gọi ngay API `answerCallbackQuery`. |
| **Hết hạn mức tiền AWS hoặc tài khoản bị khóa** | Viết vòng lặp vô tận, hoặc để lộ API Key lên GitHub công khai. | Cài đặt AWS Budgets cảnh báo 10$, dùng Git Secret Scanning, tuyệt đối không commit token vào Git. |
| **Bị mất tin nhắn khi người già gửi liên tiếp** | Hàm gom tin xóa hộp đệm vô điều kiện khi đang có tin mới gửi đến. | Dùng điều kiện `max_seq == seq` khi xóa đệm. Nếu sai thì giữ lại để lượt sau xử lý tiếp. |
| **Lỗi SnapStart khi deploy Java** | Khởi tạo kết nối mạng hoặc đọc Secret Token ngay trong constructor lúc Lambda khởi động. | Đổi sang khởi tạo lười (Lazy initialization) khi nhận request đầu tiên. |
| **Số điện thoại/Link lạ trong tin nhắn gửi người thân bấm không được** | Telegram không cho gắn link `tel:` vào nút bấm Inline. | Dùng API `sendContact` để gửi danh bạ thật cho người dùng bấm vào gọi ngay. |

---

## 19. Những điểm chưa chắc chắn (⚠️) cần kiểm chứng

Trong tuần đầu tiên, hai bạn cần thực nghiệm và xác nhận lại các mục kỹ thuật này:
1. `java21` trên Lambda ở Singapore (`ap-southeast-1`) đã hỗ trợ SnapStart mượt mà chưa? Hãy chạy thử nghiệm ở bài tập `BE-14` để đo thời gian khởi động thật.
2. Thư viện sinh code `jsonschema2pojo` có đọc tốt các kiểu dữ liệu nâng cao (Union, Optional) từ file JSON Schema của team AI không? Nếu bị lỗi, chuyển sang phương án viết tay 15 class `record` Java chuẩn.
3. Kiểm tra thực tế xem mạng 4G Viettel, VNPT, MobiFone có kết nối tới Telegram Bot ổn định không, ghi lại nhật ký ngày thử nghiệm vào file README.

---

## 20. Phụ lục: Thuật ngữ & Bảng tra mã việc

### 20.1 Giải thích thuật ngữ cho sinh viên
* **Serverless (Không máy chủ):** Mô hình điện toán đám mây mà bạn không cần thuê hay quản lý hệ điều hành máy chủ. Nhà cung cấp (AWS) tự lo hạ tầng, bạn chỉ trả tiền cho số mili-giây code của bạn thực sự chạy.
* **Idempotency (Tính bất biến khi lặp):** Tính chất đảm bảo một hành động dù được thực hiện 1 lần hay 10 lần thì kết quả cuối cùng vẫn giống nhau (VD: phép gán `x = 5` là idempotent, nhưng phép cộng `x = x + 1` thì không).
* **DLQ (Dead Letter Queue):** Thùng chứa các thông điệp bị lỗi sau nhiều lần thử lại thất bại, giúp kỹ sư dễ dàng kiểm tra nguyên nhân mà không làm gián đoạn hệ thống.
* **Cold Start (Khởi động lạnh):** Độ trễ phát sinh khi một hàm Serverless được gọi lần đầu tiên sau một thời gian nhàn rỗi do máy chủ phải nạp môi trường máy ảo JVM.
* **Defang URL:** Kỹ thuật vô hiệu hóa một đường dẫn độc hại bằng cách thay thế các ký tự nhạy cảm (như đổi `http` thành `hxxp`, đổi dấu chấm `.` thành `[.]`) để người dùng không vô tình click vào.

### 20.2 Bảng phân công nhanh theo mã số công việc

| Mã số | Tên công việc ngắn gọn | Người làm chính | Tuần | Mức độ phức tạp |
|---|---|---|---|---|
| **BE-01** | Tạo tài khoản AWS & Cài đặt ngân sách cảnh báo tiền | BE1 | W1 | Nhẹ (S) |
| **BE-02** | Tạo khung mã nguồn Maven Java 21 | BE2 | W1 | Nhẹ (S) |
| **BE-03** | Viết template AWS SAM triển khai hạ tầng | BE1 + BE2 | W1–W2 | Vừa (M) |
| **BE-14** | Thử nghiệm SnapStart & DynamoDB Local | BE1 + BE2 | W1 | Nhẹ (S) |
| **BE-05** | Bật quyền gọi Bedrock AI ở Sydney | BE1 | W1 | Nhẹ (S) |
| **BE-05b**| Cấu hình sinh mã Java từ JSON Schema | BE1 | W1–W2 | Vừa (M) |
| **BE-20** | Tạo bot Telegram & chế độ debug local | BE2 | W1 | Nhẹ (S) |
| **BE-21** | Bộ phân tích gói tin Telegram Adapter | BE2 | W1–W2 | Vừa (M) |
| **BE-22** | Viết hàm Lambda Webhook Handler | BE1 | W2 | Vừa (M) |
| **BE-24** | Khung kết nối DynamoDB chung | BE2 | W2 | Vừa (M) |
| **BE-07** | Đấu nối hàng đợi SQS sang Worker | BE1 | W2–W3 | Vừa (M) |
| **BE-27** | Luồng xử lý công việc chính trong Worker | BE1 | W2–W3 | Vừa (M) |
| **BE-23** | Thuật toán gom tin liên tiếp (Debounce) | BE1 | W3 | Vừa (M) |
| **BE-04** | Tự động hóa CI/CD với GitHub Actions | BE2 | W3 | Vừa (M) |
| **BE-25** | Luồng chào hỏi & xin ý kiến người dùng | BE2 | W3 | Vừa (M) |
| **BE-26** | Bộ đếm giới hạn lượt dùng trong ngày | BE2 | W3 | Nhẹ (S) |
| **BE-35** | Chặn file độc hại `.apk` | BE2 | W4 | Nhẹ (S) |
| **BE-28** | Luồng mời và kết nối con cháu an toàn | BE2 | W4 | Lớn (L) |
| **BE-13** | Công tắc khẩn cấp ngắt kết nối AI (Kill switch) | BE1 | W5 | Nhẹ (S) |
| **BE-29** | Gửi tin nhắn cảnh báo khẩn cấp cho con cháu | BE1 | W4 | Vừa (M) |
| **BE-30** | Tiếp nhận phản hồi khi bot đánh giá sai | BE2 | W5 | Nhẹ (S) |
| **BE-31** | Tính năng xóa dữ liệu cá nhân theo luật | BE2 | W5 | Vừa (M) |
| **BE-08a**| Ghi log và dựng màn hình theo dõi hệ thống | BE1 | W4–W5 | Vừa (M) |
| **BE-36** | Menu xem lịch sử và quản lý con cháu trong chat | BE2 | W5 | Vừa (M) |
| **BE-08b**| Cài đặt chuông báo động sự cố qua Email | BE2 | W6 | Nhẹ (S) |
| **BE-09a**| Rà soát an toàn bảo mật cho nửa của BE2 | BE1 | W5 | Nhẹ (S) |
| **BE-09b**| Rà soát an toàn bảo mật cho nửa của BE1 | BE2 | W5 | Nhẹ (S) |
| **BE-11** | Đóng gói môi trường chạy thật (`prod`) | BE2 | W6 | Vừa (M) |
