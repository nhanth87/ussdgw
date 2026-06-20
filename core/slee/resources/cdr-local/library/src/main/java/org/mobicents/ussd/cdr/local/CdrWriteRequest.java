package org.mobicents.ussd.cdr.local;

import java.io.Serializable;

/**
 * Fire-and-forget CDR payload submitted from USSD SBB to the local CDR RA.
 */
public final class CdrWriteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String recordId;
    private final String line;
    private final long submittedAtMillis;

    public CdrWriteRequest(String recordId, String line) {
        this.recordId = recordId;
        this.line = line;
        this.submittedAtMillis = System.currentTimeMillis();
    }

    public String getRecordId() {
        return recordId;
    }

    public String getLine() {
        return line;
    }

    public long getSubmittedAtMillis() {
        return submittedAtMillis;
    }
}
