"""
Minimal XmlMAPDialog reader/writer for the USSD Gateway.

The gateway serializes the MAP dialogue to an XML document (see the Java
``EventsSerializeFactory`` and the reference layout produced by the HTTP example servlet's
``buildManualXmlResponse``). This module parses the inbound request dialogue and builds a valid
response dialogue that the gateway can deserialize. It deliberately implements only the subset of
the format needed for USSD pull/push menus.
"""

import xml.etree.ElementTree as ET

APP_CONTEXT = "networkUnstructuredSsContext_version2"


def parse_request(xml_bytes: bytes) -> dict:
    """
    Parse an inbound dialogue.

    Returns a dict with: message_type, ussd_string, invoke_id, local_id, network_id.
    message_type is one of: 'process_request', 'ussd_response', 'other'.
    """
    text = xml_bytes.decode("utf-8", errors="replace").strip()
    result = {
        "message_type": "other",
        "ussd_string": None,
        "invoke_id": 0,
        "local_id": None,
        "network_id": 0,
        "tcap": None,
    }
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return result

    result["local_id"] = root.get("localId") or root.get("localDialogId")
    result["tcap"] = root.get("type")
    try:
        result["network_id"] = int(root.get("networkId") or "0")
    except ValueError:
        result["network_id"] = 0

    for child in root:
        tag = _local(child.tag)
        if tag == "processUnstructuredSSRequest_Request":
            result["message_type"] = "process_request"
        elif tag == "unstructuredSSRequest_Response":
            result["message_type"] = "ussd_response"
        else:
            continue
        result["invoke_id"] = _int(child.get("invokeId"), 0)
        result["ussd_string"] = _extract_ussd_string(child)
        break
    return result


def _extract_ussd_string(elem) -> str:
    # USSD text can be an attribute ("string") or a child element (<string>...)
    attr = elem.get("string")
    if attr is not None:
        return attr
    for sub in elem:
        if _local(sub.tag) == "string":
            return (sub.text or "").strip()
    return None


def build_response(message_type: str, text: str, invoke_id: int, end: bool,
                   user_object: str = None, network_id: int = 0) -> bytes:
    """
    Build a response dialogue.

    end=False -> Continue + unstructuredSSRequest_Request (prompt the user again).
    end=True  -> End + processUnstructuredSSRequest_Response (final message).
    """
    tcap = "End" if end else "Continue"
    if end:
        msg = (
            '<processUnstructuredSSRequest_Response invokeId="%d" dataCodingScheme="15">'
            "<string>%s</string></processUnstructuredSSRequest_Response>" % (invoke_id, _esc(text))
        )
    else:
        msg = (
            '<unstructuredSSRequest_Request invokeId="%d" dataCodingScheme="15">'
            "<string>%s</string></unstructuredSSRequest_Request>" % (invoke_id, _esc(text))
        )

    parts = ['<?xml version="1.0" encoding="UTF-8"?>']
    head = '<dialog type="%s" appCntx="%s" networkId="%d" mapMessagesSize="1" returnMessageOnError="false"' % (
        tcap, APP_CONTEXT, network_id)
    if user_object:
        head += ' userObject="%s"' % _esc(user_object)
    if end:
        head += ' prearrangedEnd="false"'
    head += ">"
    parts.append(head)
    parts.append("<errComponents/>")
    parts.append("<rejectComponents/>")
    parts.append(msg)
    parts.append("</dialog>")
    return "".join(parts).encode("utf-8")


def build_dialog_end() -> bytes:
    return (
        '<?xml version="1.0" encoding="UTF-8" ?>'
        '<dialog type="End" mapMessagesSize="0" prearrangedEnd="false"></dialog>'
    ).encode("utf-8")


def build_push_request(text: str, msisdn: str, invoke_id: int = 1, network_id: int = 0,
                       empty_handshake: bool = False) -> bytes:
    """NI push: unstructuredSSRequest_Request with subscriber MSISDN."""
    extra = ' emptyDialogHandshake="true"' if empty_handshake else ""
    msg = (
        '<unstructuredSSRequest_Request invokeId="%d" dataCodingScheme="15" string="%s">'
        '<msisdn nai="international_number" npi="ISDN" number="%s"/>'
        "</unstructuredSSRequest_Request>"
        % (invoke_id, _esc(text), _esc(msisdn))
    )
    head = (
        '<?xml version="1.0" encoding="UTF-8" ?>'
        '<dialog mapMessagesSize="1" networkId="%d"%s>' % (network_id, extra)
    )
    return (head + msg + "</dialog>").encode("utf-8")


def build_push_notify(text: str, msisdn: str, network_id: int = 0) -> bytes:
    msg = (
        '<unstructuredSSNotify_Request dataCodingScheme="15" string="%s">'
        '<msisdn nai="international_number" npi="ISDN" number="%s"/>'
        "</unstructuredSSNotify_Request>"
        % (_esc(text), _esc(msisdn))
    )
    head = (
        '<?xml version="1.0" encoding="UTF-8" ?>'
        '<dialog mapMessagesSize="1" networkId="%d">' % network_id
    )
    return (head + msg + "</dialog>").encode("utf-8")


def build_pull_response_simple(text: str, invoke_id: int = 0, end: bool = False) -> bytes:
    """Legacy HTTP simulator XML shape (examples.txt)."""
    if end:
        inner = (
            '<processUnstructuredSSRequest_Response invokeId="%d" '
            'dataCodingScheme="15" string="%s"/>' % (invoke_id, _esc(text))
        )
    else:
        inner = (
            '<unstructuredSSRequest_Request dataCodingScheme="15" string="%s"/>' % _esc(text)
        )
    return (
        '<?xml version="1.0" encoding="UTF-8" ?>\n'
        '<dialog mapMessagesSize="1" prearrangedEnd="false">\n\t%s\n</dialog>' % inner
    ).encode("utf-8")


def _local(tag: str) -> str:
    return tag.split("}", 1)[-1] if "}" in tag else tag


def _int(value, default):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _esc(s: str) -> str:
    if s is None:
        return ""
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&apos;"))
