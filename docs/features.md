# Tính năng của Hỏi Trước (Features)

> Bản tổng quan cho cả team: **sản phẩm gồm những tính năng gì, mỗi tính năng làm tới đâu và việc nào phải làm trước**. Nguồn: danh sách FR ở [ARCH §1.2](system-architecture.md#12-yêu-cầu-chức-năng-fr) và các task ở [PLAN §6–§7](implementation-plan.md#6-chi-tiết-công-việc-theo-track) (v3.1, 19/10–29/11/2026). Mâu thuẫn thì ARCH/PLAN đúng; hãy báo để sửa file này.

- **File này** trả lời câu hỏi *tính năng là gì, xong khi nào thì tính là xong*.
- **[Board "Ask First: Sprint Board"](https://github.com/orgs/ask-first-fcaj/projects/1)** cho biết *đang làm tới đâu*. Mỗi FR là một issue cha (label `type: feature`), các task là sub-issue của nó, nên mở issue FR là thấy thanh tiến độ và danh sách task.

## Mục lục

- [1. Tổng quan một trang](#1-tổng-quan-một-trang)
- [2. Thứ tự làm](#2-thứ-tự-làm)
- [3. Chi tiết từng tính năng](#3-chi-tiết-từng-tính-năng)
- [4. Nhóm việc không phải tính năng](#4-nhóm-việc-không-phải-tính-năng)
- [5. Hoãn và ngoài phạm vi](#5-hoãn-và-ngoài-phạm-vi)
- [6. Cách dùng board](#6-cách-dùng-board)

## 1. Tổng quan một trang

Người cao tuổi **chuyển tiếp, chụp màn hình hoặc dán link** tin đáng ngờ cho bot Telegram. Trong khoảng 15 giây bot trả lời bằng tiếng Việt dễ hiểu: tin có dấu hiệu của kịch bản lừa đảo nào, vì sao, cần làm gì ngay. Nếu rủi ro cao, bot **báo người thân** đã được đồng ý trước (ARCH §1.1).

| FR | Tính năng | Phạm vi 6 tuần | Xong ở mốc | Người làm chính | Issue |
|---|---|---|---|---|---|
| FR1 | Nhận văn bản, link, ảnh chụp màn hình, album, tin chuyển tiếp | MVP | M1 (văn bản, W3) · M2 (ảnh, W4) | BE1, BE2, AI1, AI2 | [#19](https://github.com/ask-first-fcaj/ask-first/issues/19) |
| FR2 | Nhận voice note và file ghi âm | ⏸ Hoãn | (bot trả "chưa hỗ trợ voice") | | [#20](https://github.com/ask-first-fcaj/ask-first/issues/20) |
| FR3 | Kết luận 4 mức kèm kịch bản, dấu hiệu, việc cần làm, kênh chính thức | MVP | M1 (W3) · M3 trích nguồn (W5) · M4 đạt chỉ tiêu | AI1, AI2, BE1 | [#21](https://github.com/ask-first-fcaj/ask-first/issues/21) |
| FR4 | Onboarding có đồng ý chủ động, rút đồng ý, xoá dữ liệu | MVP | W3 (đồng ý) · W5 (rút, xoá) | BE2 | [#22](https://github.com/ask-first-fcaj/ask-first/issues/22) |
| FR5 | Liên kết người thân (double opt-in), chọn mức chia sẻ | MVP | M2 (W4) | BE2 | [#23](https://github.com/ask-first-fcaj/ask-first/issues/23) |
| FR6 | Cảnh báo người thân khi rủi ro cao | MVP | M2 (W4) | BE1 | [#24](https://github.com/ask-first-fcaj/ask-first/issues/24) |
| FR7 | Nút "Bot báo sai" để thu feedback | MVP | W5 | BE2 | [#25](https://github.com/ask-first-fcaj/ask-first/issues/25) |
| FR8 | Hỏi thêm một câu khi thiếu thông tin (multi-turn) | ⏸ Hoãn | | | [#26](https://github.com/ask-first-fcaj/ask-first/issues/26) |
| FR9 | Nhắc lại sau 15 phút ("Bác đã gọi cho con chưa?") | MVP (làm trong BE-29) | M2 (W4) | BE1 | [#27](https://github.com/ask-first-fcaj/ask-first/issues/27) |
| FR10 | Checklist "30 phút đầu" sau khi đã bị lừa | MVP | W3 | BE2, AI2 | [#28](https://github.com/ask-first-fcaj/ask-first/issues/28) |
| FR11 | Quản lý ngay trong chat: `/nguoithan`, `/lichsu`, `/rutdongy`, `/xoadulieu` | MVP | W5 | BE2 | [#29](https://github.com/ask-first-fcaj/ask-first/issues/29) |
| FR12 | Kill switch: tắt LLM, chỉ chạy luật, không cần deploy | MVP | W5 | BE1 | [#30](https://github.com/ask-first-fcaj/ask-first/issues/30) |
| FR13 | Người thân bấm "Tôi đã liên hệ", người cao tuổi được báo lại | MVP (làm trong BE-29) | M2 (W4) | BE1 | [#31](https://github.com/ask-first-fcaj/ask-first/issues/31) |
| FR14 | `/caidat`: chọn xưng hô Bác/Cô/Chú | MVP (làm trong BE-25) | W3 | BE2 | [#32](https://github.com/ask-first-fcaj/ask-first/issues/32) |
| FR15 | Xử lý đầu vào không hỗ trợ (video, sticker, vị trí, danh bạ, `.apk`) | MVP | W4 | BE1, BE2 | [#33](https://github.com/ask-first-fcaj/ask-first/issues/33) |
| FR17 | Mini App: giao diện web trong Telegram | ⏸ Hoãn | | | [#34](https://github.com/ask-first-fcaj/ask-first/issues/34) |

- **Không có FR16**: ARCH đánh số nhảy từ FR15 sang FR17. File này giữ nguyên mã cho khớp ARCH.
- **FR9 và FR14** ghi "MVP+" ở ARCH §1.2, nhưng PLAN v3 đã gộp vào BE-29 và BE-25, nên trong 6 tuần vẫn làm.
- **4 mức kết luận**: `DANGEROUS`, `SUSPICIOUS`, `UNSURE`, `LOW_RISK`. Không có mức "an toàn".
- Chỉ tiêu chung của MVP (ARCH §1.3, PLAN §4 M4): recall lớp lừa đảo ≥ 90%, báo nhầm ≤ 10% trên bộ 300 mẫu; p95 ≤ 15 giây cho văn bản và ảnh; ảnh gốc không ra khỏi Lambda `ai`; LLM chỉ nhận văn bản đã mask; log không có nội dung.

## 2. Thứ tự làm

### 2.1 Chuỗi mốc

Mỗi mốc mở khoá phần sau. Làm lệch thứ tự thì phần sau bị chặn.

| Tuần | Mốc / gate | Mở khoá được gì | Tính năng xong ở mốc này |
|---|---|---|---|
| W1 (19–25/10) | **G0** contract BE↔AI | Cả hai track code song song trên cùng schema | (nền tảng) |
| W2 (26/10–01/11) | **G1** chốt LLM · **G2** chốt OCR | Judge prompt (AI-04), OCR production (AI-26), image `ai` (BE-06) | (spike) |
| W3 (02–08/11) | **M1** văn bản E2E | Ghép ảnh, người thân | FR1 (văn bản), FR3 bản đầu, FR4 (đồng ý), FR10, FR14 |
| W4 (09–15/11) | **M2** ảnh + người thân | RAG, test tải | FR1 (ảnh), FR5, FR6, FR9, FR13, FR15 |
| W5 (16–22/11) | **M3** RAG · **M4** MVP freeze | Test nội bộ; sau M4 chỉ sửa bug | FR3 (trích nguồn, đạt chỉ tiêu), FR4 (rút, xoá), FR7, FR11, FR12 |
| W6 (23–29/11) | **M5** test nội bộ · **M6** thuyết trình | | |

### 2.2 Ba thứ trên board cho biết thứ tự

1. **Blocked by / Blocking** trên từng issue: lấy từ cột "Cần trước" của PLAN §6. Gate được thay bằng task chốt gate đó: G0 → [#2](https://github.com/ask-first-fcaj/ask-first/issues/2), G1 → AI-03 [#38](https://github.com/ask-first-fcaj/ask-first/issues/38), G2 → AI-20 [#17](https://github.com/ask-first-fcaj/ask-first/issues/17), M4 → AI-13 [#75](https://github.com/ask-first-fcaj/ask-first/issues/75). Issue còn "Blocked by" chưa đóng thì chưa tới lượt.
2. **Start Date / Target Date**: theo lịch tuần ở PLAN §5 (hạn là thứ Sáu của tuần kết thúc; AI-22 và AI-23 hạn 04/11).
3. **Ưu tiên**:
   - **P0 · Đường găng**: một gate hoặc mốc đang chờ trực tiếp việc này; trễ là trễ mốc. Có cả việc mở khoá cho người khác (vd BE-01, BE-24).
   - **P1 · Bình thường**: cần cho MVP nhưng còn chút dư thời gian.
   - **P2 · Cắt được nếu trễ**: nằm trong thứ tự cắt ở PLAN §5 (AI-11 → BE-10 → phần người thân của BE-36 → rút gọn AI-14). BE-36 vẫn để P1 vì chỉ cắt một phần.

   Đây là đề xuất ban đầu; team đổi mức trên board khi thấy cần.

**Phụ thuộc suy ra** (không nằm trong cột "Cần trước" của PLAN, mà suy từ nội dung việc): G0 cần SH-01 (SH-01 "xong khi G0 qua"); INT-01 cần BE-27 và AI-06 (pipeline văn bản chạy qua worker và có bước respond); INT-02 cần BE-06 và AI-26 (image `ai` có OCR); INT-03 cần AI-29 (RAG).

### 2.3 Chọn việc tiếp theo

1. Mở view **👤 Việc của tôi** trên board (đã sắp theo Start Date).
2. Lấy việc đầu tiên chưa Done mà không còn issue "Blocked by" nào mở.
3. Có nhiều việc cùng sẵn sàng thì làm **P0** trước.
4. Bị chặn quá nửa ngày thì báo người giữ việc đang chặn ngay trong ngày, không ngồi chờ tới standup.

## 3. Chi tiết từng tính năng

Mỗi mục gồm: người dùng làm gì, bot phải làm gì, xong khi nào (kiểm được), và các task. Bảng task sinh từ PLAN; mã task bấm được sang issue.

### FR1. Nhận văn bản, link, ảnh chụp màn hình, album, tin chuyển tiếp

**Issue:** [#19](https://github.com/ask-first-fcaj/ask-first/issues/19) · **Phạm vi:** MVP · **Xong ở:** M1 (văn bản, W3), M2 (ảnh, W4) · **Tham chiếu:** ARCH §5.1, §5.2, §6.3

**Người dùng:** chuyển tiếp tin, dán link, gửi ảnh chụp màn hình hoặc album ảnh cho bot. Có thể gửi nhiều tin liền nhau.

**Bot phải:**
- Hiện tin "⏳ Bác chờ chút, đang kiểm tra…" ngay, **chỉ cho tin đầu của một cụm**.
- Gom các tin gửi liền nhau (chờ 3 giây) và album (`media_group_id`) thành **một** câu trả lời; tin đến muộn vẫn được trả lời, không ghi đè kết quả cũ (ARCH §5.2).
- Đọc chữ trong ảnh bằng **OCR chạy local** trong Lambda `ai`; ảnh gốc không ra khỏi Lambda. Giải mã QR local.
- Lấy link từ cả chữ lẫn link ẩn sau chữ (`entities`: `url`, `text_link`).
- Webhook luôn trả 200 nhanh, chống xử lý trùng một update, chỉ nhận chat riêng.

**Xong khi:**
- Gửi 3 tin trong 2 giây thì có đúng 1 tin "⏳" và 1 câu trả lời; album 4 ảnh thì 1 câu trả lời (BE-23).
- OCR chép đúng URL ≥ 90% trên 50 ảnh thật (G2, AI-26).
- p95 với ảnh ≤ 15 giây khi Lambda đã warm (M2).

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [BE-21 #14](https://github.com/ask-first-fcaj/ask-first/issues/14) | Telegram adapter (Java): parseUpdate, send/edit, contract test payload thật | BE2 + BE1 | W1–W2 | P0 | [G0](https://github.com/ask-first-fcaj/ask-first/issues/2), [BE-05b](https://github.com/ask-first-fcaj/ask-first/issues/12) |
| [AI-20 #17](https://github.com/ask-first-fcaj/ask-first/issues/17) | Spike OCR: so sánh RapidOCR / VietOCR / Tesseract trên 50 ảnh thật (gate G2) | AI2 + AI1 (cùng chốt G2) | W1–W2 | P0 | — |
| [BE-22 #43](https://github.com/ask-first-fcaj/ask-first/issues/43) | Webhook handler (Java): secret constant-time, dedup, đường nhanh/chậm | BE1 (pair BE2) | W2 | P0 | [BE-03](https://github.com/ask-first-fcaj/ask-first/issues/1), [BE-21](https://github.com/ask-first-fcaj/ask-first/issues/14) |
| [BE-23 #59](https://github.com/ask-first-fcaj/ask-first/issues/59) | Gom tin (debounce) và album | BE1 (pair BE2) | W3 | P1 | [BE-27](https://github.com/ask-first-fcaj/ask-first/issues/46) |
| [AI-26 #61](https://github.com/ask-first-fcaj/ask-first/issues/61) | OCR production: ONNX int8, sửa URL, decode QR | AI1 (AI2 phụ: MODELS.md, QR, regex URL) | W4 | P0 | [AI-20](https://github.com/ask-first-fcaj/ask-first/issues/17) |
| [INT-02 #66](https://github.com/ask-first-fcaj/ask-first/issues/66) | Ghép image ai đầy đủ + OCR local (M2) | BE1 + AI1 (AI2 phụ) | W4 | P0 | [BE-06](https://github.com/ask-first-fcaj/ask-first/issues/62), [AI-26](https://github.com/ask-first-fcaj/ask-first/issues/61) |

### FR2. Nhận voice note và file ghi âm (⏸ hoãn)

**Issue:** [#20](https://github.com/ask-first-fcaj/ask-first/issues/20) · **Phạm vi:** ⏸ hoãn ngoài 6 tuần (PLAN §5) · **Tham chiếu:** ARCH §1.2, §6.0

Trong MVP, gửi voice (mọi độ dài) thì bot trả câu "chưa hỗ trợ voice" (BE-34, xem FR15). Việc hoãn: AI-21 spike ASR [#18](https://github.com/ask-first-fcaj/ask-first/issues/18), AI-28 ASR production (chưa tạo issue).

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [AI-21 #18](https://github.com/ask-first-fcaj/ask-first/issues/18) | Spike ASR: so sánh Zipformer-vi / PhoWhisper trên 20+ voice (gate G2) | AI2 | Hoãn | ⏸ | — |

### FR3. Kết luận 4 mức kèm kịch bản, dấu hiệu, việc cần làm và kênh chính thức

**Issue:** [#21](https://github.com/ask-first-fcaj/ask-first/issues/21) · **Phạm vi:** MVP · **Xong ở:** M1 (luật + Qwen3, W3), M3 (trích nguồn, W5), M4 (đạt chỉ tiêu, W5) · **Tham chiếu:** ARCH §6.2–§6.10, PLAN §3.2

Đây là lõi của sản phẩm: luồng `normalize → mask → rules → (RAG) → judge → fuse → respond` trong Lambda `ai`, do worker Java gọi.

**Người dùng nhận được:**
- Một trong 4 mức: `DANGEROUS`, `SUSPICIOUS`, `UNSURE`, `LOW_RISK` (không có "an toàn").
- Tên kịch bản lừa đảo (N0–N6), các dấu hiệu trích từ chính tin, việc cần làm ngay, hotline hoặc kênh chính thức đã xác minh, và trích nguồn cảnh báo chính thức (từ M3).
- Phần kết luận ≤ 600 ký tự; link nghi ngờ đã bị vô hiệu (`hxxps://…[.]…`), không bấm được.

**Bot phải:**
- Che số điện thoại, số tài khoản, CCCD, OTP, email **trước** mọi lời gọi ra ngoài; LLM chỉ thấy văn bản đã che (AI-22).
- Luật đủ chắc (sàn ≥ 85) thì trả lời bằng template, không gọi LLM (fast path).
- LLM lỗi hoặc bị throttle thì chuyển sang một model dự phòng, rồi tới chế độ chỉ chạy luật; vẫn luôn trả lời (AI-07).
- Phát hiện prompt injection thì cộng điểm rủi ro (AI-08).

**Xong khi:**
- M1: 20 mẫu văn bản đúng ≥ 85% trên bot `dev`.
- M3: Recall@4 scenario card ≥ 90% trên `real_scam`; truy vấn RAG < 100 ms.
- M4: recall ≥ 90%, báo nhầm ≤ 10% trên 300 mẫu; JSON hợp lệ ≥ 98%.

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [AI-02 #16](https://github.com/ask-first-fcaj/ask-first/issues/16) | Bedrock client (ap-southeast-2) + spike Qwen3: Converse, structured output, tắt thinking | AI1 + BE1 | W1 | P0 | [BE-05](https://github.com/ask-first-fcaj/ask-first/issues/11) |
| [AI-04 #40](https://github.com/ask-first-fcaj/ask-first/issues/40) | Judge prompt v1 + output schema + post-validation | AI1 | W2–W3 | P0 | [AI-03](https://github.com/ask-first-fcaj/ask-first/issues/38) |
| [AI-22 #41](https://github.com/ask-first-fcaj/ask-first/issues/41) | Mask PII: SĐT, STK, CCCD, OTP, email, số viết bằng chữ | AI2 (AI1 review như test bảo mật) | W2–W3 | P0 | [G0](https://github.com/ask-first-fcaj/ask-first/issues/2) |
| [BE-27 #46](https://github.com/ask-first-fcaj/ask-first/issues/46) | Worker orchestration (Java) + ai bản stub | BE1 (pair BE2, deploy ai cùng AI1) | W2–W3 | P0 | [BE-07](https://github.com/ask-first-fcaj/ask-first/issues/42), [BE-24](https://github.com/ask-first-fcaj/ask-first/issues/44), [BE-24b](https://github.com/ask-first-fcaj/ask-first/issues/45), [BE-05b](https://github.com/ask-first-fcaj/ask-first/issues/12), [AI-03b](https://github.com/ask-first-fcaj/ask-first/issues/39) |
| [SH-05 #47](https://github.com/ask-first-fcaj/ask-first/issues/47) | Xác minh hotline và kênh trình báo chỉ từ trang chính thức | AI2 + BE2 | W2–W3 | P1 | — |
| [AI-25 #48](https://github.com/ask-first-fcaj/ask-first/issues/48) | Dữ liệu KB v1: scenarios, allowlist, brands, hotlines, checklist | AI2 (AI1 kèm) | W2–W3 | P1 | [SH-02](https://github.com/ask-first-fcaj/ask-first/issues/4), [SH-05](https://github.com/ask-first-fcaj/ask-first/issues/47) |
| [AI-05 #51](https://github.com/ask-first-fcaj/ask-first/issues/51) | Fuse: điểm, mức, sàn cứng, chế độ chỉ luật, fast path | AI1 | W3 | P0 | [G0](https://github.com/ask-first-fcaj/ask-first/issues/2) |
| [AI-23 #52](https://github.com/ask-first-fcaj/ask-first/issues/52) | Chuẩn hoá văn bản + rules engine v1 | AI2 (AI1 dựng khung interface trước) | W3 | P0 | [AI-22](https://github.com/ask-first-fcaj/ask-first/issues/41) |
| [SH-08 #54](https://github.com/ask-first-fcaj/ask-first/issues/54) | Copy tin nhắn của bot: chào, đồng ý, 4 mức, checklist, cảnh báo người thân | BE2 + AI2 (AI1 duyệt câu chữ) | W3 | P0 | — |
| [AI-06 #55](https://github.com/ask-first-fcaj/ask-first/issues/55) | Respond: template 4 mức, cảnh báo người thân, defang URL | AI1 | W3 | P0 | [AI-05](https://github.com/ask-first-fcaj/ask-first/issues/51), [SH-08](https://github.com/ask-first-fcaj/ask-first/issues/54) |
| [INT-01 #56](https://github.com/ask-first-fcaj/ask-first/issues/56) | Ghép RealPipeline văn bản vào Lambda ai (M1) | BE1 + AI1 | W3 | P0 | [AI-04](https://github.com/ask-first-fcaj/ask-first/issues/40), [AI-05](https://github.com/ask-first-fcaj/ask-first/issues/51), [AI-06](https://github.com/ask-first-fcaj/ask-first/issues/55), [AI-22](https://github.com/ask-first-fcaj/ask-first/issues/41), [AI-23](https://github.com/ask-first-fcaj/ask-first/issues/52), [AI-03b](https://github.com/ask-first-fcaj/ask-first/issues/39), [BE-27](https://github.com/ask-first-fcaj/ask-first/issues/46) |
| [AI-24a #57](https://github.com/ask-first-fcaj/ask-first/issues/57) | URL intel cơ bản: eTLD+1, allowlist, typosquat, homoglyph | AI2 (AI1 kèm) | W3–W4 | P1 | [AI-23](https://github.com/ask-first-fcaj/ask-first/issues/52) |
| [AI-29 #63](https://github.com/ask-first-fcaj/ask-first/issues/63) | Embedding + Retriever (RAG local), corpus 100 cảnh báo | AI2 (AI1 kèm phần nạp model ONNX) | W4–W5 | P0 | [AI-25](https://github.com/ask-first-fcaj/ask-first/issues/48) |
| [AI-07 #67](https://github.com/ask-first-fcaj/ask-first/issues/67) | Cascade và fallback: một model dự phòng, chế độ chỉ luật | AI1 | W4 | P1 | [AI-04](https://github.com/ask-first-fcaj/ask-first/issues/40) |
| [AI-08 #68](https://github.com/ask-first-fcaj/ask-first/issues/68) | Guardrails: prompt attack, ApplyGuardrail | AI2 (AI1 duyệt) | W4 | P1 | [AI-02](https://github.com/ask-first-fcaj/ask-first/issues/16) |
| [AI-24b #70](https://github.com/ask-first-fcaj/ask-first/issues/70) | URL intel phần mạng: mở shortener chống SSRF, cache URL | AI1 | W4–W5 | P1 | [AI-24a](https://github.com/ask-first-fcaj/ask-first/issues/57) |
| [INT-03 #76](https://github.com/ask-first-fcaj/ask-first/issues/76) | Ghép RAG vào Lambda ai, bật FEATURE_RAG (M3) | BE2 + AI2, AI1 kèm (BE1 pair) 🔀 | W5 | P0 | [AI-29](https://github.com/ask-first-fcaj/ask-first/issues/63) |

### FR4. Onboarding có đồng ý chủ động, rút đồng ý, xoá dữ liệu

**Issue:** [#22](https://github.com/ask-first-fcaj/ask-first/issues/22) · **Phạm vi:** MVP · **Xong ở:** W3 (đồng ý), W5 (rút, xoá) · **Tham chiếu:** ARCH §5.3, §5.6, §9.1

**Người dùng:** gõ `/start`, đọc lời chào và tóm tắt chính sách 5 dòng (kèm link chính sách đầy đủ), bấm [Tôi đồng ý]. Sau đó có thể `/rutdongy` (toàn bộ hoặc từng mục) và `/xoadulieu` (hỏi xác nhận 2 bước).

**Bot phải:**
- Nói rõ "Bot KHÔNG BAO GIỜ xin OTP/mật khẩu/tiền".
- **Chưa đồng ý thì không xử lý nội dung.**
- Đồng ý tách theo 4 mục đích, mặc định tắt, hỏi đúng lúc cần: `analyze` (lúc `/start`), `guardian` (lần đầu `/nguoithan`), `voice` (lần đầu gửi voice), `donate` (khi bấm "Đóng góp mẫu").
- Lưu bằng chứng đồng ý (`consent_version`, `policy_sha256`, `AUDIT`); đổi phiên bản chính sách thì phải đồng ý lại.
- `/xoadulieu`: xoá mọi dữ liệu của người dùng, thu hồi liên kết người thân, xoá mẫu đã đóng góp, hướng dẫn người dùng tự xoá chat trong Telegram.
- Có trang chính sách quyền riêng tư công khai (FE-00) để gắn vào lời chào.

**Xong khi:**
- Chưa đồng ý thì không có job nào được đưa vào hàng đợi; đổi `policy_sha256` thì bị chặn tới khi đồng ý lại (BE-25).
- Sau `/xoadulieu`, `USER#uid` chỉ còn bản ghi audit tối thiểu và prefix `donated/` của user trống (BE-31).

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [SH-07 #49](https://github.com/ask-first-fcaj/ask-first/issues/49) | Chính sách quyền riêng tư và disclaimer (tiếng Việt, dễ đọc) | BE2 + AI1 | W2 | P1 | — |
| [FE-00 #50](https://github.com/ask-first-fcaj/ask-first/issues/50) | Trang chính sách quyền riêng tư (static, HTTPS) | BE2 | W2 | P1 | [SH-07](https://github.com/ask-first-fcaj/ask-first/issues/49) |
| [BE-25 #53](https://github.com/ask-first-fcaj/ask-first/issues/53) | Onboarding và đồng ý theo mục đích, /caidat, /huongdan, /bilua | BE2 (pair BE1) | W3 | P0 | [BE-22](https://github.com/ask-first-fcaj/ask-first/issues/43), [SH-07](https://github.com/ask-first-fcaj/ask-first/issues/49) |
| [BE-31 #81](https://github.com/ask-first-fcaj/ask-first/issues/81) | /xoadulieu, /rutdongy, AUDIT | BE2 (pair bắt buộc BE1) 🔀 | W5 | P1 | [BE-24](https://github.com/ask-first-fcaj/ask-first/issues/44), [BE-24b](https://github.com/ask-first-fcaj/ask-first/issues/45) |

### FR5. Liên kết người thân (double opt-in)

**Issue:** [#23](https://github.com/ask-first-fcaj/ask-first/issues/23) · **Phạm vi:** MVP · **Xong ở:** M2 (W4) · **Tham chiếu:** ARCH §5.4

**Luồng:**
1. Người cao tuổi gõ `/nguoithan`, bấm [Mời người thân]; bot gửi link mời để chuyển cho con (vd qua Zalo).
2. Người thân bấm link, đồng ý nhận cảnh báo; có thể chia sẻ số điện thoại để có nút "Gọi cho con".
3. Người cao tuổi xác nhận và chọn **mức chia sẻ**: Mức 1 chỉ báo có nguy cơ (mặc định), Mức 2 thêm tên kịch bản và dấu hiệu chính, Mức 3 thêm tóm tắt đã che số.
4. Chỉ khi cả hai đã đồng ý thì liên kết mới có hiệu lực.

**Quy tắc:** tối đa 5 người thân; mã mời dùng một lần, hết hạn sau 24 giờ; một bên huỷ thì liên kết bị thu hồi; người thân chặn bot thì liên kết thành `unreachable` và người cao tuổi được báo.

**Xong khi:** kịch bản test tay 6 bước pass trên 2 tài khoản Telegram thật; mã hết hạn hoặc đã dùng bị từ chối (BE-28).

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [BE-28 #64](https://github.com/ask-first-fcaj/ask-first/issues/64) | Liên kết người thân double opt-in | BE2 (pair BE1) | W4 | P0 | [BE-25](https://github.com/ask-first-fcaj/ask-first/issues/53) |

### FR6. Cảnh báo người thân khi rủi ro cao

**Issue:** [#24](https://github.com/ask-first-fcaj/ask-first/issues/24) · **Phạm vi:** MVP · **Xong ở:** M2 (W4) · **Tham chiếu:** ARCH §5.5

**Khi nào báo tự động:** mức `DANGEROUS` **và** có hành động rủi ro (chuyển tiền, đưa OTP, cài app, chia sẻ màn hình, giữ bí mật) **và** có người thân đang `active`. Ở mức `SUSPICIOUS` bot chỉ hiện nút "📣 Báo cho con" để người cao tuổi tự quyết.

**Bot phải:**
- Gửi **song song** cho mọi người thân `active`, nội dung theo mức chia sẻ.
- Không gửi ảnh hay voice gốc, không gửi số đầy đủ; URL nguy hiểm đã vô hiệu, tắt xem trước link.
- **Chỉ nói "đã báo cho con" khi Telegram xác nhận gửi được**; gửi lỗi thì nói thật và bảo người cao tuổi gọi trực tiếp.
- Không gửi lại cảnh báo cùng kịch bản trong 30 phút.

**Xong khi:** demo tin nguy hiểm thì điện thoại "con" rung trong ≤ 5 giây sau kết quả; người thân đã chặn bot thì người cao tuổi **không** thấy dòng "đã báo" (BE-29).

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [BE-29 #65](https://github.com/ask-first-fcaj/ask-first/issues/65) | Cảnh báo người thân + nhắc lại 15 phút + nút Tôi đã liên hệ | BE1 (pair bắt buộc BE2) 🔀 | W4 | P0 | [BE-28](https://github.com/ask-first-fcaj/ask-first/issues/64), [INT-01](https://github.com/ask-first-fcaj/ask-first/issues/56) |

### FR7. Nút "Bot báo sai"

**Issue:** [#25](https://github.com/ask-first-fcaj/ask-first/issues/25) · **Phạm vi:** MVP · **Xong ở:** W5 · **Tham chiếu:** ARCH §5.6

**Luồng:** dưới kết quả có nút báo sai; bấm vào thì bot hỏi "Tin này thật ra là: [Tin thật] [Lừa đảo mà bot bỏ sót]" và lưu vào `CHECK.feedback`. Người dùng có thể "Đóng góp mẫu (đã che thông tin)" nếu đồng ý riêng mục `donate`; bản đã che được giữ 30 ngày để team bổ sung bộ eval.

**Xong khi:** feedback hiện trong `CHECK.feedback` (BE-30).

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [BE-30 #80](https://github.com/ask-first-fcaj/ask-first/issues/80) | Feedback Bot báo sai + Đóng góp mẫu | BE2 (pair bắt buộc BE1) 🔀 | W5 | P1 | [BE-27](https://github.com/ask-first-fcaj/ask-first/issues/46) |

### FR8. Hỏi thêm một câu khi thiếu thông tin (⏸ hoãn)

**Issue:** [#26](https://github.com/ask-first-fcaj/ask-first/issues/26) · **Phạm vi:** ⏸ hoãn ngoài 6 tuần · **Tham chiếu:** ARCH §6.12

Việc hoãn: BE-33 và AI-12 (chưa tạo issue). MVP trả lời ngay một lượt.

### FR9. Nhắc lại sau 15 phút

**Issue:** [#27](https://github.com/ask-first-fcaj/ask-first/issues/27) · **Phạm vi:** MVP, làm trong BE-29 [#65](https://github.com/ask-first-fcaj/ask-first/issues/65) · **Xong ở:** M2 (W4) · **Tham chiếu:** ARCH §5.5

**Luồng:** 15 phút sau khi gửi cảnh báo, nếu chưa người thân nào bấm [Tôi đã liên hệ]:
- hỏi người cao tuổi "Bác đã gọi cho con chưa?" [Rồi] [Chưa, gọi giúp tôi] (nút sau gửi số của người thân);
- nhắc người thân một lần, gửi im lặng. Sau đó dừng.

**Xong khi:** demo BE-29 có tin nhắc sau 15 phút. Issue FR này bị chặn bởi BE-29 nên tự hiện tiến độ qua BE-29.

### FR10. Checklist "30 phút đầu" sau khi đã bị lừa

**Issue:** [#28](https://github.com/ask-first-fcaj/ask-first/issues/28) · **Phạm vi:** MVP · **Xong ở:** W3 · **Tham chiếu:** ARCH §2.5, §5.3; PLAN BE-25, AI-25, SH-08

**Luồng:** người dùng bấm nút trên bàn phím cố định hoặc gõ `/bilua`; bot gửi checklist những việc cần làm ngay và kênh trình báo chính thức. Nội dung lấy từ KB (template checklist ở AI-25, hotline đã xác minh từ trang chính thức ở SH-05), câu chữ ở SH-08.

**Xong khi:** 2 người lớn tuổi (người nhà) đọc thử và hiểu đúng (SH-08); mọi mục trong KB có nguồn (AI-25). Issue FR này bị chặn bởi BE-25, AI-25 và SH-08.

### FR11. Quản lý ngay trong chat

**Issue:** [#29](https://github.com/ask-first-fcaj/ask-first/issues/29) · **Phạm vi:** MVP · **Xong ở:** W5 · **Tham chiếu:** ARCH §5.9

| Lệnh | Người cao tuổi thấy | Người thân thấy |
|---|---|---|
| `/nguoithan` | Danh sách người thân (tên, mức chia sẻ, trạng thái) với nút [Đổi mức] [Tạm dừng / Bật lại] [Gỡ]; cuối có [Mời người thân] | Danh sách người mình bảo vệ, nút [Ngừng nhận cảnh báo] |
| `/lichsu` | 10 lượt kiểm tra gần nhất: giờ, mức, tên kịch bản (**chỉ metadata**); nút [Xem thêm] | Không xem được lịch sử của người cao tuổi |
| `/caidat` | Chọn xưng hô (FR14) | Chọn xưng hô |
| `/rutdongy`, `/xoadulieu` | Như FR4, hỏi xác nhận 2 bước | Như FR4 |

Mọi lệnh chạy ở đường nhanh của webhook (không qua SQS); nút chỉ mang mã ngắn, không chứa dữ liệu cá nhân; mỗi trang ≤ 10 dòng.

**Xong khi:** người thân gõ `/lichsu` không thấy lịch sử của người cao tuổi; `callback_data` ≤ 64 byte; 2 người lớn tuổi dùng thử đổi mức và xem lịch sử mà không phải hỏi (BE-36). `/rutdongy` và `/xoadulieu` làm ở BE-31 (thuộc FR4).

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [BE-36 #82](https://github.com/ask-first-fcaj/ask-first/issues/82) | Quản lý trong chat: /nguoithan, /lichsu | BE2 (pair BE1) | W5 | P1 | [BE-28](https://github.com/ask-first-fcaj/ask-first/issues/64), [BE-24](https://github.com/ask-first-fcaj/ask-first/issues/44), [BE-24b](https://github.com/ask-first-fcaj/ask-first/issues/45) |

### FR12. Kill switch

**Issue:** [#30](https://github.com/ask-first-fcaj/ask-first/issues/30) · **Phạm vi:** MVP · **Xong ở:** W5 · **Tham chiếu:** ARCH §5.8

**Luồng:** thành viên team chạy `scripts/flags.py llm off` bằng quyền AWS của mình. Trong ≤ 30 giây bot ngừng gọi LLM, chỉ chạy luật (`path=rules_only`), kèm một câu báo đang bảo trì và kênh chính thức. Không cần deploy, không có lệnh admin trên Telegram. Alarm vận hành gửi email qua SNS.

**Xong khi:** chạy `flags.py llm off` thì trong ≤ 30 giây `ai` không gọi Bedrock; alarm DLQ test gửi được email (BE-13).

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [BE-13 #79](https://github.com/ask-first-fcaj/ask-first/issues/79) | Kill switch: cờ LLM trong DynamoDB, flags.py, SNS alarm | BE1 (pair BE2) | W5 | P1 | [BE-03](https://github.com/ask-first-fcaj/ask-first/issues/1), [BE-07](https://github.com/ask-first-fcaj/ask-first/issues/42) |

### FR13. Người thân bấm "Tôi đã liên hệ"

**Issue:** [#31](https://github.com/ask-first-fcaj/ask-first/issues/31) · **Phạm vi:** MVP, làm trong BE-29 [#65](https://github.com/ask-first-fcaj/ask-first/issues/65) · **Xong ở:** M2 (W4) · **Tham chiếu:** ARCH §5.5

**Luồng:** tin cảnh báo gửi người thân có nút [Tôi đã liên hệ]. Bấm vào thì cảnh báo được đánh dấu đã xử lý, người cao tuổi nhận "Nam đã liên hệ bác", và không còn nhắc lại sau 15 phút (FR9).

**Xong khi:** nằm trong demo của BE-29. Issue FR này bị chặn bởi BE-29.

### FR14. `/caidat`: chọn xưng hô

**Issue:** [#32](https://github.com/ask-first-fcaj/ask-first/issues/32) · **Phạm vi:** MVP, làm trong BE-25 [#53](https://github.com/ask-first-fcaj/ask-first/issues/53) · **Xong ở:** W3 · **Tham chiếu:** ARCH §5.3, §5.9

**Luồng:** `/caidat` chỉ cho chọn xưng hô Bác/Cô/Chú; lưu trong hồ sơ người dùng; câu trả lời của bot dùng xưng hô đó.

**Xong khi:** nằm trong BE-25. Issue FR này bị chặn bởi BE-25.

### FR15. Xử lý đầu vào không hỗ trợ và tệp `.apk`

**Issue:** [#33](https://github.com/ask-first-fcaj/ask-first/issues/33) · **Phạm vi:** MVP · **Xong ở:** W4 · **Tham chiếu:** ARCH §5.7

Xử lý ngay ở webhook, không đưa vào hàng đợi và không tải file.

| Đầu vào | Bot làm gì |
|---|---|
| Video, sticker, vị trí, danh bạ | Không tải; trả lý do ngắn và cách thay thế ("chụp màn hình hoặc gõ lại nội dung") |
| Voice/audio | MVP: trả "chưa hỗ trợ voice" (FR2 hoãn) |
| Tệp dạng ảnh (`image/*`) | Xử lý như ảnh |
| Tệp khác (PDF, Word…) | Không tải; hướng dẫn chụp màn hình |
| Tệp `.apk` | Không tải, không chạy; trả ngay cảnh báo riêng "Đừng cài tệp này…" (template, không gọi LLM) |
| Ảnh > 20 MB | Từ chối kèm câu giải thích |
| Văn bản quá dài | Cắt ở 4.000 ký tự và báo đã cắt |

**Xong khi:** test từng loại; `.apk` không bao giờ bị tải về; không có job nào được đưa vào hàng đợi cho các loại trên (BE-34, BE-35).

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [BE-34 #72](https://github.com/ask-first-fcaj/ask-first/issues/72) | Chặn media trước khi tạo job (size, duration, album) | BE1 (pair BE2) | W4 | P1 | [BE-21](https://github.com/ask-first-fcaj/ask-first/issues/14) |
| [BE-35 #73](https://github.com/ask-first-fcaj/ask-first/issues/73) | Đầu vào không hỗ trợ và tệp .apk | BE2 (pair BE1) | W4 | P1 | [BE-21](https://github.com/ask-first-fcaj/ask-first/issues/14) |

### FR17. Mini App (⏸ hoãn)

**Issue:** [#34](https://github.com/ask-first-fcaj/ask-first/issues/34) · **Phạm vi:** ⏸ hoãn ngoài 6 tuần; chỉ làm nếu M4 đạt sớm · **Tham chiếu:** ARCH §13, PLAN §6.4

Giao diện web trong Telegram cho các việc ở FR11. Việc hoãn: FE-01…FE-07, BE-32, INT-04 (chưa tạo issue). MVP dùng lệnh trong chat (FR11) thay thế.

## 4. Nhóm việc không phải tính năng

Người dùng không thấy trực tiếp các việc này, nhưng mọi tính năng đều cần. Mỗi nhóm cũng là một issue cha trên board.

### 🧱 Nền tảng: hạ tầng, repo, contract BE↔AI

**Issue:** [#35](https://github.com/ask-first-fcaj/ask-first/issues/35) · Tài khoản AWS, khung repo và CI, SAM, contract (G0), codegen Java, runtime Lambda `ai`, SQS → worker, DynamoDB, image `ai`, CI/CD.

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [BE-03 #1](https://github.com/ask-first-fcaj/ask-first/issues/1) | SAM skeleton: webhook hello, DynamoDB, SQS, S3, SSM (stage dev) | BE2 + BE1 | W1–W2 | P0 | [BE-01](https://github.com/ask-first-fcaj/ask-first/issues/8) |
| [Gate G0 #2](https://github.com/ask-first-fcaj/ask-first/issues/2) | Gate G0: Contracts BE↔AI chốt và merge vào main | Cả team (1 BE + 1 AI duyệt) | W1 | P0 | [SH-01](https://github.com/ask-first-fcaj/ask-first/issues/3) |
| [SH-01 #3](https://github.com/ask-first-fcaj/ask-first/issues/3) | Kickoff: đọc ARCH + contract, dựng models/stub/fixtures, TelegramAdapter.parseUpdate | AI1 + BE2 (cả team) | W1 | P0 | — |
| [SH-06 #7](https://github.com/ask-first-fcaj/ask-first/issues/7) | Test truy cập Telegram trên Viettel, VNPT, FPT, MobiFone | BE2 | W1 | P1 | — |
| [BE-01 #8](https://github.com/ask-first-fcaj/ask-first/issues/8) | Thiết lập tài khoản AWS: MFA, Budgets, CloudTrail, region | BE1 + BE2 | W1 | P0 | — |
| [BE-02 #9](https://github.com/ask-first-fcaj/ask-first/issues/9) | Khung repo: backend/ (Java 21), ai/ (Python 3.12), contracts/, CI 3 OS | BE2 + BE1 | W1 | P0 | — |
| [BE-14 #10](https://github.com/ask-first-fcaj/ask-first/issues/10) | Spike Java + AWS học chung (worker→ai SnapStart, DynamoDB Testcontainers) | BE1 + BE2 | W1 | P1 | [BE-01](https://github.com/ask-first-fcaj/ask-first/issues/8) |
| [BE-05 #11](https://github.com/ask-first-fcaj/ask-first/issues/11) | Bật Bedrock ở ap-southeast-2 + xin tăng quota | BE1 + AI1 | W1 | P0 | [BE-01](https://github.com/ask-first-fcaj/ask-first/issues/8) |
| [BE-05b #12](https://github.com/ask-first-fcaj/ask-first/issues/12) | Codegen Java từ JSON Schema + AiClient + FakeAiClient (spike W1) | BE1 + BE2 | W1–W2 | P0 | [G0](https://github.com/ask-first-fcaj/ask-first/issues/2) |
| [BE-20 #13](https://github.com/ask-first-fcaj/ask-first/issues/13) | Tạo bot Telegram dev/prod, script set_webhook, chế độ polling local | BE2 + BE1 | W1 | P1 | — |
| [AI-03b #39](https://github.com/ask-first-fcaj/ask-first/issues/39) | Runtime Lambda ai: handler.py, cache, tải media, deploy bản stub | AI1 + AI2 (deploy cùng BE1) | W2 | P0 | [G0](https://github.com/ask-first-fcaj/ask-first/issues/2), [BE-03](https://github.com/ask-first-fcaj/ask-first/issues/1) |
| [BE-07 #42](https://github.com/ask-first-fcaj/ask-first/issues/42) | Đường ống SQS → worker, DLQ, SnapStart | BE1 (pair BE2) | W2–W3 | P0 | [BE-03](https://github.com/ask-first-fcaj/ask-first/issues/1) |
| [BE-24 #44](https://github.com/ask-first-fcaj/ask-first/issues/44) | DynamoDB nền + repos lane B (Testcontainers, USER/GUARDIAN/INVITE/RATE/AUDIT) | BE2 (pair BE1) | W2 | P0 | [G0](https://github.com/ask-first-fcaj/ask-first/issues/2) |
| [BE-24b #45](https://github.com/ask-first-fcaj/ask-first/issues/45) | Repos lane A: CHECK, SESSION, BUFFER, DEDUP, ALERT, FLAGS | BE1 (pair BE2) | W2 | P0 | [BE-24](https://github.com/ask-first-fcaj/ask-first/issues/44) |
| [BE-04 #58](https://github.com/ask-first-fcaj/ask-first/issues/58) | CI/CD GitHub Actions dùng OIDC, deploy dev khi merge | BE2 (pair BE1) | W3 | P1 | [BE-03](https://github.com/ask-first-fcaj/ask-first/issues/1) |
| [BE-26 #60](https://github.com/ask-first-fcaj/ask-first/issues/60) | Rate limit theo user (30/giờ, 100/ngày) | BE2 (pair BE1) | W3 | P1 | [BE-24](https://github.com/ask-first-fcaj/ask-first/issues/44) |
| [BE-06 #62](https://github.com/ask-first-fcaj/ask-first/issues/62) | Container image cho Lambda ai (OCR, arm64, IAM) | BE1 (pair BE2) | W3–W4 | P0 | [AI-20](https://github.com/ask-first-fcaj/ask-first/issues/17), [AI-26](https://github.com/ask-first-fcaj/ask-first/issues/61) |

### 🛡 Vận hành và bảo mật

**Issue:** [#36](https://github.com/ask-first-fcaj/ask-first/issues/36) · Observability, alarm và runbook, rà bảo mật chéo hai lane, stage `prod`, đo tải, trực test nội bộ.

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [BE-08a #71](https://github.com/ask-first-fcaj/ask-first/issues/71) | Observability: Powertools Java/Python, metrics, dashboard | BE1 (pair BE2) | W4–W5 | P1 | [INT-02](https://github.com/ask-first-fcaj/ask-first/issues/66) |
| [BE-09a #77](https://github.com/ask-first-fcaj/ask-first/issues/77) | Rà bảo mật lane B (người không viết code đó rà) | BE1 rà lane B 🔀 | W5 | P1 | — |
| [BE-09b #78](https://github.com/ask-first-fcaj/ask-first/issues/78) | Rà bảo mật lane A + ai, quét dependency | BE2 rà lane A + ai 🔀 | W5 | P1 | — |
| [BE-08b #85](https://github.com/ask-first-fcaj/ask-first/issues/85) | Alarm + runbook cho cả hệ thống | BE2 (pair BE1) 🔀 | W6 | P1 | [BE-08a](https://github.com/ask-first-fcaj/ask-first/issues/71), [BE-13](https://github.com/ask-first-fcaj/ask-first/issues/79) |
| [BE-11 #86](https://github.com/ask-first-fcaj/ask-first/issues/86) | Stage prod: bot riêng, stack riêng, runbook sự cố | BE2 (pair BE1) | W6 | P1 | [BE-04](https://github.com/ask-first-fcaj/ask-first/issues/58) |
| [BE-12 #87](https://github.com/ask-first-fcaj/ask-first/issues/87) | Trực test nội bộ (M5), xoay vòng | BE1 + BE2 (xoay vòng cả team) | W6 | P1 | [AI-13](https://github.com/ask-first-fcaj/ask-first/issues/75) |
| [BE-10 #88](https://github.com/ask-first-fcaj/ask-first/issues/88) | Đo tải nhẹ + cold start, script làm nóng trước demo | BE1 (pair BE2) | W6 | P2 | [INT-03](https://github.com/ask-first-fcaj/ask-first/issues/76) |

### 📊 Eval và nghiên cứu người dùng

**Issue:** [#37](https://github.com/ask-first-fcaj/ask-first/issues/37) · Hướng dẫn gán nhãn, bộ eval 100 → 300 mẫu, eval harness, benchmark chốt LLM (G1), eval trong CI, hiệu chỉnh ngưỡng, báo cáo freeze, red-team, phỏng vấn người dùng.

**Task:**

| Task | Việc | Owner | Tuần | Ưu tiên | Cần trước |
|---|---|---|---|---|---|
| [SH-02 #4](https://github.com/ask-first-fcaj/ask-first/issues/4) | Hướng dẫn gán nhãn eval: định nghĩa kịch bản N0–N6 | AI2 + AI1 | W1 | P1 | — |
| [SH-03 #5](https://github.com/ask-first-fcaj/ask-first/issues/5) | Bộ eval v0: 100 mẫu (mỗi người 25), tiến tới v1 300 mẫu | Cả team | W1–W4 | P0 | — |
| [SH-04 #6](https://github.com/ask-first-fcaj/ask-first/issues/6) | Phỏng vấn người dùng: 5 người cao tuổi, 5 con cháu | Cả team | W1–W2 | P1 | — |
| [AI-01 #15](https://github.com/ask-first-fcaj/ask-first/issues/15) | Eval harness: eval/run_eval.py chạy in-process, xuất báo cáo | AI1 | W1–W2 | P0 | [G0](https://github.com/ask-first-fcaj/ask-first/issues/2) |
| [AI-03 #38](https://github.com/ask-first-fcaj/ask-first/issues/38) | Benchmark xác nhận Qwen3 32B trên 100 mẫu (gate G1) | AI1 | W2 | P0 | [AI-01](https://github.com/ask-first-fcaj/ask-first/issues/15), [AI-02](https://github.com/ask-first-fcaj/ask-first/issues/16), [SH-03](https://github.com/ask-first-fcaj/ask-first/issues/5) |
| [AI-09 #69](https://github.com/ask-first-fcaj/ask-first/issues/69) | Eval trong CI: chặn merge khi recall < 90% hoặc báo nhầm > 10% | AI2 + BE2 | W4–W5 | P1 | [AI-01](https://github.com/ask-first-fcaj/ask-first/issues/15), [BE-04](https://github.com/ask-first-fcaj/ask-first/issues/58) |
| [AI-10 #74](https://github.com/ask-first-fcaj/ask-first/issues/74) | Hiệu chỉnh ngưỡng 75/50/25 trên bộ 300 mẫu | AI1 | W5 | P0 | [SH-03](https://github.com/ask-first-fcaj/ask-first/issues/5), [INT-02](https://github.com/ask-first-fcaj/ask-first/issues/66) |
| [AI-13 #75](https://github.com/ask-first-fcaj/ask-first/issues/75) | Báo cáo eval cho MVP freeze (gate M4) | AI1 | W5 | P0 | [AI-10](https://github.com/ask-first-fcaj/ask-first/issues/74) |
| [AI-11 #83](https://github.com/ask-first-fcaj/ask-first/issues/83) | LLM-as-judge chấm phần giải thích + chấm tay 20 mẫu | AI2 (AI1 duyệt rubric) | W5 | P2 | [AI-06](https://github.com/ask-first-fcaj/ask-first/issues/55), [AI-02](https://github.com/ask-first-fcaj/ask-first/issues/16) |
| [AI-14 #84](https://github.com/ask-first-fcaj/ask-first/issues/84) | Red-team: bổ sung ≥ 30 mẫu hard | AI2 (AI1 kèm) | W5–W6 | P2 | [INT-03](https://github.com/ask-first-fcaj/ask-first/issues/76) |

## 5. Hoãn và ngoài phạm vi

- **Hoãn ngoài 6 tuần** (PLAN §5): voice/ASR (FR2: AI-21, AI-28), VLM đọc ảnh (AI-27), multi-turn (FR8: BE-33, AI-12), Mini App (FR17: FE-01…FE-07, BE-32, INT-04), Phase 2. Chỉ AI-21 có issue (để giữ kết quả bàn bạc); các việc còn lại tạo issue khi được mở lại.
- **Cố ý không làm** (ARCH §1.4): chặn cuộc gọi, chặn chuyển khoản, danh sách đen công khai số điện thoại hay số tài khoản, phát hiện deepfake video thời gian thực, gửi SMS, tự ghi âm cuộc gọi.

## 6. Cách dùng board

| View | Dùng để | Thiết lập |
|---|---|---|
| 🧩 Features | Xem sản phẩm gồm gì và mỗi phần xong bao nhiêu | Chỉ issue `type: feature`; cột Sub-issues progress |
| 👤 Việc của tôi | Biết hôm nay làm gì, theo thứ tự | `assignee:@me`, ẩn Done, sắp theo Start Date |
| 🗓 Roadmap | Xem ai làm gì theo tuần, việc nào nối tiếp việc nào | Timeline theo Start/Target Date, gom theo người |
| 🏃 Sprint Kanban | Kéo thẻ theo trạng thái trong tuần | Ẩn issue feature |
| 📋 Danh Sách Nhiệm Vụ | Tra mọi task kèm FR cha, ưu tiên, hạn | Thêm cột Parent issue, Ưu tiên, Start/Target Date |

**Khi thêm việc mới:** tạo issue task → gắn làm sub-issue của FR (hoặc nhóm) phù hợp → điền Start/Target Date, Ưu tiên → thêm "Blocked by" nếu cần việc khác xong trước. Đổi phạm vi một FR thì sửa ARCH/PLAN trước, rồi sửa file này trong cùng PR.
