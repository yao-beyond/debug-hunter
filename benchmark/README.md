# debug-hunter benchmark — 從「demo proof」走向「benchmark proof」

> **這個目錄回答一個問題：debug-hunter 抓漏洞到底準不準？**
> 23 個 demo 證明「閉環跑得起來」，但證明不了「召回率多高、誤報率多低」。
> 這裡把每個 demo 的 ground-truth 抽成機器可讀標註，用 scorer 算出可重複、可被第三方驗證的
> **precision / recall / 證據完整率**——讓可信度從「宣稱」變成「可量測」。

## 它怎麼運作

```text
cases/*.json            findings JSON              metrics.json
(ground-truth 標註)  ─┐   (偵測器輸出)        ┌─►  precision / recall / F1
                      ├─►  score.py  ─────────┤    evidence_complete_rate
examples/ 真實程式碼 ─┘   (純 stdlib，零依賴)  └─►  TP / FP / FN / TN（分軌）
```

1. **`cases/*.json`** — 每個 case 把一個 demo 的漏洞版/修復版**精確錨定到真實函式與行號**，
   標註 taint source/sink、缺少的防護、被違反的不變量、應達定級、證據門檻。
   遵循 [`schema/case.schema.json`](schema/case.schema.json)。
2. **findings JSON** — 偵測器跑完 case 後的正規化輸出，遵循 [`schema/findings.schema.json`](schema/findings.schema.json)。
3. **`score.py`** — 對照兩者計分：
   - 漏洞 target 被命中 → **TP**（再判證據是否完整、定級是否足夠）；沒命中 → **FN**（漏報）
   - 安全 target 被命中 → **FP**（誤報）；正確略過 → **TN**
   - 命中不到任何 target 的 finding → **FP**（雜訊）

## 跑跑看（示範資料，30 秒）

```bash
python3 benchmark/score.py --findings benchmark/samples/baseline-findings.sample.json
```

會印出每個 target 的判定表 + 混淆矩陣 + 指標，並寫出 `benchmark/metrics.json`。

> ⚠️ **誠實聲明**：`samples/baseline-findings.sample.json` 是**人工示範樣本**（刻意含 1 漏報、
> 1 誤報、1 證據不完整），用來證明 scorer 真的能區分對錯——**它不是 debug-hunter 的真實偵測能力**。
> scorer 會把這件事寫進 `metrics.json` 的 `is_real_measurement: false` 與 `caveat` 欄位，不會假裝是真數據。

## 怎麼取得**真實**測量

```bash
# 1. 讓 debug-hunter 實跑這些 case 的原始碼
claude --agent plugins/debug-hunter/AGENT.md \
  "掃描 benchmark/cases 列出的 source_file，對每個 finding 輸出 case_id / target / severity / evidence"

# 2. 把代理輸出整理成 findings JSON（source 設為 "agent-run"），存成 out.json

# 3. 計分（這次是真實數據）
python3 benchmark/score.py --findings out.json --out benchmark/metrics.json

# 4.（可選）當 CI release gate：低於門檻則 exit 1
python3 benchmark/score.py --findings out.json --gate
```

`--gate` 預設門檻：precision ≥ 0.80、recall ≥ 0.70、evidence_complete_rate ≥ 0.90
（可在 `score.py` 的 `DEFAULT_THRESHOLDS` 調整）。

## 目前涵蓋的 case（4，逐步擴充至 30 條 PAT）

| case_id | PAT | 軌道 | 錨定 demo |
|---------|-----|------|-----------|
| PAT-SEC-101-idor | PAT-SEC-101 | security | IdorDemo.java |
| PAT-SEC-103-doublespend | PAT-SEC-103 | security | DoubleSpendDemo.java |
| PAT-FIN-002-floatmoney | PAT-FIN-002 | correctness | FloatMoneyDemo.java |
| PAT-FIN-006-allocation-residue | PAT-FIN-006 | correctness | AllocationResidueDemo.java |

> 路線圖：把 [DEMO-COVERAGE.md](../plugins/debug-hunter/knowledge-base/DEMO-COVERAGE.md) 的 23 個 runnable demo
> 全數補上 ground-truth manifest，讓 benchmark 覆蓋與 demo 對齊；再引入 mutation-based 注入產生更多 ground truth。

## 新增一個 case

1. 在 `cases/` 新增 `<PAT>-<slug>.json`，依 `schema/case.schema.json` 標註，`label` 用真實函式名、`line` 對齊原始碼。
2. （可選）用 `python3 -m json.tool cases/<file>.json` 驗證 JSON 合法。
3. 重跑 scorer 確認新 case 被納入計分。

### 證據標註指南 (Evidence Labeling Guide)

為了避免「憑感覺亂標」，請遵循以下維度（Dimension）來選取 `required_evidence`：

| 證據標籤 (`required_evidence`) | 語義 (Meaning) | 適用場景 |
| :--- | :--- | :--- |
| **`trace_path`** | **觸發路徑**：證明漏洞是如何被「抵達」的 | Taint flow, Control flow, Concurrency interleaving sequence |
| **`logic_invariant`** | **邏輯違反**：證明哪條「財務規則」被破壞了 | Invariant broken (e.g. balance < 0), Auth bypass |
| **`state_mutation`** | **狀態破壞**：證明最終「數據/精度」出錯了 | DB state corruption, Floating point rounding error |
| **`guard_absence`** | **防護缺失**：證明關鍵「防線」並不存在 | Missing locks, Missing @Transactional, Missing ownership check |

#### 軌道建議組合 (Presets)

*   **Security (安全/舞弊)**: 建議 `trace_path` + `logic_invariant` (證明有路徑進來且違反權限)。
*   **Correctness (正確性/精度)**: 建議 `logic_invariant` + `state_mutation` (證明運算規則錯了且數據確實偏差)。
*   **Concurrency (併發/原子性)**: 建議 `trace_path` + `guard_absence` (證明存在交錯時序且缺乏鎖機制)。

> **Tip**: 如果你的 bug 沒有 Taint Flow (例如精度誤差)，請改標 `state_mutation`；如果你的 bug 關鍵在於「沒加鎖」，請務必標註 `guard_absence`。
