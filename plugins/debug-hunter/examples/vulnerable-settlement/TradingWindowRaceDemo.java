// 端到端示範：委託時間窗口競態（PAT-BIZ-001）的「偵測 → 復現 → 驗收」閉環。
// 零外部依賴（純 JDK），可直接 `javac TradingWindowRaceDemo.java && java TradingWindowRaceDemo`。
// 對應知識庫：PAT-BIZ-001 / INV-T-03（時間一致）/ INV-ST-05（窗口狀態）/ RULE-BIZ-003。
public class TradingWindowRaceDemo {

    static final long CUTOFF = 1000; // 窗口截止時間（含義：decisionTime < CUTOFF 才可接單）

    // 可控時鐘（避免依賴系統時間，讓競態可重現）
    static final class Clock {
        long t;
        Clock(long t) { this.t = t; }
        long now() { return t; }
        void advance(long d) { t += d; }
    }

    static final class Order {
        boolean accepted;
        long decisionTime = -1;
    }

    // ====== 漏洞版：check-then-act —— 早早快照 isOpen，延遲後仍用過時判斷接單 ======
    static void acceptVulnerable(Clock c, Order o) {
        boolean open = c.now() < CUTOFF;   // T1 檢查（此刻 999 → open=true）
        c.advance(2);                      // 模擬處理延遲，時間來到 1001（已過 cutoff）
        if (open) {                        // ← 用過時的 open，接了不該接的單
            o.accepted = true;
            o.decisionTime = c.now();      // 實際決策時間 1001 ≥ cutoff
        }
    }

    // ====== 修復版：決策點以權威時鐘一次取定 decisionTime，原子判斷窗口 ======
    static void acceptFixed(Clock c, Order o) {
        c.advance(2);                      // 同樣的處理延遲，時間來到 1001
        long decision = c.now();           // 於「接單決策點」取權威時間（非早期快照）
        if (decision < CUTOFF) {           // 原子判斷：已過 cutoff 即拒
            o.accepted = true;
            o.decisionTime = decision;
        }
    }

    // INV：已接受的委託，其決策時間必須落在窗口內（< CUTOFF）。
    static boolean invWindowViolated(Order o) {
        return o.accepted && o.decisionTime >= CUTOFF;
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter 端到端 demo：PAT-BIZ-001 委託時間窗口競態 ===\n");
        System.out.println("[DETECT]  並發/時序: isOpen() 檢查與接單之間窗口已關（check-then-act 跨 cutoff）→ candidate finding");

        // PoC：請求在 cutoff 前一刻（t=999）進來，處理延遲後實際接單在 t=1001（已過 cutoff）
        System.out.println("\n[REPRODUCE] PoC：t=999 檢查窗口開，處理延遲 2，實際接單於 t=1001（cutoff=" + CUTOFF + "）");
        {
            Order o = new Order();
            acceptVulnerable(new Clock(999), o);
            boolean violated = invWindowViolated(o);
            System.out.println("           漏洞版：accepted=" + o.accepted + "，decisionTime=" + o.decisionTime
                    + "，INV(決策時間<cutoff) 違反=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（用過時 isOpen 接了 cutoff 之後的單，歸期/風控錯誤）");
        }

        System.out.println("\n[VERIFY]   修復版（權威時鐘 + 原子判斷）重跑同一情境");
        {
            Order o = new Order();
            acceptFixed(new Clock(999), o);
            boolean ok = !o.accepted && !invWindowViolated(o);
            System.out.println("           修復版：accepted=" + o.accepted + "（cutoff 後正確拒單），INV 恆成立=" + !invWindowViolated(o));
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（決策點重新取權威時間，過 cutoff 即拒）");
        }

        System.out.println("\n[VERIFY+]  負面對照：窗口內委託（t=500）應被接受");
        {
            Order o = new Order();
            acceptFixed(new Clock(500), o);   // 500+2=502 < 1000
            boolean ok = o.accepted && o.decisionTime < CUTOFF && !invWindowViolated(o);
            System.out.println("           窗口內接單 accepted=" + o.accepted + "，decisionTime=" + o.decisionTime + "，未誤殺=" + ok);
            if (!ok) { System.out.println("  ✗ 正常委託被誤殺"); failures++; }
            else System.out.println("           ✔ 窗口內委託正常接受");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
