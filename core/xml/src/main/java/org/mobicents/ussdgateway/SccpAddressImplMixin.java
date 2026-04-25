package org.mobicents.ussdgateway;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.restcomm.protocols.ss7.indicator.AddressIndicator;

/**
 * Jackson Mixin for SccpAddressImpl to map legacy XML element name "ai" to "addressIndicator".
 */
public abstract class SccpAddressImplMixin {
    @JsonProperty("addressIndicator")
    @JsonAlias("ai")
    private AddressIndicator ai;
}
