# Việc nghiên cứu phụ cho AI2: bộ OTP, test mask, đo độ dễ đọc

> Người viết: AI1. Đọc cho: AI2 (minhvhq). Ngày: 20/10/2026.
> Nguồn: ghi chú nghiên cứu AI của AI1 (file local, chưa qua review, ngày 15/10/2026), đối chiếu với `docs/system-architecture.md` §6.4, §9.2 và `docs/implementation-plan.md` §3.8. Con số mục tiêu bên dưới lấy từ ghi chú đó, **chưa kiểm lại độc lập**. Chỗ nào ghi "đề xuất" nghĩa là chưa có nguồn, chỉnh được khi làm.

## Vì sao có 3 việc này

Ghi chú nghiên cứu của AI1 liệt kê khoảng 46 thí nghiệm. Phần lớn cần model, ảnh hay voice thật. Ba việc dưới đây thì **không cần model, không cần ảnh, không cần voice**: chỉ viết dữ liệu tổng hợp hoặc một script nhỏ. Chúng giúp 3 chỗ trong MVP:

| Việc | Giúp chỗ nào | Liên quan |
|---|---|---|
| A. Bộ dữ liệu OTP | Kiểm 2 luật `R_OTP_REQUEST` và `R_OTP_GENUINE` không giẫm lên nhau; thêm mẫu cho `benign_suspicious` | AI-23 (#52), SH-03 (#5) |
| B. Bộ test cho mask PII | Có số đo recall của mask thay vì tin vào regex | AI-22 (#41) |
| C. Script đo độ dễ đọc | Gate rẻ cho lời giải thích dành cho người cao tuổi, trước khi nhờ người thật chấm | AI-06 (#55) |

## Quy tắc dữ liệu (áp cho cả 3 việc)

- **Chỉ dữ liệu tổng hợp hoặc viết lại từ nguồn công khai.** Không dùng tin nhắn thật của người dùng, của người thân hay của thành viên team.
- **Không dùng số điện thoại, STK, CCCD, email của người thật.** Tự bịa. Không lấy số của thành viên team hay người quen. Mã OTP dùng số bịa (ví dụ `482913`).
- **Không sinh bằng LLM** cho mẫu lừa đảo. Viết lại từ cảnh báo chính thức, ghi `source_url` thật. Chưa tìm được URL thì để `source_url` rỗng và ghi `notes` là "mẫu tự viết", không bịa URL.
- Không gửi dữ liệu này cho dịch vụ AI nào trong lúc làm.

## Định dạng chung

Việc A dùng định dạng eval ở `docs/implementation-plan.md` §3.8 (`eval/datasets/*.jsonl`, mỗi dòng một mẫu). Việc B có định dạng riêng ở dưới. `eval.validate` chưa có (thuộc SH-03 #5), nên giờ chỉ tự kiểm bằng mắt và bằng `python -c "import json; [json.loads(l) for l in open(...)]"`.

---

## Việc A: bộ OTP (≥ 100 tin thật + ≥ 100 tin lừa)

**Vấn đề cần kiểm.** `R_OTP_REQUEST` (cứng, +45) khớp cụm "cung cấp / đọc / gửi / nhập + mã OTP". `R_OTP_GENUINE` (−20) khớp tin có mã số kèm câu "không cung cấp mã cho bất kỳ ai". Hai luật này có thể:
1. `R_OTP_REQUEST` khớp nhầm vào câu phủ định của SMS OTP thật ("**không** chia sẻ **mã** này") và báo nhầm tin thật.
2. Kẻ gian chèn đúng câu "không cung cấp mã cho bất kỳ ai" vào tin lừa để kích `R_OTP_GENUINE` và hạ điểm.

**Việc cần làm**
1. Viết **≥ 100 tin lành tính**: SMS OTP và thông báo biến động số dư, theo mẫu công khai của các ngân hàng lớn (xem trang hỗ trợ/cảnh báo của chính ngân hàng). Đa dạng ngân hàng, đa dạng cách diễn đạt. Nhãn: `split="benign_suspicious"`, `is_scam=false`, `max_level="CHUA_CHAC"`.
2. Viết **≥ 100 tin lừa xin OTP**, viết lại từ cảnh báo chính thức, có `source_url`. Nhãn: `split="real_scam"`, `is_scam=true`, `nhom="N1"`, `kich_ban="N1.1"`, `min_level="NGHI_NGO"`, `risky_actions=["cung_cap_otp"]`.
3. Trong 100 tin lừa, **≥ 20 tin có câu phủ định giả**, gồm 2 kiểu: chèn câu "không cung cấp mã cho bất kỳ ai" (đánh lừa `R_OTP_GENUINE`) và kiểu trấn an "đừng lo, không sao đâu, bác cứ đọc mã OTP". Gắn `"notes":"neg-trap"` để lọc sau.
4. Chữ trong tin dùng mã OTP bịa dạng số thường (`482913`). Không tự viết `[OTP_1]`: bước mask của pipeline sẽ đổi giúp (ARCH §9.2 nói `mask` chạy trước `signals`, nên luật so khớp trên token).
5. File: `eval/datasets/otp_pairs.jsonl`. `id` theo dạng `otp-b-001` (benign), `otp-s-001` (scam).

**Xong khi**
- [ ] Đủ số lượng (≥ 100 + ≥ 100, ≥ 20 neg-trap), không trùng nội dung giữa các dòng.
- [ ] Mọi tin lừa có `source_url` hoặc `notes` ghi rõ là tự viết.
- [ ] Không có số điện thoại, STK, email, URL của người thật trong text.
- [ ] AI1 review 20 mẫu ngẫu nhiên, đồng ý nhãn.

**Đo (làm sau, khi có rules engine AI-23 #52).** Chạy luật trên bộ này, báo: tỷ lệ `R_OTP_REQUEST` khớp nhầm trên tin lành tính (mục tiêu đề xuất: 0), tỷ lệ `R_OTP_GENUINE` khớp vào tin lừa (mục tiêu đề xuất: 0), recall của `R_OTP_REQUEST` trên tin lừa. Phần đo không thuộc issue này.

---

## Việc B: bộ test cho mask PII (≥ 500 mẫu)

**Vấn đề cần kiểm.** Mask chạy trước mọi lời gọi ra ngoài (ARCH §9.2). Mask sót là lộ dữ liệu, nên cần số đo recall theo từng loại.

**Việc cần làm**
1. Viết **≥ 500 mẫu** text tiếng Việt có chứa PII bịa, gồm các loại: SĐT (`0x…`, `+84…`, có khoảng trắng hoặc dấu chấm), STK (gần chữ "STK", "số tài khoản" hoặc tên ngân hàng), CCCD (12 số), OTP/mã xác thực/PIN, email, số viết bằng chữ ("không chín tám…", "năm mươi triệu"), số viết cách hoặc sai định dạng.
2. Cân đối giữa các loại, mỗi loại ≥ 50 mẫu để có số đo tách riêng được.
3. Thêm **≥ 50 mẫu "bẫy" không có PII** nhưng có dãy số trông giống: mã đơn hàng, giờ giấc, giá tiền, số nhà, mã vận đơn, dãy 12 số không phải CCCD. Dùng để đo mask có che nhầm không.
4. File: `eval/datasets/mask_pii.jsonl`. Mỗi dòng:
   ```json
   {"id":"mask-001","text":"Chuyển khoản vào STK 0123456789 Vietcombank nhé","pii":[{"type":"stk","value":"0123456789"}],"notes":""}
   ```
   `type` ∈ `phone`, `stk`, `cccd`, `otp`, `email`, `spoken_number`. Mẫu bẫy để `"pii":[]`. `value` là đúng chuỗi xuất hiện trong `text`.
5. Cách kiểm sau này: chạy mask lên `text`, mỗi `value` còn xuất hiện nguyên trong kết quả là một FN; token che nhầm ở mẫu bẫy là một FP.

**Xong khi**
- [ ] ≥ 500 mẫu, mỗi loại ≥ 50, ≥ 50 mẫu bẫy.
- [ ] Mọi `value` đúng là chuỗi con của `text`.
- [ ] Không dùng số/email của người thật. CCCD chỉ cần đủ 12 số, **không** cố làm đúng mã tỉnh (bảng mã chưa xác nhận từ văn bản gốc, nên không dựa vào).
- [ ] AI1 review 30 mẫu ngẫu nhiên.

**Đo (làm sau, khi có `mask.py` của AI-22 #41).** Mục tiêu đề xuất trong ghi chú nghiên cứu: recall ≥ 0,98 cho SĐT, CCCD, OTP; báo FN theo từng loại. Đây là con số đề xuất, chưa có nguồn bên ngoài.

---

## Việc C: script đo độ dễ đọc của lời giải thích

**Vấn đề cần kiểm.** Lời giải thích gửi cho người cao tuổi phải ngắn và dễ hiểu. Nhờ người thật chấm thì chậm và tốn, nên cần một gate rẻ chạy tự động trước.

**Việc cần làm**
1. Viết `eval/readability.py` nhận một đoạn text (đã bỏ thẻ HTML), trả về:
   - số ký tự, số câu, số từ trung bình mỗi câu;
   - số từ Hán-Việt khó (dựa trên một danh sách từ nhỏ do mình tự lập);
   - có lẫn từ tiếng Anh hay không (danh sách từ hay gặp: "link", "app", "account", "OTP"…).
2. Danh sách từ để ở `eval/readability_wordlist.txt`, mỗi dòng một từ, có chú thích loại.
3. Kèm test `eval/tests/test_readability.py`, ít nhất 8 case (câu đơn giản, câu có từ khó, câu có tiếng Anh, chuỗi rỗng, có dấu `\n`).
4. Chỉ dùng thư viện chuẩn của Python. Chạy được trên Windows, macOS, Linux (không giả định đường dẫn kiểu bash).
5. Ngưỡng đề xuất ban đầu: **chưa có nguồn khoa học cho ngưỡng nào**. Để ngưỡng là tham số có giá trị mặc định tạm, ghi rõ "chưa hiệu chỉnh", chỉnh sau khi có ví dụ từ template AI-06 (#55).

**Xong khi**
- [ ] `python -m pytest eval/tests/test_readability.py` pass trên máy local.
- [ ] Chạy thử lên ≥ 5 câu giải thích mẫu và đưa kết quả vào mô tả PR.
- [ ] Không phụ thuộc thư viện ngoài.

---

## Mốc và ghi kết quả

Mỗi việc có **hai mốc**: (1) làm xong dữ liệu/script, (2) đo và ghi kết quả. Mốc (2) phụ thuộc việc khác nên không thể sớm hơn mốc phụ thuộc. Hạn dưới là theo lịch trong plan, chưa phải cam kết của người làm.

| Việc | Mốc 1: xong dữ liệu / script | Mốc 2: đo và ghi kết quả | Mốc 2 chờ | File kết quả |
|---|---|---|---|---|
| A (#90) | 30/10/2026 (cuối W2) | sau khi rules engine AI-23 (#52) merge (W3, 02–08/11) | #52 | `docs/results/otp-rules.md` |
| B (#91) | 06/11/2026 (cuối W3) | sau khi `mask.py` của AI-22 (#41) merge (W2–W3) | #41 | `docs/results/mask-pii.md` |
| C (#92) | 06/11/2026 (cuối W3) | sau khi template lời giải thích AI-06 (#55) merge (W3) | #55 | `docs/results/readability.md` |

**Hệ quả:** trước cuối W1 (23/10) chưa có số đo nào của 3 việc này, vì chưa có rules engine, mask hay template để đo. Quyết định nào cần số đo của chúng phải đợi mốc 2. Mốc 1 chỉ cho biết đã đủ dữ liệu (số lượng, phân bố loại), không cho biết hệ thống đúng hay sai.

**Mỗi file kết quả (`docs/results/*.md`) phải có:**
1. **Kết luận 1–3 dòng** và **đề xuất lựa chọn** (giữ luật như cũ / sửa luật thế nào / đổi ngưỡng…), kèm việc nào chờ quyết định.
2. **Cách chạy lại:** lệnh, commit hash của code và của bộ dữ liệu, ngày chạy, Python version, hệ điều hành.
3. **Bảng số đo:** mỗi chỉ số ghi dạng `x/n` (ví dụ `3/100`) kèm khoảng tin cậy Wilson 95%. Không chỉ ghi phần trăm, vì tập nhỏ nên phần trăm đơn lẻ dễ gây hiểu nhầm.
4. **Ví dụ lỗi:** các mẫu bị sai (đã anonymize, không có PII thật), tối đa 10 mẫu mỗi loại lỗi.
5. **Chưa kiểm / hạn chế:** ghi "not verified" cho mọi thứ chưa đo. Không viết "đã kiểm" nếu không có lệnh chạy chứng minh.

File kết quả đi cùng PR đo (hoặc PR riêng ngay sau). Chưa có kết quả thì không tạo file rỗng.

---

## Cách làm việc

- AI1 kèm khoảng 30 phút mỗi ngày (theo plan §5): cứ hỏi trong issue hoặc nhắn trực tiếp.
- Mỗi việc một PR nhỏ cho mốc 1 và một PR cho mốc 2 (kết quả), commit theo conventional commits (`feat(eval): …`), cập nhật `CHANGELOG.md`.
- Việc nào có chỗ plan chưa rõ thì ghi vào issue, không tự đoán.
