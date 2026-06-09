# CodeQL 規則：語意型財務安全

> 把 Semgrep 容易誤報的「語意型」規則改以 CodeQL 實作（跨方法 / 控制流 / 註解語意）。
> 對應 `knowledge-base/rules-registry.md` 的 RULE-SEC-103/104/108/112。

| 查詢 | 規則 | 偵測重點 | precision |
|------|------|---------|-----------|
| `RuleSec103Toctou.ql` | RULE-SEC-103 | 餘額 check-then-act 非原子且無鎖（TOCTOU 雙花） | medium |
| `RuleSec104CallbackNoSignature.ql` | RULE-SEC-104 | 回調端點入帳前無簽章驗證 | medium |
| `RuleSec108MakerChecker.ql` | RULE-SEC-108 | 高權限調帳直接生效，無 maker-checker | low |
| `RuleSec112RateLimit.ql` | RULE-SEC-112 | 敏感金流端點無速率/velocity 控制 | low |

## 為何用 CodeQL 而非 Semgrep
這四條需要「方法內是否同時存在 A 呼叫但缺少 B 呼叫/鎖/註解」這類**語意 + 控制流**判斷，Semgrep 的單點 pattern 表達力不足、誤報高。CodeQL 的 `MethodCall` / `Callable` / `Annotation` 模型能精準表達「同一 callable 內缺少對應防護」。

## 本地執行
```bash
# 需安裝 CodeQL CLI 與 java-all 套件
codeql database create db --language=java-kotlin --build-mode=none --source-root=.
codeql database analyze db ./rules/codeql --format=sarif-latest --output=results.sarif
```

## CI
`.github/workflows/codeql.yml` 以 `build-mode: none` 在每次 push/PR 自動編譯並執行這些查詢（GitHub Code Scanning）。
`fixtures/CodeqlFixtures.java` 提供每條規則的命中 / 不命中對照標的。

> 精度說明：108/112 標 low precision（依命名 heuristic），定位為「提示 + 人工覆核」；
> 103/104 標 medium。真實落地時應依專案實際 sink/sanitizer 名稱微調 predicate。
