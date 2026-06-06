// 端到端示範：行情/匯率預言機操縱（PAT-SEC-105）的「偵測 → 復現(PoC) → 驗收」閉環。
// 零外部依賴（純 JDK），可直接 `javac OracleManipulationDemo.java && java OracleManipulationDemo`。
// 對應知識庫：PAT-SEC-105 / threat-catalog AB-05 / INV-ST-03（資產守恆）/ CORP-005。
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OracleManipulationDemo {

    static final BigDecimal MAX_DEVIATION = new BigDecimal("0.10"); // 與上一有效價偏離上限 10%
    static final long MAX_AGE_MS = 60_000;                          // 價格時效窗 1 分鐘
    static final int MIN_SOURCES = 3;                              // 最少有效來源數

    record Quote(String source, BigDecimal price, long ts) {}

    static class StalePriceException extends RuntimeException { StalePriceException(String m){super(m);} }
    static class PriceDeviationException extends RuntimeException { PriceDeviationException(String m){super(m);} }

    // ====== 漏洞版：單一來源、直接用、不看時效（PAT-SEC-105）======
    static BigDecimal priceVulnerable(List<Quote> feed) {
        return feed.get(0).price();   // ← 只信第一個來源（可被操縱），也不檢查是否陳舊
    }

    // ====== 修復版：多源中位數 + 時效窗 + 偏離熔斷 ======
    static BigDecimal priceFixed(List<Quote> feed, long now, BigDecimal lastValid) {
        List<BigDecimal> fresh = new ArrayList<>();
        for (Quote q : feed) if (now - q.ts() <= MAX_AGE_MS) fresh.add(q.price());
        if (fresh.size() < MIN_SOURCES)
            throw new StalePriceException("有效來源不足或價格陳舊（fresh=" + fresh.size() + "）");
        BigDecimal median = median(fresh);
        BigDecimal dev = median.subtract(lastValid).abs().divide(lastValid, 6, RoundingMode.HALF_UP);
        if (dev.compareTo(MAX_DEVIATION) > 0)
            throw new PriceDeviationException("價格偏離異常 " + dev.movePointRight(2) + "%，暫停結算");
        return median;
    }

    static BigDecimal median(List<BigDecimal> xs) {
        List<BigDecimal> s = new ArrayList<>(xs);
        Collections.sort(s);
        int n = s.size();
        if (n % 2 == 1) return s.get(n / 2);
        return s.get(n / 2 - 1).add(s.get(n / 2)).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
    }

    // 結算賠付 = 持倉數量 × 結算價
    static BigDecimal settle(BigDecimal qty, BigDecimal price) {
        return qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
    }

    // INV-ST-03（簡化）：結算價必須貼近公允價（誠實多源中位數）±10%，否則＝憑空多付/少付，破壞守恆。
    static boolean priceSane(BigDecimal used, BigDecimal fair) {
        return used.subtract(fair).abs()
                .divide(fair, 6, RoundingMode.HALF_UP)
                .compareTo(MAX_DEVIATION) <= 0;
    }

    public static void main(String[] args) {
        int failures = 0;
        long now = 1_000_000_000_000L;                 // 固定「現在」時間（避免依賴系統時鐘）
        BigDecimal qty = new BigDecimal("1");          // 持倉 1 單位
        BigDecimal fair = new BigDecimal("100");       // 公允價（誠實來源中位數）
        BigDecimal lastValid = new BigDecimal("100");  // 上一筆有效結算價

        System.out.println("=== debug-hunter 端到端 demo：PAT-SEC-105 預言機操縱 ===\n");
        System.out.println("[DETECT]  taint: priceApi.getPrice() 單一來源 → 直接用於結算，無多源/時效/偏離校驗 → candidate finding");

        // STAGE 2.5 REPRODUCE：攻擊者瞬間拉抬「漏洞版所讀的那個來源」到 100000
        System.out.println("\n[REPRODUCE] PoC：攻擊者把單一來源瞬間拉抬到 100000（公允價 100）");
        {
            List<Quote> feed = List.of(
                new Quote("attacker-pumped", new BigDecimal("100000"), now),  // 漏洞版讀這個
                new Quote("exchange-A", new BigDecimal("100"), now),
                new Quote("exchange-B", new BigDecimal("101"), now),
                new Quote("exchange-C", new BigDecimal("99"), now));
            BigDecimal used = priceVulnerable(feed);
            BigDecimal payout = settle(qty, used);
            boolean violated = !priceSane(used, fair);
            System.out.println("           漏洞版：結算價=" + used + "，賠付=" + payout
                    + "（公允應約 " + settle(qty, fair) + "），偏離公允=" + violated);
            if (!violated) { System.out.println("  ✗ 預期攻擊成功，PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（以被操縱價結算，定向多賠 ~1000 倍）");
        }

        // STAGE 4 VERIFY：修復版用多源中位數，操縱的離群值被排除
        System.out.println("\n[VERIFY]   修復版（多源中位數）重跑同一攻擊");
        {
            List<Quote> feed = List.of(
                new Quote("attacker-pumped", new BigDecimal("100000"), now),
                new Quote("exchange-A", new BigDecimal("100"), now),
                new Quote("exchange-B", new BigDecimal("101"), now),
                new Quote("exchange-C", new BigDecimal("99"), now));
            BigDecimal used = priceFixed(feed, now, lastValid);
            BigDecimal payout = settle(qty, used);
            boolean sane = priceSane(used, fair);
            System.out.println("           修復版：結算價=" + used + "（中位數，已排除離群），賠付=" + payout
                    + "，貼近公允=" + sane);
            if (!sane) { System.out.println("  ✗ 修復後仍偏離公允"); failures++; }
            else System.out.println("           ✔ 驗收通過（操縱離群值被中位數排除）");
        }

        // 變體 1：陳舊價（快取 10 分鐘前的價）→ 修復版時效校驗擋下
        System.out.println("\n[VERIFY+]  變體：陳舊價（10 分鐘前）");
        {
            List<Quote> stale = List.of(new Quote("cache", new BigDecimal("100"), now - 600_000));
            boolean blocked = false;
            try { priceFixed(stale, now, lastValid); }
            catch (StalePriceException e) { blocked = true; System.out.println("           陳舊被擋：" + e.getMessage()); }
            if (!blocked) { System.out.println("  ✗ 陳舊價未被擋"); failures++; }
            else System.out.println("           ✔ 時效窗攔截陳舊價");
        }

        // 變體 2：多源同時異常（閃崩/來源被攻陷）→ 偏離熔斷擋下，寧可暫停不亂結算
        System.out.println("\n[VERIFY++] 變體：多源同時異常偏離 → 偏離熔斷");
        {
            List<Quote> feed = List.of(
                new Quote("A", new BigDecimal("100000"), now),
                new Quote("B", new BigDecimal("100050"), now),
                new Quote("C", new BigDecimal("99950"), now));
            boolean halted = false;
            try { priceFixed(feed, now, lastValid); }
            catch (PriceDeviationException e) { halted = true; System.out.println("           熔斷：" + e.getMessage()); }
            if (!halted) { System.out.println("  ✗ 異常偏離未熔斷"); failures++; }
            else System.out.println("           ✔ 偏離過大時暫停結算（人工介入）");
        }

        // 正向對照：正常多源新鮮價 → 正確結算，不誤殺
        System.out.println("\n[VERIFY+++] 負面對照：正常多源新鮮價應正確結算");
        {
            List<Quote> feed = List.of(
                new Quote("A", new BigDecimal("100"), now),
                new Quote("B", new BigDecimal("101"), now),
                new Quote("C", new BigDecimal("99"), now));
            boolean ok = true;
            BigDecimal used = BigDecimal.ZERO;
            try { used = priceFixed(feed, now, lastValid); } catch (Exception e) { ok = false; }
            ok = ok && priceSane(used, fair);
            System.out.println("           正常結算價=" + used + "，賠付=" + settle(qty, used) + "，未誤殺=" + ok);
            if (!ok) { System.out.println("  ✗ 正常流程被誤殺"); failures++; }
            else System.out.println("           ✔ 正常流程正確結算");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
