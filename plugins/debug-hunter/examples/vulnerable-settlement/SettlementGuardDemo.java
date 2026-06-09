// 端到端示範：結算結果缺乏業務合理性校驗（PAT-BIZ-002）。純 JDK。
// 對應：PAT-BIZ-002 / RULE-BIZ-001 / INV-TXN-06。
import java.math.BigDecimal;
import java.math.RoundingMode;

public class SettlementGuardDemo {

    static final BigDecimal MAX_PROFIT_RATE = new BigDecimal("2.0");   // 收益率上限 200%
    static final BigDecimal MAX_SINGLE_PAYOUT = new BigDecimal("1000000");

    static BigDecimal credited; // 模擬入帳

    // 漏洞版：算完直接入帳，無守衛
    static void settleVulnerable(BigDecimal amount, BigDecimal profit) { credited = profit; }

    // 修復版：入帳前業務合理性守衛
    static void settleFixed(BigDecimal amount, BigDecimal profit) {
        if (profit.signum() < 0) throw new IllegalStateException("收益為負");
        BigDecimal rate = profit.divide(amount, 6, RoundingMode.HALF_UP);
        if (rate.compareTo(MAX_PROFIT_RATE) > 0) throw new IllegalStateException("收益率超上限 " + rate);
        if (profit.compareTo(MAX_SINGLE_PAYOUT) > 0) throw new IllegalStateException("單筆賠付超上限");
        credited = profit;
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-BIZ-002 結算缺業務合理性校驗 ===\n");
        System.out.println("[DETECT]  計算完直接 credit，無收益率/單筆上限守衛 → candidate finding");

        BigDecimal amount = new BigDecimal("100");
        BigDecimal buggyProfit = new BigDecimal("5000");   // 計算 bug 算出 50 倍收益（5000%）

        System.out.println("\n[REPRODUCE] PoC：計算 bug 產生異常收益（本金 100、收益 5000 = 5000%）直接入帳");
        {
            credited = null;
            settleVulnerable(amount, buggyProfit);
            BigDecimal rate = buggyProfit.divide(amount, 6, RoundingMode.HALF_UP);
            boolean violated = rate.compareTo(MAX_PROFIT_RATE) > 0 && credited != null;
            System.out.println("           漏洞版：收益率=" + rate + "（上限 " + MAX_PROFIT_RATE + "），仍入帳=" + credited
                    + "，INV-TXN-06 違反=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（異常收益無守衛，計算 bug 直接變資損）");
        }

        System.out.println("\n[VERIFY]   修復版：SettlementGuard 入帳前校驗");
        {
            credited = null;
            boolean blocked = false;
            try { settleFixed(amount, buggyProfit); }
            catch (IllegalStateException e) { blocked = true; System.out.println("           攔截：" + e.getMessage()); }
            // 正常收益應放行
            credited = null;
            settleFixed(amount, new BigDecimal("8"));  // 8% 正常
            boolean normalOk = new BigDecimal("8").compareTo(credited) == 0;
            if (!(blocked && credited != null && normalOk)) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（異常收益被擋、正常收益正常入帳）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
