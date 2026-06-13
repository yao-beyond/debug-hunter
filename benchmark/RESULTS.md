# benchmark 真實測量紀錄

> 用 `score.py` 對 cases 計分。findings 由**盲測代理**產生——它只拿到 debug-hunter
> 偵測方法論與原始碼，**未讀取 `cases/` 答案**，獨立判斷哪個函式有漏洞。
> 這是 `source: agent-run` 的真實數據（非示範樣本）。

## 證據分類的演進（CCG：Codex 定義 × Gemini 落地）

第一次測量暴露原證據列舉（為安全類設計）套不住併發/正確性類。經三模型協作重構：

- **Codex**（安全/架構視角）：指出 TOCTOU 不該要 `taint_path`、正確性類需要「實際偏差」證據，並給出三軌分類原則。
- **Gemini**（DX/schema 視角）：把證據從「技術手段」抽象成 4 個**證明維度**，新增 `concurrency` 軌道，用 JSON Schema `if-then` 強制每軌證據 preset（make wrong states unrepresentable），並遷移既有 cases。
- **Claude**：覆查一致性、修正 case 與軌道 preset 的內部矛盾、跑盲測驗證。

新證據維度（取代舊的 taint_path/invariant_violation/db_evidence/counter_evidence_checked）：

| 維度 | 語義 | 典型軌道 |
|------|------|---------|
| `trace_path` | 漏洞如何被「抵達」（taint / 控制流 / 併發交錯時序） | security, concurrency |
| `logic_invariant` | 哪條財務規則/不變量被破壞 | 全軌共用 |
| `state_mutation` | 最終數據/精度確實出錯（殘差、漂移、溢位） | correctness |
| `guard_absence` | 關鍵防線不存在（缺鎖/事務/歸屬校驗） | security, concurrency |

軌道 preset（schema 強制至少含其一）：security={trace_path, logic_invariant}、correctness={logic_invariant, state_mutation}、concurrency={trace_path, guard_absence}。

## 測量演進

| 回合 | precision | recall | evidence_complete | 說明 |
|------|-----------|--------|-------------------|------|
| run-1（舊證據列舉） | 100% | 100% | **60%** | 抓出 2 處 manifest 規格 bug（taint_path 誤套 TOCTOU、float `==` 強套 INV） |
| run-2（新分類遷移後） | 100% | 100% | **80%** | 抓出 1 處殘餘矛盾：correctness case 要求 `guard_absence`，與其軌道 preset `state_mutation` 衝突 |
| run-2 + spec 對齊 | 100% | 100% | **100%** | 兩個 correctness case 的證據對齊軌道 preset 後，同一份盲測達標 |

混淆矩陣（最終）：TP=5, FP=0, FN=0, TN=4。三軌皆 0 誤報、0 漏報，代理正確略過全部 4 個修復版函式。

> **關鍵解讀**：60%→80%→100% 不代表「偵測變強」，而是「**benchmark 逐步修乾淨自己的規格 bug**」。
> 三回合的證據缺口全是 manifest 規格問題，沒有一次是代理真的偵測失敗——這正是 benchmark 該有的價值：
> 每次跑都把 ground-truth 的標註瑕疵逼出來。

## 這個 100% 是「開卷考」——真實盲測只有一半

`examples/vulnerable-settlement/` 的 demo 檔在註解裡直接標了 `漏洞版`/`修復版`/`PAT-SEC-101`，
代理看得到，所以上面的 100% 是**「自帶標籤的教學程式碼上的最佳情況」**。

為了量真實數字，`benchmark/mutation/` 把缺陷注入**乾淨、無標籤**的程式碼（去 main、去註解、leak-check、
業務中性命名），每個 mutant 派**獨立** agent 盲測（杜絕跨檔 diff）。同一代理、同樣 4 類缺陷：

| 指標 | 開卷考（`cases/`） | **真實盲測（`mutation/`）** |
|------|------|------|
| recall | 100% | **50%**（2/4） |
| security 軌 recall | 100% | **0%**（IDOR 漏報） |
| precision | 100% | 100% |
| specificity（乾淨方法不誤報） | — | **100%**（12/12 TN） |

**開卷考的召回率虛高一倍。** 最尖銳的發現：移除 IDOR 歸屬校驗後，代理不只漏報，
還**幻覺宣稱「授權守衛齊備」**——這種安全關鍵盲測弱點，靠帶答案的 demo 永遠測不出來。
另一面，precision/specificity 100% 說明 debug-hunter 偏保守：**弱點是漏報，不是誤報**。

> 詳見 [`mutation/README.md`](mutation/README.md)。三段 gate（compile + clean-oracle + mutant-oracle）
> 保證每個注入都是「真的讓不變量失效」的有效漏洞，valid_mutant_rate=100%。

### measure → improve → re-measure（已驗證閉環）

用首輪暴露的弱點去強化 detector 方法論（`agents/detector.md` + `agents/security-fraud-detector.md`
加入 **Mandatory Audit Protocol**：強制逐方法定位歸屬比對行號與金額 scale，找不到即判漏洞），同一套
mutation 重跑：

| 指標 | run-1（改進前） | run-2（+MAP） | run-3（+證據標註） |
|------|------|------|------|
| blind recall | 50% | 100% | 80%（變異，見下） |
| security 軌 recall | 0% | 100% | 100% |
| precision / specificity | 100% | 100% | 100% |
| evidence_complete_rate | 60% | 20% | **100%** |

兩輪改進各中各的目標：run-2 的 MAP 把 recall 50%→100%（新增 `transfer` IDOR 通用化驗證，非背答案）；
run-3 的證據標註步驟把 evidence_complete 20%→100%。

**run-3 的 recall 80% 不是退步，而是照出 LLM 變異**：最模糊的 `rounding` 案例跨 5 次觀測命中 4 次
（80%），run-3 抽到那次漏報。→ **單次 recall 點值不可靠，必須多輪取分佈**。

**run-4 修正 rounding（已驗證）**：根因是方法論只查 `divide()`、漏了 `multiply()` 後不收斂，且代理
常用「multiply 數學精確」合理化掉。在 `detector.md` 加 **Multiply/Ratio Scale Trace** 並明文禁止該合理化後：

| `rounding`(MUT-005) 命中率 | 修正前 | 修正後 |
|---|---|---|
| 5 次觀測 | 4/5 = 80% | **5/5 = 100%** |

零誤報代價：強化後有 setScale 的乾淨 applyRate 仍正確判乾淨（MUT-001/003 驗證），specificity 維持 100%。

### 後續

- [x] ~~強化 detector，驗證 recall 提升~~ → done：50%→100%（MAP）。
- [x] ~~證據標註紀律~~ → done：evidence_complete 20%→100%（證據標註步驟）。
- [x] ~~rounding 偵測邊界不穩~~ → done：80%→100%（Multiply/Ratio Scale Trace，封死「乘法精確」合理化）。
- [ ] 多輪取 recall 分佈、報 per-mutant 命中率（已對 MUT-005 做 10 次，需推廣到全部 mutant）。
- [ ] 加併發 seed 支援 TOCTOU operator；M×N 擴到數十 mutant。
- [ ] 把 23 個 runnable demo 全數補上 cases manifest（目前 4/23）。
- [ ] （可選）CI 用 jsonschema 強制 case.schema 的 if-then 約束。

## 如何重現

```bash
# 1. 盲測代理掃描 cases 的 source_file，輸出 source=agent-run 的 findings（勿給它看 cases/）
# 2. 計分
python3 benchmark/score.py --findings benchmark/out.agent-run.json --out benchmark/metrics.json
```

> 註：`out.*.json` 與 `metrics.json` 為執行產物，已 gitignore。本檔記錄的是代表性執行的解讀，非自動更新的數據看板。
