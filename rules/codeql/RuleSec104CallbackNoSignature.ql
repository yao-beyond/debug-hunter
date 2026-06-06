/**
 * @name 支付/結算回調缺乏簽章驗證（Webhook 偽造）
 * @description 回調端點直接以 body 驅動入帳/狀態躍遷，但方法內未見簽章/HMAC 驗證。
 *              對應 PAT-SEC-104 / RULE-SEC-104 / INV-T-03。
 * @kind problem
 * @problem.severity error
 * @security-severity 9.3
 * @precision medium
 * @id debug-hunter/rule-sec-104-callback-no-signature
 * @tags security
 *       external/cwe/cwe-345
 *       external/cwe/cwe-347
 *       financial
 */

import java

/** Spring 映射註解 */
predicate hasMappingAnnotation(Method m) {
  m.getAnAnnotation().getType().getName().matches("%Mapping")
}

/** 看起來是回調/通知端點（依方法名或映射 URL 字串） */
predicate isCallbackEndpoint(Method m) {
  hasMappingAnnotation(m) and
  (
    m.getName().toLowerCase().matches(["%callback%", "%notify%", "%webhook%"])
    or
    exists(StringLiteral s |
      s = m.getAnAnnotation().getAValue() and
      s.getValue().toLowerCase().matches(["%callback%", "%notify%", "%webhook%"])
    )
  )
}

/** 入帳 / 狀態躍遷匯點 */
predicate moneySink(MethodCall ma) {
  ma.getMethod().getName() = ["credit", "markPaid", "settle", "doSettle", "payout"]
}

/** 方法內具備簽章/完整性驗證 */
predicate verifiesSignature(Callable c) {
  exists(MethodCall v |
    v.getEnclosingCallable() = c and
    v.getMethod().getName().toLowerCase().matches(["%verify%", "%sign%", "%hmac%", "%checksum%"])
  )
}

from Method m, MethodCall sink
where
  isCallbackEndpoint(m) and
  sink.getEnclosingCallable() = m and
  moneySink(sink) and
  not verifiesSignature(m)
select sink,
  "回調端點在入帳/狀態躍遷前未見簽章驗證，可被偽造 (PAT-SEC-104 / RULE-SEC-104)。"
