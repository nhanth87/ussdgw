package org.mobicents.ussdgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.testng.annotations.Test;

public class JacksonXmlTest {
    @Test
    public void testParseExternalXml() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
                + "<dialog type=\"Begin\" mapMessagesSize=\"1\">\n"
                + "<unstructuredSSNotify_Request dataCodingScheme=\"15\" string=\"Your new balance...\">\n"
                + "<msisdn nai=\"international_number\" npi=\"ISDN\" number=\"79273605819\"/>\n"
                + "</unstructuredSSNotify_Request>\n"
                + "</dialog>";

        XmlMapper mapper = new XmlMapper();
        JsonNode node = mapper.readTree(xml.getBytes());
        System.out.println("Root node: " + node);
        System.out.println("mapMessages node: " + node.get("mapMessages"));
        System.out.println("unstructuredSSNotify_Request node: " + node.get("unstructuredSSNotify_Request"));
        
        JsonNode msgNode = node.get("unstructuredSSNotify_Request");
        if (msgNode != null) {
            System.out.println("msgNode fields: " + msgNode.fieldNames());
            System.out.println("msgNode.dataCodingScheme: " + msgNode.get("dataCodingScheme"));
            System.out.println("msgNode.msisdn: " + msgNode.get("msisdn"));
        }
    }
}
