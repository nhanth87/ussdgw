package org.mobicents.ussdgateway.xml;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * Guards the HTTP USSD XML tag contract used by SLEE library callers.
 * Values must stay aligned with upstream RestComm ussdgateway XMLTags.
 */
public class XMLTagsContractTest {

    @Test
    public void xmlHeaderMatchesRestCommContract() {
        assertEquals(XMLTags.XML_HEADER, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    }

    @Test
    public void requestResponseAbortTagsUnchanged() {
        assertEquals(XMLTags.REQUEST, "request");
        assertEquals(XMLTags.RESPONSE, "response");
        assertEquals(XMLTags.ABORT, "abort");
    }

    @Test
    public void ussdFieldTagsUnchanged() {
        assertEquals(XMLTags.INVOKE_ID, "invokeId");
        assertEquals(XMLTags.USSD_CODING, "ussdCoding");
        assertEquals(XMLTags.USSD_STRING, "ussdString");
        assertEquals(XMLTags.MSISDN, "msisdn");
        assertEquals(XMLTags.LAST_RESULT, "lastResult");
        assertEquals(XMLTags.END, "end");
    }

    @Test
    public void abortReasonTagsUnchanged() {
        assertEquals(XMLTags.USER_SPECIFIC_REASON, "userSpecificReason");
        assertEquals(XMLTags.USER_RESOURCE_LIMITATION, "userResourceLimitation");
        assertEquals(XMLTags.RESOURCE_UNAVAILABLE, "resourceUnavailable");
        assertEquals(XMLTags.SHORT_TERM_RESOURCE_LIMITATION, "shortTermResourceLimitation");
        assertEquals(XMLTags.LONG_TERM_RESOURCE_LIMITATION, "longTermResourceLimitation");
        assertEquals(XMLTags.APP_PROCEDURE_CANCELLATION, "applicationProcedureCancellation");
        assertEquals(XMLTags.HANDOVER_CANCELLATION, "handoverCancellation");
        assertEquals(XMLTags.RADIO_CHANNEL_RELEASE, "radioChannelRelease");
        assertEquals(XMLTags.NETWORK_PATH_RELEASE, "networkPathRelease");
        assertEquals(XMLTags.CALL_RELEASE, "callRelease");
        assertEquals(XMLTags.ASSOCIATED_PROC_FAILURE, "associatedProcedureFailure");
        assertEquals(XMLTags.TANDEM_DIALOGUE_RELEASE, "tandemDialogueRelease");
        assertEquals(XMLTags.REMOTE_OPERATION_FAILURE, "remoteOperationsFailure");
    }
}
