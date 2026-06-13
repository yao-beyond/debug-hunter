#!/usr/bin/env python3
"""debug-hunter benchmark scorer.

把 ground-truth case manifest（benchmark/cases/*.json）與偵測器輸出（findings JSON）
對照，算出每類漏洞的 TP / FP / FN / TN、precision / recall / F1、證據完整率與定級命中率，
寫出 metrics.json 並印出一張可讀表格。純 stdlib，零第三方依賴。

用法：
  python3 benchmark/score.py --findings benchmark/samples/baseline-findings.sample.json
  python3 benchmark/score.py --findings out.json --out benchmark/metrics.json
  python3 benchmark/score.py --findings out.json --gate   # 低於門檻則 exit 1（CI gate）

計分模型（以 case 內的 target 為單位）：
  - is_vulnerable=true 的 target 被某 finding 命中 → TP（再看證據是否完整、定級是否足夠）
                                     未被命中       → FN（漏報）
  - is_vulnerable=false 的 target 被命中 → FP（在安全程式碼上誤報）
                            未被命中     → TN（正確略過）
  - 命中不到任何 target 的 finding      → FP（無對應落點的雜訊誤報）
"""
import argparse
import datetime
import glob
import json
import os
import sys

SEV_ORDER = {"info": 0, "low": 1, "medium": 2, "high": 3, "critical": 4}

# 預設 gate 門檻（report-only 時不套用；--gate 才生效）
DEFAULT_THRESHOLDS = {
    "precision": 0.80,
    "recall": 0.70,
    "evidence_complete_rate": 0.90,
}


def load_cases(cases_dir):
    cases = {}
    for path in sorted(glob.glob(os.path.join(cases_dir, "*.json"))):
        with open(path, encoding="utf-8") as f:
            c = json.load(f)
        cases[c["case_id"]] = c
    return cases


def sev_ge(actual, expected):
    return SEV_ORDER.get(actual, -1) >= SEV_ORDER.get(expected, 99)


def target_match(finding_target, target_label):
    ft = (finding_target or "").strip().lower()
    lbl = (target_label or "").strip().lower()
    if not ft or not lbl:
        return False
    return ft == lbl or lbl in ft or ft in lbl


def score(cases, findings_doc):
    findings = findings_doc.get("findings", [])
    by_case = {}
    for fnd in findings:
        by_case.setdefault(fnd.get("case_id"), []).append(fnd)

    rows = []
    agg = {"TP": 0, "FP": 0, "FN": 0, "TN": 0,
           "evidence_complete": 0, "severity_ok": 0}
    track_agg = {}

    for case_id, case in cases.items():
        track = case.get("track", "unknown")
        ta = track_agg.setdefault(track, {"TP": 0, "FP": 0, "FN": 0, "TN": 0})
        cf = by_case.get(case_id, [])
        used = set()

        for tgt in case["targets"]:
            hits = [i for i, fnd in enumerate(cf)
                    if target_match(fnd.get("target"), tgt["label"])]
            if tgt.get("is_vulnerable"):
                if hits:
                    i = hits[0]
                    used.add(i)
                    fnd = cf[i]
                    req = set(tgt.get("required_evidence", []))
                    got = set(fnd.get("evidence", []))
                    ev_ok = req.issubset(got)
                    sv_ok = sev_ge(fnd.get("severity", "info"),
                                   tgt.get("expected_severity", "high"))
                    agg["TP"] += 1
                    ta["TP"] += 1
                    if ev_ok:
                        agg["evidence_complete"] += 1
                    if sv_ok:
                        agg["severity_ok"] += 1
                    detail = ("證據✓" if ev_ok else "證據✗ 缺" + ",".join(sorted(req - got))) \
                        + " / " + ("定級✓" if sv_ok else "定級不足")
                    rows.append((case_id, tgt["label"], "TP", detail))
                else:
                    agg["FN"] += 1
                    ta["FN"] += 1
                    rows.append((case_id, tgt["label"], "FN", "漏報（未產生 finding）"))
            else:
                if hits:
                    for i in hits:
                        used.add(i)
                    agg["FP"] += 1
                    ta["FP"] += 1
                    rows.append((case_id, tgt["label"], "FP", "在安全程式碼上誤報"))
                else:
                    agg["TN"] += 1
                    ta["TN"] += 1
                    rows.append((case_id, tgt["label"], "TN", "正確略過"))

        for i, fnd in enumerate(cf):
            if i in used:
                continue
            if any(target_match(fnd.get("target"), t["label"]) for t in case["targets"]):
                continue
            agg["FP"] += 1
            ta["FP"] += 1
            rows.append((case_id, fnd.get("target", "?"), "FP", "無對應 target 的雜訊誤報"))

    return rows, agg, track_agg


def derive_metrics(agg):
    tp, fp, fn = agg["TP"], agg["FP"], agg["FN"]
    prec = tp / (tp + fp) if (tp + fp) else 0.0
    rec = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = 2 * prec * rec / (prec + rec) if (prec + rec) else 0.0
    evr = agg["evidence_complete"] / tp if tp else 0.0
    svr = agg["severity_ok"] / tp if tp else 0.0
    return {
        "precision": round(prec, 4),
        "recall": round(rec, 4),
        "f1": round(f1, 4),
        "evidence_complete_rate": round(evr, 4),
        "severity_hit_rate": round(svr, 4),
    }


def fmt_pct(x):
    return f"{x * 100:5.1f}%"


def main():
    ap = argparse.ArgumentParser(description="debug-hunter benchmark scorer")
    ap.add_argument("--cases", default=os.path.join(os.path.dirname(__file__), "cases"))
    ap.add_argument("--findings", required=True, help="偵測器輸出的 findings JSON")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "metrics.json"))
    ap.add_argument("--gate", action="store_true", help="低於門檻則 exit 1")
    args = ap.parse_args()

    cases = load_cases(args.cases)
    if not cases:
        print(f"✗ 在 {args.cases} 找不到任何 case manifest", file=sys.stderr)
        return 2
    with open(args.findings, encoding="utf-8") as f:
        findings_doc = json.load(f)

    rows, agg, track_agg = score(cases, findings_doc)
    m = derive_metrics(agg)
    source = findings_doc.get("source", "unknown")

    print(f"\n=== debug-hunter benchmark｜{len(cases)} cases｜findings source={source} ===\n")
    print(f"{'case':32} {'target':26} {'verdict':8} detail")
    print("-" * 100)
    for case_id, label, verdict, detail in rows:
        print(f"{case_id:32} {label:26} {verdict:8} {detail}")

    print("\n--- 混淆矩陣 ---")
    print(f"TP={agg['TP']}  FP={agg['FP']}  FN={agg['FN']}  TN={agg['TN']}")
    print("\n--- 分軌 ---")
    for track, ta in sorted(track_agg.items()):
        print(f"  {track:12} TP={ta['TP']} FP={ta['FP']} FN={ta['FN']} TN={ta['TN']}")

    print("\n--- 指標 ---")
    print(f"  precision               {fmt_pct(m['precision'])}")
    print(f"  recall                  {fmt_pct(m['recall'])}")
    print(f"  f1                      {fmt_pct(m['f1'])}")
    print(f"  evidence_complete_rate  {fmt_pct(m['evidence_complete_rate'])}")
    print(f"  severity_hit_rate       {fmt_pct(m['severity_hit_rate'])}")

    out_doc = {
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "findings_source": source,
        "is_real_measurement": source == "agent-run",
        "case_count": len(cases),
        "confusion": agg,
        "metrics": m,
        "by_track": track_agg,
    }
    if source != "agent-run":
        out_doc["caveat"] = (
            "findings source 非 agent-run：此 metrics 為 harness 示範，非 debug-hunter 真實偵測能力。"
            "要取得真實數據，請以 /debug-hunter:debug-hunt 跑這些 case，把正規化輸出存成 findings 後重跑本 scorer。"
        )
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out_doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"\n→ 已寫出 {args.out}")
    if source != "agent-run":
        print("⚠ 注意：findings source 非 agent-run，上述指標僅為 harness 示範，非真實偵測能力。")

    if args.gate:
        failed = []
        for k, threshold in DEFAULT_THRESHOLDS.items():
            if m[k] < threshold:
                failed.append(f"{k}={m[k]:.3f} < {threshold}")
        if failed:
            print("\n✗ GATE 未通過：" + "；".join(failed), file=sys.stderr)
            return 1
        print("\n✓ GATE 通過")
    return 0


if __name__ == "__main__":
    sys.exit(main())
