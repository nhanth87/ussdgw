package org.mobicents.ussd.cdr.local;

import javax.slee.resource.ResourceAdaptorTypeID;

/**
 * SBB-facing interface for the USSD CDR Local RA.
 */
public interface CdrLocalResourceAdaptorSbbInterface {

    ResourceAdaptorTypeID RATYPE_ID = new ResourceAdaptorTypeID(
            "CdrLocalResourceAdaptorType", "org.mobicents.ussd", "1.0");

    /**
     * Fire-and-forget CDR submit. Returns immediately; write happens on RA worker threads.
     */
    void submit(CdrWriteRequest request);
}
