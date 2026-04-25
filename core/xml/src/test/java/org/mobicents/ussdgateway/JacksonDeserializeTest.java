package org.mobicents.ussdgateway;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class JacksonDeserializeTest {
    @Test
    public void testDeserializePushRequest() throws Exception {
        String xml = "<?xml version=\"1.0\"?>\n" +
            "<dialog appCntx=\"networkUnstructuredSsContext\" version=\"2\">\n" +
            "  <processUnstructuredSSRequest_Request>\n" +
            "    <dataCodingScheme>15</dataCodingScheme>\n" +
            "    <ussdString>*100#</ussdString>\n" +
            "  </processUnstructuredSSRequest_Request>\n" +
            "</dialog>";
        
        EventsSerializeFactory factory = new EventsSerializeFactory();
        XmlMAPDialog dialog = factory.deserializeFromString(xml);
        
        assertNotNull(dialog, "dialog should not be null");
        assertNotNull(dialog.getMAPMessages(), "mapMessages should not be null");
        System.out.println("mapMessages size = " + dialog.getMAPMessages().size());
        System.out.println("appCntx = " + dialog.getApplicationContext());
    }
}
