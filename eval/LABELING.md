# Hướng dẫn gán nhãn (SH-02)

Mục đích: 4 người gán cùng 1 tin nhắn thì ra **cùng 1 nhãn**. Dùng cho bộ eval (SH-03) và scenario card của KB (AI-25).

> **Trạng thái:** bản 2 (30/10/2026). Đổi sang **mã kịch bản theo `docs/research-concept.md` §2.2** (`N1.4`, `N3.1`…), vì plan §3.8, fixture contract và KB (AI-25) đều dùng bảng đó. Bản 1 dùng N1–N6 với nghĩa khác (N2 = ngân hàng, N6 = tống tiền…), nên nhãn cũ **không dùng lại được**; bảng đổi ở mục 12.
> Thêm 3 mã con vào §2.2 cho những loại team đã chốt ở bản 1 mà bảng §2.2 chưa có: `N1.6` trúng thưởng/quà tri ân, `N2.3` ghép ảnh nhạy cảm tống tiền, `N5.4` chiếm tài khoản chat vay tiền; nới `N3.3` để gồm cả "bạn quen online gửi quà rồi đòi phí". **Cần AI1 duyệt** phần đổi taxonomy này.
> Chưa đạt điều kiện xong SH-02: 4 người chưa gán thử (mục 10).

---

## 0. Nguyên tắc chung

1. **1 mẫu = đúng 1 mã kịch bản** (`scenario`), lấy từ bảng mục 1. `group` luôn là phần đầu của mã (`N1.4` → `N1`); `N0` và `OTHER` thì `group` = `scenario`.
2. Gán theo **ý đồ của tin nhắn**, không theo việc có link hay không.
3. Thứ tự khi đọc 1 tin:
   1. Có đòi tiền, OTP, mật khẩu, cài app, chia sẻ màn hình, bấm link lạ, hay giữ bí mật không? **Không**, và tin đúng là thông báo thật → **N0**.
   2. Có → tìm **chiêu mở đầu**: ai bị mạo danh, hoặc mồi là gì (việc làm, đầu tư, tình cảm, mua bán…) → chọn mã ở mục 1.
   3. Khớp 2 mã → **mục 9 (phá thế hòa)**.
4. Tin lừa đảo mà gán N0 là lỗi nặng nhất (bỏ sót). Phân vân giữa N0 và một mã lừa đảo thì đọc lại dấu hiệu ở mục 2.
5. Chỉ gán theo **những gì có trong mẫu** (chữ, ảnh, `forward_origin`). Không gán N2 (deepfake) cho tin chữ chỉ vì `notes` kể có cuộc gọi video: tin chữ không cho bot thấy cuộc gọi đó.
6. Mẫu dễ nhầm đều ghi **nhãn đúng + lý do**. Lý do chưa thuyết phục thì sửa guide, đừng sửa đáp án cho khớp.

---

## 1. Bảng mã kịch bản

Nhóm cấp 1 theo 5 nhóm của Bộ Công an (research-concept §2.1): mạo danh cơ quan, tổ chức; AI/Deepfake; đầu tư và việc làm; mua bán trực tuyến; chiếm quyền thiết bị/tài khoản. Thêm N6 và N0.

| Mã | Kịch bản | Nhận ra bằng |
|---|---|---|
| `N0` | Lành tính, kể cả khi trông đáng ngờ | OTP thật dặn "không chia sẻ", biến động số dư, shipper báo giao, lịch phỏng vấn thật |
| `N1.1` | Mạo danh công an/VKS/toà án | "liên quan vụ án", "tài khoản an toàn", "không được nói với ai" |
| `N1.2` | "Người thân gặp nạn" (người lạ báo tin) | Người xưng giáo viên/bác sĩ/công an báo con cháu tai nạn, đòi chuyển gấp |
| `N1.3` | Giả ngân hàng, ví điện tử, công ty tài chính | Khoá thẻ, điểm thưởng hết hạn, xác minh giao dịch, cập nhật sinh trắc học, vay ưu đãi |
| `N1.4` | Giả dịch vụ công: phạt nguội, VNeID, thuế, BHXH, hải quan, cư trú | Tên miền giả DVC/thuế, "cài app DVC/VNeID" qua link, hải quan đòi phí khi **không** có bối cảnh bạn quen |
| `N1.5` | Giả điện lực, nhà mạng | Cắt điện, khoá SIM vì chưa chuẩn hoá, nợ cước |
| `N1.6` | **(mới)** Giả doanh nghiệp/sàn TMĐT báo trúng thưởng, quà tri ân | Trúng xe/điện thoại, quà tri ân, bắt đóng phí trước hoặc quét QR nhận quà |
| `N2.1` | Deepfake mặt/giọng người thân, sếp | **Có** dấu hiệu cuộc gọi video/voice giả trong mẫu: hình mờ, "mạng yếu", gọi rất ngắn rồi nhắn vay |
| `N2.2` | Gọi thu mẫu giọng nói | Cuộc gọi im lặng, khảo sát dài bất thường |
| `N2.3` | **(mới)** Ghép ảnh/video nhạy cảm để tống tiền | "Tôi giữ video nhạy cảm của anh", đòi tiền nếu không sẽ phát tán |
| `N3.1` | Việc nhẹ lương cao, làm nhiệm vụ | Like video, đơn hàng ảo, thu nhập phi thực tế, "bao ăn ở", nạp tiền để nhận nhiệm vụ |
| `N3.2` | Đầu tư chứng khoán, tiền số, ngoại hối | Cam kết lãi cao, nhóm kín "chuyên gia", sàn lạ |
| `N3.3` | Lừa tình cảm (**nới**: rủ đầu tư, **hoặc** gửi quà rồi đòi phí thông quan) | Xưng hô thân mật, quen qua mạng, chưa gặp, "anh gửi quà về" |
| `N4.1` | Biên lai giả, lừa đặt cọc khi mua bán | Ảnh "chuyển khoản thành công" nhưng tiền không về, đòi cọc trước |
| `N4.2` | Vé concert, máy bay, tour giả | "Pass vé giá gốc", chuyển khoản cá nhân |
| `N4.3` | Giả shipper | Đơn không đặt, đòi phí/bấm link để nhận hàng |
| `N5.1` | App giả, mã độc Android **không gắn với bên bị mạo danh cụ thể** | Link `.apk`, xin quyền Trợ năng |
| `N5.2` | Dụ chia sẻ màn hình | AnyDesk, UltraViewer, "bật chia sẻ màn hình để hướng dẫn" |
| `N5.3` | QR độc hại không gắn với bên bị mạo danh cụ thể | QR trên tờ rơi/tin "nhận ưu đãi" dẫn tới trang đăng nhập |
| `N5.4` | **(mới)** Chiếm tài khoản chat (Zalo/Facebook) rồi nhắn người quen vay tiền | Tin **từ tài khoản người quen**, nhắn chữ xin chuyển gấp, lý do "thẻ lỗi", hứa trả ngay |
| `N6` | "Lấy lại tiền bị lừa" | Hacker/luật sư/"công an" nhận lấy lại tiền, đòi phí trước |
| `OTHER` | Lừa đảo hoặc đáng ngờ rõ ràng nhưng không khớp mã nào | Chỉ dùng khi đã qua mục 9 mà vẫn không khớp; ghi lý do vào `notes` |

Card KB (AI-25) dùng đúng các mã này. Thêm mã mới thì sửa research-concept §2.2, mục này và `SCENARIOS` trong `eval/validate.py` **cùng một PR**.

---

## 2. N0: Lành tính

**Định nghĩa:** tin thật từ ngân hàng, cơ quan, shipper, nhà tuyển dụng. Không đòi gì ngoài việc người dùng tự thao tác trên app/trang chính thức. N0 dạy bot **không báo nhầm**.

**Dấu hiệu:** OTP kèm câu "không chia sẻ"; biến động số dư; shipper báo giao hàng; không có link lạ; không bảo giữ bí mật; không đòi chuyển tiền.

**Dương 1: OTP thật**
> `[Ngân hàng A] Ma OTP giao dich cua ban la 000000. Het han sau 5 phut. KHONG cung cap ma nay cho bat ky ai.`

**Dương 2: biến động số dư**
> `[Ngân hàng A] TK 0000xxx000 -250,000 VND luc 10:15 19/10/2026. SD: 1,200,000 VND. ND: THANH TOAN HOA DON.`

**Dễ nhầm → `N1.3` (không phải N0)**
> `[Ngân hàng A] Ma OTP la 000000. Nhan vien se goi cho ban, vui long doc ma nay de huy giao dich la.`

Lý do: trông như tin OTP nhưng **bảo đọc mã cho người khác**. Tin OTP thật luôn dặn *không* chia sẻ. Đây là bước lấy OTP mà Công an Gia Lai mô tả (giả nhân viên hỗ trợ gọi xin OTP).
Nguồn: https://hangthat.thuonghieucongluan.com.vn/cong-an-canh-bao-chieu-gia-mao-techcombank-gui-sms-bam-nham-link-co-the-mat-sach-tien-a291635.html

**Dễ nhầm → N0 (không phải `N1.3`)**
> `[Ngan hang A] Quy khach luu y: khong chia se OTP, mat khau cho bat ky ai. Moi thac mac lien he tong dai tren the.`

Lý do: tin nhắc cảnh giác thật, có chữ "ngân hàng" và "OTP" nhưng không link, không đòi gì, chỉ **dặn đừng chia sẻ**.

---

## 3. N1: Mạo danh cơ quan, tổ chức

**Định nghĩa:** giả một cơ quan hoặc tổ chức có thật (công an, dịch vụ công, ngân hàng, điện lực, nhà mạng, doanh nghiệp/sàn TMĐT) để dọa hoặc dụ. Mã con theo **bên bị mạo danh** (bảng mục 1).

**Dương 1 (`N1.4`): phạt nguội kèm link giả**
> `[CSGT] Phuong tien cua ban vi pham giao thong, chua nop phat. Tra cuu va nop phat tai: dichvucong-phat[.]xyz/tracuu`

**Dương 2 (`N1.3`): điểm thưởng sắp hết hạn + tên miền gần giống**
> `[Ngan hang A] Diem thuong the tin dung cua ban sap het han. Doi qua ngay tai: nganhanga[.]cfd`

Nguồn:
- `N1.4`: https://bocongan.gov.vn/bai-viet/canh-bao-thu-doan-gia-danh-co-quan-cong-an-gui-tin-nhan-phat-nguoi-kem-duong-link-gia-mao-de-lua-dao-chiem-doat-tai-san-1788945012 (09/09/2026): SMS "phạt nguội", link giống Cổng DVC, "giảm 50% nếu nộp sớm", trang giả đòi đăng nhập ngân hàng/OTP.
- `N1.3`: https://hangthat.thuonghieucongluan.com.vn/cong-an-canh-bao-chieu-gia-mao-techcombank-gui-sms-bam-nham-link-co-the-mat-sach-tien-a291635.html (15/07/2026, theo Công an Gia Lai): điểm thưởng hết hạn, xác minh giao dịch, link kiểu `techcombank[.]cfd`, `tcb[.]lat`.
- `N1.6`: https://bocongan.gov.vn/bai-viet/thanh-hoa-triet-xoa-o-nhom-lua-dao-chiem-doat-tai-san-bang-hinh-thuc-mua-hang-boc-tham-trung-thuong-d22-t45630 (18/06/2025, Công an Thanh Hóa): báo trúng xe SH mode, đòi 1,5–7 triệu "làm biển số", rồi đòi tiếp tiền vận chuyển.

**Dễ nhầm → `N1.4` (không phải `N1.3`)**
> `Cong dich vu cong thong bao: ban co khoan phat chua nop. Vui long nhap so the va ma OTP tren trang de xac nhan nop phat.`

Lý do: có "thẻ", "OTP" nên dễ gán ngân hàng. Nhưng **bên bị mạo danh là Cổng DVC**. Nhãn theo bên bị mạo danh, không theo thứ bị lấy cắp.

**Dễ nhầm → `N1.3` (không phải `N1.6`)**
> `[Ngan hang A] Ban duoc chon nhan phan thuong doc quyen tu the tin dung. Nhan ngay tai: nganhanga[.]cfd`

Lý do: mồi là "phần thưởng", nhưng **giả ngân hàng** → `N1.3`. `N1.6` chỉ dùng khi bên bị mạo danh là doanh nghiệp/sàn bán hàng, không phải ngân hàng hay cơ quan.

---

## 4. N2: AI, Deepfake

**Định nghĩa:** dùng AI giả mặt/giọng người thật, hoặc ghép ảnh/video nhạy cảm để tống tiền.

**Dương 1 (`N2.3`): ghép ảnh nhạy cảm tống tiền**
> `Toi dang giu toan bo hinh anh va video nhay cam cua anh. Chuyen ngay 50 trieu vao STK 0000000000 trong vong 24 gio, neu khong toi se phat tan len mang va gui cho toan bo co quan anh.`

**Dương 2 (`N2.1`): tin nhắn ngay sau cuộc gọi video chập chờn**
> `Anh oi em vua goi video ma mang yeu qua, hinh bi do. Em dang o benh vien can gap 20 trieu vien phi, anh chuyen ngay vao so tai khoan cua bac si giup em.`

Nguồn:
- `N2.3`: https://mps.gov.vn/bai-viet/canh-giac-voi-thu-doan-ghep-anh-tong-tien-tren-khong-gian-mang-1791190146 (05/10/2026, Công an Thanh Hóa): cắt ghép ảnh nhạy cảm tống tiền, có vụ đòi 1,5 tỷ.
- `N2.1`: https://hvktcnan.bocongan.gov.vn/TrangChu/tin-tuc/1931-phong-chong-hoat-dong-loi-dung-cong-nghe-deepfake-lua-dao-tren-khong-gian-mang.html (Học viện Kỹ thuật và Công nghệ An ninh): deepfake video call, bối cảnh khẩn cấp (cấp cứu, viện phí).

**Dễ nhầm → `N5.4` (không phải `N2.1`)**
> `Anh oi em dang o benh vien, chuyen giup em 2 trieu dong vien phi, dien thoai em sap het pin nen nhan tin thoi nhe.` (tin từ tài khoản Zalo người quen, không có cuộc gọi video)

Lý do: chỉ có **chữ** từ tài khoản người quen, không có dấu hiệu mặt/giọng giả trong mẫu → nick bị chiếm, `N5.4`. Chỉ gán `N2.1` khi mẫu có chi tiết cuộc gọi video/voice giả (nguyên tắc 5).

**Dễ nhầm → `N1.1` (không phải N2)**
> `Day la Cong an. Ban co lenh bat vi lien quan duong day rua tien. De chung minh trong sach, chuyen tien vao tai khoan an toan va khong noi voi ai.`

Lý do: đe dọa và đòi tiền, nhưng kẻ gian **mạo danh công an**, không dùng ảnh nhạy cảm hay mặt/giọng giả.

---

## 5. N3: Việc làm, đầu tư, tình cảm

**Định nghĩa:** mồi là thu nhập (việc nhẹ lương cao, đầu tư lãi khủng) hoặc tình cảm với người quen qua mạng.

**Dương 1 (`N3.1`): việc nhẹ lương cao bao ăn ở**
> `Tuyen dung viec lam online: thu nhap 500k/ngay, bao an o, khong can kinh nghiem, khong thu bat ky khoan phi nao. Co xe dua don tan noi tai Ben xe Mien Dong.`

**Dương 2 (`N3.3`): bạn quen online gửi quà, giục vay tiền nộp phí thông quan**
> `Anh oi, buu kien sap bi hoan tra ve nuoc roi. Anh vay tam tien chuyen phi thong quan 140 trieu di, em se tra anh gap doi khi ve nuoc. Nhanh len anh!`

Nguồn:
- `N3.1`: https://bocongan.gov.vn/bai-viet/cong-an-tinh-vinh-long-canh-giac-voi-bay-viec-nhe-luong-cao-1780907526 (08/06/2026, Công an Vĩnh Long): "500 ngàn đồng/ngày, bao chỗ ở, không đóng bất kỳ khoản phí nào", hẹn xe đón rồi đưa sang Campuchia.
- `N3.2`: https://thanhnien.vn/cong-an-da-nang-khoi-to-vu-lua-dao-tien-ao-da-cap-viec-nhe-luong-cao-185260709121531318.htm (Công an Đà Nẵng, đường dây tiền ảo đa cấp).
- `N3.3`: https://www.bocongan.gov.vn/bai-viet/cong-an-xa-bat-xat-kip-thoi-ngan-chan-vu-lua-dao-qua-tang-quoc-te-giup-nguoi-dan-tranh-mat-140-trieu-dong-1783613735 (09/07/2026, Công an xã Bát Xát): Facebook giả kết bạn lâu ngày, báo gửi quà, đồng bọn xưng hải quan đòi 140 triệu.

**Dễ nhầm → N0 (không phải `N3.1`)**
> `Cong ty ABC thong bao lich phong van vi tri nhan vien kinh doanh, 9h sang 21/10 tai van phong dia chi ... Khong thu bat ky khoan phi nao.`

Lý do: có "tuyển dụng" nhưng **không đòi tiền, không kéo sang kênh lạ, không hứa lương phi thực tế**.

**Dễ nhầm → `N1.4` (không phải `N3.3`)**
> `Chi cuc Hai quan thong bao: Kien hang cua ban dang bi giu vi chua hoan tat thu tuc. Vui long chuyen 15.000.000d phi thong quan vao STK 0000000000.`

Lý do: tin **đứng một mình**, chỉ có "hải quan" đòi phí, không có bối cảnh bạn quen/tình cảm → mạo danh cơ quan nhà nước. Có xưng hô thân mật hoặc nhắc "bạn/người yêu gửi quà" thì mới là `N3.3`.

---

## 6. N4: Mua bán trực tuyến

**Định nghĩa:** lừa trong giao dịch mua bán: biên lai chuyển khoản giả, đặt cọc, vé/tour giả, shipper giả.

**Dương 1 (`N4.3`): shipper giả đòi phí cho đơn không đặt**
> `Shipper day a, don hang cua chi da toi nhung con thieu phi giao 35.000d. Chi bam link nay thanh toan giup em de em giao luon: giaohang-nhanh[.]top/pay`

**Dương 2 (`N4.1`): người mua gửi biên lai giả, giục giao hàng**
> `Minh chuyen khoan roi nhe, anh gui anh bill day. Ben ngan hang bao tien ve cham 1-2 tieng, ban giao hang truoc giup minh, minh dang can gap.`

Nguồn: research-concept §2.2 (dòng N4.1, N4.3) và danh sách 24 hình thức lừa đảo của Cục An toàn thông tin (có "biên lai chuyển tiền giả"): https://congan.kontum.gov.vn/an-ninh-trat-tu/toi-pham-va-vi-pham-phap-luat/canh-bao-toi-pham/canh-bao-ve-24-thu-doan-lua-dao-tren-khong-gian-mang.html. Hai mẫu này team tự dựng theo mô tả trong nguồn, **chưa có bài nguồn mô tả đúng câu chữ**; AI2 bổ sung bài cảnh báo riêng cho N4 khi làm KB (AI-25).

**Dễ nhầm → N0 (không phải `N4.3`)**
> `GHN: Don hang 5KX2VN0031 se giao trong hom nay tu 14h-17h. Tai xe se goi truoc khi den, khong thu them phi.`

Lý do: báo giao hàng thật, không đòi phí, không link.

---

## 7. N5: Chiếm quyền thiết bị, tài khoản

**Định nghĩa:** kỹ thuật chiếm thiết bị hoặc tài khoản (app giả, chia sẻ màn hình, QR độc) **khi không mạo danh bên cụ thể nào**, và lừa bằng tài khoản chat đã bị chiếm (`N5.4`).

**Dương 1 (`N5.4`): tài khoản người quen bị chiếm nhắn vay tiền**
> `(tin Zalo từ tài khoản của em họ) Chi oi chuyen giup em 3 trieu vao so tai khoan nay voi, app ngan hang cua em dang bao tri, toi nay em gui lai chi nhe.`

**Dương 2 (`N5.2`): dụ chia sẻ màn hình**
> `Ban tai UltraViewer roi gui ma ID cho minh, minh vao may huong dan ban nhan tien hoan.`

Nguồn:
- `N5.4`: https://cafef.vn/cong-an-canh-bao-chieu-chiem-quyen-tai-khoan-zalo-ma-nguoi-dan-can-canh-giac-188260521103656632.chn (Công an cảnh báo chiếm tài khoản Zalo nhắn tin vay tiền).
- `N5.2`: research-concept §2.2 (dòng N5.2) và ARCH §6.4 luật `R_SCREEN_SHARE`; mẫu team tự dựng, chưa có bài nguồn đúng câu chữ.

**Dễ nhầm → `N1.4` (không phải `N5.1`)**
> `BHXH Viet Nam: So BHXH cua ong/ba chua dong bo voi CCCD gan chip. Tai ung dung VssID ban cap nhat tai bhxh-capnhat[.]top de tiep tuc huong che do.`

Lý do: bước cuối là cài app giả, nhưng tin **mạo danh BHXH (dịch vụ công)** → `N1.4`. `N5.1` chỉ dùng khi không mạo danh bên cụ thể (vd "tải app này để nhận quà" từ số lạ).

---

## 8. N6 và OTHER

**N6: "Lấy lại tiền bị lừa".** Người tự xưng hacker, luật sư, "cán bộ" nhận lấy lại tiền đã mất, đòi phí trước. Thắng mọi mạo danh khác, vì lời khuyên khác hẳn: chỉ trình báo công an.
> `Ban bi lua tien qua mang? Doi ngu luat su cua chung toi lay lai 100% tien trong 48h. Phi ho so 2 trieu, hoan lai khi xong.`

Nguồn: research-concept §2.2 dòng N6 và danh sách 24 hình thức của Cục ATTT (có "lấy lại tiền bị lừa"), link ở mục 6. Mẫu team tự dựng.

**OTHER.** Lừa đảo hoặc đáng ngờ rõ ràng nhưng **không khớp mã nào** sau khi đã đi qua mục 9. Không dùng OTHER để né phân vân giữa 2 mã. Mỗi mẫu OTHER ghi lý do vào `notes`; có từ 3 mẫu OTHER cùng một kiểu thì đề xuất thêm mã mới.

---

## 9. Quy tắc phá thế hòa (áp dụng theo thứ tự)

1. **Mồi "lấy lại tiền bị lừa" → N6**, kể cả khi người nhắn xưng công an/luật sư.
2. **Trong mẫu có mặt/giọng giả, hoặc đe dọa phát tán ảnh/video nhạy cảm → N2** (`N2.1`/`N2.3`), kể cả khi kẻ gian tự xưng là ai.
3. **Bối cảnh tình cảm, bạn quen qua mạng (xưng hô thân mật, "anh/em gửi quà") → `N3.3`**, kể cả khi bước hiện tại nhắc hải quan, bưu điện.
4. **Tin từ tài khoản người quen xin chuyển tiền bằng chữ → `N5.4`.** Người lạ báo người thân gặp nạn → `N1.2`.
5. **Có mạo danh cơ quan/tổ chức cụ thể → N1, mã con theo bên bị mạo danh**, kể cả khi bước cuối là cài app, quét QR, đăng nhập ngân hàng, hay mồi là phần thưởng. Riêng shipper/đơn hàng → `N4.3`.
6. **Mồi việc làm/nhiệm vụ → `N3.1`; đầu tư → `N3.2`; mua bán → N4.**
7. **Chỉ có kỹ thuật (APK, chia sẻ màn hình, QR) mà không mạo danh ai → `N5.1`–`N5.3`.**
8. **Tin thật, không đòi hành động nguy hiểm, không link lạ, không bảo giữ bí mật → N0**, kể cả khi có "OTP", "ngân hàng", "tuyển dụng".
9. Vẫn phân vân: chọn mã của **quy tắc đứng trước** trong danh sách này, ghi phân vân vào `notes`, báo AI2 để sửa guide.

---

## 10. Các trường khác của nhãn (schema ở plan §3.8)

| Trường | Gán thế nào |
|---|---|
| `is_scam` | `true` khi mục đích là lừa (dù chưa đòi gì cụ thể). `false` chỉ cho N0. Split `real_scam` luôn `true`; `benign_*` luôn `false`; `hard` tùy mẫu |
| `group` | Phần đầu của `scenario` (`N1.4` → `N1`). N0 → `N0`, OTHER → `OTHER` |
| `scenario` | Mã ở bảng mục 1. Không tự đặt mã mới trong bộ eval |
| `min_level` (lừa đảo) | `DANGEROUS` khi tin **đòi hành động gây mất tiền/tài khoản ngay**: chuyển tiền, đọc OTP/mật khẩu, cài app, chia sẻ màn hình, nhập thẻ trên link lạ. `SUSPICIOUS` khi mới là mồi: có link lạ hoặc lời mời nhưng chưa đòi hành động nguy hiểm, hoặc chỉ nhìn chữ thì người thật cũng có thể nhắn như vậy (vd người quen vay tiền) |
| `max_level` (lành tính) | `LOW_RISK` cho tin bình thường (`benign_normal`). `UNSURE` cho tin thật nhưng trông đáng ngờ (`benign_suspicious`): bot nói "chưa chắc" vẫn chấp nhận được, báo `SUSPICIOUS` là báo nhầm |
| `risky_actions` | Những việc **tin nhắn đòi người nhận làm**, chọn trong `transfer_money`, `share_otp`, `share_password`, `install_app`, `share_screen`, `click_link`, `keep_secret`, `share_id_card`. Mẫu lành tính để rỗng |
| `must_signals` | Chỉ mã tín hiệu trong ARCH §6.4 (`R_…`, `U_…`) mà **tầng luật chắc chắn phải bắt được** từ chính chữ trong mẫu (vd có `tài khoản an toàn` → `R_SAFE_ACCOUNT`; tên miền giả DVC → `U_TYPOSQUAT`). Không chắc thì để rỗng: thiếu tín hiệu chỉ được ghi vào báo cáo, không tính là sai |
| `source_url` | Bắt buộc với `real_scam`: bài cảnh báo chính thức mà mẫu viết lại theo |
| `provenance` | Mẫu từ đâu: `rewritten` (team viết lại từ bài cảnh báo), `ai_drafted` (AI soạn nháp từ bài cảnh báo, người duyệt từng mẫu), `constructed` (team tự dựng theo định dạng tin thật), `real_anonymized` (tin thật đã ẩn danh). Mẫu `ai_drafted` **phải có người thứ hai duyệt nhãn** trước khi dùng để chấm Gate. `eval/validate.py` bắt buộc trường này với 4 split `real_scam`, `benign_suspicious`, `benign_normal`, `hard`. |

**Quy tắc nguồn:** tên miền giả viết dạng `abc[.]xyz` (defang, kể cả `t[.]me/...`); số điện thoại, số tài khoản dùng dãy bịa (`0000000000`).

---

## 11. Gán thử 10 mẫu (điều kiện xong)

1. AI2 chọn 10 mẫu **mới** (không trùng mẫu minh họa): 1–2 mẫu N0, ít nhất 5 mã lừa đảo khác nhau, **ít nhất 3 mẫu biên**. Đề ở `eval/labeling_trial/samples.md`, đáp án ở `eval/labeling_trial/answer_key.md`.
2. 4 người gán **độc lập**, không bàn trước, ghi `mẫu | scenario | ghi chú`.
3. Một mẫu tính là **đồng ý** khi cả 4 người ra cùng **`scenario`**. Cần **≥ 8/10**. Ghi thêm số mẫu cùng `group` để biết lệch nằm ở mã con hay ở nhóm.
4. Mẫu lệch: sửa guide ở đúng chỗ gây lệch, rồi gán lại bằng **10 mẫu mới**. Không sửa đáp án cho khớp.
5. Ghi kết quả vào PR:

| Lần | Cùng `scenario` / 10 | Cùng `group` / 10 | Mẫu lệch | Đã sửa gì trong guide |
|---|---|---|---|---|
| 1 | | | | |

Checklist SH-02:
- [x] Mỗi nhóm N0–N6 có định nghĩa, 2 ví dụ dương, ít nhất 1 ví dụ dễ nhầm (mục 2–8).
- [x] Mã kịch bản thống nhất với research-concept §2.2, plan §3.8 và KB (AI-25).
- [x] Giải thích cách gán `min_level`/`max_level`, `risky_actions`, `must_signals`, `provenance` (mục 10).
- [ ] AI1 duyệt 3 mã mới (`N1.6`, `N2.3`, `N5.4`) và phần nới `N3.3`.
- [ ] Bổ sung bài nguồn riêng cho mẫu minh họa N4, `N5.2`, N6 (AI-25).
- [ ] 4 người gán thử đạt ≥ 8/10 cùng `scenario`, điền bảng mục 11.

---

## 12. Đổi từ bản 1 (nhóm cũ → mã mới)

| Bản 1 | Bản 2 |
|---|---|
| N0 lành tính | `N0` |
| N1 mạo danh nhà nước | `N1.1` (công an/VKS), `N1.4` (dịch vụ công, thuế, hải quan, VNeID) |
| N2 mạo danh ngân hàng/tài chính | `N1.3` |
| N3 việc nhẹ lương cao / đầu tư | `N3.1` / `N3.2` |
| N4 trúng thưởng / quà tri ân | `N1.6`; bưu phẩm tri ân bắt quét QR mà không nêu doanh nghiệp nào → `N5.3` |
| N5 tình cảm / gửi quà | `N3.3` |
| N6 tống tiền ảnh nhạy cảm / deepfake | `N2.3` / `N2.1` (chỉ khi mẫu có dấu hiệu cuộc gọi giả) |
| OTHER hack Zalo vay tiền | `N5.4` |
| (chưa có) điện lực/nhà mạng, shipper, biên lai giả, APK, chia sẻ màn hình, lấy lại tiền | `N1.5`, `N4.3`, `N4.1`, `N5.1`, `N5.2`, `N6` |
