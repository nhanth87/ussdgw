package org.mobicents.ussdgateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.restcomm.protocols.ss7.indicator.AddressIndicator;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;

/**
 * Jackson Mixin for SccpAddressImpl to add property mapping.
 * jSS7 9.2.11 has Jackson annotations but missing @JsonProperty on ai field.
 * SccpAddress is always deserialized as SccpAddressImpl (no polymorphism needed for USSD Gateway).
 * 
 * Handles legacy javolution XML format element names:
 * - ai -> addressIndicator (maps to ai field)
 * - pc -> signallingPointCode (maps to pc field)
 * - ssn -> subsystemNumber (maps to ssn field)
 * - gt -> globalTitle (maps to gt field)
 */
@JsonDeserialize(as = SccpAddressImpl.class)
public abstract class SccpAddressMixin {
    // Map legacy element name "ai" to actual field "ai"
    @JsonProperty("ai")
    private AddressIndicator ai;
    
    // Map legacy element name "pc" to actual field "pc"
    @JsonProperty("pc")
    private Object pc;
    
    // Map legacy element name "ssn" to actual field "ssn"
    @JsonProperty("ssn")
    private Object ssn;
    
    // Map legacy element name "gt" to actual field "gt"
    @JsonProperty("gt")
    private Object gt;
}
