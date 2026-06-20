/*
 * Normalizes RestComm javolution XML persistence into Jackson-readable form.
 */
package org.mobicents.ussdgateway;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges legacy javolution XML config files to the Jackson XmlMapper layout used
 * after the migration. Keeps on-disk files from older USSD Gateway releases readable.
 */
public final class LegacyXmlConfigAdapter {

    private static final Pattern SC_ROUTING_RULE =
            Pattern.compile("<scroutingrule\\s+([^>]*)/\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_ATTR =
            Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern USSD_GT_ELEMENT =
            Pattern.compile("<UssdGwGtNetworkIdElement\\s+([^>]*)/\\s*>", Pattern.CASE_INSENSITIVE);

    private LegacyXmlConfigAdapter() {
    }

    public static String stripXmlDeclaration(String xml) {
        if (xml == null) {
            return "";
        }
        String content = xml.trim();
        if (content.startsWith("<?xml")) {
            int declEnd = content.indexOf("?>");
            if (declEnd >= 0) {
                content = content.substring(declEnd + 2).trim();
            }
        }
        return content;
    }

    /**
     * Extract inner payload from a single-root wrapper such as scroutingrulelist.
     */
    public static String unwrapSingleRootElement(String xml) {
        String content = stripXmlDeclaration(xml);
        int startIdx = content.indexOf('>');
        int endIdx = content.lastIndexOf('<');
        if (startIdx >= 0 && endIdx > startIdx) {
            return content.substring(startIdx + 1, endIdx).trim();
        }
        return content;
    }

    /**
     * Convert javolution scroutingrule attribute entries to Jackson ArrayList/item layout.
     */
    public static String normalizeScRoutingRuleListContent(String innerContent) {
        if (innerContent == null || innerContent.isEmpty()) {
            return innerContent;
        }
        if (innerContent.contains("<ArrayList")) {
            return innerContent;
        }
        if (!innerContent.toLowerCase().contains("<scroutingrule")) {
            return innerContent;
        }

        StringBuilder sb = new StringBuilder("<ArrayList>");
        Matcher matcher = SC_ROUTING_RULE.matcher(innerContent);
        while (matcher.find()) {
            sb.append(attributeBlockToItem(matcher.group(1)));
        }
        sb.append("</ArrayList>");
        return sb.toString();
    }

    /**
     * Wrap javolution multi-root property files or pass through Jackson HashMap files.
     */
    public static String normalizeUssdPropertiesXml(String xml) {
        String body = stripXmlDeclaration(xml).trim();
        if (body.isEmpty()) {
            return body;
        }
        if (body.startsWith("<HashMap")) {
            return normalizeUssdGtListInsideHashMap(body);
        }
        return normalizeUssdGtListInsideHashMap("<HashMap>" + body + "</HashMap>");
    }

    private static String normalizeUssdGtListInsideHashMap(String hashMapXml) {
        if (hashMapXml == null || !hashMapXml.contains("UssdGwGtNetworkIdElement")) {
            return hashMapXml;
        }

        Pattern listPattern = Pattern.compile("<ussdgtlist>(.*?)</ussdgtlist>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher listMatcher = listPattern.matcher(hashMapXml);
        StringBuffer sb = new StringBuffer();
        while (listMatcher.find()) {
            String inner = listMatcher.group(1);
            if (inner.contains("<ArrayList")) {
                listMatcher.appendReplacement(sb, Matcher.quoteReplacement(listMatcher.group(0)));
                continue;
            }
            StringBuilder converted = new StringBuilder("<ussdgtlist><ArrayList>");
            Matcher elem = USSD_GT_ELEMENT.matcher(inner);
            while (elem.find()) {
                converted.append(attributeBlockToItem(elem.group(1)));
            }
            converted.append("</ArrayList></ussdgtlist>");
            listMatcher.appendReplacement(sb, Matcher.quoteReplacement(converted.toString()));
        }
        listMatcher.appendTail(sb);
        return sb.toString();
    }

    private static String attributeBlockToItem(String attrs) {
        StringBuilder item = new StringBuilder("<item>");
        Matcher attrMatcher = XML_ATTR.matcher(attrs);
        while (attrMatcher.find()) {
            String name = attrMatcher.group(1);
            String value = attrMatcher.group(2);
            item.append('<').append(name).append('>')
                    .append(escapeXmlText(value))
                    .append("</").append(name).append('>');
        }
        item.append("</item>");
        return item.toString();
    }

    private static String escapeXmlText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Extract per-network GT entries from Jackson HashMap or legacy javolution ussdgtlist nodes.
     */
    @SuppressWarnings("unchecked")
    public static java.util.List<UssdGwGtNetworkIdElement> parseUssdGtList(Object raw) {
        java.util.List<UssdGwGtNetworkIdElement> result = new java.util.ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (raw instanceof java.util.List) {
            appendGtElements(result, (java.util.List<Object>) raw);
            return result;
        }
        if (raw instanceof java.util.Map) {
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) raw;
            Object listNode = map.get("ArrayList");
            if (listNode == null) {
                listNode = map.get("item");
            }
            if (listNode instanceof java.util.List) {
                appendGtElements(result, (java.util.List<Object>) listNode);
            } else if (listNode instanceof java.util.Map) {
                result.add(mapToGtElement((java.util.Map<String, Object>) listNode));
            } else if (map.containsKey("networkId") || map.containsKey("ussdGwGt")) {
                result.add(mapToGtElement(map));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void appendGtElements(java.util.List<UssdGwGtNetworkIdElement> result, java.util.List<Object> items) {
        for (Object item : items) {
            if (item instanceof UssdGwGtNetworkIdElement) {
                result.add((UssdGwGtNetworkIdElement) item);
            } else if (item instanceof java.util.Map) {
                result.add(mapToGtElement((java.util.Map<String, Object>) item));
            }
        }
    }

    private static UssdGwGtNetworkIdElement mapToGtElement(java.util.Map<String, Object> map) {
        UssdGwGtNetworkIdElement elem = new UssdGwGtNetworkIdElement();
        Object networkId = map.get("networkId");
        if (networkId instanceof Number) {
            elem.networkId = ((Number) networkId).intValue();
        } else if (networkId != null) {
            elem.networkId = Integer.parseInt(networkId.toString());
        }
        Object gt = map.get("ussdGwGt");
        if (gt != null) {
            elem.ussdGwGt = gt.toString();
        }
        return elem;
    }
}
