// 端到端示範：手動調帳缺雙人覆核 maker-checker（PAT-SEC-108）。純 JDK。
// 對應：PAT-SEC-108 / RULE-SEC-108 / INV-T-01。
import java.math.BigDecimal;

public class MakerCheckerDemo {

    static BigDecimal balance = new BigDecimal("1000");

    // 漏洞版：單人 admin 直接調帳
    static void adjustVulnerable(String operator, BigDecimal delta) {
        balance = balance.add(delta);
    }

    // 修復版：maker 發起 → checker（不同人）覆核才生效
    record Adjustment(String maker, BigDecimal delta, boolean approved) {}
    static Adjustment submit(String maker, BigDecimal delta) { return new Adjustment(maker, delta, false); }
    static void approveAndApply(Adjustment ar, String checker) {
        if (checker.equals(ar.maker())) throw new SecurityException("覆核人不得為發起人（maker==checker）");
        balance = balance.add(ar.delta());   // 雙人通過才動帳
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-SEC-108 調帳缺 maker-checker ===\n");
        System.out.println("[DETECT]  高權限調帳端點單人即可生效，無雙人覆核 → candidate finding");

        System.out.println("\n[REPRODUCE] PoC：單一 admin（alice）直接把自己帳戶 +1,000,000");
        {
            balance = new BigDecimal("1000");
            adjustVulnerable("alice", new BigDecimal("1000000"));
            boolean violated = balance.compareTo(new BigDecimal("1001000")) == 0;
            System.out.println("           漏洞版：單人調帳後餘額=" + balance + "（無第二人覆核、難追責），INV-T-01 風險=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（單一被盜/惡意 admin 即可掏空）");
        }

        System.out.println("\n[VERIFY]   修復版：maker-checker 雙人覆核");
        {
            balance = new BigDecimal("1000");
            Adjustment ar = submit("alice", new BigDecimal("1000000"));
            boolean selfBlocked = false;
            try { approveAndApply(ar, "alice"); } catch (SecurityException e) { selfBlocked = true; System.out.println("           自我覆核被擋：" + e.getMessage()); }
            boolean stillUnchanged = balance.compareTo(new BigDecimal("1000")) == 0;
            // 不同人覆核才生效
            approveAndApply(ar, "bob");
            boolean appliedByChecker = balance.compareTo(new BigDecimal("1001000")) == 0;
            if (!(selfBlocked && stillUnchanged && appliedByChecker)) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（maker 自核被擋；需不同人覆核方生效）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
