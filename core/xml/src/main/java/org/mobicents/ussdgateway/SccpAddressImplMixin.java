package org.mobicents.ussdgateway;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.restcomm.protocols.ss7.indicator.AddressIndicator;

/**
 * Jackson Mixin for SccpAddressImpl to handle legacy javolution XML format.
 * Maps legacy element name "ai" to "addressIndicator".
 */
public abstract class SccpAddressImplMixin {
    @JsonIgnore
    private AddressIndicator ai;
    
    @JsonProperty("addressIndicator")
    private AddressIndicator addressIndicator;
    
    @JsonIgnore
    private Object pc;
    
    @JsonProperty("signallingPointCode")
    private Object signallingPointCode;
    
    @JsonIgnore
    private Object ssn;
    
    @JsonProperty("subsystemNumber")
    private Object subsystemNumber;
    
    @JsonIgnore
    private Object gt;
    
    @JsonProperty("globalTitle")
    private Object globalTitle;
}
