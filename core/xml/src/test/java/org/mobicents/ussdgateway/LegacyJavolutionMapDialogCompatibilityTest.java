package org.mobicents.ussdgateway;

import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSNotifyRequest;
import org.restcomm.protocols.ss7.map.service.supplementary.ProcessUnstructuredSSRequestImpl;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

/**
 * Regression suite for MAP/SIP XML payloads exchanged over HTTP in RestComm USSD Gateway.
 * Fixtures mirror javolution-era samples from upstream documentation and production traffic.
 */
public class LegacyJavolutionMapDialogCompatibilityTest {

    private EventsSerializeFactory factory;

    @BeforeClass
    public void setUp() {
        factory = EventsSerializeFactory.getInstance();
    }

    @DataProvider(name = "legacyMapFixtures")
    public Object[][] legacyMapFixtures() throws Exception {
        List<Object[]> cases = new ArrayList<Object[]>();
        cases.add(new Object[] {"legacy/map/push-unstructuredSSNotify.xml", "push-notify"});
        cases.add(new Object[] {"legacy/map/pull-processUnstructuredSSRequest-element.xml", "pull-element"});
        cases.add(new Object[] {"legacy/map/pull-processUnstructuredSSRequest-attribute.xml", "pull-attribute"});
        cases.add(new Object[] {"legacy/map/dialog-with-sccp-address.xml", "sccp-address"});
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "legacyMapFixtures")
    public void deserializeLegacyMapFixture(String resource, String label) throws Exception {
        String xml = readResource(resource);
        XmlMAPDialog dialog = factory.deserializeFromString(xml);

        assertNotNull(dialog, label + ": dialog");
        assertFalse(dialog.getMAPMessages().isEmpty(), label + ": mapMessages");

        if ("push-notify".equals(label)) {
            assertTrue(dialog.getMAPMessages().get(0) instanceof UnstructuredSSNotifyRequest);
            UnstructuredSSNotifyRequest notify = (UnstructuredSSNotifyRequest) dialog.getMAPMessages().get(0);
            assertEquals(notify.getMSISDNAddressString().getAddress(), "11111111111111");
        } else if ("pull-element".equals(label) || "pull-attribute".equals(label)) {
            ProcessUnstructuredSSRequestImpl req =
                    (ProcessUnstructuredSSRequestImpl) dialog.getMAPMessages().get(0);
            assertNotNull(req.getMSISDNAddressString(), label + ": msisdn");
            assertEquals(req.getMSISDNAddressString().getAddress(), "1234567890");
            assertNotNull(req.getUSSDString(), label + ": ussdString");
            assertEquals(req.getUSSDString().getString(null), "*100#");
        } else if ("sccp-address".equals(label)) {
            assertEquals(dialog.getMAPMessages().size(), 1, label + ": map message");
            assertNotNull(dialog.getLocalAddress(), label + ": localAddress");
            assertNotNull(dialog.getLocalAddress().getGlobalTitle(), label + ": globalTitle");
            assertEquals(dialog.getLocalAddress().getGlobalTitle().getDigits(), "79023700299");
        }
    }

    @Test
    public void deserializeLegacySipUssdPayload() throws Exception {
        String xml = readResource("legacy/map/sip-ussd-data-javolution.xml");
        SipUssdMessage message = factory.deserializeSipUssdMessage(xml.getBytes(StandardCharsets.UTF_8));

        assertNotNull(message);
        assertEquals(message.getUssdString(), "*100#");
        assertEquals(message.getLanguage(), "en");
    }

    @Test
    public void roundTripLegacyPushPayloadPreservesMsisdn() throws Exception {
        String xml = readResource("legacy/map/push-unstructuredSSNotify.xml");
        XmlMAPDialog original = factory.deserializeFromString(xml);
        byte[] serialized = factory.serialize(original);
        XmlMAPDialog copy = factory.deserialize(serialized);

        UnstructuredSSNotifyRequest notify = (UnstructuredSSNotifyRequest) copy.getMAPMessages().get(0);
        assertEquals(notify.getMSISDNAddressString().getAddress(), "11111111111111");
    }

    private static String readResource(String resource) throws Exception {
        InputStream is = LegacyJavolutionMapDialogCompatibilityTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(is, "Missing fixture: " + resource);
        byte[] buffer = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int read;
        while ((read = is.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        is.close();
        return sb.toString();
    }
}
