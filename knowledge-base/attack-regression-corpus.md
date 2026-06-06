---
file_id: attack-regression-corpus
kind: reference
status: active
schema_version: 1.0
last_reviewed: 2026-06-01
stale_after_days: 90
owner: knowledge-writer-agent
---

# 攻擊回歸語料庫（Attack Regression Corpus）

> 地位：**漏洞 PoC 樣本庫**。提供真實漏洞的請求/響應樣板，用於回歸測試與檢測模型校準。

---

## 1. 樣本：越權動帳 (IDOR on Funds)
- **ID**: CORP-001
- **類型**: IDOR
- **Payload**:
```http
POST /api/v1/withdraw
Content-Type: application/json
Authorization: Bearer <UserA_Token>

{
  "accountId": "USER_B_ACCOUNT_ID_001",
  "amount": 1000.00,
  "currency": "USD"
}
```
- **Expected Outcome**: `403 Forbidden` 或 `401 Unauthorized`。
- **Failed Case**: `200 OK` 且 User B 餘額減少。

---

## 2. 樣本：整數溢出繞過校驗 (Integer Overflow)
- **ID**: CORP-002
- **類型**: Numerical Overflow
- **Payload**:
```json
{
  "amount": 9223372036854775808, 
  "target": "attacker_wallet"
}
```
- **分析**: 超過 `Long.MAX_VALUE`，若後端轉型為 `long` 可能變為負數，繞過 `amount > 0` 校驗。

---

## 3. 樣本：競態條件 / TOCTOU 雙花 (Race Condition)
- **ID**: CORP-003
- **類型**: PAT-SEC-103 / TOCTOU 雙花
- **描述**: 並發發送多個相同的提款請求，製造「檢查與扣款之間」的競態。
- **驗收標準**: 僅有一筆交易成功，餘額不為負（INV-ST-01）。
- **可執行 PoC**: `examples/vulnerable-settlement/DoubleSpendDemo.java`（CyclicBarrier 穩定復現，CI 每次自動跑）

---

## 4. 樣本：偽造支付回調 (Webhook Forgery)
- **ID**: CORP-004
- **類型**: PAT-SEC-104 / 偽造回調
- **Payload**:
```http
POST /payment/callback
Content-Type: application/x-www-form-urlencoded

orderNo=ORD-1&userId=alice&status=SUCCESS&amount=100000
（無有效 HMAC 簽章）
```
- **Expected Outcome**: `SecurityException 回調簽章驗證失敗`，不入帳。
- **Failed Case**: 偽造回調直接入帳（白嫖），INV-T-03 內外對帳破裂。
- **可執行 PoC**: `examples/vulnerable-settlement/PaymentCallbackDemo.java`（CI 每次自動跑）

---

## 5. 樣本：預言機操縱 / 陳舊價 (Oracle Manipulation)
- **ID**: CORP-005
- **類型**: PAT-SEC-105 / 行情操縱
- **攻擊**: 瞬間拉抬單一行情來源至 100000（公允價 100），或注入 10 分鐘前的陳舊價。
- **Expected Outcome**: 多源中位數排除離群 / 時效窗拒陳舊 / 偏離熔斷暫停結算。
- **Failed Case**: 以被操縱價結算，定向多賠 ~1000 倍，INV-ST-03 守恆破壞。
- **可執行 PoC**: `examples/vulnerable-settlement/OracleManipulationDemo.java`（CI 每次自動跑）

---

## 6. 如何使用
- **Detection**: AI 掃描代碼時，將代碼邏輯與此語料庫的攻擊向量比對。
- **Validation**: 修復後，執行此語料庫中的 PoC 確保無法觸發。
