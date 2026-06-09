---
file_id: financial-security-patterns
kind: pattern-catalog
status: active
schema_version: 1.0
last_reviewed: 2026-06-01
stale_after_days: 180
owner: knowledge-writer-agent
external_refs: ["OWASP API Top 10 2023#API1", "CWE-639", "CWE-841", "CWE-863", "ASVS 4.0.3#V4"]
---

# 金融安全 / 舞弊漏洞模式知識庫

> 檔案路徑：knowledge-base/financial-security-patterns.md
> 用途：收錄「對抗性」的財務安全與舞弊漏洞（有人**故意**攻擊），與 `financial-bug-patterns.md`（功能正確性 bug，**不小心**寫錯）互補。
> 更新規則：每次事後檢視後，由 `agents/knowledge-writer.md` 自動追加
> 偵測代理人：`agents/security-fraud-detector.md`（taint source→sink 驅動）
> 核心心智模型：**正確性 bug 問「程式會不會算錯」；安全漏洞問「攻擊者能不能讓它替他算」。**

---

## 為何要與 financial-bug-patterns.md 分開

| 維度 | financial-bug-patterns | financial-security-patterns（本檔） |
|------|------------------------|-------------------------------------|
| 威脅來源 | 開發者失誤 | 惡意使用者 / 內部人員 / 被攻陷的上游 |
| 觸發者 | 系統自己 | 攻擊者主動構造輸入 |
| 偵測法 | 特徵比對 + 資料流 | **taint source→sink + 威脅建模 + 不變量** |
| 復現法 | 功能單元測試 | **攻擊 PoC（負面測試 / 競態 / 偽造請求）** |
| 驗收法 | 回歸測試通過 | **攻擊不再成功 + 不變量恆成立** |

---

## 汙染源 → 匯點模型（Taint Source → Sink）

財務安全偵測的核心是追蹤「**不可信輸入**是否未經驗證/授權就抵達**資金匯點**」。

### 汙染源（Sources，不可信輸入）
- HTTP 請求參數 / body / header / cookie（`@RequestParam`、`@RequestBody`、`@PathVariable`、`@ModelAttribute`）
- 訊息佇列 payload（Kafka / RocketMQ message body）
- 外部 API 回應（行情、匯率、支付閘道回調、第三方 KYC）
- 使用者可控的檔案 / 匯入（CSV 對帳匯入）
- 快取 / 前端傳回的「信任值」（前端算好的金額、折扣）

### 資金匯點（Sinks，敏感操作）
- 錢包/餘額變更：`walletService.credit / debit / setBalance / batchUpWallets`
- 帳本寫入：`ledgerService.post`、`journalEntry.save`
- 結算/賠付：`settlementService.settle / payout`
- 提款/轉帳：`withdrawService.withdraw`、`transferService.transfer`
- 動態 SQL：`StringBuilder` 拼接的 `where` / `jdbcTemplate.query(sql)`
- 狀態機關鍵躍遷：`order.setStatus(PAID)`、`coupon.markUsed()`

### 必經的「清洗閘」（Sanitizers，沒有就是漏洞）
1. **身分/歸屬校驗**：操作對象的 `accountId/userId` 必須屬於當前認證主體
2. **數值域校驗**：金額 > 0、≤ 上限、scale 符合資產精度、非 NaN/Inf
3. **授權校驗**：角色/權限/雙人覆核
4. **完整性校驗**：簽章 / HMAC / nonce / 時間窗（防偽造與重放）
5. **原子性保護**：檢查與變更在同一交易/鎖內（防 TOCTOU）

> 偵測規則：**任何 Source 到 Sink 的路徑上，缺少對應的 Sanitizer，即為一個 Finding。**

---

## Pattern 索引

| 代碼 | 名稱 | 預設等級 | 攻擊類別 | 缺失清洗閘 |
|------|------|---------|---------|-----------|
| PAT-SEC-101 | 資金操作缺乏帳戶歸屬校驗（越權動帳 IDOR） | P0 | 越權 | 身分/歸屬 |
| PAT-SEC-102 | 金額參數未驗正負與上限（參數竄改） | P0 | 竄改 | 數值域 |
| PAT-SEC-103 | 餘額檢查與扣款非原子（TOCTOU 雙花） | P0 | 競態 | 原子性 |
| PAT-SEC-104 | 支付/結算回調缺乏簽章與來源驗證（Webhook 偽造） | P0 | 偽造 | 完整性 |
| PAT-SEC-105 | 行情/匯率預言機可被操縱或使用陳舊價 | P0 | 操縱 | 完整性 |
| PAT-SEC-106 | Mass assignment 綁定金額/餘額/狀態欄位 | P0 | 竄改 | 數值域/授權 |
| PAT-SEC-107 | 請求重放（缺 nonce / 時間窗 / 一次性令牌） | P0 | 重放 | 完整性 |
| PAT-SEC-108 | 手動調帳/補償端點缺雙人覆核（maker-checker） | P0 | 內部舞弊 | 授權 |
| PAT-SEC-109 | 金融查詢/報表動態 SQL 注入 | P0 | 注入 | 數值域 |
| PAT-SEC-110 | 金流操作缺乏不可竄改稽核軌跡與不可否認性 | P1 | 抵賴 | — |
| PAT-SEC-111 | 敏感金融資料寫入日誌/回應（餘額/卡號/PII 外洩） | P1 | 資訊洩漏 | — |
| PAT-SEC-112 | 金流端點缺速率限制與提款速度監控（velocity） | P1 | 自動化濫用 | — |
| PAT-SEC-113 | 優惠/紅利可疊加套利（promo / 邏輯套利） | P1 | 業務濫用 | 數值域 |
| PAT-SEC-114 | 冪等鍵可預測/低熵（越權覆蓋他人交易） | P1 | 竄改 | 完整性 |

---

## PAT-SEC-101：資金操作缺乏帳戶歸屬校驗（越權動帳 IDOR）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-101
title: 資金操作缺乏帳戶歸屬校驗（IDOR）
status: active
confidence: high
severity_base: P0
applies_to: [withdraw, transfer, settlement, query-balance]
cwe: [CWE-639, CWE-863]
owasp_api: [API1]
flows: [MF-01, MF-02]
invariants: [INV-ST-01]
compliance: ["PCI:Req7", "ASVS:V4.1", "SOC2:CC6.1"]
sources: ["HTTP @RequestParam/@PathVariable accountId|userId|orderId"]
sinks: ["walletService.debit(accountId, amount)", "settlementService.settle(order)"]
required_sanitizers:
  - "account.userId == securityContext.userId（歸屬錨定於認證主體，非請求參數）"
detect:
  static_queries: ["每個 source id 追蹤到資金 sink，path 上需有歸屬校驗"]
  db_evidence: ["歸屬是否由 repository SQL 的 WHERE user_id=? 強制？若是則非漏洞"]
false_positive_checks:
  - "FP-001 歸屬校驗是否在 enum lambda / delegate 間接路徑內？"
  - "歸屬是否由 DB 層 / repository 強制（非僅 controller）？"
confirm_when: ["source 抵達 sink 且 path 上（含 DB 層）無等效 ownership sanitizer"]
reproduce: ["以 user A 的 token 帶入 user B 的 accountId 提款"]
oracle: ["INV-ST-01 被違反，或越權動帳成功"]
fix_strategy: "以認證主體為錨點，請求 id 僅用於一致性校驗"
rule_ref: RULE-SEC-101
poc_ref: examples/vulnerable-settlement/IdorDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
資金操作（查餘額、提款、結算、轉帳）以請求參數中的 `accountId/userId/orderId` 直接定位資源，未校驗該資源是否屬於當前認證主體。攻擊者改一個 ID 即可操作他人帳戶。這是金融系統 **最高頻、損失最直接** 的漏洞。

**觸發特徵**：
```java
// 特徵：直接拿請求帶來的 accountId 動帳，無歸屬校驗
@PostMapping("/withdraw")
public Result withdraw(@RequestParam Long accountId, @RequestParam BigDecimal amount) {
    return walletService.debit(accountId, amount); // ← accountId 來自請求，未綁定 currentUser
}

// 變體：用 orderId 反查後直接結算，未確認 order.userId == currentUser
Order order = orderMapper.selectById(orderId);
settlementService.settle(order); // ← 任意 orderId 皆可被他人結算
```

**危害等級**：P0 — 任意提領/操作他人資金，直接資損 + 監管事件

**修復策略**：所有資金操作以「認證主體」為錨點，請求 ID 僅用於校驗一致性
```java
Long currentUserId = securityContext.getUserId(); // 來自已驗證的 JWT/Session，非請求參數
Account acc = accountMapper.selectById(accountId);
if (acc == null || !acc.getUserId().equals(currentUserId)) {
    throw new AccessDeniedException("帳戶歸屬不符 accountId=" + accountId);
}
// 後台/服務間呼叫需走另一條具備服務授權的路徑，並留審計
```

**偵測法（taint）**：Source = `@RequestParam/@PathVariable` 的 id 類參數；Sink = wallet/settlement/withdraw；路徑上若無 `ownerCheck/userId.equals` 類校驗 → Finding。

**反哺規則**：RULE-SEC-101

---

## PAT-SEC-102：金額參數未驗正負與上限（參數竄改）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-102
title: 金額參數未驗正負與上限（參數竄改）
status: active
confidence: high
severity_base: P0
applies_to: [transfer, withdraw, payment]
cwe: [CWE-20, CWE-840]
owasp_api: [API6]
flows: [MF-01, MF-02]
invariants: [INV-TXN-02]
sources: ["HTTP @RequestParam/@RequestBody BigDecimal amount", "前端傳回的已算金額/折扣"]
sinks: ["walletService.debit(amount)", "walletService.credit(amount)"]
required_sanitizers:
  - "amount.signum() > 0"
  - "amount.scale() <= asset.getScale()"
  - "amount <= asset.maxSingleTxn"
  - "折扣/手續費/應付一律後端重算（不信前端）"
detect:
  static_queries: ["金額 source 抵達資金 sink，path 上無 signum/上限/scale 校驗"]
false_positive_checks: ["校驗是否在共用 AOP/攔截器統一施加？"]
confirm_when: ["金額 source 抵達 sink 且無數值域校驗"]
reproduce: ["送 amount=-100 轉帳；或超精度繞過上限"]
oracle: ["INV-TXN-02 被違反（憑空造錢 / 繞過上限）"]
fix_strategy: "伺服器端強制數值域校驗，金額一律後端重算"
rule_ref: RULE-SEC-102
poc_ref: examples/vulnerable-settlement/AmountTamperDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
金額直接取自請求且未校驗，攻擊者送負數、超大值、超精度小數或 0。負數提款可能變成「反向入帳」，超精度可繞過上限或觸發捨入利用。

**觸發特徵**：
```java
// 特徵：amount 直接用於記帳，無 > 0 / 上限 / scale 校驗
public void transfer(Long from, Long to, BigDecimal amount) {
    walletService.debit(from, amount);
    walletService.credit(to, amount); // ← amount = -100 → from 反而增加
}
// 變體：前端傳「已算好的折扣後金額」直接信任
BigDecimal payable = req.getFrontendCalculatedAmount(); // ← 前端可改
```

**危害等級**：P0 — 憑空造錢 / 繞過風控上限

**修復策略**：伺服器端強制數值域校驗，金額一律後端重算
```java
if (amount == null || amount.signum() <= 0)
    throw new ValidationException("金額必須為正");
if (amount.scale() > asset.getScale())
    throw new ValidationException("金額精度超出資產定義");
if (amount.compareTo(asset.getMaxSingleTxn()) > 0)
    throw new ValidationException("超出單筆上限");
// 折扣、手續費、應付金額一律後端依權威資料重算，不信任前端傳值
```

**反哺規則**：RULE-SEC-102

---

## PAT-SEC-103：餘額檢查與扣款非原子（TOCTOU 雙花）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-103
title: 餘額檢查與扣款非原子（TOCTOU 雙花）
status: active
confidence: medium
severity_base: P0
applies_to: [withdraw, transfer, settlement]
cwe: [CWE-362, CWE-367]
flows: [MF-01]
invariants: [INV-ST-01]
sources: ["並發的提款/扣款請求"]
sinks: ["walletService.debit（前置 getBalance 檢查）"]
required_sanitizers:
  - "原子扣款 SQL：UPDATE ... WHERE balance >= amount"
  - "或帳戶粒度分散式鎖 / 樂觀鎖"
detect:
  static_queries: ["getBalance 檢查與 debit 之間無鎖/原子條件"]
  db_evidence: ["扣款是否原子 SQL（WHERE balance>=?）或 SELECT FOR UPDATE？"]
false_positive_checks: ["FP-003：DB 是否已有原子扣款 / 行鎖？"]
confirm_when: ["check-then-act 跨非原子邊界且存在並發可達路徑"]
reproduce: ["CyclicBarrier/CountDownLatch 並發 N 筆提款，斷言餘額不為負"]
oracle: ["INV-ST-01 餘額變負，或成功筆數 > 可支撐筆數"]
fix_strategy: "檢查下推為扣款 SQL 原子條件，或加鎖"
rule_ref: RULE-SEC-103
poc_ref: examples/vulnerable-settlement/DoubleSpendDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
「先查餘額足夠 → 再扣款」兩步之間沒有鎖或原子條件。攻擊者並發送出 N 個提款請求，全部通過餘額檢查，導致**超提（double-spend）**。與 PAT-CON-004 不同：那是**意外**的批次覆蓋，這是**攻擊者主動**用並發製造的競態。

**觸發特徵**：
```java
// 特徵：check-then-act 跨越非原子邊界
BigDecimal balance = walletService.getBalance(accountId);  // T1 檢查
if (balance.compareTo(amount) >= 0) {
    walletService.debit(accountId, amount);                // T2 扣款（中間無鎖）
}
// 兩個並發請求都在 T1 看到足額，T2 各扣一次 → 餘額變負
```

**危害等級**：P0 — 經典雙花，可被腳本化大規模利用

**修復策略**：把「檢查」下推為扣款 SQL 的原子條件，或加分散式鎖
```java
// 方案 A：原子條件式扣款（DB 保證）
int updated = walletMapper.debitIfEnough(accountId, amount);
// UPDATE wallet SET balance = balance - #{amount}
// WHERE account_id = #{id} AND balance >= #{amount}
if (updated == 0) throw new InsufficientBalanceException();

// 方案 B：分散式鎖（key 至帳戶粒度，TTL > 業務時間）
// 搭配 settlement-checklist C 類並發控制
```

**復現法**：CountDownLatch 同時放行 N 個提款執行緒，斷言最終餘額不為負、成功筆數 ≤ 餘額可支撐筆數。

**反哺規則**：RULE-SEC-103

---

## PAT-SEC-104：支付/結算回調缺乏簽章與來源驗證（Webhook 偽造）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-104
title: 支付/結算回調缺乏簽章與來源驗證（Webhook 偽造）
status: active
confidence: high
severity_base: P0
applies_to: [payment-callback, settlement-callback]
cwe: [CWE-345, CWE-347]
owasp_api: [API8]
flows: [MF-04]
invariants: [INV-T-03]
compliance: ["PCI:Req4"]
sources: ["外部回調 @RequestBody（status / amount / tradeNo）"]
sinks: ["orderService.markPaid", "walletService.credit"]
required_sanitizers:
  - "HMAC/簽章驗證 rawBody"
  - "金額以我方訂單為準比對"
  - "tradeNo 冪等"
detect:
  static_queries: ["回調 handler 內 markPaid/credit 前無簽章驗證"]
false_positive_checks: ["簽章驗證是否在攔截器/filter 統一施加？"]
confirm_when: ["回調 body 直接驅動入帳且 path 無簽章驗證"]
reproduce: ["直接 POST 偽造 status=SUCCESS 回調（無有效簽章）"]
oracle: ["INV-T-03 內外對帳不一致（入帳 > 閘道實收）"]
fix_strategy: "驗簽 + 來源校驗 + 我方金額比對 + 冪等"
rule_ref: RULE-SEC-104
poc_ref: examples/vulnerable-settlement/PaymentCallbackDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
支付閘道 / 行情 / 第三方回調僅憑 body 內的 `code==1`、`status=SUCCESS` 就入帳，未驗 HMAC 簽章、未驗來源 IP、未確認金額與我方訂單一致。攻擊者直接 POST 偽造「支付成功」即可白嫖。

**觸發特徵**：
```java
// 特徵：回調只看 body 欄位，無簽章驗證
@PostMapping("/payment/callback")
public String onPaymentCallback(@RequestBody PaymentNotify notify) {
    if ("SUCCESS".equals(notify.getStatus())) {          // ← 任何人可偽造
        orderService.markPaid(notify.getOrderId());
        walletService.credit(notify.getUserId(), notify.getAmount()); // ← 金額也信任對方
    }
    return "ok";
}
```

**危害等級**：P0 — 偽造入帳 / 偽造結算結果，資損無上限

**修復策略**：驗簽 + 來源校驗 + 我方權威金額比對 + 冪等
```java
if (!signatureVerifier.verify(rawBody, header.get("X-Signature"), gatewayPublicKey))
    throw new SecurityException("回調簽章驗證失敗");
Order order = orderMapper.selectByGatewayNo(notify.getTradeNo());
if (order.getAmount().compareTo(notify.getAmount()) != 0)   // 金額以我方訂單為準
    throw new SecurityException("回調金額與訂單不符");
// 冪等：同一 tradeNo 只處理一次（見 financial-bug-patterns PAT-CON-001）
```

**推廣原則**：**凡是會導致入帳/狀態躍遷的外部回調，必須先驗完整性（簽章），金額/數量一律以我方權威紀錄為準，外部值只用於對帳比對。** 與 PAT-BIZ-003（外部 API 失敗靜默預設值）共同構成「外部資料不可信」防線。

**反哺規則**：RULE-SEC-104

---

## PAT-SEC-105：行情/匯率預言機可被操縱或使用陳舊價

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-105
title: 行情/匯率預言機可被操縱或使用陳舊價
status: active
confidence: medium
severity_base: P0
applies_to: [settlement, clearing, valuation]
cwe: [CWE-345, CWE-829]
flows: [MF-03]
invariants: [INV-ST-03]
compliance: ["AML:市場操縱監控"]
sources: ["外部行情/匯率 API", "快取 / 上一筆價"]
sinks: ["結算計算使用的 price"]
required_sanitizers:
  - "多源中位數（排除離群）"
  - "時效窗（拒陳舊價）"
  - "偏離熔斷（與上一有效價偏離過大暫停）"
detect:
  static_queries: ["結算 price 來自單一來源且無時效/偏離校驗"]
false_positive_checks: ["是否已用多源聚合 + 時效戳？"]
confirm_when: ["單源/陳舊價直接用於結算且無熔斷"]
reproduce: ["瞬間拉抬單一來源；或注入陳舊價"]
oracle: ["結算價偏離公允價 > 閾值 → INV-ST-03 守恆破壞"]
fix_strategy: "多源中位數 + 時效窗 + 偏離熔斷"
rule_ref: RULE-SEC-105
poc_ref: examples/vulnerable-settlement/OracleManipulationDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
結算/清算依賴的價格來源（行情 API、單一交易所、鏈上預言機、內部報價）可被操縱，或使用了**過期/陳舊（stale）**價格。PAT-BIZ-003 處理「API 失敗」，本模式處理「API 成功但價格被污染或過時」——更隱蔽。

**觸發特徵**：
```java
// 特徵 1：單一價格來源，無偏離/時效校驗
BigDecimal price = priceApi.getPrice(code);              // ← 單點、可操縱
runner.settle(price);

// 特徵 2：用快取/上一筆價格，無時效戳檢查
QuoteVo q = quoteCache.get(code);                        // ← 可能是 10 分鐘前的價
int dir = close.compareTo(q.getOpen());
```

**危害等級**：P0 — 攻擊者拉抬/砸盤瞬間價格或利用陳舊價，定向獲利

**修復策略**：多源中位數 + 時效窗 + 偏離熔斷
```java
List<BigDecimal> prices = priceSources.stream()      // 多來源
    .map(s -> s.getPrice(code)).filter(Objects::nonNull).sorted().toList();
BigDecimal median = median(prices);
// 時效：拒絕陳舊價
if (now() - quote.getTimestamp() > maxQuoteAgeMs)
    throw new StalePriceException(code);
// 偏離熔斷：與上一有效價偏離過大需人工/暫停
if (deviation(median, lastValid) > maxDeviation)
    settlementGuard.halt("價格偏離異常 " + code);
```

**反哺規則**：RULE-SEC-105

---

## PAT-SEC-106：Mass assignment 綁定金額/餘額/狀態欄位

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-106
title: Mass assignment 綁定金額/餘額/狀態欄位
status: active
confidence: high
severity_base: P0
applies_to: [account-update, profile]
cwe: [CWE-915]
owasp_api: [API3]
flows: [MF-01]
invariants: [INV-ST-02, INV-ST-05]
sources: ["@RequestBody/@ModelAttribute 綁定到持久化實體（含 balance/status）"]
sinks: ["repo.updateById(entity)", "repo.save(entity)"]
required_sanitizers:
  - "白名單 DTO，僅可變更欄位"
  - "balance 只能經記帳路徑、status 只能經狀態機"
detect:
  static_queries: ["@RequestBody $Entity 直接 updateById/save"]
false_positive_checks: ["是否用 DTO + 逐欄賦值（非整個 entity 綁定）？"]
confirm_when: ["請求實體含敏感欄位且直接持久化"]
reproduce: ["body 夾帶 balance=999999、status=ACTIVE"]
oracle: ["INV-ST-02 餘額≠初始+Σ分錄；或 INV-ST-05 狀態機被繞過"]
fix_strategy: "白名單 DTO 與實體分離，逐欄賦值"
rule_ref: RULE-SEC-106
poc_ref: examples/vulnerable-settlement/MassAssignmentDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
直接用框架把請求綁定到實體/DTO（`@ModelAttribute`、`@RequestBody` 綁到 entity、Jackson 全欄位反序列化），請求中夾帶 `balance`、`status`、`isAdmin`、`fee` 等本不該由使用者設定的欄位即被寫入。

**觸發特徵**：
```java
// 特徵：請求直接綁定持久化實體，欄位無白名單
@PostMapping("/account/update")
public Result update(@RequestBody Account account) { // ← 含 balance/status 欄位
    accountMapper.updateById(account);               // ← 攻擊者送 balance=999999
}
```

**危害等級**：P0 — 直接竄改餘額/狀態/權限

**修復策略**：請求/回應 DTO 與持久化實體分離，僅白名單欄位可入
```java
public class AccountUpdateReq {     // 只含可變更欄位
    private String nickname; private String avatar;   // 無 balance/status
}
Account acc = accountMapper.selectById(currentUserId);
acc.setNickname(req.getNickname());                   // 顯式逐欄賦值
accountMapper.updateById(acc);
```

**反哺規則**：RULE-SEC-106

---

## PAT-SEC-107：請求重放（缺 nonce / 時間窗 / 一次性令牌）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-107
title: 請求重放（缺 nonce / 時間窗 / 一次性令牌）
status: active
confidence: medium
severity_base: P0
applies_to: [transfer, withdraw, order]
cwe: [CWE-294]
flows: [MF-02]
invariants: [INV-T-04]
sources: ["已簽章的資金請求（可被攔截重送）"]
sinks: ["transferService.transfer", "withdrawService.withdraw"]
required_sanitizers:
  - "nonce 一次性消費（setIfAbsent）"
  - "時間窗校驗"
  - "nonce 與金額/收款人一起簽章"
detect:
  static_queries: ["資金請求驗簽後執行，無 nonce/時間窗一次性消費"]
false_positive_checks: ["冪等鍵是否綁定請求簽章（非伺服器自產）？"]
confirm_when: ["同一封包可重送並重複生效"]
reproduce: ["原樣重送同一已簽章資金請求第二次"]
oracle: ["INV-T-04 重放後狀態改變（非零增益）"]
fix_strategy: "客戶端 nonce + 時間窗 + 簽章，伺服器一次性消費"
rule_ref: RULE-SEC-107
poc_ref: examples/vulnerable-settlement/ReplayDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
資金請求（提款、轉帳、下單）可被攔截後原樣重送（replay），系統重複執行。與冪等性 bug 不同：冪等防的是系統自己重送，這裡防的是**攻擊者主動重放**。冪等鍵若由伺服器產生而非綁定請求簽章，仍可被繞過。

**觸發特徵**：
```java
// 特徵：敏感操作無 nonce / timestamp / 一次性 token
@PostMapping("/transfer")
public Result transfer(@RequestBody TransferReq req) {
    return transferService.transfer(req);  // ← 同一封包重送 N 次 = 轉 N 次
}
```

**危害等級**：P0 — 重放放大資損

**修復策略**：客戶端產生 requestId（nonce）+ 時間窗 + 簽章，伺服器一次性消費
```java
if (Math.abs(now() - req.getTimestamp()) > 300_000)        // 5 分鐘窗
    throw new SecurityException("請求過期");
Boolean first = redis.opsForValue()
    .setIfAbsent("nonce:" + req.getNonce(), "1", Duration.ofMinutes(10));
if (Boolean.FALSE.equals(first))
    throw new SecurityException("重放偵測：nonce 已使用");
// nonce 與金額/收款人一起參與簽章，防止竄改後重放
```

**反哺規則**：RULE-SEC-107

---

## PAT-SEC-108：手動調帳/補償端點缺雙人覆核（maker-checker）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-108
title: 手動調帳/補償端點缺雙人覆核（maker-checker）
status: active
confidence: low
severity_base: P0
applies_to: [admin-adjust, manual-compensation, refund]
cwe: [CWE-862, CWE-732]
owasp_api: [API5]
flows: [MF-05]
invariants: [INV-T-01]
compliance: ["SOC2:CC6.3 職責分離", "AML:內控"]
sources: ["後台高權限調帳請求"]
sinks: ["walletService.credit/debit（admin 路徑）"]
required_sanitizers:
  - "maker-checker：checkerId != makerId"
  - "額度分級 + 強制理由 + append-only 審計"
detect:
  static_queries: ["admin 調帳端點單人即可生效，無覆核狀態流轉"]
  db_evidence: ["是否有待覆核狀態 + 雙人簽核紀錄？"]
false_positive_checks: ["是否已走 submit→approve 兩階段？"]
confirm_when: ["高權限動帳單一操作者可完成"]
reproduce: ["以單一 admin token 直接調帳"]
oracle: ["動帳無第二人覆核且無審計可追溯（INV-T-01）"]
fix_strategy: "maker-checker 雙人覆核 + 額度分級 + 審計"
rule_ref: RULE-SEC-108
poc_ref: examples/vulnerable-settlement/MakerCheckerDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
後台調帳、人工補單、手動結算修正等高權限資金端點，單一操作者即可完成，無第二人覆核、無金額上限、無強制審計理由。內部舞弊與帳號被盜的主要途徑。

**觸發特徵**：
```java
// 特徵：單人完成高權限調帳，無覆核流程
@PostMapping("/admin/adjustBalance")
public Result adjust(@RequestParam Long accountId, @RequestParam BigDecimal delta) {
    walletService.credit(accountId, delta);  // ← 一個 admin token 即可任意調帳
}
```

**危害等級**：P0 — 內部人員/被盜帳號直接掏空，且難追責

**修復策略**：maker-checker 雙人覆核 + 額度分級 + 強制理由 + 審計
```java
// maker 發起，進入待覆核狀態，不直接生效
AdjustmentRequest ar = adjustmentService.submit(accountId, delta, reason, makerId);
// checker（不同人）覆核；超過額度需更高層級
adjustmentService.approve(ar.getId(), checkerId);  // checkerId != makerId 強制校驗
// 全程寫入不可竄改審計（見 PAT-SEC-110）
```

**反哺規則**：RULE-SEC-108

---

## PAT-SEC-109：金融查詢/報表動態 SQL 注入

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-109
title: 金融查詢/報表動態 SQL 注入
status: active
confidence: high
severity_base: P0
applies_to: [report, query]
cwe: [CWE-89]
owasp_api: [API8]
flows: [MF-07]
sources: ["@RequestParam 篩選/排序欄位"]
sinks: ["字串拼接 SQL", "MyBatis ${} 插值"]
required_sanitizers:
  - "參數化 #{} / PreparedStatement"
  - "排序欄位白名單"
detect:
  static_queries: ["taint: 請求參數拼接進 SQL 字串或 ${}"]
false_positive_checks: ["是否全程參數化 + 排序白名單？"]
confirm_when: ["使用者可控值進入 SQL 字串拼接"]
reproduce: ["注入 ' OR '1'='1 / 竄改 ORDER BY"]
oracle: ["全表外洩或繞過 WHERE user_id 資料隔離"]
fix_strategy: "參數化 + 排序欄位白名單"
rule_ref: RULE-SEC-109
created: 2026-06-01
reproduced_count: 0
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
對帳報表、條件查詢用字串拼接 SQL，使用者可控的篩選條件（帳號、日期、幣別、排序欄位）未參數化。除竊取全表資料外，可被用於繞過 `WHERE user_id=` 的資料隔離。

**觸發特徵**：
```java
// 特徵：拼接 SQL / ${} 而非 #{}
String sql = "SELECT * FROM ledger WHERE account_id = " + accountId
           + " ORDER BY " + sortField;            // ← 注入點
// MyBatis: ORDER BY ${sortField}                 // ← ${} 危險
```

**危害等級**：P0 — 全帳本外洩 / 資料隔離繞過

**修復策略**：參數化 + 排序欄位白名單
```java
// #{} 參數化；排序欄位限白名單
private static final Set<String> SORTABLE = Set.of("created_at", "amount");
if (!SORTABLE.contains(sortField)) throw new ValidationException("非法排序欄位");
```

**反哺規則**：RULE-SEC-109（可由 Semgrep/CodeQL taint 規則自動偵測）

---

## PAT-SEC-110：金流操作缺乏不可竄改稽核軌跡與不可否認性

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-110
title: 金流操作缺乏不可竄改稽核軌跡與不可否認性
status: active
confidence: low
severity_base: P1
applies_to: [all-money-ops]
cwe: [CWE-778, CWE-285]
flows: [MF-01, MF-05]
invariants: [INV-T-01]
compliance: ["PCI:Req10 審計", "SOC2:CC7.2", "AML:交易紀錄保存"]
sources: ["資金變動操作"]
sinks: ["walletService.debit/credit（無審計）"]
required_sanitizers:
  - "append-only 審計（前後餘額/操作者/來源/原因/關聯交易）"
  - "審計表禁 UPDATE/DELETE，可加雜湊鏈"
detect:
  static_queries: ["資金變動後無 auditLog.append"]
  db_evidence: ["審計表是否 append-only（無 UPDATE/DELETE 權限）？"]
false_positive_checks: ["審計是否由 AOP/事件統一寫入？"]
confirm_when: ["資金 delta 無對應不可竄改審計分錄"]
reproduce: ["動帳後查無審計紀錄 / 審計可被竄改"]
oracle: ["INV-T-01 餘額 delta 無法追溯到唯一審計分錄"]
fix_strategy: "每筆資金變動寫 append-only 審計"
rule_ref: RULE-SEC-110
poc_ref: examples/vulnerable-settlement/AuditTrailDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
資金變動（誰、何時、從何 IP、變更前後餘額、原因、覆核人）未寫入**僅可追加（append-only）**的審計紀錄，或審計與業務同庫同權限可被一併竄改。事故發生後無法追責、無法重建、不符合監管與 AML 要求。

**觸發特徵**：
```java
// 特徵：動帳後無審計，或審計可被 UPDATE/DELETE
walletService.debit(accountId, amount);  // ← 無 before/after、無 operator、無原因
```

**危害等級**：P1（合規面常升 P0）— 無法追責、監管不過

**修復策略**：每筆資金變動寫入 append-only 審計（含前後餘額、操作者、來源、關聯交易），審計表禁 UPDATE/DELETE，並可加雜湊鏈防竄改
```java
auditLog.append(AuditEntry.builder()
    .accountId(accountId).operator(currentUserId).sourceIp(ip)
    .balanceBefore(before).balanceAfter(after).delta(amount)
    .reason(reason).correlationId(txnId).prevHash(lastHash).build());
```

**反哺規則**：RULE-SEC-110

---

## PAT-SEC-111：敏感金融資料寫入日誌/回應（餘額/卡號/PII 外洩）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-111
title: 敏感金融資料寫入日誌/回應（餘額/卡號/PII 外洩）
status: active
confidence: high
severity_base: P1
applies_to: [logging, api-response]
cwe: [CWE-532, CWE-200]
compliance: ["PCI:Req3 儲存保護", "個資法/GDPR 最小化"]
data_classification_ref: authorization-ownership-matrix#7   # P1/P2 敏感資料
sources: ["卡號/CVV/餘額/身分證/完整 token（L3/L4 敏感資料）"]
sinks: ["log.info(...)", "回傳 entity 的 API response"]
required_sanitizers:
  - "遮罩（PAN 遮罩、CVV 永不記）"
  - "回應走 DTO 白名單、敏感欄位 @JsonIgnore"
detect:
  static_queries: ["敏感欄位出現在 log 參數或 entity 直接回傳"]
false_positive_checks: ["是否已遮罩 / 走 DTO 白名單？"]
confirm_when: ["L3/L4 敏感資料進入日誌/回應/明文儲存"]
reproduce: ["觸發含敏感欄位的日誌或回應"]
oracle: ["敏感資料明文出現在日誌或 API 回應"]
fix_strategy: "遮罩 + 回應 DTO 白名單 + 禁記敏感欄位"
rule_ref: RULE-SEC-111
created: 2026-06-01
reproduced_count: 0
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
日誌、錯誤回應、API 回傳含完整卡號、CVV、餘額、身分證號、完整 token。日誌外洩或越權查詢即造成 PII/資金資訊洩漏。

**觸發特徵**：
```java
log.info("付款 card={} cvv={} balance={}", card, cvv, balance); // ← 明文敏感
return ResponseEntity.ok(account);  // ← 回應夾帶內部欄位（餘額/風控標記）
```

**危害等級**：P1 — 合規違規（PCI-DSS / GDPR / 個資法）+ 詐騙素材

**修復策略**：遮罩 + 回應 DTO 白名單 + 禁止記錄敏感欄位
```java
log.info("付款 card={} ", mask(card));   // 6224********1234；CVV 一律不記
// 回應只回 DTO，不回 entity；序列化層對敏感欄位 @JsonIgnore
```

**反哺規則**：RULE-SEC-111

---

## PAT-SEC-112：金流端點缺速率限制與提款速度監控（velocity）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-112
title: 金流端點缺速率限制與提款速度監控（velocity）
status: active
confidence: low
severity_base: P1
applies_to: [withdraw, otp, promo]
cwe: [CWE-799, CWE-307]
owasp_api: [API4]
flows: [MF-01]
compliance: ["AML:交易監控/可疑交易"]
sources: ["高頻敏感端點請求"]
sinks: ["withdraw", "OTP 驗證"]
required_sanitizers:
  - "端點速率限制"
  - "帳戶級 velocity（單日累計金額/筆數）"
  - "超閾值升級驗證/凍結"
detect:
  static_queries: ["敏感金流端點無 rateLimiter/velocityGuard"]
false_positive_checks: ["是否有 gateway/網關層速率限制？"]
confirm_when: ["金流端點無速率/velocity 控制"]
reproduce: ["腳本高頻提款 / 暴力 OTP"]
oracle: ["單位時間累計超出風控閾值未被攔"]
fix_strategy: "速率限制 + velocity 規則 + 異常升級"
rule_ref: RULE-SEC-112
poc_ref: examples/vulnerable-settlement/VelocityDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
提款、OTP 驗證、優惠領取端點無速率限制與**速度（velocity）監控**（單位時間累計金額/次數）。被盜帳號可在風控反應前快速掏空；OTP 可暴力破解。

**觸發特徵**：
```java
// 特徵：敏感端點無 rate limit / velocity 規則
@PostMapping("/withdraw") // ← 可每秒數十次；單日累計無上限監控
```

**危害等級**：P1 — 加速資損、放大其他漏洞

**修復策略**：端點速率限制 + 帳戶級速度規則 + 異常觸發升級驗證/凍結
```java
rateLimiter.acquire("withdraw:" + userId);           // 次數限制
velocityGuard.check(userId, amount);                 // 單日累計金額/筆數
// 超閾值 → 強制二次驗證 / 風控人工審 / 暫凍
```

**反哺規則**：RULE-SEC-112

---

## PAT-SEC-113：優惠/紅利可疊加套利（promo / 邏輯套利）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-113
title: 優惠/紅利可疊加套利（promo / 邏輯套利）
status: active
confidence: low
severity_base: P1
applies_to: [promo, coupon, referral, cashback]
cwe: [CWE-840]
flows: [MF-06]
invariants: [INV-TXN-05]
sources: ["優惠/券/推薦/返現請求"]
sinks: ["折扣計算", "credit 紅利/返現"]
required_sanitizers:
  - "互斥規則 + 單筆/單帳號上限 + 一次性消費"
  - "退款連動回收已發紅利"
  - "自我推薦偵測（推薦人 != 被推薦人 + 風控）"
detect:
  static_queries: ["折扣疊加無互斥/上限；退款不撤回已發紅利"]
false_positive_checks: ["是否走 promoEngine 互斥規則 + 上限？"]
confirm_when: ["優惠可疊加/重複領取/自我推薦套利"]
reproduce: ["疊多券至折後為負；先返現後退款薅羊毛"]
oracle: ["群體性資損 / 折後為負（INV-TXN-05 殘差守恆破壞）"]
fix_strategy: "互斥 + 上限 + 一次性 + 退款連動回收"
rule_ref: RULE-SEC-113
poc_ref: examples/vulnerable-settlement/PromoAbuseDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
折扣券、紅利、返現、推薦獎勵缺乏互斥/上限/一次性校驗，可疊加、可重複領取、可自我推薦套利，或利用「先返現後退款」薅羊毛。屬業務邏輯漏洞，靜態工具難抓，需業務規則 + 不變量。

**觸發特徵**：
```java
// 特徵：優惠疊加無互斥/總額上限；退款不回收已發紅利
total = total.subtract(coupon1).subtract(coupon2).subtract(cashback); // ← 可疊到負
// 退款路徑未撤銷已發放的返現/紅利
```

**危害等級**：P1 — 大規模薅羊毛，群體性資損

**修復策略**：互斥規則 + 單筆/單帳號上限 + 一次性消費 + 退款連動回收 + 自我推薦偵測
```java
promoEngine.applyExclusive(order, candidates);   // 同類互斥、取最優一張
if (discounted.signum() < 0) discounted = ZERO;  // 折後不為負
// 退款時連動撤銷已發放紅利；推薦人 != 被推薦人 且需風控評分
```

**反哺規則**：RULE-SEC-113

---

## PAT-SEC-114：冪等鍵可預測/低熵（越權覆蓋他人交易）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0）↓↓↓ -->
```yaml
id: PAT-SEC-114
title: 冪等鍵可預測/低熵（越權覆蓋他人交易）
status: active
confidence: medium
severity_base: P1
applies_to: [settlement, transfer, order]
cwe: [CWE-340, CWE-330]
flows: [MF-02]
invariants: [INV-T-02]
sources: ["可預測的冪等鍵/交易單號（自增 / 時間戳 / userId+date）"]
sinks: ["冪等鍵去重邏輯（Redis setIfAbsent / DB 唯一鍵）"]
required_sanitizers:
  - "高熵 requestId（UUID/隨機）"
  - "冪等鍵與金額/收款人一起簽章"
detect:
  static_queries: ["冪等鍵由可預測值組成（LocalDate.now / 自增 id）"]
false_positive_checks: ["鍵是否含高熵隨機 + 簽章綁定？"]
confirm_when: ["冪等鍵可被他人預測/猜測"]
reproduce: ["預測他人冪等鍵搶占/覆蓋其交易"]
oracle: ["越權干擾他人交易或繞過防重（INV-T-02）"]
fix_strategy: "高熵 requestId + 簽章綁定"
rule_ref: RULE-SEC-114
poc_ref: examples/vulnerable-settlement/IdempotencyKeyDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
冪等鍵或交易單號用自增 ID、時間戳、`userId+date` 等可預測值。攻擊者可預測他人冪等鍵，搶先占用或覆蓋他人交易，或讓自己的重複請求繞過冪等。

**觸發特徵**：
```java
String key = "settle:" + userId + ":" + LocalDate.now();   // ← 可預測
String orderNo = String.valueOf(idGenerator.next());       // ← 自增可猜
```

**危害等級**：P1 — 越權干擾他人交易 / 繞過防重

**修復策略**：冪等鍵綁定請求簽章與不可預測隨機數
```java
// 客戶端產生高熵 requestId，與金額/收款人一起簽章；伺服器以 (userId, requestId) 為冪等鍵
String key = "idem:" + userId + ":" + req.getRequestId(); // requestId = UUID/隨機
```

**反哺規則**：RULE-SEC-114

---

## 新增模式（模板）

```markdown
## PAT-SEC-{序號}：{模式名稱}

**描述**：{攻擊者視角的危害情境}

**觸發特徵**：
\`\`\`java
// Source → Sink 路徑與缺失的 Sanitizer
\`\`\`

**危害等級**：{P0/P1}

**修復策略**：
\`\`\`java
// 補上對應的清洗閘
\`\`\`

**偵測法（taint）**：Source = ...；Sink = ...；缺失 Sanitizer = ...
**反哺規則**：{RULE-SEC-NNN}
**來源事件**：{BUG-YYYY-NNN}
**新增日期**：{YYYY-MM-DD}
```

---

## 與閉環 Stage 的對應

- **Stage 0 THREAT-MODEL**：用本檔的攻擊類別對每條金流做 STRIDE + 濫用案例，產生「待驗證假設」
- **Stage 1 DETECT**：`agents/security-fraud-detector.md` 以 taint source→sink 比對本檔特徵
- **Stage 2.5 REPRODUCE**：安全發現需產出**攻擊 PoC**（負面測試 / 並發雙花 / 偽造回調），非僅功能測試
- **Stage 4 VERIFY**：驗收標準為「攻擊不再成功」+「資金不變量恆成立」（見 financial-invariants.md）
- **Stage 5 RECYCLE**：新模式回填本檔，並對應 RULE-SEC-1xx 與合規條目（PCI-DSS / AML / SOC2）

---

## 6. 安全編碼基準 (Secure Coding Baseline)

> 這是對具體代碼實現的最低要求，用於 `knowledge-writer` 驗收修復代碼。

### 6.1 金額處理
- **禁止使用 `double`/`float`**：必須使用 `BigDecimal` 並指定 `RoundingMode`，或使用分作為單位的 `Long`。
- **範圍校驗**：金額入參必須第一時間校驗 `> 0`。

### 6.2 併發與鎖
- **樂觀鎖優先**：涉及餘額更新必須使用 `version` 欄位。
- **虛擬線程安全 (Java 21)**：在使用 `Virtual Threads` 時，避免在 `synchronized` 塊中執行阻塞式 I/O (Pinning 問題)，優先使用 `ReentrantLock`。

### 6.3 密鑰與敏感信息
- **禁止硬編碼**：所有 API Key、Salt 必須從 Secrets Manager 讀取。
- **日誌脫敏**：卡號、手機號、用戶名必須在 Logback 配置中進行正則脫敏。

### 6.4 異常處理
- **禁止洩露堆棧**：生產環境 API 響應不得包含具體的代碼行號或框架版本。
- **具體異常捕捉**：優先捕捉 `FinancialException`, `AuthException`，避免通用的 `catch (Exception e)`。
