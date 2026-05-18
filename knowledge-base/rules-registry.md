# 靜態掃描規則登錄

> 檔案路徑：knowledge-base/rules-registry.md
> 用途：將知識庫中的 Bug 模式轉化為可執行的靜態掃描規則
> 更新時機：每次事後檢視後，由 `agents/knowledge-writer.md` 自動追加
> 工具對應：SpotBugs 自訂規則 / SonarQube Custom Rules / PR Review Checklist

---

## 規則分類

```
RULE-FIN-XXX  → 金融計算相關
RULE-CON-XXX  → 並發與一致性相關
RULE-SCH-XXX  → 排程任務相關
RULE-BIZ-XXX  → 業務邏輯相關
RULE-MON-XXX  → 監控告警相關
```

---

## 規則清單

### RULE-FIN-003：金融欄位禁止使用 double/float

**來源模式**：PAT-FIN-002
**嚴重等級**：CRITICAL

**違規特徵**：
```
欄位名稱符合 /amount|price|fee|profit|balance|rate|value/ (忽略大小寫)
且欄位型別為 double 或 float 或 Double 或 Float
```

**PR Review 檢查點**：
```
☑ 所有金融相關欄位（金額、比率、餘額、手續費）使用 BigDecimal
☑ 沒有 .doubleValue() 轉換後再計算的代碼
```

---

### RULE-FIN-004：BigDecimal 比較必須使用 compareTo()

**來源模式**：PAT-FIN-003
**嚴重等級**：MAJOR

**違規特徵**：
```
BigDecimal 型別的變數
呼叫 .equals() 方法
```

**SonarQube 已有對應規則**：`java:S2244`（確認已啟用）

---

### RULE-CON-001：Kafka 消費者必須有冪等保護

**來源模式**：PAT-CON-001
**嚴重等級**：CRITICAL

**違規特徵**：
```
類別有 @KafkaListener 或 @RabbitListener 等訊息監聽器標注
且
方法體內沒有 setIfAbsent / idempotent / duplicate 相關呼叫
且
方法名稱或呼叫的方法名稱包含 settle / credit / debit / transfer / charge
```

**PR Review 檢查點**：
```
☑ 所有涉及資金操作的 MQ 消費者有 Redis setIfAbsent 防重
☑ 有 DB 狀態機作為第二層保護
☑ 業務失敗時 Redis key 會被清除，允許重試
```

---

### RULE-CON-002：@Transactional 禁止加在 private 方法

**來源模式**：PAT-CON-002
**嚴重等級**：MAJOR

**違規特徵**：
```
方法修飾符為 private
且有 @Transactional 標注
```

**SonarQube 已有對應規則**：`java:S2229`（確認已啟用）

---

### RULE-CON-003：@Transactional 必須指定 rollbackFor

**來源模式**：PAT-CON-002
**嚴重等級**：MAJOR

**違規特徵**：
```
@Transactional 標注
且 rollbackFor 屬性未設定
且方法宣告有 throws Exception 或非 RuntimeException
```

**修復範例**：
```java
// ✅ 正確
@Transactional(rollbackFor = Exception.class)
public void settle(Long orderId) throws SettlementException { ... }
```

---

### RULE-BIZ-001：結算結果必須通過業務合理性校驗

**來源模式**：PAT-BIZ-002
**嚴重等級**：CRITICAL

**違規特徵**：
```
方法名稱包含 'settle' 或 'settlement'
且
包含對 walletService.credit() 或 walletService.debit() 的呼叫
且
呼叫前沒有 settlementGuard.validate() 或等效的合理性校驗
```

**PR Review 檢查點**：
```
☑ 所有結算流程在入帳前都通過 SettlementGuard.validate()
☑ 最大收益率設定已在 Nacos 配置，且有預設安全值
☑ 單筆賠付上限已設定
```

---

---

### RULE-CON-004：批量讀取錢包後的更新必須有行鎖或樂觀鎖保護

**來源模式**：PAT-CON-004
**嚴重等級**：CRITICAL

**違規特徵**：
```
呼叫 batchFindWallets 或等效的批量錢包讀取方法
且
後續有 batchUpWallets 或等效的批量覆蓋更新
且
中間沒有 FOR UPDATE / version 欄位比對 / SELECT FOR UPDATE
且
上游有 flatMap / parallel 等並發操作（並發度 > 1）
```

**SonarQube 自訂規則描述**：
```
Rule: Wallet batch read followed by bulk overwrite without concurrency control
When: batchFindWallets() is called in a reactive/parallel context
      AND followed by batchUpWallets() or updateWallet()
      AND no version field or FOR UPDATE found in the call chain
Severity: BLOCKER
Tag: financial-safety, concurrency
```

**PR Review 檢查點**：
```
☑ batchFindWallets 對應的 SQL 有 FOR UPDATE（悲觀鎖）
  或 updateWallet/batchUpWallets 的 SQL 有 WHERE version=? AND id=?（樂觀鎖）
☑ 若使用樂觀鎖，更新 0 筆時有加入 retryIds 重試機制
☑ 並發度（flatMap 第二參數）已知曉且有對應的鎖策略
```

---

### RULE-BIZ-002：結算依賴的外部 API 失敗必須拋出例外，禁止靜默返回預設值

**來源模式**：PAT-BIZ-003
**嚴重等級**：CRITICAL

**違規特徵**：
```
方法名稱包含 fetch / get / query / retrieve 等查詢動詞
且 呼叫外部 API（非本服務）取得價格、比率、行情等計算依賴數據
且 API 回傳非成功狀態時，返回 BigDecimal.ZERO / 0 / null / 空物件
且 返回值後續直接參與結算計算（而非只用於展示）
```

**修復範例**：
```java
// ✅ 正確：外部 API 失敗必須拋出例外
if (resp.getCode() != 1) {
    throw new ExternalApiException(
        "行情 API 失敗，禁止以預設值繼續結算: " + resp);
}
// 額外校驗返回值的合理性
if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
    throw new InvalidPriceException("行情價格異常（≤0）: " + price);
}
```

**PR Review 檢查點**：
```
☑ 所有取得行情價格的方法，失敗時拋出例外（不靜默返回 ZERO）
☑ 所有取得收益率/匯率的方法，有對返回值做正數校驗
☑ 上層的 onErrorResume 不會吞掉這些例外（需讓結算流程停止）
```

---

### RULE-CON-005：有重試機制的流程，失敗時的狀態修改必須是冪等的

**來源模式**：PAT-CON-005
**嚴重等級**：MAJOR

**違規特徵**：
```
方法名稱含 changeType / toggle / flip / switch 等切換語意
且 在 catch 區塊或 onErrorResume 中被呼叫
且 上游或同層有 retryWhen / Retry.backoff / @Retryable 重試設定
```

**修復範例**：
```java
// ✅ 正確：單向冪等標記，無論呼叫幾次結果相同
public void markError() {
    this.resultType.set(1); // set 不是 toggle
}

// 使用方：
} catch (Exception e) {
    countDto.markError();   // 冪等，重試 N 次都是 set(1)
    throw e;
}
```

**PR Review 檢查點**：
```
☑ 有 retryWhen / Retry 的流程，catch 區塊內的狀態修改是單向設定（非切換）
☑ onErrorResume（最終失敗處理）有傳入 ex 物件到 log.error()
```

---

### RULE-CON-006：批次 Kafka Listener 使用 hasError 不 ACK 時，每筆訊息必須有獨立冪等鍵

**來源模式**：PAT-CON-001 變體 B
**嚴重等級**：CRITICAL

**違規特徵**：
```
@KafkaListener containerFactory 為 BATCH 型（批次消費）
且 使用 AtomicBoolean hasError 控制整批 ack / not-ack
且 單筆訊息（runner / order）的處理沒有 Redis setIfAbsent 或等效防重
且 業務操作涉及金額入帳或餘額修改
```

**修復範例**：
```java
// ✅ 每筆 Runner 處理前加獨立冪等鍵
String idempotentKey = "settle:runner:" + runner.getId();
Boolean isFirst = redisTemplate.opsForValue()
    .setIfAbsent(idempotentKey, "processing", Duration.ofHours(24));
if (Boolean.FALSE.equals(isFirst)) {
    return Mono.empty(); // 此 Runner 已在處理中或已完成，跳過
}
```

**PR Review 檢查點**：
```
☑ 批次 Listener 中，每筆業務單元（Runner / Order）有獨立的 Redis 冪等鍵
☑ 成功後將 Redis key 改為 "settled" 且延長 TTL（7 天）
☑ 失敗後清除 Redis key，允許 Kafka 重送時再次進入
```

---

## 規則健康度追蹤

| 規則代碼 | 規則摘要 | 最後觸發 | 觸發次數 | 攔截 Bug | 狀態 |
|---------|---------|---------|---------|---------|------|
| RULE-FIN-003 | 金融欄位禁止 double/float | — | 0 | 0 | 🟢 就緒 |
| RULE-FIN-004 | BigDecimal 比較用 compareTo | — | 0 | 0 | 🟢 就緒 |
| RULE-CON-001 | Kafka 消費者必須有冪等保護 | SettlementFlow.java | 1 | 1 | 🟢 就緒 |
| RULE-CON-002 | @Transactional 禁止加在 private 方法 | — | 0 | 0 | 🟢 就緒 |
| RULE-CON-003 | @Transactional 必須指定 rollbackFor | — | 0 | 0 | 🟢 就緒 |
| RULE-CON-004 | 批量讀錢包後更新需行鎖或樂觀鎖 | SettlementFlow.java | 1 | 1 | 🆕 就緒 |
| RULE-CON-005 | Retry 場景狀態修改必須冪等 | SettlementFlow.java | 1 | 1 | 🆕 就緒 |
| RULE-CON-006 | 批次 Listener 每筆需獨立冪等鍵 | SettlementFlow.java | 1 | 1 | 🆕 就緒 |
| RULE-BIZ-001 | 結算前必須通過業務合理性校驗 | — | 0 | 0 | 🟢 就緒 |
| RULE-BIZ-002 | 外部 API 失敗禁止靜默返回預設值 | SettlementFlow.java | 1 | 1 | 🆕 就緒 |

> 最後更新：SettlementFlow.java 掃描後（v1.1）· 新增 4 條規則 · 累計攔截 Bug：5 個
