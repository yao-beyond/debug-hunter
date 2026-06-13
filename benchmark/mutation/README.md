# mutation 語料庫 — 量測**真實盲測**召回率

> `benchmark/cases/` 的 demo 在註解裡有答案（`漏洞版`/`PAT-xxx`），是「開卷考」，召回率虛高。
> 這裡反過來：把缺陷注入**乾淨、無標籤**的程式碼，產生黑箱 mutant，逼出 AI 在「沒有提示」時的真實偵測能力。

## 為什麼需要它（一個數字說明一切）

同一個偵測代理、同樣的缺陷類別，開卷考 vs 盲測：

| | 開卷考（`benchmark/cases/`） | **盲測（本目錄 mutation）** |
|---|---|---|
| recall | 100% | **50%**（首輪） |
| security 軌 recall | 100% | **0%（IDOR 被漏掉）** |
| precision / specificity | 100% | **100%（0 誤報）** |

→ 開卷考的 100% 虛高一倍；偵測器的真實弱點是**漏報（尤其安全類）而非誤報**。
**這就是 mutation 語料庫存在的理由**：它量到的數字才能拿給企業/稽核看。

## measure → improve → re-measure（已驗證的閉環）

用首輪盲測暴露的弱點（IDOR/rounding 漏報）去**強化 detector 方法論**（在
`agents/detector.md` + `agents/security-fraud-detector.md` 加入 **Mandatory Audit Protocol**：
強制逐方法定位「歸屬比對行號」與「金額 scale/RoundingMode」，找不到即判漏洞），再用同一套
mutation 重跑：

| 指標 | run-1（改進前） | run-2（+MAP） | run-3（+證據標註） |
|------|------|------|------|
| blind recall | 50% | 100% | 80%（見下方變異說明） |
| security 軌 recall | 0% | 100% | 100% |
| precision / specificity | 100% | 100% | 100%（20/20 TN） |
| evidence_complete_rate | 60% | 20% | **100%** |

兩輪改進各打中各的目標：
- **run-2（加 Mandatory Audit Protocol）**：blind recall 50%→100%，補上 IDOR/rounding 漏報。
  非背答案——新增不同簽章的 `transfer` IDOR（方法論未針對它）照樣被抓 → **通用化**。
- **run-3（加證據標註步驟）**：evidence_complete 20%→100%，IDOR 補上 `logic_invariant`、correctness 補上 `state_mutation`。

### recall 是分佈，不是點值（run-3 實證 → run-4 修正）

run-3 的 recall 掉到 80% 不是退步，而是**照出了 LLM 變異**：最模糊的 `rounding`（applyRate 移除 setScale）
案例，修正前跨 5 次觀測命中 4 次（run-2✅ / run-3❌ / probe-a✅ / probe-b✅ / probe-c✅）= **80%**；
其餘 4 個 mutant 每次都穩定命中。

**根因**：方法論的 Financial Precision Audit 只查 `divide()`，**漏了 `multiply()` 後不收斂**的情況，
且代理常用「`multiply` 數學精確 → 沒問題」合理化掉 applyRate 的漏洞。

**run-4 修正**：在 `detector.md` 加入 **Multiply/Ratio Scale Trace**——金額經 multiply 後未 setScale 即外流
為漏洞，並明文**禁止以「乘法精確／scale 是邊界職責」判乾淨**（唯一豁免＝呼叫端有可證明的 normalize 契約）。

| `rounding`(MUT-005) 命中率 | 修正前 | 修正後 |
|---|---|---|
| 5 次觀測 | 4/5 = **80%** | **5/5 = 100%** |

且**零誤報代價**：強化後，其他 mutant 中**有 setScale 的乾淨 applyRate 仍正確判乾淨**
（代理明確引用「有 setScale，符合豁免條件」），specificity 維持 100%。

> **方法論教訓**：單次 recall 點值不可靠，必須**多輪取分佈、報 per-mutant 命中率**；
> 而一旦定位到「導致漏報的具體合理化推理」（此例：multiply 精確），就能在方法論明文封死它。
> precision/specificity 跨所有觀測（含修正後）穩定 100%。

## 三層架構（消除答案洩漏）

```text
seeds/                 乾淨、可編譯、業務化命名的基線（withdraw/split/applyRate/accumulate 全部正確）
  LedgerService.java     ← 偵測對象的母本（無 main）
  Oracle.java            ← 不變量斷言 main，當 mutation 是否生效的 oracle【絕不給偵測器】
operators/operators.json 宣告式缺陷注入規則 + 每軌 ground-truth + 去汙染黑名單
generate.py              套 operator → 三段 gate → 去標籤 → 黑箱 mutant + 答案庫 + score.py case
mutants/   (generated)   MUT-NNN/LedgerService.java  無 main、無註解、過 leak-check 的黑箱
manifests/hidden/ (gen)  MUT-NNN.json  答案庫（operator/不變量/oracle 結果）【絕不給偵測器】
generated-cases/  (gen)  MUT-NNN.json  score.py 相容 case（1 漏洞 target + 多個乾淨 decoy）
out/       (gen)         盲測代理輸出 + 合併 findings + metrics
```

> `mutants/`、`manifests/`、`generated-cases/`、`out/` 皆為衍生物，已 gitignore。
> 唯一 source of truth 是 `seeds/` + `operators/operators.json`，全部可重新生成。

## 三段 gate（保證 ground-truth 可信）

每個 operator 注入後必須全過，否則剔除（不污染語料庫）：

1. **compile gate** — mutant 可編譯
2. **clean-oracle gate** — 未注入時 `Oracle` exit 0（基線確實正確）
3. **mutant-oracle gate** — 注入後 `Oracle` exit≠0 **且**輸出含該 operator 宣告的 `oracle_fail_signal`
   （證明「注入確實造成不變量被違反」，而非等價變異 / 只是移除告警）

`generate.py` 末尾印的 `valid_mutant_rate` 衡量**語料庫品質**（非偵測能力）。

## 去汙染原則（讓 AI 無法靠提示猜）

- mutant **無 main、無 `//` 註解**（`generate.py` 去標籤）
- **leak-check**：禁止出現 `Vulnerable/Fixed/PAT-/SEC-/FIN-/INV-/idor/toctou/residue/exploit…`（見 operators.json `forbidden_tokens`）
- 命名**業務中性**（`LedgerService`/`withdraw`/`split`，非 `withdrawVulnerable`）
- 每個 mutant **只變異一個方法**，其餘方法是**乾淨 decoy**（measures specificity：偵測器在 decoy 報警＝誤報）
- 盲測時**一個 agent 只看一個 mutant**，杜絕跨 mutant diff 找「哪個不一樣」

## 跑一次

```bash
# 1. 生成黑箱 mutant（需 JDK；純 stdlib + javac，零第三方依賴）
python3 benchmark/mutation/generate.py

# 2. 盲測：每個 mutant 派一個獨立 agent，只給它該 mutant 一個檔，
#    禁讀 operators/manifests/generated-cases/其他 mutant；findings 寫到 out/MUT-NNN.json

# 3. 合併 out/MUT-*.json → out/merged-findings.json（source=agent-run），再計分（重用主 scorer）
python3 benchmark/score.py \
  --cases benchmark/mutation/generated-cases \
  --findings benchmark/mutation/out/merged-findings.json \
  --out benchmark/mutation/out/metrics.json
```

## 目前覆蓋（5 operators，逐步擴充）

| operator | 缺陷 | 注入 | oracle 不變量 | run-1 | run-2(改進後) |
|----------|------|------|--------------|-------|------|
| OP-IDOR-REMOVE-OWNERSHIP | PAT-SEC-101 越權 | 移除 withdraw 歸屬校驗 | INV-ST-01 | ❌ 漏 | ✅ 中 |
| OP-IDOR-TRANSFER | PAT-SEC-101 越權 | 移除 transfer 歸屬校驗（不同簽章，generalization 測試） | INV-ST-02 | —（新增） | ✅ 中 |
| OP-FLOAT-ACCUMULATE | PAT-FIN-002 浮點金額 | accumulate 改 double 累加 | INV-ST-03 | ✅ 中 | ✅ 中 |
| OP-RESIDUE-DROP-BACKFILL | PAT-FIN-006 殘差 | split 末方不吃殘差 | INV-TXN-05 | ✅ 中 | ✅ 中 |
| OP-ROUNDING-DROP-SCALE | PAT-FIN-004 捨入 | applyRate 移除 setScale | INV-ST-04 | ❌ 漏 | ✅ 中 |

> run-1（改進前）最值得注意：移除 IDOR 歸屬校驗後，代理不僅漏報，還**幻覺宣稱「授權守衛齊備」**。
> run-2 加入 Mandatory Audit Protocol 後，兩個 IDOR（含全新 transfer）與 rounding 全數補上 → recall 50%→100%。

## 新增一個缺陷類型（3 步）

1. 確認 `seeds/LedgerService.java` 有對應的**正確**方法，`Oracle.java` 有對應不變量斷言（基線須 exit 0）。
2. 在 `operators/operators.json` 加一條：`find`/`replace`（錨定式精確替換）+ `oracle_fail_signal` + 該軌 `required_evidence` + ground-truth。
3. 跑 `generate.py`，確認該 operator 過三段 gate（顯示 `valid`）。若顯示等價/無效，代表注入沒真的破壞不變量，需強化 oracle 或修正變換。

## 路線圖

- [ ] 加併發 seed（atomic withdraw）支援 TOCTOU operator（PAT-SEC-103）
- [ ] M×N：多個 seed（payment/orderbook/settlement）× operators，擴到數十 mutant
- [ ] 升級為 AST 變換（JavaParser/OpenRewrite）取代錨定字串，增強 operator 韌性
- [ ] 多跑幾輪盲測取 recall 分佈（LLM 有變異）
- [ ] recall 覆蓋矩陣（缺陷類 × seed）自動產生，追蹤真實能力趨勢
- [ ] 針對盲測弱點（IDOR/rounding 漏報）強化 detector prompt 後，重跑驗證 recall 是否提升
