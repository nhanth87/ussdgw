package org.mobicents.ussdgateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;

/**
 * Exposes SCCP addresses on dialog XML for javolution-compatible HTTP payloads.
 * Fields on {@link XmlMAPDialog} stay {@code @JsonIgnore}; this mixin wires
 * {@code localAddress}/{@code remoteAddress} through bean accessors only.
 */
public abstract class XmlMAPDialogSccpMixin {

    @JsonProperty("localAddress")
    @JacksonXmlProperty(localName = "localAddress")
    @JsonDeserialize(as = SccpAddressImpl.class)
    abstract SccpAddress getLocalAddress();

    @JsonProperty("localAddress")
    abstract void setLocalAddress(SccpAddress address);

    @JsonProperty("remoteAddress")
    @JacksonXmlProperty(localName = "remoteAddress")
    @JsonDeserialize(as = SccpAddressImpl.class)
    abstract SccpAddress getRemoteAddress();

    @JsonProperty("remoteAddress")
    abstract void setRemoteAddress(SccpAddress address);
}
