// 端到端示範：Mass Assignment 請求參數直接綁定實體欄位（PAT-SEC-106）的「偵測 → 復現(PoC) → 驗收」閉環。
// 零外部依賴（純 JDK），可直接 `javac MassAssignmentDemo.java && java MassAssignmentDemo`。
// 對應知識庫：PAT-SEC-106 / INV-ST-02（餘額=初始+Σ分錄）/ INV-ST-05（狀態機）/ CORP-006。
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MassAssignmentDemo {

    // ====== 領域模型 ======
    static class Entry { String id; BigDecimal amount; Entry(BigDecimal a){ id = UUID.randomUUID().toString(); amount = a; } }

    static class Account {
        String id;
        String nickname;
        BigDecimal balance;     // 敏感：只能透過記帳路徑變更
        String status;          // 敏感：只能由狀態機合法躍遷（FROZEN 不可直接變 ACTIVE）
        List<Entry> entries = new ArrayList<>();
        Account(String id, String nickname, BigDecimal init, String status) {
            this.id = id; this.nickname = nickname; this.balance = init; this.status = status;
        }
        // 模擬框架（Jackson/Spring ModelAttribute）的自動綁定：有 setter + 請求有對應 key 就被呼叫
        void setNickname(String n) { this.nickname = n; }
        void setBalance(BigDecimal b) { this.balance = b; }   // ← 漏洞根源
        void setStatus(String s) { this.status = s; }         // ← 漏洞根源
        // 合法入帳：必須產生分錄
        void deposit(BigDecimal amt) { entries.add(new Entry(amt)); balance = balance.add(amt); }
    }

    // ====== 漏洞版：請求 Map 全欄位綁定到 Entity（PAT-SEC-106）======
    static void vulnerableUpdate(Account acc, Map<String, Object> body) {
        if (body.containsKey("nickname")) acc.setNickname((String) body.get("nickname"));
        if (body.containsKey("balance"))  acc.setBalance((BigDecimal) body.get("balance"));   // 造錢
        if (body.containsKey("status"))   acc.setStatus((String) body.get("status"));         // 越過狀態機
    }

    // ====== 修復版：白名單 DTO，只允許 nickname ======
    static void fixedUpdate(Account acc, Map<String, Object> body) {
        if (body.containsKey("nickname")) acc.setNickname((String) body.get("nickname"));
        // balance / status 不在白名單 → 忽略
    }

    // INV-ST-02：餘額 == 初始 + Σ分錄
    static boolean invST02Holds(Account acc, BigDecimal initial) {
        BigDecimal sum = acc.entries.stream().map(e -> e.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return acc.balance.compareTo(initial.add(sum)) == 0;
    }

    public static void main(String[] args) {
        int failures = 0;
        BigDecimal initial = new BigDecimal("100.00");
        BigDecimal attackAmt = new BigDecimal("999999.00");

        System.out.println("=== debug-hunter 端到端 demo：PAT-SEC-106 Mass Assignment 改餘額 ===\n");
        System.out.println("[DETECT]  taint: @RequestBody/@ModelAttribute 直接綁定持久化實體，欄位無白名單 → candidate finding");

        // 攻擊 body：在改暱稱的請求裡夾帶 balance（造錢）與 status（解凍）
        Map<String, Object> attack = new HashMap<>();
        attack.put("nickname", "Hacker");
        attack.put("balance", attackAmt);
        attack.put("status", "ACTIVE");

        System.out.println("\n[REPRODUCE] PoC：FROZEN 帳戶收到 { nickname:Hacker, balance:999999.00, status:ACTIVE }");
        {
            Account acc = new Account("A001", "Alice", initial, "FROZEN");
            vulnerableUpdate(acc, attack);
            boolean balViolated = !invST02Holds(acc, initial);
            boolean stateBypass = "ACTIVE".equals(acc.status);   // FROZEN 被直接改成 ACTIVE（INV-ST-05）
            System.out.println("           漏洞版：餘額=" + acc.balance + "、status=" + acc.status
                    + "（分錄 0 筆），INV-ST-02 違反=" + balViolated + "，狀態機被繞過=" + stateBypass);
            if (!(balViolated && stateBypass)) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（憑空造錢 + 解凍帳戶，餘額與帳本對不上）");
        }

        System.out.println("\n[VERIFY]   修復版（白名單 DTO）重跑同一請求");
        {
            Account acc = new Account("A002", "Bob", initial, "FROZEN");
            fixedUpdate(acc, attack);
            boolean ok = invST02Holds(acc, initial)
                    && acc.balance.compareTo(initial) == 0
                    && "FROZEN".equals(acc.status)
                    && "Hacker".equals(acc.nickname);
            System.out.println("           修復版：餘額=" + acc.balance + "（不變）、status=" + acc.status
                    + "（仍凍結）、nickname=" + acc.nickname + "（已更新）");
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（balance/status 被擋，只有白名單欄位生效）");
        }

        System.out.println("\n[VERIFY+]  負面對照：合法只改暱稱應成功，餘額/狀態不動");
        {
            Account acc = new Account("A003", "Carol", initial, "NORMAL");
            Map<String, Object> legit = new HashMap<>();
            legit.put("nickname", "Carol_new");
            fixedUpdate(acc, legit);
            boolean ok = "Carol_new".equals(acc.nickname) && acc.balance.compareTo(initial) == 0
                    && invST02Holds(acc, initial);
            System.out.println("           更新後 nickname=" + acc.nickname + "、餘額=" + acc.balance + "，未誤殺=" + ok);
            if (!ok) { System.out.println("  ✗ 正常流程被誤殺"); failures++; }
            else System.out.println("           ✔ 正常流程正確");
        }

        System.out.println("\n[VERIFY++] 正向對照：經 deposit 合法入帳 $50（有分錄）→ INV-ST-02 仍成立");
        {
            Account acc = new Account("A004", "Dave", initial, "NORMAL");
            acc.deposit(new BigDecimal("50.00"));
            boolean ok = invST02Holds(acc, initial) && acc.balance.compareTo(new BigDecimal("150.00")) == 0;
            System.out.println("           入帳後餘額=" + acc.balance + "（含 1 筆分錄），不變量成立=" + ok);
            if (!ok) { System.out.println("  ✗ 合法記帳反被判違反"); failures++; }
            else System.out.println("           ✔ 合法記帳路徑成立（對照重點：差別在『有沒有分錄』）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
