// 端到端示範：金額以最小單位存 long 的整數溢位（PAT-FIN-008）。純 JDK。
// 對應：PAT-FIN-008 / RULE-FIN-009。
import java.math.BigInteger;

public class LongOverflowDemo {

    // 漏洞版：long 直接相乘
    static long feeVulnerable(long amountCents, long rateBps) {
        return amountCents * rateBps / 10_000;   // ← 中間值 amountCents*rateBps 可能溢位回繞
    }
    // 修復版：Math.multiplyExact（溢位即拋）或 BigInteger
    static long feeFixed(long amountCents, long rateBps) {
        return Math.multiplyExact(amountCents, rateBps) / 10_000;
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-FIN-008 long 金額整數溢位 ===\n");
        System.out.println("[DETECT]  long 金額直接 * / += 聚合，未用 Math.*Exact / BigInteger → candidate finding");

        long amountCents = 2_000_000_000_000_000L; // 大額（200 億元，以分計）
        long rateBps = 100_000;                    // 放大的費率

        System.out.println("\n[REPRODUCE] PoC：大額 long 乘法 amountCents × rateBps（中間值超過 Long.MAX）");
        {
            long bad = feeVulnerable(amountCents, rateBps);
            BigInteger exact = BigInteger.valueOf(amountCents)
                    .multiply(BigInteger.valueOf(rateBps)).divide(BigInteger.valueOf(10_000));
            boolean violated = BigInteger.valueOf(bad).compareTo(exact) != 0;
            System.out.println("           漏洞版 long 結果=" + bad + "（可能為負/回繞），精確值=" + exact
                    + "，溢位=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效（此輸入未溢位）"); failures++; }
            else System.out.println("           ✔ PoC 成功（long 中間值溢位回繞，金額算錯且不報錯）");
        }

        System.out.println("\n[VERIFY]   修復版：Math.multiplyExact（溢位即拋）");
        {
            boolean threw = false;
            try { feeFixed(amountCents, rateBps); }
            catch (ArithmeticException e) { threw = true; System.out.println("           溢位被攔：" + e.getMessage()); }
            // 正常範圍仍正確
            boolean normalOk = feeFixed(100_00L, 50) == 100_00L * 50 / 10_000;
            if (!(threw && normalOk)) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（溢位顯式拋例外、正常範圍仍正確）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
