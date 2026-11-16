"""Đo mask() trên eval/mask_pii/mask_pii.jsonl (AI-31, #91). Chạy từ thư mục ai/:
  uv run python ../eval/run_mask_eval.py --split dev        # chỉnh mask theo lỗi ở đây
  uv run python ../eval/run_mask_eval.py --split heldout    # CHỈ khi chốt số liệu, không sửa mask theo lỗi của split này
  --ai-src <ai/src khác>   (mặc định: ai/src của repo chứa file này)
Sót = value còn nguyên sau mask. Che nhầm = mẫu bẫy bị đổi thành token. Báo x/n kèm Wilson CI 95%."""
import argparse, collections, json, math, pathlib, re, sys

HERE = pathlib.Path(__file__).parent
DATA = HERE / "mask_pii" / "mask_pii.jsonl"
TOKEN = re.compile(r"\[[^\]\s]*_\d+[^\]]*\]")


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", choices=["dev", "heldout", "all"], default="dev")
    ap.add_argument("--ai-src", default=str(HERE.parent / "ai" / "src"))
    ap.add_argument("--max-examples", type=int, default=10)
    a = ap.parse_args()
    sys.path.insert(0, a.ai_src)
    from askfirst.pipeline.mask import mask
    print("mask từ:", sys.modules["askfirst.pipeline.mask"].__file__, "| split:", a.split)

    rows = [json.loads(l) for l in DATA.read_text(encoding="utf8").splitlines() if l.strip()]
    if a.split != "all":
        rows = [r for r in rows if r["split"] == a.split]
    miss, tot, ex = collections.Counter(), collections.Counter(), collections.defaultdict(list)
    fp, ntrap, fpex = 0, 0, []
    for r in rows:
        out = mask(r["text"]).text
        for p in r["pii"]:
            tot[p["type"]] += 1
            if p["value"] in out:
                miss[p["type"]] += 1
                ex[p["type"]].append((r["id"], p["value"], out))
        if not r["pii"]:
            ntrap += 1
            if TOKEN.search(out):
                fp += 1
                fpex.append((r["id"], out))
    print(f"\n{len(rows)} mẫu. Recall = (n − sót)/n, Wilson 95%\n")
    print("| Loại | recall | CI 95% | sót |")
    print("|---|---|---|---|")
    K = N = 0
    for t in sorted(tot):
        n, k = tot[t], tot[t] - miss[t]
        lo, hi = wilson(k, n)
        print(f"| {t} | {k}/{n} | {lo:.0%}–{hi:.0%} | {miss[t]} |")
        K += k; N += n
    lo, hi = wilson(K, N)
    print(f"| **tất cả** | {K}/{N} | {lo:.0%}–{hi:.0%} | {N-K} |")
    lo, hi = wilson(fp, ntrap)
    print(f"\nChe nhầm ở mẫu bẫy: {fp}/{ntrap} (Wilson {lo:.0%}–{hi:.0%})")
    for t in sorted(ex):
        print(f"\n### Sót {t} (tối đa {a.max_examples})")
        for i, v, o in ex[t][: a.max_examples]:
            print(f"- {i}: `{v}` → {o[:110]!r}")
    if fpex:
        print(f"\n### Che nhầm (tối đa {a.max_examples})")
        for i, o in fpex[: a.max_examples]:
            print(f"- {i}: {o[:110]!r}")


if __name__ == "__main__":
    main()
