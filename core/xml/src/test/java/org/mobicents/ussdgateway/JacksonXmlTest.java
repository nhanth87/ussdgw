package org.mobicents.ussdgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Tests that Jackson XmlMapper can parse external XML payloads
 * that conform to the legacy javolution XML format.
 */
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

        assertNotNull(node, "Root node should not be null");
        assertTrue(node.has("unstructuredSSNotify_Request"),
                "XML should contain unstructuredSSNotify_Request element");

        JsonNode msgNode = node.get("unstructuredSSNotify_Request");
        assertNotNull(msgNode, "unstructuredSSNotify_Request node should exist");

        // Verify attributes are parsed
        assertNotNull(msgNode.get("dataCodingScheme"), "dataCodingScheme attribute should exist");
        assertEquals(msgNode.get("dataCodingScheme").asText(), "15");

        assertNotNull(msgNode.get("string"), "string attribute should exist");
        assertEquals(msgNode.get("string").asText(), "Your new balance...");

        // Verify nested element with attributes
        assertTrue(msgNode.has("msisdn"), "msisdn element should exist");
        JsonNode msisdn = msgNode.get("msisdn");
        assertNotNull(msisdn.get("number"), "msisdn number attribute should exist");
        assertEquals(msisdn.get("number").asText(), "79273605819");

        // Verify dialog-level attributes
        assertNotNull(node.get("type"), "type attribute should exist");
        assertEquals(node.get("type").asText(), "Begin");

        assertNotNull(node.get("mapMessagesSize"), "mapMessagesSize attribute should exist");
        assertEquals(node.get("mapMessagesSize").asText(), "1");
    }
}
