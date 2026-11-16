"""Sinh ~350 mẫu biến thể định dạng cho mask PII (AI-31 / #91), gộp với hand.jsonl (150 mẫu viết/duyệt tay), chia dev/heldout.
Chạy: python gen_mask_samples.py   → ghi lại mask_pii.jsonl (hand + sinh, có trường split). Cùng seed thì ra cùng kết quả.
Số/email là ngẫu nhiên theo seed, KHÔNG lấy từ người thật; xác suất trùng số thật tồn tại (không có dải SĐT dành riêng), không đưa lên ngoài repo.
"""
import collections, json, pathlib, random, re

HERE = pathlib.Path(__file__).parent
SEED = 20261116
rnd = random.Random(SEED)

COUNT = {"otp": 40, "phone": 65, "stk": 65, "cccd": 65, "email": 65, "spoken_number": 50}
BANKS = ["Vietcombank", "Techcombank", "MB Bank", "BIDV", "Agribank", "ACB", "Sacombank", "VPBank", "TPBank", "VietinBank"]
NAMES = ["NGUYEN VAN AN", "TRAN THI BICH", "LE MINH CUONG", "PHAM THI DUNG", "HOANG VAN EM", "VU THI HOA", "DANG VAN KHOA", "BUI THI LAN"]
DIGIT_WORDS = ["không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"]


def digits(n, first=None):
    d = "".join(str(rnd.randrange(10)) for _ in range(n))
    return (first + d[len(first):]) if first else d


def groups(s, sizes, sep):
    out, i = [], 0
    for k in sizes:
        if i >= len(s):
            break
        out.append(s[i:i + k]); i += k
    if i < len(s): out.append(s[i:])
    return sep.join(out)


# ---- từng loại: trả về (giá trị viết trong text) ----
def gen_phone():
    p = rnd.choice(["03", "05", "07", "08", "09"]) + digits(8)
    f = rnd.choice(["plain", "s433", "s334", "dot", "dash", "plus84", "plus84s", "84", "s2233", "paren"])
    if f == "plain": return p
    if f == "s433": return groups(p, [4, 3, 3], " ")
    if f == "s334": return groups(p, [3, 3, 4], " ")
    if f == "dot": return groups(p, [4, 3, 3], ".")
    if f == "dash": return groups(p, [4, 3, 3], "-")
    if f == "plus84": return "+84" + p[1:]
    if f == "plus84s": return "+84 " + groups(p[1:], [3, 3, 3], " ")
    if f == "84": return "84" + p[1:]
    if f == "s2233": return groups(p, [2, 2, 3, 3], " ")
    return f"({p[:4]}) {p[4:]}"


def gen_stk():
    n = rnd.choice([9, 10, 10, 11, 12, 12, 13, 14])
    s = digits(n)
    f = rnd.choice(["plain", "plain", "s4", "dash4", "dot4", "dash3"])
    if f == "plain": return s
    if f == "s4": return groups(s, [4] * 4, " ")
    if f == "dash4": return groups(s, [4] * 4, "-")
    if f == "dot4": return groups(s, [4] * 4, ".")
    return groups(s, [3, 3, 3, 3], "-")


def gen_cccd():
    if rnd.random() < 0.12:
        return digits(9)  # CMND cũ
    s = f"{rnd.randrange(1, 97):03d}" + digits(9)
    f = rnd.choice(["plain", "plain", "s333", "s3_6_3", "dash"])
    if f == "plain": return s
    if f == "s333": return groups(s, [3, 3, 3, 3], " ")
    if f == "s3_6_3": return groups(s, [3, 6, 3], " ")
    return groups(s, [3, 3, 3, 3], "-")


def gen_email():
    a = rnd.choice(["minh", "lan", "hung", "thao", "nam", "hoa", "tuan", "mai", "khoa", "trang", "quan", "yen"])
    b = rnd.choice(["", "", ".", "_", "."]) 
    c = rnd.choice(["nguyen", "tran", "le", "pham", "vu", "dang", "bui", "ho"])
    n = rnd.choice(["", "", str(rnd.randrange(1, 99)), str(rnd.randrange(1980, 2008))])
    dom = rnd.choice(["gmail.com", "yahoo.com", "outlook.com", "hotmail.com", "icloud.com", "gmail.com", "vnu.edu.vn"])
    e = f"{a}{b}{c}{n}@{dom}"
    f = rnd.choice(["plain", "plain", "plain", "plain", "at"])
    return e if f == "plain" else e.replace("@", " [at] ")


def gen_otp():
    n = rnd.choice([4, 6, 6, 6, 6, 6, 8])
    return digits(n)


def gen_spoken(kind):
    n = {"phone": 10, "otp": 6, "stk": rnd.choice([10, 12, 13]), "cccd": 12}[kind]
    ds = rnd.choice(["0", "0", "0"]) + digits(n - 1) if kind == "phone" else digits(n)
    sep = rnd.choice([" ", " ", ", ", " - "])
    zero = rnd.choice(["không", "không", "không", "zero"])
    return sep.join(zero if c == "0" else DIGIT_WORDS[int(c)] for c in ds)


# ---- khung câu: {X} là chỗ PII ----
FR = {
    "phone": ["Anh gọi em theo số {X} nha", "Số mới của cô là {X}, con lưu lại giúp cô", "Zalo em là {X}, kết bạn đi anh",
              "Liên hệ nhân viên hỗ trợ qua số {X} trong giờ hành chính", "Em lưu số {X} đúng chưa bác",
              "Có việc gấp bác nhắn vào {X} nhé", "Gọi lại cho em số {X} càng sớm càng tốt", "Số của người đặt hàng: {X}",
              "Mẹ ơi con mất máy, mẹ gọi tạm số {X} của bạn con nha", "Tổng đài viên sẽ gọi bác từ số {X}"],
    "stk": ["Chuyển khoản vào STK {X} {B}, chủ TK {N}", "Số tài khoản {X} ({B}) nhờ anh chuyển trước chiều nay",
            "Tài khoản nhận tiền: {X} - {B} - {N}", "Bác ghi lại: STK {X} tại {B} nhé", "Em gửi anh số tài khoản {X} {B}",
            "Tiền nhà chuyển vào {X} {B}, nội dung ghi tên phòng", "Em chuyển nhầm, anh hoàn lại vào tài khoản {X} {B} giúp em",
            "Hoàn tiền về số tài khoản {X}, chủ tài khoản {N}", "Số TK {X}\n{B}\n{N}", "Cô nhờ con chuyển vào tài khoản {X} của {B}"],
    "cccd": ["Số CCCD của bác là {X}, cháu cần để làm thủ tục", "Em gửi anh số căn cước {X}", "Căn cước công dân số {X}, cấp tại Cục Cảnh sát QLHC",
             "Bác đọc giúp cháu số trên thẻ căn cước: {X}", "Ghi giúp em CCCD {X} vào mẫu đăng ký", "Số định danh cá nhân: {X}",
             "Em cần số giấy tờ tùy thân {X} để xác minh", "CMND/CCCD: {X}, họ tên {N}", "Thông tin cá nhân: CCCD {X}, quê quán Nam Định",
             "Cô bị yêu cầu cung cấp căn cước số {X}, có nên gửi không con"],
    "email": ["Gửi hồ sơ về email {X} nhé", "Mail của em: {X}, anh nhớ kiểm tra thư rác", "Liên hệ qua thư điện tử {X} để nhận hóa đơn",
              "Bác cung cấp địa chỉ email {X} để cháu gửi bản scan", "Tài khoản đăng nhập là {X}", "Cô ơi cô cho con xin mail, con ghi {X} đúng không",
              "Vui lòng phản hồi về hòm thư {X} trong 24 giờ", "Thư xác nhận đã gửi tới {X}", "Em để lại email {X}, anh gửi file sang giúp em",
              "Anh quản lý bảo gửi cc cho {X}"],
    "otp": ["Mã OTP của quý khách là {X}, không chia sẻ cho ai", "Anh đọc dãy số vừa gửi về máy: {X}", "Mã xác thực {X} có hiệu lực 3 phút",
            "Cho em xin mã vừa nhận về điện thoại: {X}", "Em cần số xác nhận trong tin nhắn, anh đọc {X} giúp em",
            "Mã bảo mật giao dịch của bác là {X}", "Bác cung cấp mã {X} để em hủy lệnh chuyển tiền", "Nhập mã xác minh {X} vào ô bên dưới",
            "Số mật khẩu dùng một lần: {X}", "Anh gửi lại dãy số em vừa gửi: {X}"],
    "spoken_number": ["Số điện thoại là {X}", "Anh đọc lại giúp em: {X}", "Mã xác nhận: {X}", "Số tài khoản là {X}", "Cô đọc em nghe: {X}",
                      "Em ghi lại số này nhé: {X}", "Bác chép giùm cháu: {X}", "Đọc chậm thôi, {X}", "Số căn cước là {X}, em viết đúng chưa",
                      "Con gọi lại số {X} nhé"],
}
SPOKEN_KIND = ["phone", "otp", "stk", "cccd"]
GEN = {"phone": gen_phone, "stk": gen_stk, "cccd": gen_cccd, "email": gen_email, "otp": gen_otp}


def make(t):
    if t == "spoken_number":
        kind = rnd.choice(SPOKEN_KIND)
        x = gen_spoken(kind)
        # khung phù hợp loại
        frames = {"phone": [FR[t][0], FR[t][4], FR[t][9]], "otp": [FR[t][2], FR[t][1]], "stk": [FR[t][3]], "cccd": [FR[t][8]]}[kind]
        return rnd.choice(frames).replace("{X}", x), x
    x = GEN[t]()
    f = rnd.choice(FR[t]).replace("{B}", rnd.choice(BANKS)).replace("{N}", rnd.choice(NAMES))
    return f.replace("{X}", x), x


def main():
    out, seen = [], set()
    counter = collections.Counter()
    for t, n in COUNT.items():
        while counter[t] < n:
            text, x = make(t)
            if text in seen or x not in text:
                continue
            seen.add(text)
            counter[t] += 1
            out.append({"id": f"gen-{t}-{counter[t]:03d}", "text": text, "pii": [{"type": t, "value": x}], "notes": "script_generated"})

    hand = [json.loads(l) for l in (HERE / "hand.jsonl").read_text(encoding="utf8").splitlines() if l.strip()]
    allr = hand + out
    # chia dev/heldout 70/30 theo nhóm (loại PII chính hoặc 'trap'), seed cố định
    key = lambda r: r["pii"][0]["type"] if r["pii"] else "trap"
    by = collections.defaultdict(list)
    for r in allr: by[key(r)].append(r)
    r2 = random.Random(SEED + 1)
    for k, rows in by.items():
        r2.shuffle(rows)
        cut = round(len(rows) * 0.7)
        for i, r in enumerate(rows): r["split"] = "dev" if i < cut else "heldout"
    allr.sort(key=lambda r: r["id"])
    for r in allr:
        assert all(p["value"] in r["text"] for p in r["pii"]), r["id"]
    (HERE / "mask_pii.jsonl").write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in allr) + "\n", encoding="utf8")

    print(f"hand={len(hand)} gen={len(out)} tổng={len(allr)}")
    cnt = collections.Counter()
    for r in allr:
        for p in r["pii"]: cnt[p["type"]] += 1
    print("PII theo loại:", dict(cnt), "| bẫy:", sum(1 for r in allr if not r["pii"]))
    print("Split:", dict(collections.Counter(r["split"] for r in allr)))
    print("ĐẠT" if all(cnt[t] >= 50 for t in COUNT) and sum(1 for r in allr if not r["pii"]) >= 50 and len(allr) >= 500 else "CHƯA ĐẠT")


if __name__ == "__main__":
    main()
