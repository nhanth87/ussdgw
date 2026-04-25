package org.mobicents.ussdgateway;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.restcomm.protocols.ss7.tcap.asn.ProblemImpl;

/**
 * Problem is always deserialized as ProblemImpl (no polymorphism needed for USSD Gateway).
 */
@JsonDeserialize(as = ProblemImpl.class)
public class ProblemMixin {
}
