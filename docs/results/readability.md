# Kết quả AI-32 (#92): độ dễ đọc của lời giải thích và tin kết luận

> Người làm: AI2. Ngày đo: 2026-11-18. Commit: `0b67fdd` (`main`). Mốc 2 của #92 theo `docs/ai-viec-nghien-cuu-phu.md` ("Việc C").

## Kết luận

- **Không tin nào vượt giới hạn 600 ký tự** của tin kết luận (ARCH §6.8): 0/100 tin có lời giải thích LLM, 0/134 tin chỉ luật.
- **Lời giải thích của LLM hay viết một câu dài:** 25/99 có trung bình > 20 tiếng/câu (Wilson 18–35%), thường theo kiểu "Bác nên cẩn trọng…, đặc biệt nếu…". Template của bot thì không có câu nào vượt (0/134).
- **LLM tự thêm tiếng Anh ngoài "OTP"/"link":** 20/99 lời giải thích có "app", "click", "website", "hotline", "QR". Tin chỉ luật có 7/134, nhưng cả 7 đều nằm trong đoạn trích tin gốc ("tải app", "việc online", "mã QR"), không phải chữ bot viết.
- **Ngưỡng tiếng Anh mặc định (`max_english_words=0`) không dùng được cho cả tin:** 100/100 và 134/134 tin kết luận bị đánh dấu, vì chính disclaimer ("Bot không bao giờ xin OTP…") và câu hành động ("ĐỪNG bấm link") có từ tiếng Anh.
- Từ khó ít gặp: > 2 từ khó chỉ ở 2/99 lời giải thích và 0/134 tin chỉ luật. Hay gặp nhất là "giả mạo", "xác minh", "truy cập".

**Đề xuất:**
1. Chưa dùng script làm gate chặn PR. Ngưỡng chưa hiệu chỉnh: cần so với kết quả đọc thử của người cao tuổi (phiếu SH-08).
2. Khi đo cả tin kết luận, không tính "OTP" và "link". Đây là từ template cố ý dùng, người dùng gặp hằng ngày. Script đo đã tách riêng chỉ số này.
3. Cần AI1 quyết: có thêm vào prompt judge yêu cầu "câu ngắn, không dùng app/click/website" không. Phải đo lại bằng Bedrock, vì số trên là của `judge_v1`.

## Cách chạy lại

```
cd ai
uv run python ../eval/run_readability.py                 # bảng dưới đây
uv run python ../eval/readability.py "Bác đừng bấm link này nhé."   # đo một câu
uv run --with numpy pytest ../eval/test_readability.py   # 11 test
```

Windows 10.0.26200, Python 3.12.14. Script đo chỉ dùng thư viện chuẩn (`eval/readability.py`). `run_readability.py` gọi thêm pipeline của repo để dựng tin chỉ luật, không gọi mạng.

## Dữ liệu

| Tập | n | Nguồn |
|---|---|---|
| Lời giải thích LLM | 99 | trường `explanation` trong `docs/results/ai11-judge-scores.jsonl` (lượt AI-11, commit `da70af5`, 11/11). Qwen3 32B trên Bedrock với prompt **`judge_v1`**, trước khi sửa xưng hô ở `judge_v2`. Bỏ 1 tin fast path không có lời giải thích |
| Tin kết luận có lời giải thích LLM | 100 | trường `reply[0]` cùng file: tin đầu tiên bot gửi, gồm tiêu đề, lời giải thích, câu hành động, disclaimer. Template lúc đó vẫn xưng "Bot" |
| Tin kết luận chỉ luật | 134 | dựng lại trên `main` từ 184 mẫu `eval/datasets/` (`llm_enabled=False`); 134 mẫu ra kết luận. Có dòng "Bot đang bảo trì" vì đường chỉ luật luôn báo bảo trì (AI-07). 63 nội dung khác nhau |

"Tiếng" là âm tiết tách bằng khoảng trắng. Câu tách theo `.`, `!`, `?`, `…` và xuống dòng. Danh sách từ khó, từ tiếng Anh ở `eval/readability_wordlist.txt` do team tự lập, chưa kiểm với người đọc.

## Kết quả

Ngưỡng tạm, **chưa hiệu chỉnh**: `max_chars=600`, `max_words_per_sentence=20`, `max_hard_words=2`, `max_english_words=0`.

| Chỉ số | Lời giải thích LLM | Tin kết luận có LLM | Tin kết luận chỉ luật |
|---|---|---|---|
| Dài > 600 ký tự | 0/99 (0–4%) | 0/100 (0–4%) | 0/134 (0–3%) |
| Câu dài TB > 20 tiếng | **25/99** (18–35%) | 0/100 (0–4%) | 0/134 (0–3%) |
| > 2 từ khó | 2/99 (1–7%) | 3/100 (1–8%) | 0/134 (0–3%) |
| Có tiếng Anh | 41/99 (32–51%) | 100/100 (96–100%) | 134/134 (97–100%) |
| Có tiếng Anh ngoài OTP/link | **20/99** (13–29%) | 35/100 (26–45%) | 7/134 (3–10%) |
| Vượt ít nhất một ngưỡng | 56/99 (47–66%) | 100/100 (96–100%) | 134/134 (97–100%) |
| Ký tự trung vị (max) | 111 (189) | 342 (596) | 244 (571) |
| Tiếng/câu trung vị (max) | 14,5 (31,0) | 11,6 (17,2) | 10,8 (13,4) |

Khoảng trong ngoặc là Wilson 95%. Tin kết luận có câu ngắn hơn lời giải thích vì tiêu đề, câu hành động và disclaimer là các dòng ngắn, kéo trung bình xuống. Một lời giải thích dài vẫn có thể nằm trong tin đạt ngưỡng.

Ở tin kết luận có LLM, 35/100 có tiếng Anh ngoài OTP/link: 24 tin có ở dòng tiêu đề kịch bản (`kb/scenarios.yaml`: "SMS giả ngân hàng…", "dụ vào web giả hoặc cài app giả"), 19 tin có trong lời giải thích LLM (một tin có thể ở cả hai).

## Ví dụ lỗi

Câu dài (lời giải thích LLM, > 20 tiếng/câu):
- `bn-ai-12` (26 tiếng): "Bác nên cẩn trọng khi nhận yêu cầu kiểm tra email hợp đồng từ người quen, đặc biệt nếu không rõ nguồn gốc hoặc nội dung."
- `bn-ai-18` (28 tiếng): "Bác nên kiểm tra lại nội dung và người gửi, vì tin nhắn viết tắt nhiều và có vẻ gấp gáp, nhưng chưa rõ mục đích cụ thể."
- `bs-ai-03` (26 tiếng): "Bác nên kiểm tra lại đơn hàng qua app hoặc gọi tổng đài sàn TMĐT để xác nhận, vì có thể bị giả shipper đòi tiền."

Tiếng Anh ngoài OTP/link (lời giải thích LLM):
- `hd-ai-07`: "…không phải là trang chính thức của dịch vụ công. Xin bác không nên click vào."
- `hd-ai-08`: "Bác ơi, đây là tin giả mạo CSGT và có website lạ, không nên truy cập."
- `bs-ai-16`: "…liên hệ ngân hàng qua số hotline in trên thẻ…"

Nhiều từ khó (> 2):
- `bs-ai-11`: "tên miền", "truy cập", "giả mạo": "Bác nên cẩn thận kiểm tra đường link trước khi truy cập, vì có thể là trang giả mạo dù tên miền trông giống chính thức."
- `hd-ai-14`: "khẩn cấp", "xác minh", "giả mạo".

Các tin trên là lời giải thích bot viết cho tin mẫu tổng hợp, không có PII.

## Chưa kiểm

- **Lời giải thích của `judge_v2`** (prompt đang dùng): chưa có bản lưu, cần Bedrock để sinh lại. Số LLM ở trên là của `judge_v1`.
- **Ngưỡng có khớp cảm nhận người đọc không:** chưa có kết quả đọc thử (phiếu SH-08). Đây là lý do chưa hiệu chỉnh ngưỡng.
- **Danh sách từ khó/tiếng Anh:** team tự lập, chưa kiểm với người đọc. Từ không có trong danh sách thì không bị đếm.
- **Template xưng "cháu" của SH-08 (#157):** chưa merge nên chưa đo. Template mới vẫn có "OTP" và "link".
- **Tin thứ hai trở đi** (chi tiết dấu hiệu, nguồn): chỉ đo tin đầu, vì người dùng đọc tin này trước.
- **Đoạn trích tin gốc** ("• "…" → …") vẫn bị đếm chung với chữ bot viết. Trên tin chỉ luật, 7/134 tin có tiếng Anh ngoài OTP/link đều do đoạn trích. Chưa tách vì cần biết cấu trúc tin, script hiện chỉ nhận chữ thuần.
- Cách tách câu và tiếng là xấp xỉ: chữ viết tắt có dấu chấm ("TP.HCM") có thể làm tách sai câu. not verified trên bộ này.
