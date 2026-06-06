# 端到端示範（End-to-End Demos）

證明 debug-hunter 的「DETECT → REPRODUCE(PoC) → VERIFY」在真實程式上成立。
所有 demo 刻意零外部依賴（純 JDK），可立即編譯執行——符合「demo 必須能真 build & test」原則。

## 執行

```bash
cd examples/vulnerable-settlement

# Demo 1：IDOR 越權提款（PAT-SEC-101）
javac IdorDemo.java && java IdorDemo

# Demo 2：偽造支付回調 / Webhook 偽造（PAT-SEC-104）
javac PaymentCallbackDemo.java && java PaymentCallbackDemo

# Demo 3：預言機操縱 / 陳舊價（PAT-SEC-105）
javac OracleManipulationDemo.java && java OracleManipulationDemo

# Demo 4：TOCTOU 雙花（PAT-SEC-103，真並發）
javac DoubleSpendDemo.java && java DoubleSpendDemo

# Demo 5：Mass assignment 改餘額（PAT-SEC-106）
javac MassAssignmentDemo.java && java MassAssignmentDemo

# Demo 6：請求重放（PAT-SEC-107）
javac ReplayDemo.java && java ReplayDemo

# Demo 7：排程多 Worker 資料競爭（PAT-SCH-001，真並發）
javac SchedulerRaceDemo.java && java SchedulerRaceDemo

# Demo 8：委託時間窗口競態（PAT-BIZ-001）
javac TradingWindowRaceDemo.java && java TradingWindowRaceDemo

# Demo 9：分散式鎖 TTL 設計缺陷（PAT-CON-003）
javac LockTtlDemo.java && java LockTtlDemo

# Demo 10：double/float 處理金額（PAT-FIN-002）
javac FloatMoneyDemo.java && java FloatMoneyDemo

# exit 0 = 閉環成立
```

---

## Demo 1 — `IdorDemo`：IDOR 越權提款

攻擊者用 user A 的 token 帶入 user B 的 `accountId` 提款。
- **漏洞版**：直接拿請求的 accountId 動帳 → B 的錢被提走
- **修復版**：歸屬校驗（accountId 必須屬於認證主體）→ 攻擊被擋
- **PoC 成功判據**：INV-ST-01（越權動帳，他人餘額被改）

| 階段 | 對應知識檔 |
|------|-----------|
| 漏洞模式 | `financial-security-patterns.md#PAT-SEC-101` |
| 修復 ground-truth | `authorization-ownership-matrix.md` |
| 不變量 | `financial-invariants.md#INV-ST-01` |
| 回歸語料 | `attack-regression-corpus.md#CORP-001` |
| 靜態規則 | `rules/semgrep/financial-security.yml#idor-money-sink-missing-ownership` |

---

## Demo 2 — `PaymentCallbackDemo`：偽造支付回調

攻擊者直接 POST 偽造的「支付成功」回調，金額灌大，從未真的付款。
- **漏洞版**：只看 body 的 `status=SUCCESS` 就入帳，並信任對方金額 → 白嫖
- **修復版**：HMAC 簽章驗證 ＋ 以我方訂單金額為準 ＋ 冪等 → 偽造與竄改都被擋
- **PoC 成功判據**：INV-T-03（內外對帳一致；入帳 > 閘道實收 = 憑空入帳）
- 涵蓋 4 個情境：偽造回調、金額竄改、真實回調正確入帳、重送冪等

| 階段 | 對應知識檔 |
|------|-----------|
| 漏洞模式 | `financial-security-patterns.md#PAT-SEC-104` |
| 威脅 / 濫用案例 | `threat-catalog.md`（AB-04）、`threat-catalog.md` 供應鏈節 |
| 不變量 | `financial-invariants.md#INV-T-03` |
| 回歸語料 | `attack-regression-corpus.md#CORP-004` |
| 靜態規則 | `rules/semgrep/financial-security.yml#...`（簽章類偏語意，建議搭配 CodeQL） |

---

## Demo 3 — `OracleManipulationDemo`：預言機操縱 / 陳舊價

攻擊者瞬間拉抬單一行情來源（或系統用到陳舊價）來扭曲結算。
- **漏洞版**：單一來源、直接用、不看時效 → 以被操縱價結算，定向多賠 ~1000 倍
- **修復版**：多源中位數（排除離群）＋ 時效窗（拒陳舊）＋ 偏離熔斷（異常暫停）
- **PoC 成功判據**：結算價偏離公允價 > 10%（INV-ST-03 守恆破壞）
- 涵蓋 5 個情境：單源操縱、陳舊價、多源異常熔斷、正常結算、未誤殺

| 階段 | 對應知識檔 |
|------|-----------|
| 漏洞模式 | `financial-security-patterns.md#PAT-SEC-105` |
| 威脅 / 濫用案例 | `threat-catalog.md`（AB-05） |
| 不變量 | `financial-invariants.md#INV-ST-03` |
| 回歸語料 | `attack-regression-corpus.md#CORP-005` |
| 修復對照 | `financial-bug-patterns.md#PAT-BIZ-003`（外部資料不可信防線） |

---

## Demo 4 — `DoubleSpendDemo`：TOCTOU 雙花（真並發）

攻擊者用並發請求製造「檢查與扣款之間」的競態，繞過餘額檢查超提。
- **漏洞版**：check-then-act 非原子；用 `CyclicBarrier` 強制 10 個執行緒都先檢查完才扣款 → 全部過檢查 → 金庫從 $100 被提到 **−$900**
- **修復版**：`synchronized` 讓檢查＋扣款原子（等同 `UPDATE ... WHERE balance >= amt`）→ 僅 1 筆成功、餘額 $0
- **PoC 成功判據**：INV-ST-01（餘額變負）或成功筆數 > 1
- 競態用 barrier **穩定復現**（非靠運氣）；多次執行結果一致

| 階段 | 對應知識檔 |
|------|-----------|
| 漏洞模式 | `financial-security-patterns.md#PAT-SEC-103` |
| 威脅 / 濫用案例 | `threat-catalog.md`（AB-03） |
| 不變量 | `financial-invariants.md#INV-ST-01` |
| DB 層修復依據 | `persistence-consistency-controls.md`（原子扣款 / 樂觀鎖） |
| 回歸語料 | `attack-regression-corpus.md#CORP-003` |

---

## Demo 5 — `MassAssignmentDemo`：Mass assignment 改餘額

攻擊者在「更新暱稱」的請求裡夾帶 `balance`、`status` 欄位，框架直接綁定到持久化實體。
- **漏洞版**：請求 body 全欄位綁定 → 餘額被灌成 $999,999.99、status 越過狀態機改成 VIP
- **修復版**：白名單 DTO 只允許 `nickname`；餘額只能經記帳路徑（會寫分錄）變更
- **PoC 成功判據**：INV-ST-02（餘額 ≠ 初始 + Σ帳本分錄；憑空改餘額卻無分錄）
- 含正向對照：合法記帳（有分錄）不變量仍成立 → 凸顯「差別在有沒有分錄」

| 階段 | 對應知識檔 |
|------|-----------|
| 漏洞模式 | `financial-security-patterns.md#PAT-SEC-106` |
| 資料分級 / 白名單 | `authorization-ownership-matrix.md`（第 7 節 P1 核心金流數據） |
| 不變量 | `financial-invariants.md#INV-ST-02` |
| 靜態規則 | `rules/semgrep/financial-security.yml#mass-assignment-entity-persist` |
| 回歸語料 | `attack-regression-corpus.md#CORP-006` |

---

## Demo 6 — `ReplayDemo`：請求重放

攻擊者攔截一筆**原本合法、已簽章**的資金請求，原樣重送。
- **漏洞版**：驗簽通過就執行，無 nonce / 時間窗 → 同封包重放 → 重複轉帳（alice 被扣兩次變負）
- **修復版**：驗簽 ＋ 時間窗 ＋ nonce 一次性消費 → 第二次重放被擋
- **PoC 成功判據**：INV-T-04（重放零增益；重送同請求不該改變狀態）
- 與 `PaymentCallbackDemo` 的差異：那是支付回調冪等；這裡是攻擊者**主動重放合法資金請求**
- 含 2 個對照：不同 nonce 正常放行、過期請求被時間窗擋下

| 階段 | 對應知識檔 |
|------|-----------|
| 漏洞模式 | `financial-security-patterns.md#PAT-SEC-107` |
| 威脅 / 濫用案例 | `threat-catalog.md`（AB-07） |
| 不變量 | `financial-invariants.md#INV-T-04` |
| 回歸語料 | `attack-regression-corpus.md#CORP-007` |

---

## Demo 7 — `SchedulerRaceDemo`：排程多 Worker 資料競爭（真並發）

分散式排程廣播模式下，多個 Worker 各自撈全量待結算資料並處理。
- **漏洞版**：4 個 Worker 各結算全部 8 筆 → 每筆被重複結算 4 次
- **修復版 A**：分片隔離（`id % WORKERS == idx`）→ 每筆恰好一次、全覆蓋
- **修復版 B**：冪等認領（`claimed.add(id)`）→ 即使全量掃描，單筆只被第一個 Worker 認領
- **PoC 成功判據**：INV-T-02（同一業務鍵僅產生一次資金效果；重複次數 > 1 即違反）

| 階段 | 對應知識檔 |
|------|-----------|
| 漏洞模式 | `financial-bug-patterns.md#PAT-SCH-001` |
| 不變量 | `financial-invariants.md#INV-T-02` |
| 修復規則 | `rules-registry.md#RULE-SCH-001`（分片隔離 + 單筆冪等） |

---

## Demo 8 — `TradingWindowRaceDemo`：委託時間窗口競態

委託在窗口邊界（cutoff）的 check-then-act 競態。
- **漏洞版**：t=999 檢查窗口開 → 處理延遲到 t=1001（已過 cutoff）→ 用過時 `isOpen` 仍接單
- **修復版**：決策點以權威時鐘一次取定 `decisionTime`，原子判斷 `decision < cutoff`，過 cutoff 即拒
- **PoC 成功判據**：已接受委託的決策時間必須 < cutoff；漏洞版接了 decisionTime=1001 的單 → 違反
- 含負面對照：窗口內（t=500）委託正常接受，未誤殺

| 階段 | 對應知識檔 |
|------|-----------|
| 漏洞模式 | `financial-bug-patterns.md#PAT-BIZ-001` |
| 不變量 | `financial-invariants.md#INV-T-03 / INV-ST-05` |
| 修復規則 | `rules-registry.md#RULE-BIZ-003`（權威時鐘 + 原子窗口判斷） |
| 相關 | `time-window-cutoff-calendar-rules.md`（帳務日/cutoff 歸期） |

---

## Demo 9 — `LockTtlDemo`：分散式鎖 TTL 設計缺陷

可控時鐘重現「鎖提前過期」與「釋放未校驗持有者」兩種互斥失效。
- **漏洞版**：TTL=5 < 業務時間=10 → A 鎖中途過期，B 於 t=6 取得 → 兩者同處臨界區 → 重複結算
- **修復版**：TTL > 業務時間（或 watchdog 續租）→ B 取鎖失敗，互斥成立、單次結算
- **持有者校驗**：不安全釋放（無條件 del）會誤刪 B 剛取得的鎖；安全釋放（Lua 校驗 token）為 no-op，保護 B 的鎖
- **PoC 成功判據**：INV-T-02（同一 runner 結算次數 > 1 即互斥失效）

| 階段 | 對應知識檔 |
|------|-----------|
| 漏洞模式 | `financial-bug-patterns.md#PAT-CON-003` |
| 不變量 | `financial-invariants.md#INV-T-02` |
| 修復規則 | `rules-registry.md#RULE-CON-008`（TTL>業務時間 + watchdog + 持有者校驗釋放） |

---

## Demo 10 — `FloatMoneyDemo`：double/float 處理金額

示範浮點精度誤差累積，以及修復版全程 BigDecimal 的精確性。
- **漏洞版**：`double` 累加 0.01 共 100 萬次 → 偏離 10000.00（誤差 ~1.7e-7）；`0.1 + 0.2 == 0.3` 為 false
- **修復版**：全程 `BigDecimal`（字串建構）+ `compareTo` → 累加精確、比較正確
- **對照**：即使來源是 BigDecimal，中途 `doubleValue()` 後計算仍會把浮點誤差引回
- **PoC 成功判據**：double 結果偏離精確值（單筆極小，高流量累加後侵蝕 INV-ST-03 守恆）

| 階段 | 對應知識檔 |
|------|-----------|
| 漏洞模式 | `financial-bug-patterns.md#PAT-FIN-002` |
| 靜態規則 | `rules/semgrep/financial-security.yml#no-double-float-for-money`（RULE-FIN-003） |
| 相關不變量 | `financial-invariants.md#INV-ST-03`（累積守恆） |

---

## 共通重點

- **PoC「成功」的定義 = 不變量被違反**，不是「丟例外」——這是安全驗收與功能測試的關鍵差異。
- 每個 demo 都附**負面對照**（正常流程應成功 / 冪等），確保修復未誤殺正常路徑，呼應 `finding-evidence-standard.md` 的降誤報精神。
- 真實系統請改用 Spring `@RestController` + MyBatis；邏輯與這些 demo 等價。
- 9 個 demo 都在 CI（`.github/workflows/ci.yml`）中每次 push 自動編譯執行。
