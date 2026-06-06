---
file_id: DEMO-COVERAGE
kind: meta
status: active
schema_version: 1.0
last_reviewed: 2026-06-07
stale_after_days: 90
owner: knowledge-writer-agent
external_refs: []
---

# Demo 覆蓋矩陣（Pattern → 偵測佐證）

> 每個 PAT 都有「可執行佐證」：要嘛是純 JDK 可執行 demo（`examples/vulnerable-settlement/`，CI 每次自動跑），要嘛是靜態規則（Semgrep/CodeQL），要嘛是復現情境（`reproduce-scenarios.md` 的 SCENE）。
> 本檔是回答「某個 pattern 有沒有被佐證」的單一權威來源——避免重複問「怎麼還有漏掉的」。
> 經 Claude + Codex 覆查分類（A=runnable demo / B=靜態規則 / C=SCENE）。

## 統計
- **30 條 PAT** 全數有佐證
- **23 個純 JDK 可執行 demo**（CI 自動編譯執行）
- **3 條** 由靜態規則涵蓋（本質靜態，runnable demo 代表性不足）
- **4 條** 由 reproduce-scenarios 的 SCENE 涵蓋（需 Kafka/DB，已有 MRS）

---

## A. 可執行 demo（23 條）

| PAT | demo | 面向 |
|-----|------|------|
| PAT-SEC-101 | IdorDemo | 越權動帳 |
| PAT-SEC-102 | AmountTamperDemo | 金額竄改（負數/超上限） |
| PAT-SEC-103 | DoubleSpendDemo | TOCTOU 雙花 |
| PAT-SEC-104 | PaymentCallbackDemo | 偽造支付回調 |
| PAT-SEC-105 | OracleManipulationDemo | 預言機操縱/陳舊價 |
| PAT-SEC-106 | MassAssignmentDemo | 屬性越權改餘額 |
| PAT-SEC-107 | ReplayDemo | 請求重放 |
| PAT-SEC-108 | MakerCheckerDemo | 缺雙人覆核 |
| PAT-SEC-110 | AuditTrailDemo | 缺不可竄改稽核軌跡 |
| PAT-SEC-112 | VelocityDemo | 缺速率/velocity 監控 |
| PAT-SEC-113 | PromoAbuseDemo | 優惠疊加套利 |
| PAT-SEC-114 | IdempotencyKeyDemo | 冪等鍵低熵/可預測 |
| PAT-FIN-002 | FloatMoneyDemo | double/float 處理金額 |
| PAT-FIN-003 | BigDecimalEqualsDemo | equals() 比較金額 |
| PAT-FIN-004 | DivideRoundingDemo | divide() 未指定 RoundingMode |
| PAT-FIN-005 | AssetScaleDemo | 多資產硬編碼 scale |
| PAT-FIN-006 | AllocationResidueDemo | 分配捨入殘差 salami |
| PAT-FIN-007 | TimestampUnitDemo | 時間戳單位/時區混淆 |
| PAT-FIN-008 | LongOverflowDemo | long 金額整數溢位 |
| PAT-CON-003 | LockTtlDemo | 分散式鎖 TTL 缺陷 |
| PAT-SCH-001 | SchedulerRaceDemo | 排程多 Worker 資料競爭 |
| PAT-BIZ-001 | TradingWindowRaceDemo | 委託時間窗口競態 |
| PAT-BIZ-002 | SettlementGuardDemo | 缺業務合理性守衛 |

## B. 靜態規則涵蓋（3 條，刻意不做 runnable demo）

| PAT | 涵蓋方式 | 原因 |
|-----|---------|------|
| PAT-SEC-109 | Semgrep `dynamic-sql-injection-ledger`（+ fixture） | SQL 注入是純語法/taint，靜態規則即佐證 |
| PAT-SEC-111 | Semgrep `RULE-SEC-111`（敏感欄位入日誌/回應） | 字串/欄位特徵，靜態規則最直接 |
| PAT-CON-002 | Semgrep/SonarQube `RULE-CON-002/003` + CodeQL | @Transactional 靜默失效依賴 Spring 代理語意，純 JDK demo 代表性不足 |

## C. reproduce-scenarios SCENE 涵蓋（4 條）

| PAT | SCENE | 原因 |
|-----|-------|------|
| PAT-CON-001 | SCENE-CON-001 | Kafka 重送，需 Testcontainers |
| PAT-CON-004 | SCENE-CON-002 | 並發批次更新錢包，已有 CountDownLatch MRS |
| PAT-CON-005 | SCENE-CON-003 | Retry 狀態奇偶污染，已有參數化測試 MRS |
| PAT-BIZ-003 | SCENE-BIZ-001 | 行情 API 失敗靜默預設值，已有 Mock MRS |

---

## 維護規則
1. 新增 PAT → 必須在本表登錄其佐證（A/B/C 其一），否則視為未完成
2. 新增 runnable demo → 更新 A 表 + 該 PAT 的 YAML `poc_ref` + CI
3. 季度覆查：B 類是否該升級為 runnable、C 類 SCENE 是否仍有效
