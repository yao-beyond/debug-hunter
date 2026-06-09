// 端到端示範：divide() 未指定 RoundingMode（PAT-FIN-004）。純 JDK。
// 對應：PAT-FIN-004 / RULE-FIN-005。
import java.math.BigDecimal;
import java.math.RoundingMode;

public class DivideRoundingDemo {
    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-FIN-004 divide() 未指定 RoundingMode ===\n");
        System.out.println("[DETECT]  BigDecimal.divide() 未給 scale+RoundingMode → 除不盡拋例外 → candidate finding");

        BigDecimal profit = new BigDecimal("100");
        BigDecimal principal = new BigDecimal("3");   // 100/3 除不盡

        System.out.println("\n[REPRODUCE] PoC：profit.divide(principal) 無 scale/RoundingMode（100/3）");
        {
            boolean threw = false;
            try {
                BigDecimal r = profit.divide(principal);   // ← ArithmeticException
                System.out.println("           非預期得到 " + r);
            } catch (ArithmeticException e) {
                threw = true;
                System.out.println("           拋出 ArithmeticException：" + e.getMessage() + "（結算流程中斷）");
            }
            if (!threw) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（除不盡未指定捨入 → 例外，可中斷結算）");
        }

        System.out.println("\n[VERIFY]   修復版：divide(divisor, scale, RoundingMode)");
        {
            BigDecimal r = profit.divide(principal, 8, RoundingMode.HALF_UP);
            boolean ok = r.compareTo(new BigDecimal("33.33333333")) == 0;
            System.out.println("           100/3 = " + r + "（明確 scale=8 + HALF_UP）");
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（明確捨入，不中斷、精度可控）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
