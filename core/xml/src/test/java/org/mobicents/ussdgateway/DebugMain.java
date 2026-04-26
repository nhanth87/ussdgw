package org.mobicents.ussdgateway;

import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.primitives.AddressStringImpl;
import org.restcomm.protocols.ss7.map.primitives.ISDNAddressStringImpl;
import org.restcomm.protocols.ss7.map.primitives.AlertingPatternImpl;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.ProcessUnstructuredSSRequestImpl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.GlobalTitle0100Impl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.DefaultEncodingScheme;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.primitives.AlertingCategory;
import org.restcomm.protocols.ss7.tcap.api.MessageType;

public class DebugMain {
    public static void main(String[] args) throws Exception {
        org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle gt = new GlobalTitle0100Impl("79023700299", 0, DefaultEncodingScheme.INSTANCE,
                org.restcomm.protocols.ss7.indicator.NumberingPlan.ISDN_TELEPHONY, NatureOfAddress.INTERNATIONAL);

        org.restcomm.protocols.ss7.sccp.parameter.SccpAddress orgAddress = new SccpAddressImpl(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE, gt, 0, 146);

        org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle gt1 = new GlobalTitle0100Impl("79023700111", 0, DefaultEncodingScheme.INSTANCE,
                org.restcomm.protocols.ss7.indicator.NumberingPlan.ISDN_TELEPHONY, NatureOfAddress.INTERNATIONAL);
        org.restcomm.protocols.ss7.sccp.parameter.SccpAddress dstAddress = new SccpAddressImpl(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE, gt1, 0, 146);

        org.restcomm.protocols.ss7.map.api.primitives.AddressString destReference = new AddressStringImpl(AddressNature.international_number,
                NumberingPlan.land_mobile, "204208300008002");
        org.restcomm.protocols.ss7.map.api.primitives.AddressString origReference = new AddressStringImpl(AddressNature.international_number, NumberingPlan.ISDN,
                "31628968300");

        ISDNAddressStringImpl isdnAddress = new ISDNAddressStringImpl(AddressNature.international_number,
                NumberingPlan.ISDN, "79273605819");
        AlertingPatternImpl alertingPattern = new AlertingPatternImpl(AlertingCategory.Category3);
        org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme cbsDataCodingScheme = new CBSDataCodingSchemeImpl(15);
        org.restcomm.protocols.ss7.map.api.primitives.USSDString ussdStr = new USSDStringImpl("*234#", cbsDataCodingScheme, null);
        ProcessUnstructuredSSRequestImpl processUnstructuredSSRequestIndication = new ProcessUnstructuredSSRequestImpl(
                cbsDataCodingScheme, ussdStr, alertingPattern, isdnAddress);

        MAPApplicationContext appCtx = MAPApplicationContext.getInstance(
                MAPApplicationContextName.networkUnstructuredSsContext, MAPApplicationContextVersion.version2);

        XmlMAPDialog original = new XmlMAPDialog(appCtx, orgAddress, dstAddress, 12l, 13l, destReference, origReference);
        original.setTCAPMessageType(MessageType.Begin);
        original.setUserObject("123456789");
        original.addMAPMessage(processUnstructuredSSRequestIndication);

        EventsSerializeFactory factory = new EventsSerializeFactory();
        byte[] serializedEvent = factory.serialize(original);
        System.out.println("SERIALIZED:");
        System.out.println(new String(serializedEvent));
    }
}
