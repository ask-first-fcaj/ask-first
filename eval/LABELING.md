# Hướng dẫn gán nhãn (SH-02)

Mục đích: 4 người gán cùng 1 tin nhắn thì ra **cùng 1 nhãn**. Dùng cho bộ eval (SH-03) và KB (AI-25).
Nhãn lấy theo taxonomy `Group = N0..N6 | OTHER` (`docs/implementation-plan.md` §3).

> **Trạng thái:** bản hoàn thiện tiêu chí (đã chốt N6 và nhóm OTHER). Sẵn sàng cho 4 người gán thử 10 mẫu (SH-02).
> **Về nguồn:** toàn bộ mẫu lừa đảo ở dưới được **viết lại bằng lời team** từ các bài báo cảnh báo chính thức của Bộ Công an và Học viện An ninh, kèm URL truy cập trực tiếp. Không dùng LLM sinh mẫu. Số điện thoại, số tài khoản, tên cá nhân đều đã bịa/ẩn danh; tên miền giả viết dạng `abc[.]xyz` để defang không bấm được.
> Mẫu N0 là mẫu tự dựng theo định dạng tin nhắn SMS thật, không cần URL nguồn.

---

## 0. Nguyên tắc chung

1. **1 mẫu = đúng 1 nhãn** chính: N0–N6, hoặc `OTHER` (mục 8).
2. Gán theo **ý đồ của tin nhắn**, không theo việc có link hay không.
3. Câu hỏi thứ tự khi đọc 1 tin:
   1. Có đòi tiền, mã OTP, mật khẩu, cài app, bấm link, hay giữ bí mật không? **Không** và tin đúng là thông báo thật → nghĩ tới **N0**.
   2. Có → xác định **bên bị mạo danh / chiêu dụ chính** (cơ quan nhà nước, ngân hàng, việc làm, quà tặng, người quen mới, kẻ tống tiền) → chọn nhóm tương ứng ở mục 1–7.
   3. Dính 2 nhóm → áp dụng **mục 9 (quy tắc phá thế hòa)**.
4. Tin lừa đảo mà gán N0 là lỗi nặng nhất (bỏ sót). Phân vân N0 và nhóm lừa đảo, hãy kiểm tra lại dấu hiệu ở mục 1.
5. Mỗi mẫu dễ nhầm đều ghi **nhãn đúng + lý do**. Nếu thấy lý do chưa thuyết phục, sửa guide, đừng sửa đáp án cho khớp.

---

## 1. N0: Lành tính (tin thật, kể cả khi trông đáng ngờ)

**Định nghĩa:** tin thật từ ngân hàng, cơ quan, shipper. Không đòi gì ngoài việc người dùng tự thao tác trên app/trang chính thức. N0 dùng để dạy bot **không báo nhầm**.

**Dấu hiệu:** OTP kèm câu "không chia sẻ"; biến động số dư; shipper báo giao hàng; không có link lạ; không bảo giữ bí mật; không đòi chuyển tiền.

**Dương 1: OTP thật**
> `[Ngân hàng A] Ma OTP giao dich cua ban la 000000. Het han sau 5 phut. KHONG cung cap ma nay cho bat ky ai.`

**Dương 2: biến động số dư**
> `[Ngân hàng A] TK 0000xxx000 -250,000 VND luc 10:15 19/10/2026. SD: 1,200,000 VND. ND: THANH TOAN HOA DON.`

**Dễ nhầm → nhãn đúng N2 (không phải N0)**
> `[Ngân hàng A] Ma OTP la 000000. Nhan vien se goi cho ban, vui long doc ma nay de huy giao dich la.`

Lý do: trông như tin OTP nhưng **bảo đọc mã cho người khác**. Tin OTP thật luôn dặn *không* chia sẻ. Hình thức này là bước lấy OTP mà cảnh báo của Công an Gia Lai mô tả (kẻ gian gọi giả nhân viên hỗ trợ để lấy OTP).
Nguồn dấu hiệu: https://hangthat.thuonghieucongluan.com.vn/cong-an-canh-bao-chieu-gia-mao-techcombank-gui-sms-bam-nham-link-co-the-mat-sach-tien-a291635.html

---

## 2. N1: Mạo danh cơ quan nhà nước

**Định nghĩa:** giả công an, CSGT, thuế, hải quan, dịch vụ công, VNeID... dọa phạt, dọa khóa giấy tờ, dọa bắt.

**Dương 1: phạt nguội kèm link giả**
> `[CSGT] Phuong tien cua ban vi pham giao thong, chua nop phat. Tra cuu va nop phat tai: dichvucong-phat[.]xyz/tracuu`

**Dương 2: hối thúc có hạn + đòi nhập thông tin ngân hàng**
> `Thong bao phat nguoi: giam 50% neu nop truoc 48 gio, qua han se tang muc phat. Dang nhap tai khoan ngan hang tai link de nop.`

Nguồn (cả 2 mẫu): https://bocongan.gov.vn/bai-viet/canh-bao-thu-doan-gia-danh-co-quan-cong-an-gui-tin-nhan-phat-nguoi-kem-duong-link-gia-mao-de-lua-dao-chiem-doat-tai-san-1788945012 (09/09/2026). Bài mô tả: tin SMS "phạt nguội", link giống Cổng Dịch vụ công, câu "giảm 50% nếu nộp sớm", "tăng mức phạt nếu không thực hiện trong 48 giờ", và trang giả đòi đăng nhập ngân hàng/OTP.

**Dễ nhầm → nhãn đúng N1 (không phải N2)**
> `Cong dich vu cong thong bao: ban co khoan phat chua nop. Vui long nhap so the va ma OTP tren trang de xac nhan nop phat.`

Lý do: có "thẻ", "OTP" nên dễ gán N2 (ngân hàng). Nhưng **bên bị mạo danh là cơ quan nhà nước** → N1. Quy tắc: nhãn theo bên bị mạo danh, không theo thứ bị lấy cắp.

---

## 3. N2: Mạo danh ngân hàng / tài chính

**Định nghĩa:** giả ngân hàng, ví điện tử, công ty tài chính: khóa tài khoản, điểm thưởng sắp hết hạn, xác minh giao dịch, nâng hạn mức, trừ phí lạ.

**Dương 1: điểm thưởng sắp hết hạn + link tên miền gần giống**
> `[Ngan hang A] Diem thuong the tin dung cua ban sap het han. Doi qua ngay tai: nganhanga[.]cfd`

**Dương 2: xác minh giao dịch + link**
> `[Ngan hang A] Phat hien giao dich bat thuong. Xac minh ngay tai: nh-a[.]lat de tranh bi khoa the.`

Nguồn (cả 2 mẫu): https://hangthat.thuonghieucongluan.com.vn/cong-an-canh-bao-chieu-gia-mao-techcombank-gui-sms-bam-nham-link-co-the-mat-sach-tien-a291635.html (15/07/2026, theo Công an tỉnh Gia Lai). Bài nêu: SMS về điểm thưởng thẻ tín dụng sắp hết hạn, ưu đãi có thời hạn, hoàn tiền, yêu cầu xác minh giao dịch; link kiểu `techcombank.cfd`, `tcb.lat`; trang giả đòi số thẻ, CVV, mật khẩu, OTP.

**Dễ nhầm → nhãn đúng N0 (không phải N2)**
> `[Ngan hang A] Quy khach luu y: khong chia se OTP, mat khau cho bat ky ai. Moi thac mac lien he tong dai tren the.`

Lý do: tin nhắc cảnh giác thật của ngân hàng, có chữ "ngân hàng" và "OTP" nên dễ báo nhầm. Không có link, không đòi gì, chỉ **dặn đừng chia sẻ** → N0.

---

## 4. N3: Việc nhẹ lương cao / đầu tư

**Định nghĩa:** đăng tin tuyển cộng tác viên làm việc online tại nhà (xem video, tương tác bài viết, làm nhiệm vụ Shopee/Telegram), tuyển việc làm không cần bằng cấp với thu nhập phi thực tế ("500k-1tr/ngày, bao ăn ở"), mời gọi nạp tiền đầu tư sàn tiền ảo/chứng khoán đa cấp hứa hẹn lãi khủng rồi chiếm đoạt hoặc lừa bán sang các cơ sở lừa đảo trực tuyến.

**Dương 1: tuyển việc nhẹ lương cao bao ăn ở**
> `Tuyen dung viec lam online: thu nhap 500k/ngay, bao an o, khong can kinh nghiem, khong thu bat ky khoan phi nao. Co xe dua don tan noi tai Ben xe Mien Dong.`

**Dương 2: mời đầu tư sàn tiền ảo nạp tiền làm nhiệm vụ**
> `Dau tu tien ao thong minh lai 5%/ngay, rut von linh hoat trong 24h. Nap tu 2 trieu vao quy de nhan tin hieu phong VIP Telegram.`

Nguồn:
- Dương 1: https://bocongan.gov.vn/bai-viet/cong-an-tinh-vinh-long-canh-giac-voi-bay-viec-nhe-luong-cao-1780907526 (08/06/2026, Công an tỉnh Vĩnh Long): quảng cáo Facebook "mức lương 500 ngàn đồng/ngày, bao chỗ ở, không đóng bất kỳ khoản phí nào", hẹn xe đón rồi lừa đưa sang sòng bạc/tổ chức lừa đảo trực tuyến tại Campuchia cưỡng ép lao động.
- Dương 2: https://thanhnien.vn/cong-an-da-nang-khoi-to-vu-lua-dao-tien-ao-da-cap-viec-nhe-luong-cao-185260709121531318.htm (Công an Đà Nẵng triệt phá đường dây tiền ảo đa cấp đội lốt việc nhẹ lương cao).

**Dễ nhầm → nhãn đúng N0 (không phải N3)**
> `Cong ty ABC thong bao lich phong van vi tri nhan vien kinh doanh, 9h sang 21/10 tai van phong dia chi ... Khong thu bat ky khoan phi nao.`

Lý do: có chữ "tuyển dụng/lương" nhưng **không đòi tiền, không bắt nhắn qua kênh lạ, không hứa lương phi thực tế** → tin thật, N0.
## 5. N4: Trúng thưởng / quà tặng tri ân

**Định nghĩa:** mạo danh doanh nghiệp, sàn thương mại điện tử hoặc đơn vị bưu chính thông báo trúng thưởng phần quà giá trị cao (xe máy, điện thoại, sổ tiết kiệm), hoặc gửi quà "tri ân khách hàng", nhưng bắt nạn nhân phải đóng các khoản phí trước (phí đăng ký biển số, phí hồ sơ, phí vận chuyển) hoặc quét mã QR/bấm link nhận quà.

**Dương 1: trúng thưởng xe máy yêu cầu đóng phí giấy tờ / biển số**
> `Chuc mung quy khach da trung thuong 01 xe may Honda SH mode tu chuong trinh tri an mua hang! De nhan thuong, vui long chuyen 2.500.000d phi lam thu tuc bien so vao STK 0000000000.`

**Dương 2: quà tri ân bưu phẩm bắt quét mã QR**
> `Buu pham tri an khach hang da ve den buu cuc. Quet ma QR tren phieu de xac nhan thong tin va nhan hang mien phi trong ngay.`

Nguồn:
- Dương 1: https://bocongan.gov.vn/bai-viet/thanh-hoa-triet-xoa-o-nhom-lua-dao-chiem-doat-tai-san-bang-hinh-thuc-mua-hang-boc-tham-trung-thuong-d22-t45630 (18/06/2025, Công an tỉnh Thanh Hóa): kịch bản gọi điện/nhắn tin thông báo trúng xe máy Honda SH mode, sau đó yêu cầu chuyển tiền từ 1,5 - 7 triệu làm thủ tục bấm biển số/giấy tờ xe, đòi tiếp 2 - 9 triệu tiền vận chuyển hoặc nộp phí đổi sang tiền mặt.
- Dương 2: https://genk.vn/mat-tien-vi-quet-ma-qr-buu-pham-tri-an-nhan-dien-toi-pham-de-phong-ngua-165260926134051255.chn (cảnh báo chiêu gửi bưu kiện chứa mã QR tri ân dẫn dụ chiếm đoạt tài khoản).

**Dễ nhầm → nhãn đúng N2 (không phải N4)**
> `[Ngan hang A] Ban duoc chon nhan phan thuong doc quyen tu the tin dung. Nhan ngay tai: nganhanga[.]cfd`

Lý do: mồi là "phần thưởng" nên dễ gán N4. Nhưng **giả danh ngân hàng** → N2 (mục 9, quy tắc 1). Bài Công an Gia Lai nêu cả "thông báo nhận phần thưởng độc quyền" nằm trong chiêu mạo danh Techcombank.
## 6. N5: Tình cảm / làm quen online

**Định nghĩa:** Kẻ gian làm quen trên mạng xã hội (Facebook, Zalo, ứng dụng hẹn hò), thường đóng vai Việt kiều, quân nhân, kỹ sư ngoại quốc hoặc người thành đạt đơn thân. Sau quá trình nhắn tin tâm sự dài ngày để tạo dựng lòng tin và tình cảm yêu đương ("Romance Scam"), kẻ gian thông báo gửi gói quà giá trị lớn (tiền đô la, trang sức, quà tặng đắt tiền) về Việt Nam. Kế đó, đồng bọn đóng giả cán bộ hải quan, an ninh sân bay hoặc bưu tá thông báo bưu kiện bị tạm giữ và yêu cầu nạn nhân chuyển tiền nộp "thuế", "phí thông quan", "tiền phạt".

**Dương 1: tạo tình cảm yêu đương rồi báo gửi quà giá trị**
> `Em nho anh nhieu lam. Em da gui cho anh mot goi qua gia tri tu nuoc ngoai ve Viet Nam, ma van don 0000. Anh nho nhan giup em nhe.`

**Dương 2: thúc ép nạn nhân vay mượn tiền để chuộc quà**
> `Anh oi, buu kien sap bi hoan tra ve nuoc roi. Anh vay tam tien chuyen phi thong quan 140 trieu di, em se tra anh gap doi khi ve nuoc. Nhanh len anh!`

Nguồn (cả 2 mẫu): https://www.bocongan.gov.vn/bai-viet/cong-an-xa-bat-xat-kip-thoi-ngan-chan-vu-lua-dao-qua-tang-quoc-te-giup-nguoi-dan-tranh-mat-140-trieu-dong-1783613735 (09/07/2026, Công an xã Bát Xát, Lào Cai). Vụ án: Facebook giả "Philip Grr" kết bạn tạo tình cảm lâu ngày, báo gửi quà ngoại quốc, sau đó đồng bọn xưng Hải quan/An ninh hàng không đòi 140 triệu tiền thông quan, tài khoản mạng thúc giục đi vay tiền chuyển khoản.

### ⚠️ Cách phân biệt ranh giới N5 và N1 (Mấu chốt để không gán nhầm):
Trong thực tế, kịch bản N5 luôn có 2 bước: (1) Bạn quen tâm sự gửi quà, (2) Hải quan đòi tiền thông quan. Do đó:
- **Gán N5 khi:** Tin nhắn có bối cảnh tình cảm, tâm sự, xưng hô thân mật (anh/em), hoặc nội dung tin nhắn nhắc đến việc *"người bạn/người quen nước ngoài gửi quà về"*.
- **Gán N1 khi (Ví dụ dễ nhầm):** Tin nhắn **hoàn toàn đứng một mình**, nội dung chỉ thuần túy là cơ quan nhà nước đòi phí mà không có bất kỳ dấu hiệu làm quen/bạn bè nào trước đó:
  > `Chi cuc Hai quan thong bao: Kien hang cua ban dang bi giu vi chua hoan tat thu tuc. Vui long chuyen 15.000.000d phi thong quan vao STK 0000000000.`
  *(Trường hợp này người nhận không biết kiện hàng từ đâu, tin nhắn mạo danh thẳng cán bộ nhà nước → Nhãn đúng là **N1**, không gán N5).*
## 7. N6: Tống tiền / dọa lộ video nhạy cảm / deepfake mượn tiền

*(Đã chốt chính thức: N6 chuyên biệt cho đe dọa tống tiền bằng hình ảnh/video cá nhân và giả mạo khuôn mặt/giọng nói bằng công nghệ deepfake).*

**Định nghĩa:**
1. Cắt ghép, chỉnh sửa hình ảnh/video khuôn mặt của nạn nhân vào các cảnh nhạy cảm, đồi trụy rồi gửi tin nhắn đe dọa, tống tiền, ép nạn nhân chuyển tiền nếu không sẽ phát tán cho người thân, bạn bè hoặc cơ quan.
2. Sử dụng công nghệ Deepfake (ghép mặt video) hoặc Deepvoice / Voice cloning (nhại giọng nói) thực hiện cuộc gọi video call chớp nhoáng giả làm người thân, tạo bối cảnh khẩn cấp (tai nạn, cấp cứu, đóng viện phí) để giục chuyển tiền gấp vào tài khoản ngân hàng lạ.

**Dương 1: cắt ghép ảnh nhạy cảm để tống tiền**
> `Toi dang giu toan bo hinh anh va video nhay cam cua anh. Chuyen ngay 50 trieu vao STK 0000000000 trong vong 24 gio, neu khong toi se phat tan len mang va gui cho toan bo co quan anh.` (kèm hình ảnh cắt ghép)

**Dương 2: deepfake video call mạo danh người thân mượn tiền cấp cứu**
> `Anh oi em dang o benh vien, dien thoai sap het pin. Em can gap 20 trieu dong vien phi cap cuu, anh chuyen ngay vao so tai khoan cua bac si nay giup em voi!` (sau cuộc gọi video có khuôn mặt và giọng nói giống người thân nhưng chập chờn, mờ ảo)

Nguồn:
- Dương 1: https://mps.gov.vn/bai-viet/canh-giac-voi-thu-doan-ghep-anh-tong-tien-tren-khong-gian-mang-1791190146 (05/10/2026, Công an tỉnh Thanh Hóa): cắt ghép ảnh nhạy cảm để tống tiền cán bộ, người dân, có vụ đòi đến 1,5 tỷ đồng.
- Dương 2: https://hvktcnan.bocongan.gov.vn/TrangChu/tin-tuc/1931-phong-chong-hoat-dong-loi-dung-cong-nghe-deepfake-lua-dao-tren-khong-gian-mang.html (Học viện Kỹ thuật và Công nghệ An ninh - Bộ Công an): phân tích thủ đoạn sử dụng Deepfake giả mạo khuôn mặt, giọng nói qua video call, tạo bối cảnh khẩn cấp (cấp cứu, thanh toán viện phí) để lừa chuyển tiền.

**Dễ nhầm → nhãn đúng N1 (không phải N6)**
> `Day la Cong an. Ban co lenh bat vi lien quan duong day rua tien. De chung minh trong sach, chuyen tien vao tai khoan an toan va khong noi voi ai.`

Lý do: có yếu tố đe dọa dồn dập và bắt nộp tiền nhưng kẻ gian **mạo danh công an/viện kiểm sát**, không dùng hình ảnh nhạy cảm hay công nghệ deepfake giả người quen → Nhãn đúng là **N1**.
## 8. OTHER: không thuộc N0–N6

Dùng khi tin nhắn **lừa đảo hoặc đáng ngờ rõ ràng** nhưng không khớp với định nghĩa của 7 nhóm trên. Tuyệt đối không dùng `OTHER` để "né" các trường hợp phân vân giữa 2 nhóm (trường hợp đó phải dùng quy tắc ở mục 9).

**Trường hợp điển hình thuộc nhãn OTHER (Đã chốt):**
- **Chiếm quyền tài khoản MXH (hack Zalo/Facebook) nhắn tin vay tiền bằng văn bản thông thường:** Kẻ gian chiếm quyền nick Zalo/Facebook của người quen rồi gửi tin nhắn văn bản xin mượn tiền ("Bạn còn tiền không cho mình vay 5 triệu mai trả"). Ca này **không sử dụng Deepfake gọi video** (khác N6) và **không mạo danh tổ chức/ngân hàng** → **Gán nhãn OTHER**.
- Nguồn cảnh báo: https://cafef.vn/cong-an-canh-bao-chieu-chiem-quyen-tai-khoan-zalo-ma-nguoi-dan-can-canh-giac-188260521103656632.chn (Công an cảnh báo chiêu chiếm quyền tài khoản Zalo nhắn tin vay tiền).

---

## 9. Quy tắc phá thế hòa (thứ tự áp dụng)

1. **Bên bị mạo danh quyết định nhóm.** Giả cơ quan nhà nước → N1. Giả ngân hàng/tài chính → N2. Mồi chiêu (quà, tiền, lương) chỉ quyết định khi không mạo danh ai.
2. **Có mạo danh tổ chức thì mạo danh thắng.** "Phần thưởng của ngân hàng" → N2, không phải N4. "Phạt nguội kèm đăng nhập ngân hàng" → N1, không phải N2.
3. **Tin có ảnh/video nhạy cảm kèm đe dọa hoặc dùng công nghệ Deepfake giọng/mặt → N6**, kể cả khi kẻ đe dọa tự xưng là ai.
4. **Mẫu có ngữ cảnh tình cảm, tâm sự làm quen hoặc quà từ bạn mạng xã hội gửi về → N5**, kể cả khi bước hiện tại có nhắc đến cơ quan hải quan/bưu điện.
5. **Tin thật, không đòi hỏi hành động nguy hiểm, không link lạ, không bảo giữ bí mật → N0**, kể cả khi có xuất hiện từ khóa "OTP", "ngân hàng", "tuyển dụng".
6. **Hack tài khoản chat hỏi vay tiền bằng chữ (không video call deepfake) → OTHER.**
7. Vẫn phân vân sau các bước trên: chọn nhóm **nguy hiểm hơn** theo thứ tự N6 > N1 > N2 > N3 > N4 > N5, ghi vào cột "ghi chú" của sheet gán thử, và báo AI2 để cập nhật guide.

---

## 10. Gán thử 10 mẫu (điều kiện xong)

1. AI2 chọn 10 mẫu **mới** (không dùng mẫu minh họa ở trên): 1–2 mẫu N0, ít nhất 5 mẫu N1–N6 khác nhau, **ít nhất 3 mẫu biên** (dễ nhầm). Đề bài lưu ở `eval/labeling_trial/samples.md`, đáp án và phân tích lưu riêng ở `eval/labeling_trial/answer_key.md`.
2. 4 người gán **độc lập**, không bàn trước, ghi vào sheet: `mẫu | nhãn | ghi chú`.
3. Một mẫu tính là **đồng ý** khi cả 4 người ra cùng nhãn. Cần **≥ 8/10** mẫu đồng ý.
4. Mẫu lệch: sửa guide ở đúng chỗ gây lệch, rồi gán lại bằng **10 mẫu mới**. Không sửa nhãn cho khớp.
5. Ghi kết quả vào PR theo bảng:

| Lần | Số mẫu đồng ý / 10 | Mẫu lệch | Đã sửa gì trong guide |
|---|---|---|---|
| 1 | | | |

## 11. Checklist hoàn thành nhiệm vụ SH-02

- [x] Chốt định nghĩa N6 (tống tiền ảnh nhạy cảm + deepfake mượn tiền).
- [x] Chốt quy tắc phân biệt N5 và N1 theo ngữ cảnh bạn bè/tình cảm.
- [x] Chốt xử lý tài khoản Zalo bị chiếm quyền mượn tiền (gán vào `OTHER`).
- [x] Trích dẫn nguồn cảnh báo chính thức từ Bộ Công an và Học viện An ninh cho các mẫu lừa đảo.
- [x] AI2 chuẩn bị 10 mẫu gán thử trong `eval/labeling_trial/samples.md` (tách đáp án sang `answer_key.md`).
- [ ] 4 người gán thử đạt kết quả đồng ý ≥ 8/10, điền bảng mục 10.
- [ ] Tạo PR và nhờ AI1 duyệt merge.
