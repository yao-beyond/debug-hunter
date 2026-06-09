// 端到端示範：優惠/紅利可疊加套利（PAT-SEC-113）。純 JDK。
// 對應：PAT-SEC-113 / RULE-SEC-113 / INV-TXN-05。
import java.math.BigDecimal;
import java.util.List;

public class PromoAbuseDemo {

    record Coupon(String type, BigDecimal value) {}

    // 漏洞版：所有券無互斥、無下限，直接相減（可疊到負）
    static BigDecimal applyVulnerable(BigDecimal total, List<Coupon> coupons) {
        BigDecimal r = total;
        for (Coupon c : coupons) r = r.subtract(c.value());
        return r;   // ← 可能為負（折扣 > 原價）
    }

    // 修復版：同類互斥取最優一張 + 折後不為負
    static BigDecimal applyFixed(BigDecimal total, List<Coupon> coupons) {
        BigDecimal best = coupons.stream().map(Coupon::value).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal r = total.subtract(best);            // 互斥：只用最優一張
        return r.signum() < 0 ? BigDecimal.ZERO : r;    // 折後不為負
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-SEC-113 優惠疊加套利 ===\n");
        System.out.println("[DETECT]  優惠疊加無互斥/上限/折後非負校驗 → candidate finding");

        BigDecimal price = new BigDecimal("100");
        List<Coupon> coupons = List.of(
                new Coupon("discount", new BigDecimal("60")),
                new Coupon("discount", new BigDecimal("60")),
                new Coupon("cashback", new BigDecimal("60")));

        System.out.println("\n[REPRODUCE] PoC：原價 100 疊三張 60 折價券");
        {
            BigDecimal payable = applyVulnerable(price, coupons);
            boolean violated = payable.signum() < 0;     // 折後為負 = 平台倒貼
            System.out.println("           漏洞版：應付=" + payable + "（折扣疊到負，平台倒貼），套利成立=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（券可疊加 → 折後為負，群體性薅羊毛）");
        }

        System.out.println("\n[VERIFY]   修復版：同類互斥取最優一張 + 折後不為負");
        {
            BigDecimal payable = applyFixed(price, coupons);
            boolean ok = payable.compareTo(new BigDecimal("40")) == 0;  // 100 - 60 = 40
            System.out.println("           修復版：應付=" + payable + "（只用最優一張 60，折後非負）");
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（互斥 + 下限保護，符合 INV-TXN-05）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
