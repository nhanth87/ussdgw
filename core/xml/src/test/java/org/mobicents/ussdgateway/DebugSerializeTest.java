package org.mobicents.ussdgateway;

import org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.UnstructuredSSResponseImpl;
import org.testng.annotations.Test;

public class DebugSerializeTest {
    @Test
    public void testSerialize() throws Exception {
        CBSDataCodingScheme cbsDataCodingScheme = new CBSDataCodingSchemeImpl(15);
        USSDString ussdStr = new USSDStringImpl("1", cbsDataCodingScheme, null);
        UnstructuredSSResponseImpl unstructuredSSResponseIndication = new UnstructuredSSResponseImpl(
                cbsDataCodingScheme, ussdStr);

        XmlMAPDialog original = new XmlMAPDialog(null, null, null, 12l, 13l, null, null);
        original.addMAPMessage(unstructuredSSResponseIndication);
        original.setTCAPMessageType(org.restcomm.protocols.ss7.tcap.api.MessageType.Continue);

        EventsSerializeFactory factory = new EventsSerializeFactory();
        byte[] serializedEvent = factory.serialize(original);
        System.out.println("SERIALIZED XML:");
        System.out.println(new String(serializedEvent));
        
        System.out.println("\nDESERIALIZING...");
        XmlMAPDialog copy = factory.deserialize(serializedEvent);
        System.out.println("COPY mapMessages size=" + copy.getMAPMessages().size());
    }
}
