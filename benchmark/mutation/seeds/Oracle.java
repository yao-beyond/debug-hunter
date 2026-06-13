import java.math.BigDecimal;

// 不變量 oracle：對 LedgerService 的每條行為做斷言。
// 基線回傳 exit 0；任一 mutation 注入後，對應不變量被違反 → 印出 "<INV> violated" 並 exit 非 0。
// 本檔只供 generate.py 驗證 mutant 是否真的壞了，【絕不】交給偵測器。
public class Oracle {
    public static void main(String[] args) {
        int fail = 0;

        // INV-ST-01：帳戶歸屬——非持有人不得動帳。
        {
            LedgerService l = new LedgerService().open(100, 1, "0").open(200, 2, "1000");
            BigDecimal before = l.balanceOf(200);
            boolean blocked = false;
            try { l.withdraw(1, 200, new BigDecimal("1000")); }
            catch (LedgerService.AccessException e) { blocked = true; }
            boolean held = l.balanceOf(200).compareTo(before) == 0;
            if (!(blocked && held)) { System.out.println("INV-ST-01 violated"); fail++; }
            else System.out.println("INV-ST-01 ok");
        }

        // INV-ST-02：轉帳來源歸屬——非來源帳戶持有人不得轉出。
        {
            LedgerService l = new LedgerService().open(100, 1, "0").open(200, 2, "1000");
            BigDecimal before = l.balanceOf(200);
            boolean blocked = false;
            try { l.transfer(1, 200, 100, new BigDecimal("500")); }
            catch (LedgerService.AccessException e) { blocked = true; }
            boolean held = l.balanceOf(200).compareTo(before) == 0;
            if (!(blocked && held)) { System.out.println("INV-ST-02 violated"); fail++; }
            else System.out.println("INV-ST-02 ok");
        }

        // INV-TXN-05：分配守恆——Σ各方 == 總額。
        {
            try {
                BigDecimal total = new BigDecimal("100.00");
                BigDecimal[] p = LedgerService.split(total, 3);
                BigDecimal sum = p[0].add(p[1]).add(p[2]);
                if (sum.compareTo(total) != 0) { System.out.println("INV-TXN-05 violated"); fail++; }
                else System.out.println("INV-TXN-05 ok");
            } catch (ArithmeticException e) { System.out.println("INV-TXN-05 violated"); fail++; }
        }

        // INV-ST-03（精度）：累加不得有浮點誤差。
        {
            BigDecimal got = LedgerService.accumulate(new BigDecimal("0.01"), 1_000_000);
            if (got.compareTo(new BigDecimal("10000.00")) != 0) { System.out.println("INV-ST-03 violated"); fail++; }
            else System.out.println("INV-ST-03 ok");
        }

        // INV-ST-04（捨入）：費率計算須在 scale 2 正確捨入。
        {
            BigDecimal rated = LedgerService.applyRate(new BigDecimal("0.10"), new BigDecimal("1.105"));
            if (rated.compareTo(new BigDecimal("0.11")) != 0) { System.out.println("INV-ST-04 violated"); fail++; }
            else System.out.println("INV-ST-04 ok");
        }

        System.out.println(fail == 0 ? "BASELINE OK" : (fail + " violation(s)"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
