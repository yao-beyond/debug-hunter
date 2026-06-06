---
file_id: financial-bug-patterns
kind: pattern-catalog
status: active
schema_version: 1.0
last_reviewed: 2026-06-01
stale_after_days: 180
owner: knowledge-writer-agent
external_refs: ["CWE-681", "CWE-190", "CWE-362"]
---

# 金融 Bug 模式知識庫

> 檔案路徑：knowledge-base/financial-bug-patterns.md
> 更新規則：每次事後檢視後，由 `agents/knowledge-writer.md` 自動追加
> 格式：每個模式包含觸發條件、特徵碼、修復策略、反哺規則
> 最後更新：settlement-flow-sample 掃描後（知識沉澱版 v1.2）
> 相關文件：[ai-scan-false-positive-patterns.md](./ai-scan-false-positive-patterns.md) — AI 掃描誤報模式與驗證 SOP

---

## 如何使用

1. 偵測階段：載入此檔案，逐一比對程式碼中的觸發特徵
2. 分類階段：依模式的預設優先等級快速判定風險
3. 修復階段：直接套用對應的修復策略
4. 新增模式：發現新類型 Bug 後，在檔案末尾新增條目

---

## Pattern 索引

| 代碼 | 名稱 | 預設等級 | 來源事件 | 案例數 |
|------|------|---------|---------|--------|
| PAT-FIN-002 | double/float 處理金額 | P0 | 靜態分析 | — |
| PAT-FIN-003 | BigDecimal.equals() 比較 | P1 | 靜態分析 | — |
| PAT-FIN-004 | divide() 未指定 RoundingMode | P1 | 靜態分析 | — |
| PAT-FIN-005 | 多資產精度硬編碼 scale | P0 | 設計審查 | — |
| PAT-FIN-006 | 分配/拆分捨入殘差未處理（salami） | P0 | 業務分析 | — |
| PAT-FIN-007 | 時間戳單位混淆（ms/s）與時區邊界 | P1 | 設計審查 | — |
| PAT-FIN-008 | 金額以最小單位存 long 的整數溢位 | P1 | 設計審查 | — |
| PAT-CON-001 | Kafka 消費者缺乏冪等保護 | P0 | 壓力測試 · SettlementFlow.java | 2 🆕 |
| PAT-CON-002 | @Transactional 靜默失效 | P0 | 代碼審查 | — |
| PAT-CON-003 | 分散式鎖 TTL 設計缺陷 | P1 | 事後檢視 | 1 🆕 |
| PAT-CON-004 | 錢包批量讀取後並發更新無行鎖 | P0 | SettlementFlow.java | 1 🆕 |
| PAT-CON-005 | Retry 場景狀態標記非冪等設計 | P1 | SettlementFlow.java | 1 🆕 |
| PAT-SCH-001 | PowerJob 多 Worker 資料競爭 | P1 | 壓力測試 | — |
| PAT-BIZ-001 | 委託時間窗口競態條件 | P1 | 業務分析 | — |
| PAT-BIZ-002 | 結算結果缺乏業務合理性校驗 | P0 | 事後檢視 | — |
| PAT-BIZ-003 | 外部行情 API 失敗靜默返回預設值 | P0 | SettlementFlow.java | 1 🆕 |

---

## PAT-FIN-002：double/float 處理金額

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；correctness 類）↓↓↓ -->
```yaml
id: PAT-FIN-002
title: double/float 處理金額
status: active
confidence: high
severity_base: P0
category: correctness
applies_to: [amount, rate, fee, balance]
cwe: [CWE-681, CWE-1339]
antipattern:
  - "double/float 型別的金額欄位"
  - "BigDecimal.doubleValue() 後再計算"
  - "金額用 == 比較"
detect:
  static_queries:
    - "欄位名 ~ amount|price|fee|profit|balance|rate 且型別為 double/float/Double/Float"
    - "金融數值用 == 比較"
false_positive_checks: ["是否為非金額用途的 double（比例顯示/統計）？"]
confirm_when: ["金額/比率/餘額以 double/float 儲存或參與計算"]
fix_strategy: "全程 BigDecimal；比較用 compareTo"
rule_ref: RULE-FIN-003
semgrep_ref: rules/semgrep/financial-security.yml#no-double-float-for-money
poc_ref: examples/vulnerable-settlement/FloatMoneyDemo.java
created: 2026-06-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
使用 `double` 或 `float` 型別儲存或計算金融數值，導致浮點精度誤差累積。單筆誤差可能極小，但在高流量下大量累加後，誤差可達數元甚至更多。

**觸發特徵**：
```java
// 特徵 1：金額欄位型別為 double 或 float
private double amount;
private float profitRate;

// 特徵 2：BigDecimal 轉 double 後再計算
double profit = order.getAmount().doubleValue() * rate;

// 特徵 3：比較金額使用 ==
if (amount1 == amount2) { ... }
```

**修復策略**：PAT-FIN-002 → 使用策略 4（BigDecimal 精確計算）

```java
// ✅ 正確：全程 BigDecimal
private BigDecimal amount;
BigDecimal profit = amount.multiply(rate)
    .setScale(8, RoundingMode.HALF_DOWN);
// 比較用 compareTo
if (amount1.compareTo(amount2) == 0) { ... }
```

**反哺規則**：RULE-FIN-003

---

## PAT-FIN-003：BigDecimal.equals() 比較

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；correctness 類）↓↓↓ -->
```yaml
id: PAT-FIN-003
title: BigDecimal.equals() 比較
status: active
confidence: high
severity_base: P1
category: correctness
applies_to: [amount, fee]
cwe: [CWE-697]
antipattern: ["BigDecimal 變數呼叫 .equals()（含與 ZERO 比較）"]
detect:
  static_queries: ["BigDecimal 型別呼叫 .equals()"]
false_positive_checks: ["是否確為 BigDecimal（非其他型別 equals）？"]
confirm_when: ["BigDecimal 用 equals 比較數值（scale 敏感）"]
fix_strategy: "改用 compareTo() == 0"
rule_ref: RULE-FIN-004
created: 2026-06-01
reproduced_count: 0
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
`BigDecimal.equals()` 同時比較數值與 scale，導致 `1.0.equals(1.00)` 回傳 `false`，在金額比較邏輯中產生隱性錯誤。

**觸發特徵**：
```java
if (calculatedAmount.equals(expectedAmount)) { ... }
if (fee.equals(BigDecimal.ZERO)) { ... }
```

**修復策略**：
```java
// ✅ 使用 compareTo
if (calculatedAmount.compareTo(expectedAmount) == 0) { ... }
if (fee.compareTo(BigDecimal.ZERO) == 0) { ... }
```

**反哺規則**：RULE-FIN-004

---

## PAT-CON-001：Kafka 消費者缺乏冪等保護

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；concurrency 類）↓↓↓ -->
```yaml
id: PAT-CON-001
title: Kafka 消費者缺乏冪等保護
status: active
confidence: high
severity_base: P0
category: concurrency
applies_to: [kafka-consumer, settlement, credit]
cwe: [CWE-694, CWE-362]
invariants: [INV-T-02]
antipattern:
  - "@KafkaListener 內直接執行資金邏輯，無冪等防護"
  - "批次 hasError 控整批 ACK，無單筆冪等（變體 B）"
detect:
  static_queries: ["@KafkaListener/@RabbitListener 方法呼叫 settle/credit/debit，無 setIfAbsent/唯一鍵防重"]
  db_evidence: ["是否有唯一索引 / 狀態過濾排除已處理？"]
false_positive_checks: ["FP-003：DB 唯一索引/狀態過濾是否已防重？"]
confirm_when: ["MQ 消費資金操作無冪等鍵，重送會重複入帳"]
fix_strategy: "Redis setIfAbsent 第一層 + DB 狀態機/唯一鍵第二層；批次每筆獨立冪等鍵"
rule_ref: RULE-CON-001
related_rule: RULE-CON-006
poc_ref: knowledge-base/reproduce-scenarios.md#SCENE-CON-001
created: 2024-01-01
reproduced_count: 2
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
Kafka 的 at-least-once 語意保證訊息至少被投遞一次，在網路抖動、Rebalance、Consumer 重啟等情況下，同一訊息可能被重複投遞，若消費者沒有冪等保護，會導致重複結算、重複入帳。

**觸發特徵**：
```java
// 特徵 A：直接在 @KafkaListener 方法內執行業務邏輯，無冪等防護
@KafkaListener(topics = "settlement-topic")
public void onSettlement(SettlementMessage msg) {
    settlementService.doSettle(msg.getOrderId()); // ← 無防重
}
```

**⚠️ 變體 B（來自 SettlementFlow.java · 2024）：批次 hasError 不 ACK 造成部分雙重結算**

批次消費時，以 `AtomicBoolean hasError` 決定整批是否 ACK。
任一筆失敗 → 整批不 ACK → Kafka 重送整批 → 已成功的訂單再次結算。

```java
// 觸發特徵 B：hasError 控制整批 ACK，無單筆冪等
AtomicBoolean hasError = new AtomicBoolean(false);
Flux.fromIterable(batch)
    .flatMap(msg -> process(msg)
        .onErrorResume(ex -> {
            hasError.set(true);
            return Mono.empty(); // 繼續跑其他，成功的已入帳
        }))
    .doOnSuccess(_ -> {
        if (!hasError.get()) ack.acknowledge();
        // ← 不 ACK 時，已成功的訂單再次被重送處理
    }).subscribe();
```

**修復策略**：PAT-CON-001 → 使用策略 3（雙層冪等保護）

```java
// 變體 B 的修復：Runner 層級 Redis 防重 + DB 狀態確認
String idempotentKey = "settle:runner:" + runner.getId();
Boolean isFirst = redisTemplate.opsForValue()
    .setIfAbsent(idempotentKey, "processing", Duration.ofHours(24));
if (Boolean.FALSE.equals(isFirst)) {
    log.warn("Runner 已在處理或已結算，跳過 runnerId={}", runner.getId());
    return Mono.empty();
}
// 成功後改為 settled 狀態，7 天內永久防重
// 失敗時清除 key，允許 Kafka 重送重試
```

**反哺規則**：RULE-CON-001、**RULE-CON-006（新增·批次 Listener 單筆冪等）**

---

## PAT-CON-002：@Transactional 靜默失效

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；concurrency 類）↓↓↓ -->
```yaml
id: PAT-CON-002
title: "@Transactional 靜默失效"
status: active
confidence: high
severity_base: P0
category: concurrency
applies_to: [transaction, settlement]
cwe: [CWE-460, CWE-754]
antipattern:
  - "@Transactional 加在 private 方法"
  - "同類內部 this 呼叫繞過 Spring 代理"
  - "try-catch 吃掉異常致不回滾"
  - "未指定 rollbackFor，非 RuntimeException 不回滾"
detect:
  static_queries:
    - "@Transactional 標於 private 方法"
    - "@Transactional 方法 throws 受檢例外但無 rollbackFor"
    - "@Transactional 方法內 catch Exception 後未 rethrow"
false_positive_checks: ["是否確走 Spring 代理（非 self-invocation）？"]
confirm_when: ["事務標注落在會靜默失效的場景"]
fix_strategy: "public + rollbackFor=Exception.class + 不吞異常 + 跨方法走代理"
rule_ref: RULE-CON-002
related_rule: RULE-CON-003
created: 2024-01-01
reproduced_count: 0
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
Spring 的 `@Transactional` 在多種情況下會靜默失效（不拋出錯誤，但事務根本沒有生效），導致部分資料更新成功、部分失敗，形成資料不一致的狀態。

**觸發特徵**：
```java
// 失效場景 1：private 方法加 @Transactional
@Transactional
private void doSettle() { ... }

// 失效場景 2：同類內部直接呼叫（繞過 Spring 代理）
public void settle() {
    this.doSettle(); // ← 繞過代理，@Transactional 不生效
}

// 失效場景 3：異常被吃掉，事務不回滾
@Transactional
public void settle() {
    try {
        updateOrder();
        updateWallet(); // 失敗...
    } catch (Exception e) {
        log.error("錯誤", e); // ← 吃掉異常，資料半寫入！
    }
}

// 失效場景 4：未指定 rollbackFor，非 RuntimeException 不回滾
@Transactional
public void settle() throws IOException { ... }
```

**修復策略**：
```java
// ✅ 正確：public 方法、明確 rollbackFor、不吃異常
@Transactional(rollbackFor = Exception.class,
               isolation = Isolation.READ_COMMITTED,
               timeout = 30)
public SettlementResult doSettle(Long orderId) {
    // 不用 try-catch 吃掉異常
    // 讓異常自然傳播，觸發事務回滾
}
```

**反哺規則**：RULE-CON-002、RULE-CON-003

---

## PAT-BIZ-002：結算結果缺乏業務合理性校驗

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；business 類）↓↓↓ -->
```yaml
id: PAT-BIZ-002
title: 結算結果缺乏業務合理性校驗
status: active
confidence: medium
severity_base: P0
category: business
applies_to: [settlement, payout]
cwe: [CWE-840]
invariants: [INV-TXN-06]
antipattern: ["計算完直接 credit 入帳，無收益率/單筆上限/非負校驗"]
detect:
  static_queries: ["settle 方法在 credit/debit 前無 settlementGuard.validate 類業務守衛"]
false_positive_checks: ["FP-001：校驗是否在 enum lambda / delegate 間接路徑？"]
confirm_when: ["結算入帳前無業務邊界守衛"]
fix_strategy: "結算前 settlementGuard.validate（收益率上限 / 單筆賠付上限 / 非負）"
rule_ref: RULE-BIZ-001
created: 2024-01-01
reproduced_count: 0
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
結算服務計算出結果後，缺乏對「業務合理性」的最終校驗。即使計算邏輯存在 Bug，也沒有任何機制能在資金真正入帳前攔截異常。

**觸發特徵**：
```java
// 特徵：計算完直接入帳，沒有業務邊界校驗
BigDecimal profit = calculateProfit(order, rate);
walletService.credit(order.getUserId(), profit); // ← 無校驗
```

**修復策略**：PAT-BIZ-002 → 使用策略 5（業務合理性守衛）

```java
// ✅ 正確：結算前強制校驗
settlementGuard.validate(order, profit);
walletService.credit(order.getUserId(), profit);

// SettlementGuard 的實作
public void validate(Order order, BigDecimal profit) {
    // 收益不能為負
    if (profit.compareTo(ZERO) < 0)
        throw new SettlementValidationException("收益為負數");

    // 收益率合理性（最關鍵的防線）
    BigDecimal impliedRate = profit.divide(order.getAmount(), 6, HALF_UP);
    if (impliedRate.compareTo(maxProfitRate) > 0) {
        alertService.sendCriticalAlert("結算收益率異常: " + impliedRate);
        throw new SettlementValidationException("收益率超出上限");
    }

    // 單筆賠付上限
    if (profit.compareTo(maxSinglePayout) > 0)
        throw new SettlementValidationException("單筆賠付超出上限");
}
```

**反哺規則**：RULE-BIZ-001

---

## PAT-CON-004：錢包批量讀取後並發更新無行鎖

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；concurrency 類）↓↓↓ -->
```yaml
id: PAT-CON-004
title: 錢包批量讀取後並發更新無行鎖
status: active
confidence: high
severity_base: P0
category: concurrency
applies_to: [wallet, batch-settlement]
cwe: [CWE-362, CWE-567]
invariants: [INV-ST-02]
antipattern:
  - "batchFindWallets 讀進記憶體 Map（無 FOR UPDATE）"
  - "記憶體改餘額後 batchUpWallets 覆蓋寫回（無版本號）"
  - "上游高並發 flatMap(..., N)"
detect:
  static_queries: ["batchFindWallets → 記憶體改餘額 → batchUpWallets，無 FOR UPDATE/version 且並發度>1"]
  db_evidence: ["更新是否有 WHERE version=? 或 SELECT FOR UPDATE？"]
false_positive_checks: ["是否已用樂觀鎖 version + retry？"]
confirm_when: ["並發批次持同一錢包快照覆蓋寫回"]
fix_strategy: "悲觀鎖 FOR UPDATE（固定順序防死鎖）或樂觀鎖 version + retryIds"
rule_ref: RULE-CON-004
poc_ref: knowledge-base/reproduce-scenarios.md#SCENE-CON-002
created: 2024-01-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
批次結算時，先將多個錢包讀取進記憶體 Map（`batchFindWallets`），在記憶體中對各錢包餘額進行修改，最後批量寫回（`batchUpWallets`）。
在高並發場景下（如 `flatMap(..., 10)` 同時跑 10 個批次），兩個批次可能讀取到同一個錢包的相同快照，各自計算後批量覆蓋寫回，導致**後寫者覆蓋先寫者的更新，餘額憑空消失**。

**觸發特徵**：
```java
// 特徵 1：批量讀錢包進 Map（無 FOR UPDATE）
walletService.batchFindWallets(walletKeys)
    .forEach(wallet -> walletMap.put(key, wallet));  // ← 記憶體快照

// 特徵 2：在記憶體 Map 中修改餘額
wallet.setBalance(wallet.getBalance().add(profit));  // ← 基於快照計算

// 特徵 3：批量寫回（覆蓋型更新，無版本號）
walletService.batchUpWallets(new ArrayList<>(walletMap.values()));
// ← 若兩個批次持有同一錢包快照，後者覆蓋前者

// 特徵 4：上游有高並發設定
.flatMap(batch -> batchSettleOrders(...), 10)  // ← 並發度 10
```

**危害等級**：P0 — 錢包餘額直接丟失，且不易被即時發現（需對帳才能察覺）

**修復策略**：選擇其一

```java
// 方案 A：悲觀鎖（SELECT FOR UPDATE）
// SQL: SELECT * FROM wallet_tbl
//      WHERE (account_id, coin_id) IN (...)
//      ORDER BY account_id, coin_id   ← 固定順序防死鎖
//      FOR UPDATE
walletService.batchFindWalletsForUpdate(walletKeys)  // ← 加鎖版本
    .forEach(wallet -> walletMap.put(key, wallet));

// 方案 B：樂觀鎖（version 欄位）+ 搭配現有 retryIds 重試
// SQL: UPDATE wallet_tbl SET balance=?, version=version+1
//      WHERE id=? AND version=?
int updated = walletMapper.updateWithVersion(wallet);
if (updated == 0) {
    retryIds.add(AccountCoinKey.of(wallet.getAccountId(), wallet.getCoinId()));
}
```

**反哺規則**：RULE-CON-004

**來源事件**：SettlementFlow.java 掃描 BUG-EXAMPLE-102
**新增日期**：2024-01
**新增人員**：Knowledge Writer Agent

---

## PAT-BIZ-003：外部行情 API 失敗靜默返回預設值

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；business 類）↓↓↓ -->
```yaml
id: PAT-BIZ-003
title: 外部行情 API 失敗靜默返回預設值
status: active
confidence: high
severity_base: P0
category: business
applies_to: [quote, fx, settlement]
cwe: [CWE-394, CWE-754]
invariants: [INV-TXN-04]
antipattern: ["外部 API resp.code!=1 時靜默 return BigDecimal.ZERO 繼續結算"]
detect:
  static_queries: ["fetch/get 外部價格方法失敗時 return ZERO/null/預設值，且返回值參與結算計算"]
false_positive_checks: ["返回值是否僅用於展示（非計算）？"]
confirm_when: ["結算依賴的外部資料失敗時以預設值繼續執行"]
fix_strategy: "外部失敗拋例外（禁靜默預設）+ 返回值非零/非負校驗"
rule_ref: RULE-BIZ-002
poc_ref: knowledge-base/reproduce-scenarios.md#SCENE-BIZ-001
related: [PAT-SEC-104, PAT-SEC-105]
created: 2024-01-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
呼叫外部行情 API 取得開盤/收盤價時，若 API 回傳錯誤狀態碼，直接靜默返回 `BigDecimal.ZERO` 作為預設值繼續執行結算流程。
當開盤價與收盤價皆為 ZERO 時，比較結果為「平局（result=0）」，導致**所有相關訂單以錯誤結果結算**，且不產生任何錯誤日誌，極難被發現。

**觸發特徵**：
```java
// 特徵：API 失敗時靜默返回 ZERO，不拋出例外
private Mono<QuoteVo> fetchQuote(String code, long ts) {
    return Mono.fromCallable(() -> {
        ResponseResult resp = priceApi.getOpenPrice(code, ts);
        if (resp.getCode() != 1) {
            return new QuoteVo(code, BigDecimal.ZERO); // ← 靜默吞錯
        }
        return objectMapper.convertValue(resp.getData(), QuoteVo.class);
    });
}

// 後續直接使用返回值計算結算方向
int i = runner.getClosePrice().compareTo(runner.getOpenPrice()); // 0 vs 0
int result = (i == 0 ? 0 : ...);  // ← 錯誤平局
```

**危害等級**：P0 — 整期所有訂單以錯誤方向結算，資損與實際行情完全脫鉤

**修復策略**：API 失敗必須拋出例外，禁止靜默返回預設值

```java
private Mono<QuoteVo> fetchQuote(String code, long ts) {
    return Mono.fromCallable(() -> {
        ResponseResult resp = priceApi.getOpenPrice(code, ts);

        // ✅ API 失敗：拋出例外，讓上層決定重試或暫停結算
        if (resp.getCode() != 1) {
            throw new PriceApiException(
                String.format("行情 API 失敗 code=%s ts=%d resp=%s", code, ts, resp));
        }

        QuoteVo vo = objectMapper.convertValue(resp.getData(), QuoteVo.class);

        // ✅ 額外校驗：價格不能為零或負數
        if (vo.getOpen() == null || vo.getOpen().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PriceApiException(
                String.format("行情價格異常（≤0）code=%s ts=%d price=%s", code, ts, vo.getOpen()));
        }

        return vo;
    }).subscribeOn(Schedulers.boundedElastic());
}
```

**推廣原則**：凡是結算流程依賴的外部 API（行情、匯率、手續費率），失敗必須拋出例外，讓結算流程進入重試或人工介入，不允許以任何預設值代替真實數據繼續執行。

**反哺規則**：RULE-BIZ-002

**來源事件**：SettlementFlow.java 掃描 BUG-EXAMPLE-103
**新增日期**：2024-01
**新增人員**：Knowledge Writer Agent

---

## PAT-CON-005：Retry 場景下狀態標記使用非冪等切換設計

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；concurrency 類）↓↓↓ -->
```yaml
id: PAT-CON-005
title: Retry 場景下狀態標記使用非冪等切換設計
status: active
confidence: high
severity_base: P1
category: concurrency
applies_to: [retry, settlement-status]
cwe: [CWE-696]
antipattern: ["catch/onErrorResume 內呼叫 changeType()/toggle 切換型方法，且同層有 retryWhen/Retry.backoff"]
detect:
  static_queries: ["切換型方法（changeType/toggle/flip）在 catch 內，且方法/上層有重試機制"]
false_positive_checks: ["是否為單次執行（無重試）流程？"]
confirm_when: ["重試流程中失敗時呼叫切換型狀態方法（結果依失敗次數奇偶）"]
fix_strategy: "改單向冪等標記（markError/set）；或 onErrorResume 集中只設定一次"
rule_ref: RULE-CON-005
poc_ref: knowledge-base/reproduce-scenarios.md#SCENE-CON-003
created: 2024-01-01
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
在有重試機制（如 `Retry.backoff(3, ...)`）的響應式流程中，失敗時呼叫 `changeType()` 等**切換型**（toggle）狀態方法。
每次重試失敗都會再次呼叫，導致狀態在每次重試間不斷翻轉，最終狀態取決於失敗次數的奇偶性，**不可預測且難以除錯**。

**觸發特徵**：
```java
// 特徵：切換型方法 + 重試機制共存
} catch (Exception e) {
    countDto.changeType();       // ← 切換：失敗第1次 → 狀態A
    throw new RuntimeException(e);
}
.retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))
// 第2次失敗 → changeType() → 狀態B（翻回去）
// 第3次失敗 → changeType() → 狀態A（又翻）
// 最終狀態：取決於重試次數，不可預測
```

**危害等級**：P1 — 結算狀態標記錯誤，可能導致本應標記失敗的 Runner 被標記為成功

**修復策略**：切換型改為單向冪等標記

```java
// ✅ 方案 A：用單向設定取代切換
} catch (Exception e) {
    log.error("批次交易失敗，runnerId={}", runner.getId(), e);
    countDto.markError();   // ← 只設定為 error，不做 toggle
    throw new RuntimeException(e);
}

// CountDto 的 markError 實作
public void markError() {
    this.resultType.set(1); // 冪等：呼叫幾次都是同一個結果
}

// ✅ 方案 B：onErrorResume 集中處理，catch 區塊不改狀態
.retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))
.onErrorResume(ex -> {
    log.error("重試3次後仍失敗，runnerId={}", runner.getId(), ex); // ← 補上 ex
    countDto.getResultType().set(1);  // 只在最終失敗時設定一次
    return Mono.empty();
})
```

**推廣原則**：在任何有重試機制的流程中，失敗時修改的狀態必須是**冪等的**（無論呼叫幾次，結果相同）。切換型（toggle）的狀態修改只適用於無重試的單次執行流程。

**反哺規則**：RULE-CON-005

**來源事件**：SettlementFlow.java 掃描 BUG-EXAMPLE-104
**新增日期**：2024-01
**新增人員**：Knowledge Writer Agent

---

## PAT-FIN-005：多資產精度硬編碼 scale

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；correctness 類）↓↓↓ -->
```yaml
id: PAT-FIN-005
title: 多資產精度硬編碼 scale
status: active
confidence: medium
severity_base: P0
category: correctness
applies_to: [multi-asset, settlement]
cwe: [CWE-681]
invariants: [INV-TXN-02]
antipattern: ["setScale(<常數>, ...) 對所有資產一視同仁"]
detect:
  static_queries: ["setScale(常數,...) 在金額路徑，scale 非來自資產中繼資料"]
false_positive_checks: ["scale 是否來自 assetMeta.getScale / coinConfig？"]
confirm_when: ["金額精度硬編碼常數，未依資產定義（BTC 8 / JPY 0）"]
fix_strategy: "scale 由資產中繼資料決定 + 入庫精度校驗"
rule_ref: RULE-FIN-006
created: 2026-06-01
reproduced_count: 0
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
不同資產的小數精度不同（BTC 8 位、USDT 6 位、JPY 0 位、TWD 0 位）。程式中硬編碼 `setScale(8, ...)` 對所有資產一視同仁，導致：對 JPY 產生不存在的小數、對需要 8 位的幣別被截斷、跨幣別比較與加總出錯。settlement-checklist A 類「統一 scale 8 位」本身在多資產系統就是個錯誤假設。

**觸發特徵**：
```java
// 特徵：scale 寫死，未依資產定義
BigDecimal profit = amount.multiply(rate).setScale(8, RoundingMode.HALF_DOWN); // ← 所有幣別都 8
if (jpyAmount.scale() == 8) { ... } // ← JPY 不該有小數
```

**危害等級**：P0 — 跨幣別精度錯誤，累積資損且難對帳

**修復策略**：scale 由資產中繼資料決定
```java
int scale = assetMeta.getScale(coinId);            // 由資產定義取得
BigDecimal profit = amount.multiply(rate).setScale(scale, assetMeta.getRounding(coinId));
// 入庫前校驗：金額 scale 不得超過資產定義
if (amount.stripTrailingZeros().scale() > scale)
    throw new ValidationException("金額精度超出資產 " + coinId + " 定義");
```

**反哺規則**：RULE-FIN-006（對應不變量 INV-TXN-02）

---

## PAT-FIN-006：分配/拆分捨入殘差未處理（salami slicing）

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；correctness 類）↓↓↓ -->
```yaml
id: PAT-FIN-006
title: 分配/拆分捨入殘差未處理（salami slicing）
status: active
confidence: medium
severity_base: P0
category: correctness
applies_to: [allocation, split, distribution, refund]
cwe: [CWE-682, CWE-1339]
invariants: [INV-TXN-05, INV-ST-03]
antipattern: ["迴圈內逐項 total×ratio 捨入後入帳，迴圈後無 Σ=total 校驗"]
detect:
  static_queries: ["分配迴圈 setScale 後無總和守恆斷言/殘差歸位"]
false_positive_checks: ["是否有最後一方吃殘差 / Σ 斷言？"]
confirm_when: ["一拆多金額運算後總和可能 != 原額"]
fix_strategy: "最大餘額法 / 殘差歸位（last-takes-remainder）+ 總和守恆斷言"
rule_ref: RULE-FIN-007
created: 2026-06-01
reproduced_count: 0
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
把一筆總額分配給多方（分潤、拆單、按比例退款）時，各方 `總額 × 比例` 個別捨入後，加總**不等於**原總額，產生殘差（少了/多了 1 個最小單位）。殘差若無明確歸屬，長期累積即「salami slicing」吞錢或造錢，且違反守恆。

**觸發特徵**：
```java
// 特徵：逐項捨入後未校驗總和 = 原總額
for (Party p : parties) {
    BigDecimal share = total.multiply(p.getRatio())
        .setScale(scale, RoundingMode.HALF_UP);    // ← 各自捨入
    credit(p, share);                              // Σshare 可能 ≠ total
}
```

**危害等級**：P0 — 累積資損/造錢，違反 INV-TXN-05 與 INV-ST-03

**修復策略**：最大餘額法 / 殘差歸位（last-takes-remainder）
```java
BigDecimal allocated = ZERO;
for (int i = 0; i < parties.size(); i++) {
    BigDecimal share = (i == parties.size() - 1)
        ? total.subtract(allocated)                       // 最後一方吃殘差，保證守恆
        : total.multiply(parties.get(i).getRatio()).setScale(scale, HALF_DOWN);
    allocated = allocated.add(share);
    credit(parties.get(i), share);
}
assert allocated.compareTo(total) == 0;                   // INV-TXN-05
```

**推廣原則**：任何「一拆多」或「多合一」的金額運算，運算後必須斷言總和守恆，殘差需有明確且一致的歸屬規則。

**反哺規則**：RULE-FIN-007

---

## PAT-FIN-007：時間戳單位混淆（ms/s）與時區邊界

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；correctness 類）↓↓↓ -->
```yaml
id: PAT-FIN-007
title: 時間戳單位混淆（ms/s）與時區邊界
status: active
confidence: medium
severity_base: P1
category: correctness
applies_to: [settlement, interest, quote]
cwe: [CWE-685, CWE-367]
invariants: [INV-T-03]
antipattern:
  - "裸 long 時間戳跨方法傳遞（ms/s 不明）"
  - "LocalDate.now() / 系統預設時區計算結算日"
detect:
  static_queries: ["秒/毫秒混用；用系統預設時區計算帳務日"]
false_positive_checks: ["單位是否型別化（Instant）/ 時區是否固定？"]
confirm_when: ["時間戳單位不明或結算日用系統時區"]
fix_strategy: "單位型別化（Instant）+ 固定業務/交易所時區"
rule_ref: RULE-FIN-008
related: time-window-cutoff-calendar-rules
created: 2026-06-01
reproduced_count: 0
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
結算依賴時間戳判定開盤/收盤、T+1、利息計息日。秒（10 位）與毫秒（13 位）混用，或用系統預設時區處理跨日邊界，導致取錯價格區間、結算日歸屬錯誤、DST 當天計息錯誤。PAT-BIZ-003 的 `fetchQuote(code, ts)` 即潛在風險點。

**觸發特徵**：
```java
// 特徵 1：ms/s 混用
long ts = System.currentTimeMillis();          // 13 位 ms
priceApi.getOpenPrice(code, ts);               // ← API 預期秒？單位未明確
new Date(epochSeconds);                         // ← 把秒當毫秒

// 特徵 2：用預設時區算結算日
LocalDate settleDate = LocalDate.now();        // ← 依伺服器時區，跨日邊界錯
```

**危害等級**：P1 — 取錯價格區間 / 結算日歸屬錯誤，定向可被利用

**修復策略**：單位顯式化 + 固定結算時區
```java
Instant ts = Instant.now();                                  // 型別即語意，避免裸 long
long epochMillis = ts.toEpochMilli();
// 結算日固定以交易所/業務時區計算
LocalDate settleDate = ts.atZone(EXCHANGE_ZONE).toLocalDate();
```

**反哺規則**：RULE-FIN-008

---

## PAT-FIN-008：金額以最小單位存 long 的整數溢位

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；correctness 類）↓↓↓ -->
```yaml
id: PAT-FIN-008
title: 金額以最小單位存 long 的整數溢位
status: active
confidence: medium
severity_base: P1
category: correctness
applies_to: [amount-minor-unit, aggregation]
cwe: [CWE-190]
antipattern: ["long 金額直接 × 或 += 聚合，無溢位防護"]
detect:
  static_queries: ["long 型金額用 * 或 += 聚合，未用 Math.*Exact / BigInteger / BigDecimal"]
false_positive_checks: ["金額量級是否遠小於 Long.MAX？聚合是否已用 BigInteger？"]
confirm_when: ["long 金額乘法/大量聚合無溢位防護"]
fix_strategy: "Math.multiplyExact/addExact 或 BigInteger/BigDecimal 聚合"
rule_ref: RULE-FIN-009
created: 2026-06-01
reproduced_count: 0
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
為效能改用 `long`（聰）儲存金額。大額聚合（總資產、平台總額、批次加總）可能超過 `Long.MAX_VALUE`，或乘法中間值溢位，靜默回繞成負/錯值。

**觸發特徵**：
```java
// 特徵：long 金額直接相乘/累加，無溢位防護
long total = 0;
for (long amt : amounts) total += amt;          // ← 大量累加可能溢位
long fee = amount * rateBps / 10000;            // ← amount * rateBps 中間值溢位
```

**危害等級**：P1 — 大額/聚合場景金額回繞，極端但破壞性高

**修復策略**：聚合與乘法用 `BigInteger`/`Math.*Exact`，或全程 BigDecimal
```java
long fee = Math.multiplyExact(amount, rateBps) / 10000;   // 溢位即拋例外
// 聚合用 BigInteger 或 BigDecimal
BigInteger total = amounts.stream().map(BigInteger::valueOf)
    .reduce(BigInteger.ZERO, BigInteger::add);
```

**反哺規則**：RULE-FIN-009

---

## PAT-FIN-004：divide() 未指定 RoundingMode

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；correctness 類）↓↓↓ -->
```yaml
id: PAT-FIN-004
title: divide() 未指定 RoundingMode
status: active
confidence: high
severity_base: P1
category: correctness
applies_to: [rate, fee, allocation, settlement]
cwe: [CWE-682, CWE-248]
antipattern:
  - "BigDecimal.divide(divisor) 未指定 scale 與 RoundingMode"
  - "divide 只給 RoundingMode 未給 scale（沿用左運算元 scale，非預期）"
detect:
  static_queries: ["BigDecimal.divide() 呼叫的引數數量 < 3（缺 scale+RoundingMode）"]
false_positive_checks: ["除數是否恆為 10 的次方（可整除，理論上不拋）？仍建議顯式 scale"]
confirm_when: ["金額/比率除法未明確 scale + RoundingMode"]
fix_strategy: "divide(divisor, scale, RoundingMode.HALF_UP)，scale 依資產精度"
rule_ref: RULE-FIN-005
created: 2026-06-07
reproduced_count: 0
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
`BigDecimal.divide(divisor)` 未指定 scale 與 RoundingMode 時，若除不盡會拋 `ArithmeticException: Non-terminating decimal expansion`，導致結算流程中斷；即使只給 RoundingMode 而未給 scale，也會沿用左運算元的 scale，產生非預期精度。

**觸發特徵**：
```java
BigDecimal rate = profit.divide(principal);                 // ← 除不盡直接拋例外
BigDecimal x = a.divide(b, RoundingMode.HALF_UP);           // ← 未定 scale，精度不可控
```

**修復策略**：
```java
BigDecimal rate = profit.divide(principal, 8, RoundingMode.HALF_UP); // 明確 scale + mode
```

**反哺規則**：RULE-FIN-005

---

## PAT-CON-003：分散式鎖 TTL 設計缺陷

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；concurrency 類）↓↓↓ -->
```yaml
id: PAT-CON-003
title: 分散式鎖 TTL 設計缺陷
status: active
confidence: medium
severity_base: P1
category: concurrency
applies_to: [distributed-lock, settlement, scheduler]
cwe: [CWE-667, CWE-362]
invariants: [INV-T-02]
antipattern:
  - "鎖 TTL 固定且短於業務執行時間（鎖提前過期）"
  - "無 watchdog 自動續租"
  - "釋放鎖未校驗持有者 token（finally 直接 del 誤刪他人鎖）"
detect:
  static_queries:
    - "setIfAbsent(key, v, 固定短 TTL) 後執行長業務"
    - "finally 區塊直接 redis.delete(lockKey)，未比對持有者 value"
false_positive_checks: ["是否使用 Redisson 等自帶 watchdog 續租？"]
confirm_when: ["鎖 TTL < 最長業務時間，或釋放未校驗持有者"]
fix_strategy: "TTL > 最長執行時間 + watchdog 續租；釋放用 Lua 校驗 value==token 才 del"
rule_ref: RULE-CON-008
poc_ref: examples/vulnerable-settlement/LockTtlDemo.java
created: 2026-06-07
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
分散式鎖（Redis SETNX / Redisson）的 TTL 設計不當：TTL 短於業務執行時間 → 鎖在業務完成前過期，第二個節點取得鎖進入臨界區，造成並發動帳；或缺 watchdog 續租；或釋放鎖時未校驗持有者，誤刪其他節點剛取得的鎖。

**觸發特徵**：
```java
redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(5)); // ← TTL 固定且短
try {
    doSettle();                  // 執行時間可能 > 5s → 鎖已過期
} finally {
    redis.delete(key);           // ← 不校驗持有者，可能刪到他人鎖
}
```

**修復策略**：
```java
// 方案 A：Redisson 自帶 watchdog 自動續租（鎖未釋放前持續延長 TTL）
// 方案 B：TTL > 最長執行時間；釋放用 Lua 原子校驗持有者
//   if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end
```

**反哺規則**：RULE-CON-008

---

## PAT-SCH-001：PowerJob 多 Worker 資料競爭

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；concurrency 類）↓↓↓ -->
```yaml
id: PAT-SCH-001
title: PowerJob 多 Worker 資料競爭
status: active
confidence: medium
severity_base: P1
category: concurrency
applies_to: [scheduler, powerjob, batch-settlement]
cwe: [CWE-362, CWE-694]
invariants: [INV-T-02]
antipattern:
  - "多 Worker 廣播/叢集模式各自撈全量待處理資料"
  - "無分片鍵隔離（shardIndex/shardTotal）"
  - "單筆無冪等鍵 / 無 DB 狀態 CAS"
detect:
  static_queries:
    - "排程 handler 撈 pending 清單後直接處理，未依 shardIndex 取模隔離"
    - "排程資金操作無 setIfAbsent/狀態 CAS"
  db_evidence: ["是否有狀態過濾/唯一鍵排除已處理？"]
false_positive_checks: ["是否單機排程或已用分片參數隔離？"]
confirm_when: ["多 Worker 並行處理同一批資料且無分片/冪等"]
fix_strategy: "分片鍵隔離（id % shardTotal == shardIndex）+ 單筆冪等鍵 + DB 狀態 CAS"
rule_ref: RULE-SCH-001
poc_ref: examples/vulnerable-settlement/SchedulerRaceDemo.java
created: 2026-06-07
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
分散式排程框架（PowerJob / xxl-job / Quartz cluster）以廣播或叢集模式執行時，多個 Worker 並行跑同一任務。若各 Worker 都撈取全量待處理資料、且無分片隔離與冪等保護，同一批訂單會被多個 Worker 重複結算/重複入帳。

**觸發特徵**：
```java
@PowerJobHandler(name = "settleJob")     // 廣播模式：每個 Worker 都執行
public ProcessResult process(TaskContext ctx) {
    List<Order> orders = orderMapper.selectPending();   // ← 各 Worker 撈到同一批
    orders.forEach(settlementService::settle);          // ← 無分片/冪等 → 重複結算
    return new ProcessResult(true);
}
```

**修復策略**：
```java
// 分片隔離：只處理屬於本 Worker 分片的資料
int idx = ctx.getShardIndex(), total = ctx.getShardTotal();
List<Order> mine = orderMapper.selectPendingByShard(idx, total); // WHERE id % total = idx
// 再加單筆冪等鍵 + 狀態 CAS（UPDATE ... WHERE status='PENDING'）
```

**反哺規則**：RULE-SCH-001

---

## PAT-BIZ-001：委託時間窗口競態條件

<!-- ↓↓↓ 機器可讀區塊（遵循 knowledge-schema.md v1.0；business 類）↓↓↓ -->
```yaml
id: PAT-BIZ-001
title: 委託時間窗口競態條件
status: active
confidence: medium
severity_base: P1
category: business
applies_to: [order, trading-window, cutoff]
cwe: [CWE-367, CWE-362]
invariants: [INV-ST-05, INV-T-03]
antipattern:
  - "isOpen() 檢查與接單之間窗口已關（check-then-act 跨窗口邊界）"
  - "用伺服器本地時間/可信前端時間判斷窗口"
  - "跨 cutoff 的委託被歸錯結算期"
detect:
  static_queries:
    - "marketStatus.isOpen()/inWindow() 檢查與 order.accept 之間非原子"
    - "用 LocalDateTime.now() 系統時區判斷交易窗口/cutoff"
false_positive_checks: ["接單是否已用帶窗口條件的原子 SQL？"]
confirm_when: ["窗口判斷與接單非原子，或 cutoff 邊界歸期不明確"]
fix_strategy: "伺服器權威時間 + 接單原子條件（WHERE window_open AND now<cutoff）；cutoff 以交易所時區明確歸期"
rule_ref: RULE-BIZ-003
poc_ref: examples/vulnerable-settlement/TradingWindowRaceDemo.java
related: [time-window-cutoff-calendar-rules, PAT-SEC-103]
created: 2026-06-07
reproduced_count: 1
```
<!-- ↑↑↑ 機器可讀區塊結束 ↑↑↑ -->

**描述**：
下單/委託在「時間窗口邊界」（開盤、收盤、結算 cutoff）發生競態：先 `isOpen()` 檢查通過、到實際接單之間窗口已關閉（check-then-act）；或委託在 cutoff 瞬間進來，被歸到錯誤的結算期。也常因用伺服器本地時間或信任前端時間判斷窗口而出錯。

**觸發特徵**：
```java
if (marketStatus.isOpen()) {        // T1：檢查窗口開
    // ... 窗口在此關閉（到了 cutoff）...
    orderService.accept(order);      // T2：仍接單，或歸到錯誤結算期
}
```

**修復策略**：
```java
// 以伺服器權威時間 + 單一原子判斷接單（資料庫條件保證）
int n = orderMapper.acceptIfWindowOpen(order, serverNow);
// SQL: INSERT/UPDATE ... WHERE :serverNow < cutoff AND window_status = 'OPEN'
if (n == 0) throw new WindowClosedException();
// cutoff 邊界以交易所時區明確歸期（見 time-window-cutoff-calendar-rules）
```

**反哺規則**：RULE-BIZ-003

---

## 新增模式（模板）

複製以下模板，在檔案末尾新增新的模式：

```markdown
## PAT-{類別}-{序號}：{模式名稱}

**描述**：
{說明這個 Bug 模式的業務背景與危害}

**觸發特徵**：
\`\`\`java
// 描述可識別的程式碼特徵
\`\`\`

**修復策略**：{策略編號} → {策略名稱}

\`\`\`java
// 修復後的正確寫法
\`\`\`

**反哺規則**：{RULE-XXX-NNN}（新增到 rules-registry.md）

**來源事件**：{BUG-YYYY-NNN} / {事後檢視報告連結}
**新增日期**：{YYYY-MM-DD}
**新增人員**：{AI Agent / 工程師姓名}
```
