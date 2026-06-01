# Debug-Hunter: AI-Driven Closed-Loop Fintech Debugging Framework

`debug-hunter` 是一個專為金融科技 (Fintech) 打造的 AI 閉環偵錯框架。它通過結構化的知識庫 (Knowledge-Base) 指引 AI 智能體完成「偵測 -> 定級 -> 復現 -> 修復 -> 驗收」的完整生命週期。

![debug-hunter v2.0 架構：7 階段閉環 × 三層防線](docs/v2-architecture.png)

## 🚀 核心特性

- **業務深潛**: 內置清結算狀態機、資金守恆不變量、與全球支付安全模式。
- **治理驅動**: 所有知識條目遵循 `knowledge-schema.md`，支持機器解析與 RECYCLE 自我進化。
- **量化風險**: 基於 `severity-loss-model.md` 的精準損益評估，告別主觀定級。
- **端到端驗證**: 集成 PBT (屬性測試) 與 PoC 語料庫，確保每一個 Bug 都能被自動復現並永久消滅。

## 🗺️ 知識庫導航 (Knowledge Base)

我們將知識分為四個關鍵層級，詳見 [**MAP.md**](knowledge-base/MAP.md)：

1.  **元治理 (Meta)**: 規範知識格式與證據標準。
2.  **基石 (Ground-Truth)**: 金流圖、狀態機、術語表。
3.  **模式 (Patterns)**: 漏洞模式、不變量、風險模型。
4.  **執行 (Execution)**: 復現腳本、PoC 語料、Semgrep 規則、Debug 劇本。

## 🛠️ 快速開始

### 1. 執行靜態偵測
```bash
# 使用內置金融規則集
semgrep --config rules/semgrep/financial-security.yml src/   # 規則 + pass/fail fixture：semgrep --test rules/semgrep/
```

### 2. AI 診斷引導
當 AI 發現異常時，請其參考：
> "請依據 `knowledge-base/financial-invariants.md` 檢查該 Findings 是否違反餘額守恆，並對比 `money-flow-map.md` 標記影響等級。"

## 📈 項目願景
消除金融系統中的「幽靈 Bug」，實現 95% 以上核心業務漏洞的自動化攔截與 85% 以上精準定級。

---
© 2026 AetherCare Systems - Financial DevSecOps Division.
