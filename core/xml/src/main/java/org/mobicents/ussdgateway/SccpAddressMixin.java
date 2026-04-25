package org.mobicents.ussdgateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.restcomm.protocols.ss7.indicator.AddressIndicator;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;

/**
 * Jackson Mixin for SccpAddressImpl to add missing property mapping.
 * jSS7 9.2.11 has Jackson annotations but missing @JsonProperty on ai field.
 * SccpAddress is always deserialized as SccpAddressImpl (no polymorphism needed for USSD Gateway).
 */
@JsonDeserialize(as = SccpAddressImpl.class)
public abstract class SccpAddressMixin {
    @JsonProperty("addressIndicator")
    private AddressIndicator ai;
}
