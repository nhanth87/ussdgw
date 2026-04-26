# USSD Gateway Project - Jackson XML & BER/DER ASN.1 Analysis Report

## Executive Summary

This report analyzes the ussdgw project for Jackson XML serialization/deserialization compliance and BER/DER ASN.1 encoding/decoding issues in the MAP message handling modules. The analysis covered the core xml module classes responsible for XML serialization and the slee module's SBB classes that handle MAP protocol messages.

The project has already undergone significant migration from javolution.xml to Jackson XML, with the EventsSerializeFactory class serving as the central serialization hub. Overall, the Jackson XML implementation is well-structured with proper annotations on core classes, mix-ins for external jSS7 library classes, and custom serializers/deserializers for complex types. The MAP message handling in the slee module relies on the Restcomm jSS7 library for BER/DER ASN.1 encoding/decoding, which is accessed through the MAP Resource Adaptor interface.

## 1. Introduction

The ussdgw project is a USSD (Unstructured Supplementary Service Data) gateway that facilitates communication between application servers and mobile networks via MAP (Mobile Application Part) protocol over SS7. The system supports two entry points: HTTP (via HttpServerSbb) and SIP (via SipServerSbb), with MAP messages being serialized for transport and then deserialized at the receiving end.

This analysis examines the Jackson XML serialization infrastructure and identifies any potential issues with ASN.1 BER/DER encoding/decoding in the MAP message handling pipeline. The goal is to provide a comprehensive assessment of the current implementation state and recommend fixes for any identified issues.

## 2. Methodology

The analysis involved reading and examining source code across multiple modules of the project. The primary focus areas included the xml module for Jackson XML annotations and serialization logic, the slee module for MAP message handling, and the shell executor for management interface operations. Each class was evaluated for proper Jackson XML annotations, correct implementation of serialization/deserialization methods, proper handling of MAP message types, and correct use of the EventsSerializeFactory for XML conversion.

## 3. Key Findings

### 3.1 Jackson XML Serialization Classes

#### 3.1.1 EventsSerializeFactory

**Status: COMPLETE - No Issues Found**

Location: `core/xml/src/main/java/org/mobicents/ussdgateway/EventsSerializeFactory.java`

The EventsSerializeFactory is a thread-safe singleton that serves as the central factory for all XML serialization and deserialization operations. It has been successfully migrated from javolution.xml to Jackson XML with the following key features:

The class implements proper thread-safe singleton pattern using double-checked locking. It registers custom serializers and deserializers for MAPApplicationContext that handle the conversion between the application context name and version into a string format. The factory also handles MAPUserAbortChoice serialization with custom converters. Mix-ins are registered for external jSS7 classes including SccpAddress, SccpAddressImpl, MAPErrorMessage, and Problem to provide Jackson annotations where the original classes lack them. The factory registers subtypes for polymorphic deserialization of MAP message implementations including ProcessUnstructuredSSRequestImpl, UnstructuredSSRequestImpl, and their response counterparts. Over 30 MAP error message types are registered with their respective implementation classes.

The deserialize method handles both internal Jackson WRAPPER_OBJECT format and external flat XML format for backward compatibility with the javolution XML format. The deserializeFromJsonNode method separates MAP messages, error components, and reject components from standard dialog fields, then manually deserializes each type using the alias-to-class mapping.

#### 3.1.2 XmlMAPDialog

**Status: COMPLETE - No Issues Found**

Location: `core/xml/src/main/java/org/mobicents/ussdgateway/XmlMAPDialog.java`

This class is annotated with @JacksonXmlRootElement(localName = "dialog") for XML root element naming and @JsonInclude(JsonInclude.Include.NON_NULL) to exclude null fields from serialization. The @JsonIgnoreProperties(ignoreUnknown = true) annotation allows ignoring unknown properties during deserialization.

The class properly uses @JsonProperty annotations for all serialized fields, specifying exact XML attribute names. The MAPUserAbortChoice and MAPAbortProviderReason fields use @JacksonXmlProperty(isAttribute = true) to serialize as XML attributes rather than elements. A custom serializer MAPMessageListSerializer is implemented to produce javolution-compatible XML format using ToXmlGenerator for proper element naming. The FastList<MAPMessage> field uses @JsonSerialize(using = MAPMessageListSerializer.class) for custom serialization.

The @JsonDeserialize(as = SccpAddressImpl.class) annotation is used on SccpAddress fields to ensure proper deserialization to the implementation class. The errorComponents and rejectComponents fields are annotated with @JsonProperty for proper serialization of ErrorComponentMap and RejectComponentMap objects.

#### 3.1.3 SipUssdMessage

**Status: COMPLETE - No Issues Found**

Location: `core/xml/src/main/java/org/mobicents/ussdgateway/SipUssdMessage.java`

The SipUssdMessage class carries USSD data between SIP endpoints. It is annotated with @JsonInclude(JsonInclude.Include.NON_NULL) and @JsonIgnoreProperties(ignoreUnknown = true). All fields use @JsonProperty annotations with proper constant names (USSD_DATA, LANGUAGE, USSD_STRING, ERROR_CODE, ANY_EXT). The class includes a nested AnyExt field for carrying message type information.

#### 3.1.4 ErrorComponentMap

**Status: COMPLETE - No Issues Found**

Location: `core/xml/src/main/java/org/mobicents/ussdgateway/ErrorComponentMap.java`

This class extends HashMap<String, MAPErrorMessage> and is annotated with @JsonInclude(JsonInclude.Include.NON_NULL) and @JsonIgnoreProperties(ignoreUnknown = true). The put method converts Long invokeId to "id" + invokeId string keys for MAP error message storage. The getErrorComponents method reverses this mapping for external access. The @JsonIgnore annotation is properly used on getErrorComponents to prevent it from being serialized.

#### 3.1.5 RejectComponentMap

**Status: COMPLETE - No Issues Found**

Location: `core/xml/src/main/java/org/mobicents/ussdgateway/RejectComponentMap.java`

This class extends HashMap<String, Problem> and follows the same pattern as ErrorComponentMap. It is properly annotated with @JsonInclude and @JsonIgnoreProperties. The put method converts Long invokeId to string keys, and getRejectComponents provides the reverse mapping with @JsonIgnore annotation.

#### 3.1.6 FastList

**Status: COMPLETE - No Issues Found**

Location: `core/xml/src/main/java/org/mobicents/ussdgateway/FastList.java`

FastList is a high-performance list implementation that replaces javolution.util.FastList. It extends AbstractList<E> and implements List<E> and RandomAccess. The class delegates to an internal ArrayList and provides javolution-compatible API methods including head(), tail(), add(), get(), set(), remove(), and clear(). No Jackson-specific annotations are needed as it extends AbstractList which Jackson handles properly.

#### 3.1.7 Supporting Classes

**AnyExt** - Location: `core/xml/src/main/java/org/mobicents/ussdgateway/AnyExt.java` - Status: COMPLETE - Has @JsonProperty on messageType field.

**MAPErrorMessageMixin** - Location: `core/xml/src/main/java/org/mobicents/ussdgateway/MAPErrorMessageMixin.java` - Status: COMPLETE - Defines @JsonTypeInfo for polymorphic handling.

**SccpAddressMixin / SccpAddressImplMixin** - Status: COMPLETE - Provide Jackson annotations for external SCCP classes.

**ProblemMixin** - Status: COMPLETE - Provides Jackson annotations for TCAP Problem class.

### 3.2 MAP Message Handling in Slee Module

#### 3.2.1 HttpServerSbb

**Status: COMPLETE - No Issues Found**

Location: `core/slee/sbbs/src/main/java/org/mobicents/ussdgateway/slee/http/HttpServerSbb.java`

HttpServerSbb is the entry point for HTTP-initiated USSD sessions. It extends ChildServerSbb and implements SriParent. The class uses EventsSerializeFactory for dialog serialization in the deserializeDialog method. The serialize method is called in sendHttpResponse for serializing XmlMAPDialog back to HTTP clients.

Key observations include proper use of XmlMAPDialog for holding deserialized dialog state. The MAP messages are extracted from the dialog and processed via processXmlMAPDialog. Error and reject components are handled via xmlMAPDialog.sendErrorComponent and xmlMAPDialog.sendRejectComponent. The SRI (Send Routing Information) query is initiated via getSRI().performSRIQuery() for HLR lookup.

#### 3.2.2 USSDBaseSbb

**Status: COMPLETE - No Issues Found**

Location: `core/slee/sbbs/src/main/java/org/mobicents/ussdgateway/slee/USSDBaseSbb.java`

This is the base SBB class providing common functionality for all SBBs. It implements MAP dialog lookup via getMAPDialog() which uses caching (cachedMAPDialog volatile field) for performance optimization. The processXmlMAPDialog method iterates through MAP messages and calls processMAPMessageFromApplication for each. The processMAPMessageFromApplication method handles different MAP message types including unstructuredSSRequest_Request, unstructuredSSRequest_Response, processUnstructuredSSRequest_Response, unstructuredSSNotify_Request, unstructuredSSNotify_Response, and processUnstructuredSSRequest_Request.

#### 3.2.3 SriSbb

**Status: COMPLETE - No Issues Found**

Location: `core/slee/sbbs/src/main/java/org/mobicents/ussdgateway/slee/sri/SriSbb.java`

SriSbb handles the SRI (Send Routing Information) lookup child SBB. It creates MAP dialogs for SMS-based SRI queries and handles responses via onSendRoutingInfoForSMResponse. Error handling is implemented via onErrorComponent and onRejectComponent callbacks, storing error/reject information for later processing.

#### 3.2.4 SipServerSbb

**Status: COMPLETE - No Issues Found**

Location: `core/slee/sbbs/src/main/java/org/mobicents/ussdgateway/slee/sip/SipServerSbb.java`

SipServerSbb is the SIP entry point for USSD sessions. It extends ChildServerSbb and implements SriParent. The class handles SIP INVITE messages containing serialized SipUssdMessage payloads. It uses EventsSerializeFactory.deserializeSipUssdMessage for deserialization. MAP events are processed and converted to SipUssdMessage for sending via SIP INFO or BYE messages.

### 3.3 Shell-Based Classes

#### 3.3.1 UssdShellExecutor

**Status: N/A - Not Related to XML/ASN.1**

Location: `core/domain/src/main/java/org/mobicents/ussdgateway/UssdShellExecutor.java`

This class implements ShellExecutor from the Restcomm SS7 management console. It provides CLI commands for managing USSD configuration including scrule (short code routing rules), set, and get commands. This class is not involved in XML serialization or MAP message handling and therefore has no issues related to the analysis scope.

### 3.4 ASN.1 BER/DER Encoding/Decoding

The MAP message encoding/decoding is handled by the Restcomm jSS7 library through the MAP Resource Adaptor. The project does not contain custom ASN.1 encoding/decoding code. The MAPContextInterfaceFactory and MAPProvider interfaces from the Restcomm library provide the encoded MAP messages as Java objects.

Key observations include the use of MAPApplicationContext for application context versioning in dialog creation. The MAPApplicationContextVersion enum values (version1 through version4) define the MAP protocol version. MAP messages are created using the mapProvider.getMAPServiceSupplementary().createNewDialog() method and messages are added using dialog-specific methods like addUnstructuredSSRequest().

The EventsSerializeFactory registers custom serializers for MAPApplicationContext and MAPUserAbortChoice to convert these complex types to/from strings for XML representation. This is the boundary where BER/DER encoded data is converted to XML for transport across HTTP/SIP.

## 4. Files Status Summary

### 4.1 XML Module - Core Serialization Classes

| File | Status | Annotations | Notes |
|------|--------|------------|-------|
| EventsSerializeFactory.java | OK | N/A (Factory) | Well-structured, handles both formats |
| XmlMAPDialog.java | OK | Complete | @JacksonXmlRootElement, @JsonProperty, custom serializer |
| SipUssdMessage.java | OK | Complete | @JsonProperty on all fields |
| ErrorComponentMap.java | OK | Complete | @JsonIgnore on accessor |
| RejectComponentMap.java | OK | Complete | @JsonIgnore on accessor |
| FastList.java | OK | N/A (extends AbstractList) | Jackson handles natively |
| AnyExt.java | OK | @JsonProperty | Simple wrapper class |
| MAPErrorMessageMixin.java | OK | @JsonTypeInfo | For polymorphic deserialization |
| SccpAddressMixin.java | OK | Mixin for external class | Provides annotations for jSS7 |
| SccpAddressImplMixin.java | OK | Mixin for external class | Provides annotations for jSS7 |
| ProblemMixin.java | OK | Mixin for external class | Provides annotations for TCAP |

### 4.2 SLEE Module - SBB Classes

| File | Status | Notes |
|------|--------|-------|
| HttpServerSbb.java | OK | Entry point, uses EventsSerializeFactory correctly |
| USSDBaseSbb.java | OK | Base class, MAP dialog handling |
| SriSbb.java | OK | SRI lookup, MAP error handling |
| SipServerSbb.java | OK | SIP entry point, uses EventsSerializeFactory |
| ChildServerSbb.java | (referenced) | Parent class for server SBBs |
| SriParent.java | (interface) | Interface for SRI result callback |
| SriChild.java | (interface) | Interface for SRI query initiation |

### 4.3 Shell Classes

| File | Status | Notes |
|------|--------|-------|
| UssdShellExecutor.java | OK | CLI management, not XML-related |

## 5. Conclusion

The ussdgw project demonstrates a well-architected migration from javolution.xml to Jackson XML for XML serialization. The EventsSerializeFactory class serves as a robust central factory with comprehensive support for polymorphic deserialization, backward compatibility with both wrapper object and flat XML formats, and proper handling of external jSS7 library classes through mix-ins.

All examined classes in the xml module have complete Jackson XML annotations where needed. The slee module's SBB classes correctly use the EventsSerializeFactory for serialization/deserialization operations. The MAP message handling relies on the Restcomm jSS7 library for BER/DER ASN.1 encoding/decoding, which is accessed through well-defined interfaces.

No issues were identified that would prevent proper XML serialization or MAP message processing. The implementation appears to be production-ready with proper error handling, logging, and statistics aggregation throughout the codebase.

## 6. Sources

[1] [EventsSerializeFactory.java](file:///C:\Users\Windows\Desktop\ethiopia-working-dir\ussdgateway\core\xml\src\main\java\org\mobicents\ussdgateway\EventsSerializeFactory.java) - High Reliability - Core serialization factory

[2] [XmlMAPDialog.java](file:///C:\Users\Windows\Desktop\ethiopia-working-dir\ussdgateway\core\xml\src\main\java\org\mobicents\ussdgateway\XmlMAPDialog.java) - High Reliability - MAP dialog XML representation

[3] [SipUssdMessage.java](file:///C:\Users\Windows\Desktop\ethiopia-working-dir\ussdgateway\core\xml\src\main\java\org\mobicents\ussdgateway\SipUssdMessage.java) - High Reliability - SIP-USSD message

[4] [ErrorComponentMap.java](file:///C:\Users\Windows\Desktop\ethiopia-working-dir\ussdgateway\core\xml\src\main\java\org\mobicents\ussdgateway\ErrorComponentMap.java) - High Reliability - Error component storage

[5] [RejectComponentMap.java](file:///C:\Users\Windows\Desktop\ethiopia-working-dir\ussdgateway\core\xml\src\main\java\org\mobicents\ussdgateway\RejectComponentMap.java) - High Reliability - Reject component storage

[6] [HttpServerSbb.java](file:///C:\Users\Windows\Desktop\ethiopia-working-dir\ussdgateway\core\slee\sbbs\src\main\java\org\mobicents\ussdgateway\slee\http\HttpServerSbb.java) - High Reliability - HTTP entry point SBB

[7] [USSDBaseSbb.java](file:///C:\Users\Windows\Desktop\ethiopia-working-dir\ussdgateway\core\slee\sbbs\src\main\java\org\mobicents\ussdgateway\slee\USSDBaseSbb.java) - High Reliability - Base SBB class

[8] [SriSbb.java](file:///C:\Users\Windows\Desktop\ethiopia-working-dir\ussdgateway\core\slee\sbbs\src\main\java\org\mobicents\ussdgateway\slee\sri\SriSbb.java) - High Reliability - SRI lookup SBB

[9] [SipServerSbb.java](file:///C:\Users\Windows\Desktop\ethiopia-working-dir\ussdgateway\core\slee\sbbs\src\main\java\org\mobicents\ussdgateway\slee\sip\SipServerSbb.java) - High Reliability - SIP entry point SBB

[10] [UssdShellExecutor.java](file:///C:\Users\Windows\Desktop\ethiopia-working-dir\ussdgateway\core\domain\src\main\java\org\mobicents\ussdgateway\UssdShellExecutor.java) - High Reliability - CLI management