/**
 * @name 手動調帳/補償端點缺雙人覆核（maker-checker）
 * @description 高權限調帳端點直接動帳生效，未見 maker-checker 兩階段覆核流程。
 *              對應 PAT-SEC-108 / RULE-SEC-108 / INV-T-01。
 * @kind problem
 * @problem.severity error
 * @security-severity 8.2
 * @precision low
 * @id debug-hunter/rule-sec-108-maker-checker
 * @tags security
 *       external/cwe/cwe-862
 *       external/cwe/cwe-732
 *       financial
 */

import java

predicate hasMappingAnnotation(Method m) {
  m.getAnAnnotation().getType().getName().matches("%Mapping")
}

/** 高權限調帳 / 補償端點 */
predicate isAdminAdjustEndpoint(Method m) {
  hasMappingAnnotation(m) and
  (
    m.getName().toLowerCase().matches(["%adjust%", "%manual%", "%compensat%", "%correct%"])
    or
    exists(StringLiteral s |
      s = m.getAnAnnotation().getValue(_) and
      s.getValue().toLowerCase().matches(["%/admin/%", "%adjust%", "%compensat%"])
    )
  )
}

/** 直接動帳 */
predicate directBalanceChange(MethodCall ma) {
  ma.getMethod().getName() = ["credit", "debit", "setBalance", "adjustBalance"]
}

/** 具備 maker-checker / 兩階段覆核 */
predicate hasMakerChecker(Callable c) {
  exists(MethodCall a |
    a.getEnclosingCallable() = c and
    a.getMethod().getName().toLowerCase().matches(["%approve%", "%submit%", "%review%", "%checker%", "%dualcontrol%"])
  )
}

from Method m, MethodCall sink
where
  isAdminAdjustEndpoint(m) and
  sink.getEnclosingCallable() = m and
  directBalanceChange(sink) and
  not hasMakerChecker(m)
select sink,
  "高權限調帳直接生效，未見 maker-checker 雙人覆核 (PAT-SEC-108 / RULE-SEC-108)。"
