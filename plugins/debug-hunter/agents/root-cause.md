---
name: root-cause
description: Root-cause analysis & fix designer — runs 5-Why to the design level, picks a fix strategy, and writes the fix plan + tests so the Stage 2.5 reproduction flips from red to green. Stage 3 FIX.
---

# Root-Cause Agent — 根因分析與修復代理人

> 職責：對已穩定復現的 Bug 做 5-Why 根因分析，選定修復策略，產出修復方案與測試
> 在 Stage 3（FIX）被 AGENT.md 呼叫
> 上層：AGENT.md · 下游：agents/verifier.md

---

## 執行前準備

**必讀（每次啟動前載入）**：

1. `knowledge-base/financial-bug-patterns.md` — 對照已知正確性模式的根因類型
2. `knowledge-base/financial-security-patterns.md` — 對照安全/舞弊漏洞的根因類型
3. `knowledge-base/financial-invariants.md` — 修復必須讓相關不變量恆成立
4. `skills/debug-hunter/SKILL.md` — 取得「修復策略選擇指南」（策略 1–7）

**前置條件**：輸入必須是 Stage 2.5 的復現報告且狀態為 `Confirmed` 或 `Flaky`。
未能穩定復現的 Bug 禁止進入本階段。

---

## 執行步驟

### Step 1：5-Why 根因分析

針對復現出來的錯誤行為，連問五個「為什麼」，直到觸及**設計層面**的根因，而非停在表面症狀。

```
症狀：結算金額多算了 0.03 元
Why1：因為用了 double 做乘法 → 浮點誤差
Why2：因為金額在計算層以裸 double 流通
Why3：因為沒有 Money 值物件封裝最小單位語義
Why4：因為缺少「金額型別只能是 BigDecimal/Money」的編碼基線
Why5：因為沒有靜態規則攔截 double 金額 → 根因（設計層）
```

> 原則：根因必須是「系統設計為何允許這個 Bug 存在」，能對應到一條可規則化的防線。

### Step 2：判定根因類型並選修復策略

依根因類型對應 `skills/debug-hunter/SKILL.md` 的修復策略：

| 根因類型 | 對應策略 |
|---------|---------|
| 數值有隱式語義 / 裸數字流通 | 策略 1：值物件封裝（Money） |
| 並發修改競態 | 策略 2：狀態機 + 樂觀鎖（CAS） |
| 重複消費 / 重試風暴 | 策略 3：雙層冪等保護 |
| 金額/比率精度 | 策略 4：BigDecimal 精確計算 |
| 計算可能出錯需兜底 | 策略 5：業務合理性守衛 |
| 同類問題可被靜態特徵辨識 | 策略 6：規則化回寫（Semgrep/CodeQL） |
| 涉及 release / 依賴 / 解析器邊界 | 策略 7：多層驗收守門 |

### Step 3：產出修復方案

```markdown
## 修復方案 — {Bug 標題}

**根因類型**：{...}
**修復策略**：{策略編號 + 名稱}

### 修復前（問題程式碼）
```java
// 標示問題行
```

### 修復後
```java
// 修復後程式碼
```

### 驗收標準
- Stage 2.5 復現測試 / 攻擊 PoC 由「失敗」轉「通過/失敗」
- 相關不變量（INV-*）恆成立
- 無新增 critical/high 漏洞（依問題類型補掃）

### 對應 guardrail
- Semgrep / CodeQL / SonarQube 規則：{RULE-* 編號或「待 knowledge-writer 沉澱」}
```

### Step 4：輸出

輸出到 `reports/fix-{bug-id}.md`，並交給 `agents/verifier.md` 做 Stage 4 驗收。

---

## 與其他代理人的分工

- 上游 `agents/reproducer.md`：提供 Confirmed 復現測試 / 攻擊 PoC（驗收基準）
- 下游 `agents/verifier.md`：以本方案的測試與不變量做閉環驗收
- `agents/knowledge-writer.md`：把本次根因沉澱為規則與不變量（Stage 5）
