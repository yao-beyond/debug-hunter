// 端到端示範：金流端點缺速率/velocity 監控（PAT-SEC-112）。純 JDK。
// 對應：PAT-SEC-112 / RULE-SEC-112。
import java.math.BigDecimal;

public class VelocityDemo {

    static final BigDecimal DAILY_LIMIT = new BigDecimal("10000"); // 單日累計提款上限
    static BigDecimal dailyWithdrawn;
    static int accepted;

    // 漏洞版：提款無 velocity 限制
    static void withdrawVulnerable(BigDecimal amt) { dailyWithdrawn = dailyWithdrawn.add(amt); accepted++; }

    // 修復版：帳戶級單日累計 velocity 守衛
    static void withdrawFixed(BigDecimal amt) {
        if (dailyWithdrawn.add(amt).compareTo(DAILY_LIMIT) > 0)
            throw new SecurityException("超出單日提款上限，需二次驗證/風控審核");
        dailyWithdrawn = dailyWithdrawn.add(amt); accepted++;
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-SEC-112 缺速率/velocity 監控 ===\n");
        System.out.println("[DETECT]  提款端點無 rate limit / 單日累計 velocity 規則 → candidate finding");

        System.out.println("\n[REPRODUCE] PoC：被盜帳號連續 20 筆各 5000 提款（單日上限 " + DAILY_LIMIT + "）");
        {
            dailyWithdrawn = BigDecimal.ZERO; accepted = 0;
            for (int i = 0; i < 20; i++) withdrawVulnerable(new BigDecimal("5000"));
            boolean violated = dailyWithdrawn.compareTo(DAILY_LIMIT) > 0;
            System.out.println("           漏洞版：放行 " + accepted + " 筆，單日累計=" + dailyWithdrawn
                    + "（遠超上限），velocity 失控=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（無 velocity → 風控反應前已掏空）");
        }

        System.out.println("\n[VERIFY]   修復版：單日累計 velocity 守衛");
        {
            dailyWithdrawn = BigDecimal.ZERO; accepted = 0;
            int blocked = 0;
            for (int i = 0; i < 20; i++) {
                try { withdrawFixed(new BigDecimal("5000")); } catch (SecurityException e) { blocked++; }
            }
            boolean ok = dailyWithdrawn.compareTo(DAILY_LIMIT) <= 0 && accepted == 2 && blocked == 18;
            System.out.println("           修復版：放行 " + accepted + " 筆、攔截 " + blocked + " 筆，單日累計=" + dailyWithdrawn);
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（達上限即攔，累計不超限）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
