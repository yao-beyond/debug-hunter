// 端到端示範：偽造支付回調（PAT-SEC-104 Webhook 偽造）的「偵測 → 復現(PoC) → 驗收」閉環。
// 零外部依賴（純 JDK，HMAC 用 javax.crypto），可直接 `javac PaymentCallbackDemo.java && java PaymentCallbackDemo`。
// 對應知識庫：PAT-SEC-104 / INV-T-03（內外對帳一致）/ threat-catalog AB-04 / CORP-004。
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class PaymentCallbackDemo {

    // 只有真正的支付閘道與我方後端知道的共享密鑰（攻擊者沒有）
    static final String GATEWAY_SECRET = "gw_live_secret_8f3a2b";

    record Order(String orderNo, String userId, BigDecimal amount, boolean paid) {}

    static final class Sys {
        final Map<String, Order> orders = new HashMap<>();
        final Map<String, BigDecimal> balance = new HashMap<>();
        // 外部真相：閘道實際收到款的訂單與金額（對帳基準）。偽造回調不會出現在這裡。
        final Map<String, BigDecimal> gatewayConfirmed = new HashMap<>();

        Sys order(String no, String user, String amt) {
            orders.put(no, new Order(no, user, new BigDecimal(amt), false));
            balance.putIfAbsent(user, BigDecimal.ZERO);
            return this;
        }
        BigDecimal bal(String user) { return balance.getOrDefault(user, BigDecimal.ZERO); }
    }

    // HMAC-SHA256 簽章（閘道對 rawBody 簽，我方用同密鑰驗）
    static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(GATEWAY_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static Map<String, String> parse(String body) {   // "k=v&k=v"
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    // ====== 漏洞版：只看 body 的 status，未驗簽、信任對方金額（PAT-SEC-104）======
    static void onCallbackVulnerable(Sys s, String rawBody) {
        Map<String, String> f = parse(rawBody);
        if ("SUCCESS".equals(f.get("status"))) {                       // ← 任何人可偽造
            String user = f.get("userId");
            BigDecimal amt = new BigDecimal(f.get("amount"));          // ← 金額也信任對方
            s.orders.computeIfPresent(f.get("orderNo"),
                (k, o) -> new Order(o.orderNo(), o.userId(), amt, true));
            s.balance.merge(user, amt, BigDecimal::add);
        }
    }

    // ====== 修復版：驗簽 + 我方權威金額比對 + 冪等 ======
    static void onCallbackFixed(Sys s, String rawBody, String signature) {
        // 1. 完整性：驗 HMAC 簽章
        if (!sign(rawBody).equals(signature))
            throw new SecurityException("回調簽章驗證失敗");
        Map<String, String> f = parse(rawBody);
        // 2. 訂單必須存在
        Order ord = s.orders.get(f.get("orderNo"));
        if (ord == null) throw new SecurityException("查無此訂單");
        // 3. 金額以我方訂單為準，外部值僅供比對
        if (ord.amount().compareTo(new BigDecimal(f.get("amount"))) != 0)
            throw new SecurityException("回調金額與訂單不符");
        // 4. 冪等：已入帳則跳過
        if (ord.paid()) return;
        s.orders.put(ord.orderNo(), new Order(ord.orderNo(), ord.userId(), ord.amount(), true));
        s.balance.merge(ord.userId(), ord.amount(), BigDecimal::add);
    }

    // INV-T-03：我方為某訂單入帳的金額，必須等於閘道實際確認收款的金額。
    // 偽造回調 → gatewayConfirmed 沒有該筆 → 一旦入帳就違反。
    static boolean invT03Violated(Sys s, String user, String orderNo) {
        BigDecimal confirmed = s.gatewayConfirmed.getOrDefault(orderNo, BigDecimal.ZERO);
        return s.bal(user).compareTo(confirmed) > 0;   // 入帳 > 閘道確認 = 憑空入帳
    }

    public static void main(String[] args) {
        int failures = 0;
        System.out.println("=== debug-hunter 端到端 demo：PAT-SEC-104 偽造支付回調 ===\n");
        System.out.println("[DETECT]  taint: HTTP 回調 body.status → markPaid + credit，path 上無簽章驗證 → candidate finding");

        // 攻擊者偽造的回調：宣稱 SUCCESS、灌大金額，但沒有有效簽章
        String forgedBody = "orderNo=ORD-1&userId=alice&status=SUCCESS&amount=100000";

        // STAGE 2.5 REPRODUCE：偽造回調打進漏洞版
        System.out.println("\n[REPRODUCE] PoC：攻擊者直接 POST 偽造回調（無有效簽章，金額灌成 100000）");
        {
            Sys s = new Sys().order("ORD-1", "alice", "100");   // alice 發起 100 儲值但「從未真的付款」
            onCallbackVulnerable(s, forgedBody);
            boolean violated = invT03Violated(s, "alice", "ORD-1");
            System.out.println("           漏洞版：alice 餘額 = " + s.bal("alice")
                    + "（閘道實收 0），INV-T-03 違反=" + violated);
            if (!violated) { System.out.println("  ✗ 預期攻擊成功，PoC 無效"); failures++; }
            else System.out.println("           ✔ PoC 成功（偽造入帳得逞，白嫖 100000）");
        }

        // STAGE 4 VERIFY：修復版擋下同一偽造回調
        System.out.println("\n[VERIFY]   修復版（驗簽 + 我方金額 + 冪等）重跑同一偽造回調");
        {
            Sys s = new Sys().order("ORD-1", "alice", "100");
            boolean blocked = false;
            try {
                onCallbackFixed(s, forgedBody, "deadbeef" /* 攻擊者亂編的簽章 */);
            } catch (SecurityException e) {
                blocked = true;
                System.out.println("           攻擊被擋：" + e.getMessage());
            }
            boolean violated = invT03Violated(s, "alice", "ORD-1");
            boolean ok = blocked && !violated && s.bal("alice").compareTo(BigDecimal.ZERO) == 0;
            System.out.println("           修復版：alice 餘額不變=" + (s.bal("alice").signum() == 0)
                    + "，攻擊被擋=" + blocked + "，INV-T-03 恆成立=" + !violated);
            if (!ok) { System.out.println("  ✗ 修復驗收失敗"); failures++; }
            else System.out.println("           ✔ 驗收通過（偽造回調無法入帳 ∧ 不變量恆成立）");
        }

        // 變體：合法簽章但竄改金額（中間人改大金額）→ 修復版以我方訂單金額為準擋下
        System.out.println("\n[VERIFY+]  變體：金額竄改（簽章對不上竄改後的 body）");
        {
            Sys s = new Sys().order("ORD-1", "alice", "100");
            String legitBody = "orderNo=ORD-1&userId=alice&status=SUCCESS&amount=100";
            String legitSig = sign(legitBody);                       // 閘道對「正確金額」簽章
            String tamperedBody = "orderNo=ORD-1&userId=alice&status=SUCCESS&amount=100000";
            boolean blocked = false;
            try { onCallbackFixed(s, tamperedBody, legitSig); }      // 用舊簽章配竄改 body
            catch (SecurityException e) { blocked = true; System.out.println("           竄改被擋：" + e.getMessage()); }
            if (!blocked) { System.out.println("  ✗ 金額竄改未被擋"); failures++; }
            else System.out.println("           ✔ 竄改 body 導致簽章不符，攔截成功");
        }

        // 正向對照（負面測試）：真實閘道回調（正確簽章 + 正確金額）應成功入帳，且不誤殺
        System.out.println("\n[VERIFY++] 負面對照：真實閘道回調（正確簽章 + 正確金額）應成功入帳");
        {
            Sys s = new Sys().order("ORD-1", "alice", "100");
            s.gatewayConfirmed.put("ORD-1", new BigDecimal("100"));  // 閘道確實收到 100
            String body = "orderNo=ORD-1&userId=alice&status=SUCCESS&amount=100";
            String sig = sign(body);                                 // 閘道用共享密鑰簽
            boolean ok = true;
            try { onCallbackFixed(s, body, sig); } catch (Exception e) { ok = false; }
            ok = ok && s.bal("alice").compareTo(new BigDecimal("100")) == 0
                    && !invT03Violated(s, "alice", "ORD-1");
            // 冪等檢查：重送同一回調不應重複入帳
            onCallbackFixed(s, body, sig);
            ok = ok && s.bal("alice").compareTo(new BigDecimal("100")) == 0;
            System.out.println("           真實回調入帳後 alice 餘額=" + s.bal("alice")
                    + "（重送一次仍為 100，冪等），正常流程未被誤殺=" + ok);
            if (!ok) { System.out.println("  ✗ 真實回調被誤殺或冪等失效"); failures++; }
            else System.out.println("           ✔ 正常流程正確入帳且冪等");
        }

        System.out.println("\n=== 結果：" + (failures == 0 ? "全部通過 ✅ 閉環成立" : (failures + " 項失敗 ❌")) + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
