# USSD Merge-State / Virtual Session Bridge — Đã chuyển sang design doc

> Tài liệu brainstorming ban đầu (transcript hỏi–đáp về ý tưởng "merge state" /
> async USSD reconciliation) đã được phân tích, hệ thống hoá và thay thế bằng một
> design specification có cấu trúc.

Vui lòng xem:

- Design spec: [`docs/design/virtual-session-bridge.md`](docs/design/virtual-session-bridge.md)

Tài liệu mới bao gồm:

- Tóm tắt kiến trúc và KPI mục tiêu (success rate, recovery rate, abandon rate).
- Máy trạng thái (FSM) của Virtual Session và schema lưu trên Infinispan.
- Sáu sequence diagram (Pull happy path, async bridge, idempotency, push retry,
  session priority, CDR dual-record).
- Catalog đầy đủ các kịch bản timeout cho Pull (P1–P15), Push (U1–U10) và Hybrid (H7–H9).
- Tham chiếu cấu hình (`sessionBridgeEnabled`, `asyncGateTimeoutMs`,
  `asyncWaitUserMessage`, `asyncHardFailMessage`, `bridgeStateTtlSec`,
  `pushRetryDelaysMs`) và hợp đồng HTTP với Application Server.

## Bản đồ hiện thực hoá (Phase 1)

| Thành phần | Vị trí |
|------------|--------|
| FSM, store, retry queue, fallback, metrics | [`core/session-bridge`](core/session-bridge) |
| Cấu hình + MBean | [`core/domain/.../UssdPropertiesManagement.java`](core/domain/src/main/java/org/mobicents/ussdgateway/UssdPropertiesManagement.java) |
| UI cấu hình | [`management/ussd-management/.../server-settings.html`](management/ussd-management/src/main/webapp/modules/server-settings.html) |
| Tích hợp MO (Pull) | [`core/slee/sbbs/.../ChildSbb.java`](core/slee/sbbs/src/main/java/org/mobicents/ussdgateway/slee/ChildSbb.java), [`HttpClientSbb.java`](core/slee/sbbs/src/main/java/org/mobicents/ussdgateway/slee/http/HttpClientSbb.java) |
| Async callback + NI Push (S2) | [`HttpServerSbb.java`](core/slee/sbbs/src/main/java/org/mobicents/ussdgateway/slee/http/HttpServerSbb.java) |
| CDR correlation (2 record/giao dịch) | [`USSDCDRState.java`](core/slee/sbbs/src/main/java/org/mobicents/ussdgateway/slee/cdr/USSDCDRState.java), [`CdrLineFormatter.java`](core/slee/sbbs/src/main/java/org/mobicents/ussdgateway/slee/cdr/CdrLineFormatter.java) |
| Cache Infinispan | [`release-wildfly/standalone-patched.xml`](release-wildfly/standalone-patched.xml) (cache-container `ussd`) |

Tính năng được bảo vệ bởi cờ `sessionBridgeEnabled` (mặc định `false`): khi tắt,
gateway hoạt động y như trước (đồng bộ, không bridge, không giải phóng dialogue sớm).
