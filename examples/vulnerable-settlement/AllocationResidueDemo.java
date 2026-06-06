// 端到端示範：分配/拆分捨入殘差未處理 salami（PAT-FIN-006）。純 JDK。
// 對應：PAT-FIN-006 / RULE-FIN-007 / INV-TXN-05、INV-ST-03。
import java.math.BigDecimal;
import java.math.RoundingMode;

public class AllocationResidueDemo {

    static final BigDecimal TOTAL = new BigDecimal("100.00");
    static final BigDecimal N = new BigDecimal("3");   // 三方均分（100/3 除不盡）

    // 漏洞版：每方各自 total/3 捨入後入帳，加總少了殘差
    static BigDecimal allocateVulnerable(BigDecimal[] out) {
        BigDecimal each = TOTAL.divide(N, 2, RoundingMode.DOWN);   // 33.33
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) { out[i] = each; sum = sum.add(each); }
        return sum;                                                 // 99.99
    }
    // 修復版：前 n-1 方均分，最後一方吃殘差，保證守恆
    static BigDecimal allocateFixed(BigDecimal[] out) {
        BigDecimal each = TOTAL.divide(N, 2, RoundingMode.DOWN);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            out[i] = (i == 2) ? TOTAL.subtract(allocated) : each;  // 最後一方 = 總額 - 已分配
            allocated = allocated.add(out[i]);
        }
        return out[0].add(out[1]).add(out[2]);
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-FIN-006 分配捨入殘差（salami）===\n");
        System.out.println("[DETECT]  逐項 total/N 捨入後入帳，迴圈後無 Σ=total 守恆校驗 → candidate finding");

        System.out.println("\n[REPRODUCE] PoC：100.00 三方均分（100/3 各 33.33，向下捨入）");
        {
            BigDecimal[] out = new BigDecimal[3];
            BigDecimal sum = allocateVulnerable(out);
            boolean violated = sum.compareTo(TOTAL) != 0;
            System.out.println("           漏洞版：各方=" + out[0] + "/" + out[1] + "/" + out[2]
                    + "，加總=" + sum + "（總額 " + TOTAL + "），殘差=" + TOTAL.subtract(sum) + "，守恆違反=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（加總 99.99 ≠ 100.00，殘差長期累積 = salami 吞錢/造錢）");
        }

        System.out.println("\n[VERIFY]   修復版：最後一方吃殘差（守恆）");
        {
            BigDecimal[] out = new BigDecimal[3];
            BigDecimal sum = allocateFixed(out);
            boolean ok = sum.compareTo(TOTAL) == 0;
            System.out.println("           修復版：各方=" + out[0] + "/" + out[1] + "/" + out[2]
                    + "，加總=" + sum + "，守恆成立=" + ok);
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（Σ各方 == 總額，符合 INV-TXN-05）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
