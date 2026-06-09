/**
 * @name 餘額檢查與扣款非原子（TOCTOU 雙花）
 * @description 方法內先讀餘額再扣款，但未見鎖或原子條件式扣款，並發下可超提（雙花）。
 *              對應 PAT-SEC-103 / RULE-SEC-103 / INV-ST-01。
 * @kind problem
 * @problem.severity error
 * @security-severity 9.1
 * @precision medium
 * @id debug-hunter/rule-sec-103-toctou
 * @tags security
 *       external/cwe/cwe-362
 *       external/cwe/cwe-367
 *       financial
 */

import java

/** 餘額讀取（檢查）呼叫 */
predicate balanceCheck(MethodCall ma) {
  ma.getMethod().getName() = ["getBalance", "findBalance", "queryBalance"]
}

/** 非原子的扣款呼叫（排除 debitIfEnough 之類的原子條件式扣款） */
predicate nonAtomicDebit(MethodCall ma) {
  ma.getMethod().getName() = ["debit", "decrease", "withdraw", "deduct"] and
  not ma.getMethod().getName().toLowerCase().matches(["%ifenough%", "%ifsufficient%", "%foratomic%"])
}

/** 方法本身或方法內具備鎖/原子保護 */
predicate hasConcurrencyGuard(Callable c) {
  c.(Method).isSynchronized()
  or
  exists(MethodCall g |
    g.getEnclosingCallable() = c and
    g.getMethod().getName().toLowerCase().matches(["%lock%", "%trylock%", "%acquire%", "%setifabsent%"])
  )
  or
  // 原子條件式扣款（UPDATE ... WHERE balance >= amount 之語意）
  exists(MethodCall a |
    a.getEnclosingCallable() = c and
    a.getMethod().getName().toLowerCase().matches(["%ifenough%", "%ifsufficient%"])
  )
}

from MethodCall debit, Callable c
where
  debit.getEnclosingCallable() = c and
  nonAtomicDebit(debit) and
  exists(MethodCall chk | chk.getEnclosingCallable() = c and balanceCheck(chk)) and
  not hasConcurrencyGuard(c)
select debit,
  "TOCTOU 風險：同一方法內先檢查餘額再扣款，未見鎖或原子條件式扣款，並發下可雙花 (PAT-SEC-103 / RULE-SEC-103)。"
