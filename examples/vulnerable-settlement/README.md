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

## 共通重點

- **PoC「成功」的定義 = 不變量被違反**，不是「丟例外」——這是安全驗收與功能測試的關鍵差異。
- 每個 demo 都附**負面對照**（正常流程應成功 / 冪等），確保修復未誤殺正常路徑，呼應 `finding-evidence-standard.md` 的降誤報精神。
- 真實系統請改用 Spring `@RestController` + MyBatis；邏輯與這些 demo 等價。
- 兩個 demo 都在 CI（`.github/workflows/ci.yml`）中每次 push 自動編譯執行。
