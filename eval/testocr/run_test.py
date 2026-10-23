import json, sys, os, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "eval"))
import ocr_benchmark as ob

dataset_path = ROOT / "eval" / "testocr" / "test_images.jsonl"
rows = [json.loads(line) for line in dataset_path.read_text(encoding="utf-8").splitlines() if line.strip()]

print(f"Loaded {len(rows)} test samples from {dataset_path}")
total_urls = sum(len(r["label"]["urls"]) for r in rows)
total_phones = sum(len(r["label"]["phones"]) for r in rows)
print(f"Total ground-truth URLs: {total_urls}, Phones: {total_phones}\n")

engines = {
    "rapidocr": ob.RapidOCREngine(),
    "rapidocr_cols": ob.RapidOCRColumnsEngine()
}

for name, eng in engines.items():
    print(f"--- Running {name} ---")
    eng.load()
    raw = {}
    
    # Warm-up
    first_img = ROOT / "eval" / rows[0]["input"]["image"]
    eng.read(str(first_img))
    
    times = []
    for r in rows:
        img_p = ROOT / "eval" / r["input"]["image"]
        t0 = time.perf_counter()
        txt = eng.read(str(img_p))
        dt = time.perf_counter() - t0
        times.append(dt)
        raw[r["id"]] = {"text": txt, "sec": dt}
    
    res = ob.score(rows, raw)
    url_strict, url_tot = res["url"]
    url_fix, _ = res["url_fix"]
    url_host, _ = res["url_host"]
    cer = res["cer"]
    p95 = res["p95"]
    p50 = res["p50"]
    ph, ph_tot = res["phone"]
    ph_fix, _ = res["phone_fix"]
    
    print(f"Results for {name}:")
    print(f"  URL Exact (Strict): {url_strict}/{url_tot} ({url_strict/url_tot*100:.1f}%)")
    print(f"  URL After Regex:    {url_fix}/{url_tot} ({url_fix/url_tot*100:.1f}%)")
    print(f"  URL Host/Path:      {url_host}/{url_tot} ({url_host/url_tot*100:.1f}%)")
    print(f"  Phones:             {ph}/{ph_tot} (after fix: {ph_fix}/{ph_tot})")
    print(f"  CER:                {cer*100:.2f}%")
    print(f"  Latency p50:        {p50:.2f}s, p95: {p95:.2f}s")
    
    if res["url_fail"]:
        print(f"  URL Failures ({len(res['url_fail'])}):")
        for rid, u, near in res["url_fail"]:
            # Check if it was fixed by regex
            hyp_repaired = ob.repair_urls(raw[rid]["text"])
            fixed = ob.url_ok(u, hyp_repaired)
            print(f"    [{rid}] Target: {u}")
            print(f"           Found near: {near}")
            print(f"           Regex fixed: {'YES' if fixed else 'NO'}")
    print()
