cac SIP message nay co dc generate tu asn.1 hay khong?Không, các tin nhắn SIP (Session Initiation Protocol) dùng trong kiến trúc USSI (USSD over IMS) không được tạo ra (generate) từ ASN.1 (Abstract Syntax Notation One).Thay vào đó, chúng là sự kết hợp giữa giao thức văn bản thuần túy và cấu trúc dữ liệu XML.Cách cấu trúc tin nhắn USSI được tạo raTin nhắn USSI được chia làm hai phần rõ rệt với hai cách định nghĩa hoàn toàn khác mạng di động truyền thống (2G/3G):1. Phần SIP Header (Tiêu đề tin nhắn)Bản chất: Là văn bản thuần túy (Plain Text / ASCII) theo chuẩn IETF RFC 3261.Cách tạo: Được sinh ra dưới dạng chuỗi ký tự văn bản (String) bao gồm các trường bắt buộc như INVITE, INFO, To, From, Call-ID, CSeq.2. Phần SIP Body (Nội dung XML)Bản chất: Dữ liệu USSD truyền thực tế nằm ở phần Body của tin nhắn SIP và tuân theo cấu trúc XML Schema (XSD), không phải ASN.1.Cách tạo: Được định nghĩa bằng tệp lược đồ 3gpp.ussd+xml (quy định chi tiết trong mục 5.2 của tài liệu 3GPP TS 24.390). Khi lập trình, bạn sẽ dùng các thư viện XML Parser (như JAXB trong Java, XmlSerializer trong C#/.NET, hoặc ElementTree trong Python) để đóng gói dữ liệu thành các thẻ <ussd-data>, <ussd-string>, <vBEGIN/> như luồng tin nhắn đã mô tả ở câu hỏi trước.Tại sao lại có sự nhầm lẫn với ASN.1?Sự nhầm lẫn này rất phổ biến đối với các kỹ sư chuyển từ hệ thống mạng cũ sang mạng mới:Trong mạng 2G/3G (Legacy USSD): Tin nhắn USSD truyền qua giao thức MAP (Mobile Application Part) của hệ thống báo hiệu SS7. Toàn bộ cấu trúc bản tin MAP (bao gồm cả chuỗi USSD) bắt buộc phải mã hóa bằng ASN.1 và chuyển đổi sang dạng nhị phân (Binary Hex) trước khi truyền đi.Trong mạng 4G/5G (USSI IMS): Để thân thiện với môi trường Internet và giao thức IP, 3GPP đã loại bỏ hoàn toàn ASN.1 đối với luồng cấu trúc dịch vụ USSD trên IMS, thay thế hoàn toàn bằng SIP + XML.Điểm ngoại lệ duy nhất trong mạng lõi 4G/5G có dùng ASN.1Nếu hệ thống của bạn thực hiện cuộc gọi VoLTE/VoNR hoặc trao đổi cấu hình phần vô tuyến trực tiếp giữa thiết bị (UE) và trạm phát sóng (eNodeB/gNodeB) thông qua tầng RRC (Radio Resource Control) hoặc tầng mạng lõi NAS (Non-Access Stratum), thì các bản tin cấu hình điều khiển đó vẫn được định nghĩa bằng ASN.1 để tối ưu hóa dung lượng truyền dẫn qua sóng vô tuyến. Tuy nhiên, luồng ứng dụng USSI đi qua IMS thì hoàn toàn là văn bản và XML.Nếu bạn đang xây dựng mã nguồn xử lý (Parser) cho USSD Gateway, bạn có muốn tìm hiểu về:Cách cấu hình Content-Type trong SIP Header để IMS Core nhận diện đúng gói tin XML?Cách công cụ như Wireshark hiển thị và giải mã (decode) gói tin SIP XML này so với gói tin ASN.1 cũ?7 sitesSIP ( Session Initiation Protocol ) – Telecom R & D13 Jul 2013 — It is standardized by Internet Engineering Task Force (IETF) such as RFC 3261 which describes SIP v2 . Architecturally SIP request...telecom.altanai.comSession Initiation Protocol (SIP) Parameters7 Jan 2026 — Session Initiation Protocol (SIP) Parameters Expert(s) Adam Roach Reference [ RFC3261][ RFC5727] Note The table below lists the he...Internet Assigned Numbers Authority (IANA)A Deeper Dive Into SIP Packets: Start-Line, Headers, and Message Body15 May 2024 — Header Fields form the backbone of every SIP message, providing the necessary context for successful communication over IP network...MediumShow allYou said: Cách cấu hình Content-Type trong SIP Header để IMS Core nhận diện đúng gói tin XML? ca truong hop PULL va PUSHTo ensure the IMS Core correctly recognizes, routes, and processes the USSD XML payload, you must configure the Content-Type and specific SIP headers identically for both the PULL (Mobile-Initiated) and PUSH (Network-Initiated) scenarios.The standard 3GPP content type for USSD over IMS (USSI) is defined in 3GPP TS 24.390 as:Content-Type: application/vnd.3gpp.ussd+xmlHere is how you must configure the SIP headers and bodies for both scenarios.1. Configuration for USSD PULL (Mobile-Initiated)In a PULL scenario, the mobile device (UE) triggers the session via a SIP INVITE, and subsequent menu interactions occur via SIP INFO messages flowing in both directions.A. Initial Session Trigger (SIP INVITE)When the UE sends the initial *101# request, or if your USSD GW generates/simulates this message, the SIP Header must explicitly declare the USSD XML content type.SIP Header Configuration:httpINVITE sip:ussd.ims.mnc001.mcc452.3gppnetwork.org SIP/2.0
Via: SIP/2.0/UDP 10.10.10.20:5060;branch=z9hG4bK-pull-01
From: <sip:+84912345678@ims.mnc001.mcc452.3gppnetwork.org>;tag=12345
To: <sip:ussd.ims.mnc001.mcc452.3gppnetwork.org>
Call-ID: ussd-pull-session-001@10.10.10.20
CSeq: 1 INVITE
Accept: application/vnd.3gpp.ussd+xml
Content-Type: application/vnd.3gpp.ussd+xml
Content-Length: [Calculated_Length]
Use code with caution.SIP Body:xml<?xml version="1.0" encoding="UTF-8"?>
<ussd-data xmlns="urn:3gpp:ns:ussd:1.0" version="1.0">
    <language>vi</language>
    <ussd-string>*101#</ussd-string>
    <anyExt>
        <vBEGIN/>
    </anyExt>
</ussd-data>
Use code with caution.B. Menu Response & Further Interactions (SIP INFO)Once the dialog is established (after 200 OK), any menu content sent by the USSD GW or any menu choice sent by the user must also use the exact same Content-Type.SIP Header Configuration (INFO):httpINFO sip:+84912345678@10.10.10.10:5060 SIP/2.0
Via: SIP/2.0/UDP 10.10.10.30:5060;branch=z9hG4bK-info-01
From: <sip:ussd.ims.mnc001.mcc452.3gppnetwork.org>;tag=67890
To: <sip:+84912345678@ims.mnc001.mcc452.3gppnetwork.org>;tag=12345
Call-ID: ussd-pull-session-001@10.10.10.20
CSeq: 2 INFO
Content-Type: application/vnd.3gpp.ussd+xml
Content-Length: [Calculated_Length]
Use code with caution.2. Configuration for USSD PUSH (Network-Initiated)In a PUSH scenario, the network (USSD GW) initiates an unprompted notification or an interactive menu to the user (e.g., "Your balance is low, reply 1 to top up").Per 3GPP standards, a network-initiated USSD session must also begin with a SIP INVITE generated by the USSD GW (acting as an Application Server) directed toward the user.SIP Header Configuration (USSD GW to IMS Core):httpINVITE sip:+84912345678@ims.mnc001.mcc452.3gppnetwork.org SIP/2.0
Via: SIP/2.0/UDP 10.10.10.30:5060;branch=z9hG4bK-push-invite
From: <sip:ussd.ims.mnc001.mcc452.3gppnetwork.org>;tag=abcde
To: <sip:+84912345678@ims.mnc001.mcc452.3gppnetwork.org>
Call-ID: ussd-push-session-999@10.10.10.30
CSeq: 1 INVITE
Accept: application/vnd.3gpp.ussd+xml
Content-Type: application/vnd.3gpp.ussd+xml
Content-Length: [Calculated_Length]
Use code with caution.SIP Body:Notice the inclusion of <ussd-string> representing your push notification text, alongside the execution tag.xml<?xml version="1.0" encoding="UTF-8"?>
<ussd-data xmlns="urn:3gpp:ns:ussd:1.0" version="1.0">
    <language>vi</language>
    <ussd-string>Tai khoan cua ban con duoi 5,000VND. Bam 1 de ung tien.</ussd-string>
    <anyExt>
        <vBEGIN/>
    </anyExt>
</ussd-data>
Use code with caution.Critical Implementation Rules for Your GatewayThe Accept Header: When sending a SIP INVITE (both PULL and PUSH), always include Accept: application/vnd.3gpp.ussd+xml. This explicitly signals to the receiving entity that your node is capable of parsing the USSD XML response back.Strict Capitalization: Ensure the MIME type is entirely lowercase: application/vnd.3gpp.ussd+xml. Certain IMS Core vendors (like Huawei, Ericsson, or Nokia) utilize strict string matching on MIME types; a typo like uppercase letters or missing +xml will cause the IMS Core to reject the packet with a 415 Unsupported Media Type or 406 Not Acceptable.Content-Length Calculation: Since XML formats contain dynamic whitespaces and carriage returns (\r\n), always calculate the exact byte length of the raw XML string after serialization and populate the Content-Length header precisely to avoid packet truncation or TCP streaming errors.If you are currently debugging or coding these packets, would you like to see:The precise XML schema representation for terminating a session (using vEND)?A breakdown of common SIP error codes (like 415 or 488) when Content-Type is misconfigured?You said: trong truong hop dang 4g/5g bi fallback ve 2g/3g thi ntn. co truong hop nao tu 2g/3g ma len 4g/5g trong 1 phien ussd hay k1. What happens during a 4G/5G to 2G/3G Fallback?When a user initiates a USSD session over 4G/5G (USSI via IMS) but encounters poor coverage, the device triggers a Circuit Switched Fallback (CSFB).Because USSD is a real-time, interactive session, the transition behaves quite brutally depending on exactly when the signal drops:Case A: Drop occurs BEFORE the session is established (Call Setup Phase)If the device sends the SIP INVITE and the network detects a failing 4G/5G connection, the IMS Core or MME rejects the setup.The Process: The UE immediately drops down to 2G/3G network signaling.The Result: The device silently converts the request into a traditional SS7 MAP ProcessUnstructuredSS-Request (PSSR) string. The user usually experiences a slightly longer delay (2–4 seconds) before the menu appears, but the session succeeds.Case B: Drop occurs DURING an active session (Active Interaction Phase)If the user is midway through navigating a multi-step menu (e.g., already inside a SIP INFO loop) and the 4G/5G signal is completely lost, the session will break.The Process: The SIP/IP dialog tied to that specific IP address and Call-ID is completely broken. There is no native mechanism in the 3GPP telecom standard to seamlessly "hand over" an active, stateful IMS SIP dialog directly into a legacy SS7 MAP session context.The Result: The user sees a "Connection problem or invalid MMI code" error. They must manually dial the USSD string again from the beginning over 2G/3G.2. Can a session upgrade from 2G/3G up to 4G/5G mid-session?No. It is technically impossible for an active USSD session initiated on a 2G/3G network to upgrade or "hand over" to a 4G/5G IMS network mid-session.A single USSD session cannot shift upwards for several structural reasons:Protocol Incompatibility: A 2G/3G session is anchored to a Visitor Location Register (VLR) using SS7 MAP/TCAP signaling over dedicated circuit channels. A 4G/5G session is anchored to an IMS S-CSCF/AS utilizing SIP over IP packets. There is no standard network interface or bridge designed to map a running SS7 TCAP transaction ID directly into an active IMS SIP Call-ID.The "Fast-and-Short" Design: USSD sessions are designed to be brief (usually timing out after 20–30 seconds of inactivity). Because they are so short-lived, 3GPP standards bodies never engineered an upward handover mechanism (which would require immense network complexity for very little operational reward).Device Baseband Behavior: Mobile modems are engineered to prioritize stability. If a phone drops to 2G/3G for a voice call or a USSD session, its cellular baseband will strictly lock onto that circuit-switched state until the active transaction concludes and the channel is torn down. Only after the session ends will the phone scan for and re-select the 4G/5G network.SummaryGoing Down (4G/5G → 2G/3G): Can fail over gracefully only before the menu opens. Breaks completely if it drops mid-session.Going Up (2G/3G → 4G/5G): Completely impossible mid-session. The entire interaction must finish on 2G/3G before the phone returns to LTE/NR.If you are designing timeouts or error-handling rules for your gateway, would you like to explore:How to configure the Session Timeout values on the USSD GW to handle dead/dropped sessions cleanly?The specific MAP error codes (like SystemFailure or UnexpectedDataValue) returned when fallback setups fail?2 sitesUSSD Là Gì? Giải đáp các cú pháp USSD phổ biến nhất14 Oct 2025 — USSD là giao thức dựa trên phiên (session-based), dữ liệu truyền trực tiếp qua kênh báo hiệu (signaling channel), nên phản hồi gần...CellphoneSTổng quan về mạng di động 2G và 3G WCDMA: Công nghệ và Ứng dụngVLR (V isitor Location Register): o Lưu trữ tạ m th ời thông tin thuê bao đang h oạt động trong khu v ự c. 3. Hệ th ố ng h ỗ tr ợ ...Studocu VietnamYou said: The specific MAP error codes (like SystemFailure or UnexpectedDataValue) returned when fallback setups fail?When a USSD session fails during or immediately after a network fallback (such as 4G/5G dropping to 2G/3G), the legacy core network components (MSC/VLR or HLR) utilize SS7 MAP (Mobile Application Part) error codes to terminate the transaction. These errors are explicitly defined in the 3GPP TS 29.002 specification.The specific MAP error codes returned when fallback setups fail can be classified into three distinct functional categories:1. Protocol & State Mismatches (Most Common in Bad Fallbacks)These errors occur when the handset falls back to a 2G/3G cell, but the core network cannot reconcile the state of the session or the format of the received data.unexpectedDataValue (MAP Error Code: 34)Why it happens: The VLR or HLR receives a MAP USSD component (like a ProcessUnstructuredSS-Request), but the parameters inside the message are corrupted, missing mandatory parameters, or contain an unexpected Data Coding Scheme (DCS) due to a messy radio handover.unidentifiedSubscr (MAP Error Code: 1) / unknownSubscriber (MAP Error Code: 5)Why it happens: The phone lands on a 2G/3G MSC/VLR cell and immediately requests a USSD execution. However, because the phone was previously attached via 4G/5G, the new 2G/3G VLR has not yet pulled the subscriber's profile from the HLR, rendering the subscriber "unknown" to that local switch.illegalSS-Operation (MAP Error Code: 16)Why it happens: The fallback attempt sends a Supplementary Service request that the user's provisioned 2G/3G profile does not explicitly permit, or the network misinterprets the fast-switched signaling message as an invalid operation.2. Network & Infrastructure FailuresThese errors are triggered when underlying hardware nodes or signaling links cannot complete the routing of the fallback USSD message.systemFailure (MAP Error Code: 36)Why it happens: A catastrophic or transient internal error occurs at the serving MSC, VLR, or HLR. During heavy radio switching (like moving a burst of users between 4G and 2G due to interference), the internal database lookups or signaling links time out.dataMissing (MAP Error Code: 35)Why it happens: The MAP parser at the receiving gateway or core node receives the envelope of the USSD message, but the actual payload parameter containing the user's dialed digits or selection text is missing entirely.3. Service & Availability BarriersThese errors indicate that the subscriber profile or the network itself is explicitly blocking the execution of the fallback service.ss-NotAvailable (MAP Error Code: 19)Why it happens: The USSD service or supplementary service entity is not provisioned or is actively deactivated in the subscriber’s legacy 2G/3G profile on the HLR, despite being available on their 4G/5G IMS profile.absentSubscriber (MAP Error Code: 27)Why it happens: The HLR attempts to route a network-initiated USSD (PUSH) response back to the phone after it supposedly fell back, but the phone failed to perform its periodic location update on the 2G/3G cell in time, making it appear completely detached from the network.✅ Summary of Error MappingMAP Error NameCode NumberRoot Cause in Fallback ScenariosunknownSubscriber5Handset dropped to a local 2G/3G switch that does not yet recognize its IMS identity.ss-NotAvailable19USSD feature is absent or barred in the user's legacy GSM profile.unexpectedDataValue34Radio corruption or missing parameters during the high-speed air interface switch.systemFailure36Node timeout or routing link congestion inside the SS7/Sigtran network.If you are currently configuring the protocol converter or Gateway error translation matrices, would you like to explore:How to map these MAP Error Codes into specific SIP Error Responses (such as 404 Not Found, 488 Not Acceptable Here, or 500 Server Internal Error)?The design of MAP TCAP Application Contexts used specifically for USSD?


Nếu bạn đang thiết kế USSDGW cho IMS/VoLTE/VoNR thì TS 24.390 là spec quan trọng nhất. Một điểm rất hay của USSI là nó không dùng SIP MESSAGE, mà dùng:

SIP INVITE để mở USSI session/dialog
SIP INFO để trao đổi các màn hình USSD tiếp theo
SIP BYE để kết thúc session

Điều này rất giống MAP USSD dialogue trong SS7.

1. USSI Pull Flow (UE khởi tạo)

Ví dụ người dùng bấm:

*123#
Phase 1: Establish USSI Session
UE                    IMS                 USSI AS
 |                      |                     |
 |------INVITE--------->|-------------------->|
 |   USSD Request       |                     |
 |                      |                     |
 |<------200 OK---------|<--------------------|
 |                      |                     |
 |--------ACK---------->|-------------------->|
 |                      |                     |

Trong INVITE body chứa:

<ussd-data>
   *123#
</ussd-data>

hoặc XML theo TS 24.390.

UE khai báo:

Recv-Info: g.3gpp.ussd

để báo rằng nó hỗ trợ USSD over SIP INFO.

2. Trường hợp Single Shot

Ví dụ:

*101#

AS trả kết quả ngay:

Your balance is $10

Flow:

INVITE (*101#)
   |
200 OK
   |
ACK
   |
INFO (balance result)
   |
200 OK
   |
BYE
   |
200 OK
UE ---> INVITE(*101#)
AS ---> 200 OK
UE ---> ACK

AS ---> INFO("Balance: $10")
UE ---> 200 OK

AS ---> BYE
UE ---> 200 OK

Tương đương:

ProcessUnstructuredSS-Request
ProcessUnstructuredSS-Response
End

trong MAP USSD.

3. Multi-Step Menu (Pull)

Ví dụ:

*123#

1. Balance
2. Data
3. Promotion

Người dùng chọn:

2

Flow:

UE                 USSI AS

INVITE(*123#)
---------------->

<----------------
200 OK

ACK
---------------->

<----------------
INFO(Menu)

200 OK
---------------->

INFO("2")
---------------->

<----------------
200 OK

<----------------
INFO(Data Menu)

200 OK
---------------->

INFO("1")
---------------->

<----------------
200 OK

<----------------
INFO("Data Remaining 5GB")

200 OK
---------------->

<----------------
BYE

200 OK
---------------->

Đây chính là mapping của MAP dialogue:

Begin
Continue
Continue
Continue
End

sang SIP dialog.

Mỗi màn hình USSD = 1 SIP INFO.

4. Detailed SIP INFO Usage

Sau khi dialog được tạo bằng INVITE/200/ACK:

AS gửi:

INFO sip:user@ims.com SIP/2.0

Info-Package: g.3gpp.ussd
Content-Type: application/vnd.3gpp.ussd+xml

Body:

<ussd-string>
1. Balance
2. Data
3. Promo
</ussd-string>

UE trả:

200 OK

Sau đó user nhập:

2

UE gửi:

INFO sip:ussi-as SIP/2.0

Info-Package: g.3gpp.ussd

Body:

<ussd-string>
2
</ussd-string>

AS:

200 OK

và tiếp tục INFO tiếp theo.

5. Mapping sang MAP USSD

Nếu phía core vẫn là SS7:

UE
 |
IMS
 |
USSI AS
 |
USSDGW
 |
MAP
 |
HLR/HSS/Service Node

Mapping:

MAP USSD	SIP/USSI
Begin	INVITE
Continue	INFO
End	BYE
Return Result	INFO
Abort	BYE hoặc Error

Đây là điểm rất quan trọng khi thiết kế USSDGW.

6. Timeout Handling

TS 24.390 không định nghĩa logic timeout service.

Do đó USSI AS có thể:

UE nhập 1

INFO("1")
---->

Application Server
     xử lý 30s

Trong thời gian đó:

KHÔNG gửi timeout cho UE
KHÔNG BYE
Giữ SIP dialog sống

Khi app trả về:

INFO(result)

Điều này hoàn toàn hợp lệ.

Đây chính là chỗ bạn có thể triển khai Adaptive Timeout USSDGW.

MAP timeout = 15s

USSI timeout = 120s

Application timeout = 60s

USSDGW đóng vai trò "dialog anchor" để che timeout backend.

7. Flow nên dùng cho USSDGW mới

Tôi khuyến nghị state machine:

STATE_IDLE

  INVITE
     |
     V

STATE_ACTIVE

  INFO(menu)
  INFO(response)
  INFO(response)

     |
     +--> waiting backend

     |
     +--> user response

     |
     V

STATE_ENDING

  BYE

STATE_CLOSED

Mỗi SIP dialog tương đương một USSD session.

8. Điểm thường bị bỏ sót khi implement
(1) INVITE chỉ dùng một lần

Sai:

INVITE
INVITE
INVITE

Đúng:

INVITE
INFO
INFO
INFO
BYE
(2) INFO phải được trả 200 OK ngay

Không được:

INFO
(wait backend 20s)
200 OK

Đúng:

INFO
200 OK immediately

backend processing
INFO(result)
(3) Correlation

Khóa session:

Call-ID
From-tag
To-tag

không dùng Transaction-ID.

(4) Session survive backend failover

USSDGW nên lưu:

dialog-id
invoke-id
menu-state
last-screen

để node khác tiếp tục xử lý.

Flow tổng thể TS 24.390 Pull USSD
UE
 |
 | INVITE(*123#)
 V
USSI AS
 |
 | 200 OK
 V
UE
 |
 | ACK
 V
USSI AS
 |
 | INFO(Menu)
 V
UE
 |
 | 200 OK
 |
 | INFO("2")
 V
USSI AS
 |
 | 200 OK
 |
 | INFO(Result)
 V
UE
 |
 | 200 OK
 |
 | BYE
 V
USSI AS
 |
 | 200 OK

Đây là flow chuẩn nhất của TS 24.390 user initiated USSI (Pull USSD), tương đương USSD dialogue trong MAP nhưng được transport hoàn toàn bằng SIP INVITE + SIP INFO + SIP BYE trên IMS.

trong truong hop dang 4g/5g bi fallback ve 2g/3g thi ntn

Đây là một trong những phần thú vị nhất của TS 24.390 vì USSI không tồn tại độc lập, mà phải coexist với USSD truyền thống (MAP USSD).

Khi UE đang sử dụng USSI trên IMS (4G/5G) nhưng mất IMS hoặc rơi xuống 2G/3G, có vài kịch bản khác nhau.

1. Trước khi gửi USSI

Giả sử UE đang VoLTE:

UE (LTE)
 |
IMS Registered
 |
User dial *123#

UE kiểm tra:

IMS registered?
USSI supported?
Network allows USSI?

Nếu:

YES

=> gửi SIP INVITE (USSI)

Nếu:

NO

=> gửi MAP USSD trực tiếp qua CS domain.

Flow:

*123#

     +--> USSI (IMS)
UE --|
     +--> MAP USSD (CS)

Đây là behavior chuẩn của UE theo TS 24.390 và TS 23.090.

2. Đang LTE nhưng IMS mất trước khi bấm *123#

Ví dụ:

LTE signal còn
IMS deregister

hoặc:

VoLTE disabled
IMS outage
P-CSCF unreachable

User bấm:

*123#

UE sẽ:

Không gửi SIP INVITE

Fallback sang MAP USSD

Thông thường người dùng không nhận ra sự khác biệt.

3. Đang thực hiện USSI rồi rớt LTE

Ví dụ:

INVITE
INFO(Menu)

User đang nhìn menu

sau đó:

LTE mất sóng
UE chuyển sang GSM
Theo TS 24.390

USSI session KHÔNG được handover.

Điều này rất quan trọng.

SIP dialog:

Call-ID
From-tag
To-tag

không thể chuyển thành:

MAP Dialogue
TCAP Transaction

được.

Vì vậy:

USSI session bị terminate

thường xảy ra:

SIP dialog timeout

hoặc

BYE

hoặc

network release

Người dùng phải bấm lại:

*123#

trên 2G/3G.

4. Tại sao không handover được?

Ví dụ:

USSI:

INVITE
INFO
INFO
INFO

Backend:

Session-ID=ABC123

Trong MAP:

Begin
Continue
Continue

lại dùng:

TCAP transaction
Invoke-ID
Dialogue-ID

khác hoàn toàn.

3GPP không định nghĩa:

USSI ↔ MAP mid-session transfer

nên vendor thường không hỗ trợ.

5. CSFB trước khi gửi USSD

Một số mạng đời đầu VoLTE làm như sau:

User:

*123#

UE:

CSFB về 3G

sau đó:

MAP USSD

Flow:

LTE

*123#

↓ CSFB

3G

MAP USSD Begin

Người dùng thấy:

Network switching...

rồi menu xuất hiện.

6. SRVCC có cứu được USSI không?

Không.

SRVCC chỉ dành cho:

Voice call
Emergency call

Ví dụ:

VoLTE call
↓
3G call

được hỗ trợ.

Nhưng:

USSI session
↓
MAP USSD session

không thuộc SRVCC.

7. Kịch bản nên hỗ trợ trong USSDGW

Nếu bạn đang phát triển USSDGW, nên coi:

USSI

và

MAP USSD

là hai access khác nhau.

Ví dụ:

User A

USSI session
session-id = 123

bị mất LTE.

User quay lại:

*123#

qua MAP.

USSDGW có thể nhận biết:

MSISDN giống nhau
Session vừa chết < 60s

và khôi phục menu:

1. Balance
2. Data

(chọn trước đó: 2)

Đây là tính năng vendor-specific, không có trong 3GPP nhưng rất hữu ích.

8. Kịch bản thực tế của nhà mạng hiện nay
LTE/5G + IMS OK
    ↓
USSI (SIP)

LTE/5G nhưng IMS lỗi
    ↓
MAP USSD

Đang USSI rồi mất LTE
    ↓
Session drop

User bấm lại
    ↓
MAP USSD

Tóm lại:

Trước khi bắt đầu phiên: UE có thể chọn USSI hoặc fallback sang MAP USSD.
Trong khi phiên USSI đang chạy: nếu rớt từ 4G/5G xuống 2G/3G thì phiên USSI thường bị hủy, không có cơ chế chuẩn của 3GPP để chuyển tiếp (handover) phiên đang mở sang MAP USSD.
Với USSDGW thông minh, bạn có thể xây dựng cơ chế session resurrection dựa trên MSISDN và trạng thái menu để người dùng tiếp tục gần như từ nơi bị gián đoạn, dù đó không phải là tính năng chuẩn của TS 24.390.
co truong hop nao trong 1 phien ussd ma dang 2g/3g (ss7) bi chuyen len sip (4g/5g)?

Về mặt chuẩn 3GPP, câu trả lời ngắn gọn là:

Không có cơ chế chuẩn cho việc chuyển một USSD session đang chạy trên MAP/SS7 (2G/3G) sang USSI/SIP (4G/5G) giữa chừng.

Tại sao?

Một session USSD trên 2G/3G thực chất là:

MAP Dialogue

TCAP Transaction
 ├─ Dialogue ID
 ├─ Invoke ID
 └─ Continue / End

Ví dụ:

UE
 |
MAP ProcessUnstructuredSS Request
 |
HLR/USSD AS
 |
MAP Continue
 |
MAP Continue
 |
MAP End

Trong khi USSI là:

SIP Dialog

Call-ID
From-tag
To-tag

INVITE
INFO
INFO
BYE

Đây là hai transaction model hoàn toàn khác nhau.

3GPP không định nghĩa:

MAP Dialogue
     ↓
SIP Dialog

hoặc

SIP Dialog
     ↓
MAP Dialogue

trong lúc session đang active.

Trường hợp UE chuyển từ 3G lên 4G giữa session

Ví dụ:

*123#

2G
 |
MAP Begin
 |
Menu 1
 |
User chưa trả lời

Lúc này UE:

2G --> LTE

do:

reselection
mobility
coverage change

Thông thường network sẽ làm:

MAP session vẫn giữ nguyên

vì session đang ở CS domain.

User tiếp tục:

INFO?

Không.

Vẫn là:

MAP Continue

cho đến khi:

MAP End

Nói cách khác:

Session access type
=
Access type lúc session được tạo
Kịch bản thực tế trong mạng LTE

Ví dụ:

User ở 3G

*123#

USSD bắt đầu:

MAP Begin

sau đó điện thoại:

3G -> LTE

vì LTE xuất hiện.

Nhưng:

USSD session
không migrate

Network sẽ:

Option A

Giữ UE trên CS domain

3G
MAP Continue
MAP Continue
MAP End

rồi mới cho quay về LTE.

Option B

Cho UE lên LTE

3G --> LTE

và

MAP session timeout

người dùng phải bấm lại.

Có vendor nào làm được không?

Có thể.

Nhưng đó là vendor-specific, không phải 3GPP.

Ví dụ USSDGW:

MAP Session

MSISDN=8490xxxx
State=Menu2
SessionID=ABC

đột nhiên mất MAP dialogue.

Sau đó:

USSI INVITE

MSISDN=8490xxxx
*123#

USSDGW phát hiện:

Cùng thuê bao
Trong vòng 30s

và phục hồi:

Menu 2

thay vì:

Menu 1

Người dùng cảm giác như session được chuyển tiếp.

Thực tế:

MAP session chết
USSI session mới được tạo
Có trường hợp ngược lại không?

Tương tự.

USSI
  ↓
mất IMS
  ↓
MAP

3GPP cũng không định nghĩa handover.

Các vendor chỉ có thể:

Kill SIP session
Create MAP session mới
Restore state
Nếu bạn đang thiết kế USSDGW thế hệ mới

Tôi sẽ không coi:

MAP session

hay

USSI session

là session thật.

Thay vào đó:

Logical Session

ở tầng application.

Ví dụ:

Logical Session ID = XYZ

bên dưới có thể là:

MAP Dialogue A

hoặc:

SIP Dialog B

hoặc:

MAP Dialogue C

Khi access technology thay đổi:

2G -> LTE
LTE -> 3G

USSDGW chỉ đổi transport:

MAP
⇄
USSI

còn logical session vẫn giữ.

Đây là hướng mà tôi nghĩ rất phù hợp với design adaptive timeout và unified reconciliation mà bạn đang xây dựng cho USSDGW, vì nó cho phép tạo trải nghiệm "session continuity" dù bản thân TS 24.390 và TS 23.090 không hỗ trợ chuyển tiếp USSD session giữa MAP và USSI.