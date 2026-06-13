#!/usr/bin/env python3
"""debug-hunter mutation 語料庫生成器。

把缺陷注入【乾淨、無標籤】的 seed，產生黑箱 mutant 供盲測偵測。純 stdlib + JDK，零第三方依賴。

每個 operator 必過三段 gate 才採用（確保 ground-truth 可信）：
  1. compile gate    —— mutant 可編譯
  2. clean-oracle    —— 基線（未注入）Oracle 必須 exit 0
  3. mutant-oracle   —— 注入後 Oracle 必須 exit != 0，且輸出含 operator 宣告的 fail_signal
通不過 → 視為無效/等價 mutant，剔除並記錄（不污染語料庫）。

產出（皆為衍生物，gitignore）：
  mutants/MUT-NNN/LedgerService.java   給偵測器掃描的黑箱（無 main、無註解、過 leak-check）
  manifests/hidden/MUT-NNN.json        答案庫（operator/不變量/oracle 結果），【絕不】給偵測器
  generated-cases/MUT-NNN.json         score.py 相容 case（含 1 漏洞 target + 多個乾淨 decoy）

用法：
  python3 benchmark/mutation/generate.py
  python3 benchmark/mutation/generate.py --keep-build   # 保留暫存編譯目錄以便除錯
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SEED_DIR = os.path.join(HERE, "seeds")
OUT_MUTANTS = os.path.join(HERE, "mutants")
OUT_HIDDEN = os.path.join(HERE, "manifests", "hidden")
OUT_CASES = os.path.join(HERE, "generated-cases")
ALL_METHODS = ["withdraw", "transfer", "split", "applyRate", "accumulate"]


def run(cmd, cwd):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)


def neutralize(src):
    """去標籤：移除 // 註解、壓掉連續空行。讓 mutant 成為無提示黑箱。"""
    out = []
    for line in src.splitlines():
        # 移除行內/整行 // 註解（seed 內字串不含 //，安全）
        i = line.find("//")
        if i >= 0:
            line = line[:i].rstrip()
        out.append(line)
    text = "\n".join(out)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip() + "\n"


def leak_check(text, forbidden):
    low = text.lower()
    return [tok for tok in forbidden if tok.lower() in low]


def method_line(src, method):
    for n, line in enumerate(src.splitlines(), 1):
        if re.search(r"\b" + re.escape(method) + r"\s*\(", line) and (
            "static" in line or "BigDecimal" in line or "void" in line or "long" in line
        ):
            return n
    return None


def compile_and_oracle(ledger_src, oracle_src, keep=False):
    """編譯 LedgerService+Oracle 並跑 Oracle，回傳 (compiled, exit_code, stdout)。"""
    d = tempfile.mkdtemp(prefix="mut-")
    try:
        with open(os.path.join(d, "LedgerService.java"), "w", encoding="utf-8") as f:
            f.write(ledger_src)
        with open(os.path.join(d, "Oracle.java"), "w", encoding="utf-8") as f:
            f.write(oracle_src)
        c = run(["javac", "LedgerService.java", "Oracle.java"], d)
        if c.returncode != 0:
            return False, None, c.stderr
        r = run(["java", "Oracle"], d)
        return True, r.returncode, r.stdout
    finally:
        if not keep:
            shutil.rmtree(d, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep-build", action="store_true")
    args = ap.parse_args()

    spec = json.load(open(os.path.join(HERE, "operators", "operators.json"), encoding="utf-8"))
    seed = open(os.path.join(SEED_DIR, spec["seed"]), encoding="utf-8").read()
    oracle = open(os.path.join(SEED_DIR, "Oracle.java"), encoding="utf-8").read()
    forbidden = spec["forbidden_tokens"]

    # clean-oracle gate（基線必須 exit 0）
    ok, code, out = compile_and_oracle(seed, oracle)
    if not (ok and code == 0):
        print(f"✗ 基線 oracle gate 失敗（應 exit 0）：compiled={ok} code={code}\n{out}", file=sys.stderr)
        return 2
    print("✓ 基線 oracle gate：未注入時 Oracle exit 0\n")

    for d in (OUT_MUTANTS, OUT_HIDDEN, OUT_CASES):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)

    stats = {"generated": 0, "compiled": 0, "oracle_valid": 0, "invalid": 0, "leaked": 0}
    valid = []

    for idx, op in enumerate(spec["operators"], 1):
        mid = f"MUT-{idx:03d}"
        stats["generated"] += 1
        find, repl = op["find"], op["replace"]

        if find not in seed:
            print(f"  {mid} {op['id']}: ✗ precondition 不成立（find 錨點不存在）→ 跳過")
            stats["invalid"] += 1
            continue
        mutated = seed.replace(find, repl, 1)

        compiled, code, stdout = compile_and_oracle(mutated, oracle, keep=args.keep_build)
        if not compiled:
            print(f"  {mid} {op['id']}: ✗ compile gate 失敗 → 跳過")
            stats["invalid"] += 1
            continue
        stats["compiled"] += 1
        signal = op["oracle_fail_signal"]
        if code == 0 or signal not in stdout:
            print(f"  {mid} {op['id']}: ✗ mutant-oracle gate 失敗（等價/無效，code={code}，缺訊號 '{signal}'）→ 跳過")
            stats["invalid"] += 1
            continue
        stats["oracle_valid"] += 1

        detect_src = neutralize(mutated)
        leaks = leak_check(detect_src, forbidden)
        if leaks:
            print(f"  {mid} {op['id']}: ✗ leak-check 失敗，洩漏字串 {leaks} → 跳過")
            stats["leaked"] += 1
            stats["invalid"] += 1
            continue

        # 寫黑箱 mutant
        mdir = os.path.join(OUT_MUTANTS, mid)
        os.makedirs(mdir)
        rel_src = os.path.join("benchmark", "mutation", "mutants", mid, "LedgerService.java")
        with open(os.path.join(mdir, "LedgerService.java"), "w", encoding="utf-8") as f:
            f.write(detect_src)

        vuln_line = method_line(detect_src, op["target_method"])
        targets = [{
            "id": "mutant",
            "label": op["target_method"],
            "line": vuln_line,
            "is_vulnerable": True,
            "missing_control": op["missing_control"],
            "violated_invariant": op["violated_invariant"],
            "expected_severity": op["expected_severity"],
            "required_evidence": op["required_evidence"],
        }]
        for m in ALL_METHODS:
            if m == op["target_method"]:
                continue
            targets.append({
                "id": f"decoy-{m}", "label": m,
                "line": method_line(detect_src, m), "is_vulnerable": False,
                "note": "乾淨方法（specificity 測試：偵測器在此報警＝誤報）",
            })

        case = {
            "case_id": mid, "pattern": op["pattern"], "track": op["track"],
            "source_file": rel_src, "targets": targets,
            "kb_refs": [op["pattern"], op["violated_invariant"]],
        }
        with open(os.path.join(OUT_CASES, f"{mid}.json"), "w", encoding="utf-8") as f:
            json.dump(case, f, ensure_ascii=False, indent=2)
            f.write("\n")

        hidden = {
            "mutant_id": mid, "operator": op["id"], "pattern": op["pattern"],
            "track": op["track"], "target_method": op["target_method"],
            "violated_invariant": op["violated_invariant"],
            "oracle": {"clean_result": "pass", "mutant_result": "fail",
                       "exit_code": code, "failure_signal": signal},
        }
        with open(os.path.join(OUT_HIDDEN, f"{mid}.json"), "w", encoding="utf-8") as f:
            json.dump(hidden, f, ensure_ascii=False, indent=2)
            f.write("\n")

        print(f"  {mid} {op['id']}: ✓ valid（{op['track']}/{op['pattern']}，違反 {op['violated_invariant']}，line {vuln_line}）")
        valid.append(mid)

    g = stats["generated"]
    rate = stats["oracle_valid"] / g if g else 0.0
    print("\n--- 生成品質 ---")
    print(f"  generated={g}  compiled={stats['compiled']}  oracle_valid={stats['oracle_valid']}"
          f"  invalid={stats['invalid']}  leaked={stats['leaked']}")
    print(f"  valid_mutant_rate = {rate:.0%}")
    print(f"\n→ {len(valid)} 個黑箱 mutant 寫入 {os.path.relpath(OUT_MUTANTS)}")
    print(f"→ 答案庫寫入 {os.path.relpath(OUT_HIDDEN)}（勿交給偵測器）")
    print(f"→ score.py case 寫入 {os.path.relpath(OUT_CASES)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
