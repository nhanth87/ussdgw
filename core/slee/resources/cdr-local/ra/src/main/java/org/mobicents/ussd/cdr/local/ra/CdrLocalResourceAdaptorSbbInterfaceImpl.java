package org.mobicents.ussd.cdr.local.ra;

import org.mobicents.ussd.cdr.local.CdrLocalResourceAdaptorSbbInterface;
import org.mobicents.ussd.cdr.local.CdrWriteRequest;

public class CdrLocalResourceAdaptorSbbInterfaceImpl implements CdrLocalResourceAdaptorSbbInterface {

    private final CdrLocalResourceAdaptor ra;

    public CdrLocalResourceAdaptorSbbInterfaceImpl(CdrLocalResourceAdaptor ra) {
        this.ra = ra;
    }

    @Override
    public void submit(CdrWriteRequest request) {
        ra.submit(request);
    }
}
