// 端到端示範：BigDecimal.equals() 比較金額（PAT-FIN-003）。純 JDK，`javac ... && java BigDecimalEqualsDemo`。
// 對應：PAT-FIN-003 / RULE-FIN-004。
import java.math.BigDecimal;

public class BigDecimalEqualsDemo {
    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-FIN-003 BigDecimal.equals() 比較 ===\n");
        System.out.println("[DETECT]  BigDecimal 用 equals() 比較（同時比 scale）→ candidate finding");

        BigDecimal a = new BigDecimal("1.0");
        BigDecimal b = new BigDecimal("1.00");

        System.out.println("\n[REPRODUCE] PoC：1.0.equals(1.00) ?");
        {
            boolean eq = a.equals(b);             // ← scale 不同 → false
            System.out.println("           equals 結果=" + eq + "（數值相等卻回 false，金額比較會誤判）");
            if (eq) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（equals 同時比 scale，1.0 ≠ 1.00）");
        }

        System.out.println("\n[VERIFY]   修復版：compareTo() == 0");
        {
            boolean ok = a.compareTo(b) == 0
                    && new BigDecimal("0.00").compareTo(BigDecimal.ZERO) == 0;
            System.out.println("           1.0 compareTo 1.00 == 0 → " + ok);
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（compareTo 只比數值，正確）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
