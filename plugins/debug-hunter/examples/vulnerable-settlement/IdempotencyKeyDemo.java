// 端到端示範：冪等鍵可預測/低熵（PAT-SEC-114）。純 JDK。
// 對應：PAT-SEC-114 / RULE-SEC-114 / INV-T-02。
import java.time.LocalDate;
import java.util.UUID;

public class IdempotencyKeyDemo {

    // 漏洞版：可預測鍵（userId + 日期）→ 攻擊者可預測他人鍵
    static String keyVulnerable(long userId) {
        return "settle:" + userId + ":" + LocalDate.of(2026, 6, 7);
    }
    // 修復版：高熵 requestId（與請求簽章綁定）
    static String keyFixed(long userId, String requestId) {
        return "idem:" + userId + ":" + requestId;   // requestId = 客戶端 UUID/隨機
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter demo：PAT-SEC-114 冪等鍵可預測/低熵 ===\n");
        System.out.println("[DETECT]  冪等鍵由可預測值組成（自增/時間戳/userId+date）→ candidate finding");

        System.out.println("\n[REPRODUCE] PoC：攻擊者預測受害者(userId=2)的冪等鍵");
        {
            String victimKey = keyVulnerable(2);                 // 系統實際用的鍵
            String attackerGuess = "settle:" + 2 + ":" + LocalDate.of(2026, 6, 7); // 攻擊者用公開規則重組
            boolean predictable = victimKey.equals(attackerGuess);
            System.out.println("           漏洞版鍵=" + victimKey + "，攻擊者猜測=" + attackerGuess + "，可預測=" + predictable);
            if (!predictable) { System.out.println("  ✗ PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（鍵可預測 → 搶先占用/覆蓋他人交易、繞過防重）");
        }

        System.out.println("\n[VERIFY]   修復版：高熵 requestId（客戶端 UUID）");
        {
            String real = keyFixed(2, UUID.randomUUID().toString());
            // 攻擊者僅憑公開資訊（userId+date）無法重組
            String guess = keyFixed(2, "settle:2:" + LocalDate.of(2026, 6, 7));
            boolean unpredictable = !real.equals(guess);
            System.out.println("           修復版鍵=" + real + "，攻擊者可重組=" + !unpredictable);
            if (!unpredictable) { System.out.println("  ✗ 驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（高熵鍵不可預測，符合 INV-T-02 防重）");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
