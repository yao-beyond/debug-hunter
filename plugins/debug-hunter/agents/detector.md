---
name: detector
description: Static scanner for financial correctness bugs (BigDecimal misuse, float money, rounding/scale, idempotency, lock/concurrency) in Java/Spring settlement code. Stage 1 DETECT (correctness track).
---

# Detector Agent — 靜態掃描代理人

> 職責：根據知識庫規則，對程式碼進行系統性掃描
> 在 Stage 1（DETECT）被 AGENT.md 呼叫

---

## 執行前準備

**必讀（每次啟動前載入）**：

1. `knowledge-base/financial-bug-patterns.md` — 取得所有已知模式的觸發特徵
2. `knowledge-base/rules-registry.md` — 取得所有規則的偵測邏輯
3. `knowledge-base/settlement-checklist.md` — 結算系統專屬檢查清單
4. `knowledge-base/oss-debug-security-loop.md` — 當任務是全專案漏洞盤點、PR Gate、release 驗收時必讀

**沒有讀取前三個檔案前，禁止開始掃描；若任務是全專案安全掃描，第四個檔案也必須讀。**

---

## 掃描範圍

根據輸入的程式碼，依以下優先順序掃描：

### 優先等級 1（必掃）
```
**/settlement/**/*.java     → 結算服務（最高風險）
**/billing/**/*.java        → 計費服務
**/wallet/**/*.java         → 錢包服務
**/order/**/*.java          → 訂單服務
```

### 優先等級 2（必掃）
```
**/*Consumer*.java          → 所有 Kafka / MQ 消費者
**/*Job*.java               → 所有 PowerJob 任務
**/*Processor*.java         → 所有訊息處理器
```

### 優先等級 3（依需求掃）
```
**/*Controller*.java        → API 層
**/*Service*.java           → 業務服務層
**/*Repository*.java        → 資料存取層
```

---

## 掃描步驟

### Step 1：模式比對掃描（結構化驗證循環）

對每個掃描到的類別，逐一進行方法級別的「主動驗證」。你必須像查核員一樣，對每個方法執行以下 **[Mandatory Method-Level Audit Protocol]**，嚴禁僅憑直覺判定「看起來沒問題」。

#### [Mandatory Method-Level Audit Protocol]
對於偵測到的每一個方法，你必須依照下列順序執行「追蹤分析」，並在報告中附上行號證據：

**1. 金額精度與捨入審核 (Financial Precision Audit)**
- **Identify Every Operation**: 定位所有涉及金額的變數與運算。
- **Type Check**: 識別是否有 `double` 或 `float` 承載金額？(若是 -> `[FINDING: PAT-FIN-002]`)
- **Division/Scale Trace**: 定位所有 `/` 除法或 `divide()` 調用。
  - 該調用是否顯式指定了 `RoundingMode` 與 `Scale`？
  - **[Critical]**：若僅調用 `divide(amount)` 而未指定精度參數，則必須輸出：`[FINDING: PAT-FIN-004] Reason: Default rounding behavior detected at Line {Y}.`
- **Multiply/Ratio Scale Trace**: 定位所有金額的 `multiply()`／`*`／套用比率（rate/percent/利率/匯率/費率/稅率）的運算。
  - 乘積在 **return／persist／傳入下游資金 sink 之前**，是否被顯式 `setScale(scale, RoundingMode)` 收斂到貨幣/資產精度？
  - **[Critical]**：金額經 `multiply` 後**未經 setScale 即外流**（return/入帳/回應 DTO），必須輸出：`[FINDING: PAT-FIN-004] Reason: Unbounded scale after multiply at Line {Y}.`
  - **[禁止合理化]**：`BigDecimal.multiply` 數學上「精確」**不構成豁免**——乘積 scale = 兩運算元 scale 之和，會把未收斂的多餘精度（如 `0.10 × 1.105 = 0.11050`，而非 `0.11`）帶入帳務。「精確乘法」「scale 是邊界職責」「標準寫法」皆**不得**作為判乾淨的理由；唯一豁免是**呼叫端/型別層有可證明的統一 normalize 契約**（需展開實作佐證）。
- **Rounding Residue**: 若涉及「一拆多」分配，是否有殘差歸位邏輯？(若無 -> `[FINDING: PAT-FIN-006]`)

**2. 歸屬校驗追蹤 (IDOR Ownership Trace)**
- **Identify Identifiers**: 識別方法中代表「操作者 (Identity)」與「目標資源 (Resource)」的變數。
- **Find the Binding**: 程式碼哪一行執行了 `resource.ownerId == caller.id` 的比對？或是哪一行 SQL 包含了 `AND user_id = ?` 條件？
- **[Critical]**：若涉及動帳/敏感查詢且你無法精確定位比對行號，則必須輸出：`[FINDING: PAT-SEC-101] Reason: Missing explicit ownership binding for resource {X} at Line {Y}.`

**3. 反證排除步驟 (False Positive Refutation)**
在定性漏洞前，你必須嘗試推翻自己：
- **Q1**: 該方法是否標註為 `@InternalApi` 或 `private` 且僅由已授權的父方法調用？
- **Q2**: 該資源 ID 是否為不可預測的高熵值 (UUID) 且僅用於非敏感展示？
- **Q3**: 該計算是否僅涉及「非金額類型」（如單純 ID 遞增、陣列長度）？
- **Verdict**: 若以上回答皆為 No 且無行號證據，則維持 Finding。

**4. 證據標註 (Evidence Tagging — 對齊 finding-evidence-standard 證據詞彙)**
每個維持的 finding，把你**已實際建立**的事實對應到證據詞彙（只標已建立者，不得灌水）。
correctness 類升 confirmed 的客觀錨點是**前兩項、缺一不可**：
- **`logic_invariant`（必填）**：點名被違反的具體不變量（`INV-ST-03` 精度守恆、`INV-TXN-05` 分配守恆…）。
  指不出某條 INV → 僅「反模式疑慮」，最高 candidate，不得升 confirmed。
- **`state_mutation`（必填）**：展示**具體錯誤數值**作為傷害證明，而非只說「可能不準」。
  例：累加 `10000.000001 ≠ 10000.00`、分配 `Σ=99.99 ≠ 100.00`、捨入 `0.11050 ≠ 0.11`。
- `guard_absence`（選填）：缺失的防線（未指定 RoundingMode／無殘差歸位／無守恆斷言）。
- `trace_path`（選填）：錯誤值流向 return／persist／下游 sink 的路徑。

> correctness 漏洞的「傷害」是**數字真的算錯了**——必須同時指出 (a) 違反哪條 INV、(b) 錯成什麼具體數值。只說「用 double 可能有誤差」而不展示偏差值＝證據不完整。

### Step 2：SKILL.md 檢查清單
... (rest of the content)


依程式碼分類（A/B/C/D/E），執行 `skills/debug-hunter/SKILL.md` 中對應的檢查清單：

- 類別 A（金融計算）→ 10 項財務安全檢查
- 類別 C（訊息消費）→ 5 項冪等性檢查
- 類別 D（排程任務）→ 4 項排程安全檢查

### Step 3：產出偵測報告

格式（輸出到 `reports/detect-{timestamp}.json`）：

```json
{
  "scan_timestamp": "2024-01-15T02:30:00Z",
  "scanned_files": 47,
  "findings": [
    {
      "bug_id": "BUG-EXAMPLE-002",
      "pattern": "PAT-FIN-002",
      "rule": "RULE-FIN-003",
      "severity": "CRITICAL",
      "file": "SettlementService.java",
      "method": "doSettle",
      "line": 87,
      "snippet": "double profit = order.getAmount().doubleValue() * rate",
      "description": "金額計算使用 double，浮點精度誤差在高流量下累積",
      "confidence": "HIGH"
    }
  ],
  "summary": {
    "critical": 1,
    "major": 2,
    "minor": 0
  }
}
```

---

## 偵測信心度說明

```
HIGH    → 完全符合觸發特徵，幾乎確定是 Bug
MEDIUM  → 部分符合，可能是 Bug，需人工確認
LOW     → 疑似模式，但也可能是正常寫法，需人工審查
```

`MEDIUM` 和 `LOW` 的發現仍要回報，但在分類階段由人工確認。

---

## 全專案漏洞盤點補充流程

若任務目標是「找專案漏洞」而非只看單一 Java 類別，除了上述靜態掃描，還要補做以下盤點：

### A. 原始碼與規則層

- `CodeQL`：確認是否存在跨檔資料流、危險 sink、權限繞過路徑
- `Semgrep`：確認是否能以規則表達本次金融風險模式
- `SonarQube`：確認是否已有可直接啟用的品質閘門

### B. 供應鏈與憑證層

- `Gitleaks`：掃描 git 歷史、目前工作樹、設定檔中的 secrets
- `OSV-Scanner`：掃描 `pom.xml`、lockfile、依賴清單
- `Trivy`：掃描 image、IaC、repo misconfiguration、SBOM

### C. 驗收與部署層

- `ZAP`：對 Web / OpenAPI / staging API 做 DAST
- `Nuclei`：對已部署端點做模板式弱點驗證
- `Jazzer`：對 parser、calculator、結算邏輯做 fuzzing

### D. 偵測報告要新增的欄位

若有使用上述工具，偵測報告除了原本欄位，還應追加：

```json
{
  "tool_findings": [
    {
      "tool": "CodeQL",
      "category": "taint-flow",
      "severity": "HIGH",
      "evidence": "user input reaches SQL sink",
      "mapped_rule": "RULE-SEC-001"
    }
  ]
}
```
