// 端到端示範：多資產精度硬編碼 scale（PAT-FIN-005）。純 JDK。
// 對應：PAT-FIN-005 / RULE-FIN-006 / INV-TXN-02。
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

public class AssetScaleDemo {

    // 各資產的正確精度
    static final Map<String, Integer> SCALE = Map.of("BTC", 8, "USDT", 6, "JPY", 0);

    // 漏洞版：所有資產硬編碼 scale=8
    static BigDecimal settleVulnerable(BigDecimal amount) {
        return amount.setScale(8, RoundingMode.HALF_DOWN);   // ← JPY 也變 8 位小數
    }
    // 修復版：依資產中繼資料
    static BigDecimal settleFixed(String coin, BigDecimal amount) {
        return amount.setScale(SCALE.get(coin), RoundingMode.HALF_DOWN);
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-FIN-005 多資產精度硬編碼 scale ===\n");
        System.out.println("[DETECT]  setScale(<常數>) 對所有資產一視同仁，未依資產定義 → candidate finding");

        System.out.println("\n[REPRODUCE] PoC：對 JPY（精度 0）硬編碼 scale=8");
        {
            BigDecimal jpy = new BigDecimal("100");
            BigDecimal bad = settleVulnerable(jpy);
            boolean violated = bad.scale() != SCALE.get("JPY");   // 8 != 0
            System.out.println("           漏洞版 JPY 結算=" + bad + "（scale=" + bad.scale()
                    + "，JPY 不該有小數），精度違反=" + violated);
            if (!violated) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（硬編碼 scale 對 JPY 產生不存在的小數，跨幣別對帳出錯）");
        }

        System.out.println("\n[VERIFY]   修復版：scale 由資產中繼資料決定");
        {
            BigDecimal jpy = settleFixed("JPY", new BigDecimal("100"));
            BigDecimal btc = settleFixed("BTC", new BigDecimal("0.12345678"));
            boolean ok = jpy.scale() == 0 && btc.scale() == 8;
            System.out.println("           JPY=" + jpy + "(scale " + jpy.scale() + ")，BTC=" + btc + "(scale " + btc.scale() + ")");
            if (!ok) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（每個資產用自己的精度，符合 INV-TXN-02）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
