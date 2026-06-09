// 端到端示範：金流操作缺乏不可竄改稽核軌跡（PAT-SEC-110）。純 JDK。
// 對應：PAT-SEC-110 / RULE-SEC-110 / INV-T-01（每筆 delta 對應唯一審計分錄）。
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class AuditTrailDemo {

    static BigDecimal balance;
    static final List<BigDecimal> auditLog = new ArrayList<>();   // append-only 審計（記每筆 delta）

    // 漏洞版：動帳不寫審計
    static void debitVulnerable(BigDecimal amt) { balance = balance.subtract(amt); }

    // 修復版：動帳同時 append-only 審計（含 delta，可重建/追溯）
    static void debitFixed(BigDecimal amt) {
        balance = balance.subtract(amt);
        auditLog.add(amt.negate());
    }

    // INV-T-01：餘額變動必可由審計分錄重建（初始 + Σ審計 == 現餘額）
    static boolean invT01Holds(BigDecimal initial) {
        BigDecimal sum = auditLog.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return initial.add(sum).compareTo(balance) == 0;
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-SEC-110 缺不可竄改稽核軌跡 ===\n");
        System.out.println("[DETECT]  資金變動後無 append-only 審計 → 無法追責/重建 → candidate finding");

        BigDecimal initial = new BigDecimal("1000");

        System.out.println("\n[REPRODUCE] PoC：連續 3 筆扣款不寫審計");
        {
            balance = initial; auditLog.clear();
            debitVulnerable(new BigDecimal("100"));
            debitVulnerable(new BigDecimal("250"));
            debitVulnerable(new BigDecimal("50"));
            boolean violated = !invT01Holds(initial);   // 餘額少了 400，但審計 0 筆 → 無法追溯
            System.out.println("           漏洞版：餘額=" + balance + "，審計筆數=" + auditLog.size()
                    + "（餘額變動無對應分錄），INV-T-01 違反=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（少了 400 卻零審計，事故無法追責/重建）");
        }

        System.out.println("\n[VERIFY]   修復版：每筆動帳寫 append-only 審計");
        {
            balance = initial; auditLog.clear();
            debitFixed(new BigDecimal("100"));
            debitFixed(new BigDecimal("250"));
            debitFixed(new BigDecimal("50"));
            boolean ok = invT01Holds(initial) && auditLog.size() == 3;
            System.out.println("           修復版：餘額=" + balance + "，審計筆數=" + auditLog.size()
                    + "，初始+Σ審計==餘額 → " + invT01Holds(initial));
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（每筆 delta 可追溯，符合 INV-T-01）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
