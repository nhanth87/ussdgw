package org.mobicents.ussdgateway;

import org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.ISDNAddressStringImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.UnstructuredSSNotifyRequestImpl;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Diagnostic tests for XML deserialization issues
 */
public class XmlDeserializationDebugTest {

    /**
     * Test: Direct Jackson deserialization of ISDNAddressString
     */
    @Test
    public void testDirectISDNAddressStringDeserialization() throws Exception {
        String xml = "<ISDNAddressString nai=\"international_number\" npi=\"ISDN\" number=\"1234567890\"/>";
        
        System.out.println("=== Test: Direct ISDNAddressString deserialization ===");
        System.out.println("Input XML: " + xml);
        
        com.fasterxml.jackson.dataformat.xml.XmlMapper xmlMapper = new com.fasterxml.jackson.dataformat.xml.XmlMapper();
        ISDNAddressStringImpl isdn = xmlMapper.readValue(xml, ISDNAddressStringImpl.class);
        
        System.out.println("Parsed: addressNature=" + isdn.getAddressNature());
        System.out.println("Parsed: numberingPlan=" + isdn.getNumberingPlan());
        System.out.println("Parsed: address=" + isdn.getAddress());
        
        assertNotNull(isdn.getAddress(), "Address should not be null");
        assertEquals(isdn.getAddress(), "1234567890");
        assertNotNull(isdn.getAddressNature(), "AddressNature should not be null");
        assertNotNull(isdn.getNumberingPlan(), "NumberingPlan should not be null");
    }

    /**
     * Test: Direct Jackson deserialization of UnstructuredSSNotifyRequest
     */
    @Test
    public void testDirectUnstructuredSSNotifyRequestDeserialization() throws Exception {
        String xml = "<unstructuredSSNotify_Request dataCodingScheme=\"15\" string=\"Test message\">\n" +
            "<msisdn nai=\"international_number\" npi=\"ISDN\" number=\"1234567890\"/>\n" +
            "</unstructuredSSNotify_Request>";
        
        System.out.println("=== Test: Direct UnstructuredSSNotifyRequest deserialization ===");
        System.out.println("Input XML: " + xml);
        
        com.fasterxml.jackson.dataformat.xml.XmlMapper xmlMapper = new com.fasterxml.jackson.dataformat.xml.XmlMapper();
        UnstructuredSSNotifyRequestImpl request = xmlMapper.readValue(xml, UnstructuredSSNotifyRequestImpl.class);
        
        System.out.println("Parsed: dataCodingScheme=" + request.getDataCodingScheme());
        System.out.println("Parsed: ussdString=" + request.getUSSDString());
        
        if (request.getMSISDNAddressString() != null) {
            System.out.println("MSISDN address=" + request.getMSISDNAddressString().getAddress());
            System.out.println("MSISDN nai=" + request.getMSISDNAddressString().getAddressNature());
            System.out.println("MSISDN npi=" + request.getMSISDNAddressString().getNumberingPlan());
            assertEquals(request.getMSISDNAddressString().getAddress(), "1234567890");
        } else {
            System.out.println("ERROR: MSISDN is NULL!");
            fail("MSISDN should not be null");
        }
    }

    /**
     * Test: Full dialog deserialization with MSISDN
     */
    @Test
    public void testFullDialogDeserialization() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
            "<dialog mapMessagesSize=\"1\">\n" +
            "  <unstructuredSSNotify_Request dataCodingScheme=\"15\" string=\"Balance notification\">\n" +
            "    <msisdn nai=\"international_number\" npi=\"ISDN\" number=\"11111111111111\"/>\n" +
            "  </unstructuredSSNotify_Request>\n" +
            "</dialog>";
        
        System.out.println("=== Test: Full dialog deserialization ===");
        System.out.println("Input XML:\n" + xml);
        
        EventsSerializeFactory factory = new EventsSerializeFactory();
        XmlMAPDialog dialog = factory.deserializeFromString(xml);
        
        System.out.println("\nDeserialized dialog:");
        System.out.println("  appCntx=" + dialog.getApplicationContext());
        System.out.println("  mapMessages size=" + dialog.getMAPMessages().size());
        
        if (dialog.getMAPMessages().size() > 0) {
            Object msg = dialog.getMAPMessages().get(0);
            System.out.println("  message type=" + msg.getClass().getSimpleName());
            
            if (msg instanceof UnstructuredSSNotifyRequestImpl) {
                UnstructuredSSNotifyRequestImpl notify = (UnstructuredSSNotifyRequestImpl) msg;
                System.out.println("  ussdString=" + notify.getUSSDString());
                System.out.println("  MSISDN=" + notify.getMSISDNAddressString());
                
                if (notify.getMSISDNAddressString() != null) {
                    System.out.println("  MSISDN.address=" + notify.getMSISDNAddressString().getAddress());
                    assertEquals(notify.getMSISDNAddressString().getAddress(), "11111111111111");
                } else {
                    System.out.println("ERROR: MSISDN is NULL!");
                    fail("MSISDN should not be null");
                }
            }
        } else {
            System.out.println("ERROR: No messages parsed!");
            fail("Should have parsed 1 message");
        }
    }

    /**
     * Test: Verify serialization then deserialization preserves MSISDN
     */
    @Test
    public void testSerializeThenDeserializeMSISDN() throws Exception {
        System.out.println("=== Test: Serialize then deserialize MSISDN ===");
        
        // Create message with MSISDN
        CBSDataCodingScheme dcs = new CBSDataCodingSchemeImpl(15);
        USSDString ussdStr = new USSDStringImpl("Test", dcs, null);
        ISDNAddressStringImpl msisdn = new ISDNAddressStringImpl(
            org.restcomm.protocols.ss7.map.api.primitives.AddressNature.international_number,
            org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan.ISDN,
            "9876543210"
        );
        
        UnstructuredSSNotifyRequestImpl original = new UnstructuredSSNotifyRequestImpl(dcs, ussdStr, null, msisdn);
        System.out.println("Original MSISDN: " + original.getMSISDNAddressString());
        
        XmlMAPDialog dialog = new XmlMAPDialog(null, null, null, 1L, 2L, null, null);
        dialog.addMAPMessage(original);
        
        // Serialize
        EventsSerializeFactory factory = new EventsSerializeFactory();
        byte[] serialized = factory.serialize(dialog);
        String xml = new String(serialized);
        System.out.println("Serialized XML:\n" + xml);
        
        // Deserialize
        XmlMAPDialog deserialized = factory.deserialize(serialized);
        
        if (deserialized.getMAPMessages().size() > 0) {
            UnstructuredSSNotifyRequestImpl copy = (UnstructuredSSNotifyRequestImpl) deserialized.getMAPMessages().get(0);
            System.out.println("Deserialized MSISDN: " + copy.getMSISDNAddressString());
            
            if (copy.getMSISDNAddressString() != null) {
                assertEquals(copy.getMSISDNAddressString().getAddress(), "9876543210");
                System.out.println("SUCCESS: MSISDN preserved after round-trip");
            } else {
                System.out.println("ERROR: MSISDN lost after round-trip!");
                fail("MSISDN should not be null after deserialization");
            }
        }
    }

    /**
     * Test: Verify element name "string" vs "ussdString" in MSISDN containing message
     */
    @Test
    public void testStringElementName() throws Exception {
        String xml = "<unstructuredSSNotify_Request dataCodingScheme=\"15\">\n" +
            "<string>Test USSD</string>\n" +
            "<msisdn nai=\"international_number\" npi=\"ISDN\" number=\"1234567890\"/>\n" +
            "</unstructuredSSNotify_Request>";
        
        System.out.println("=== Test: string element name ===");
        System.out.println("Input XML: " + xml);
        
        com.fasterxml.jackson.dataformat.xml.XmlMapper xmlMapper = new com.fasterxml.jackson.dataformat.xml.XmlMapper();
        UnstructuredSSNotifyRequestImpl request = xmlMapper.readValue(xml, UnstructuredSSNotifyRequestImpl.class);
        
        System.out.println("Parsed USSD String: " + request.getUSSDString());
        System.out.println("Parsed MSISDN: " + request.getMSISDNAddressString());
        
        if (request.getMSISDNAddressString() != null) {
            System.out.println("MSISDN.address=" + request.getMSISDNAddressString().getAddress());
            assertEquals(request.getMSISDNAddressString().getAddress(), "1234567890");
        } else {
            fail("MSISDN should not be null");
        }
    }
}