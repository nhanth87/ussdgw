package org.mobicents.ussdgateway.slee.cdr;

import java.sql.Timestamp;

import org.testng.Assert;
import org.testng.annotations.Test;

public class CdrLineFormatterTest {

    @Test
    public void formatMatchesLegacyPlainLayoutWithoutSccpAddresses() {
        USSDCDRState state = new USSDCDRState();
        state.init(42L, "*123#", null, null, null, null, null);
        state.setRemoteDialogId(99L);
        state.setUssdString("hello");
        state.setRecordStatus(RecordStatus.SUCCESS);
        state.setDialogDuration(500L);

        Timestamp fixedTs = Timestamp.valueOf("2024-01-15 10:20:30.123");
        String line = CdrLineFormatter.format(state, fixedTs, ":");

        String expected = "*123#"
                + ":"
                + ":::"
                + ":::"
                + ":::"
                + ":::"
                + ":"
                + "SUCCESS"
                + ":"
                + "PULL"
                + ":"
                + fixedTs
                + ":"
                + "42"
                + ":"
                + "99"
                + ":"
                + "500"
                + ":"
                + "hello"
                + ":"
                + state.getId();

        Assert.assertEquals(line, expected);
    }

    @Test
    public void finalizeRecordIsIdempotent() {
        USSDCDRState state = new USSDCDRState();
        state.init(1L, "100", null, null, null, null, null);
        Assert.assertTrue(CdrLineFormatter.finalizeRecord(state, RecordStatus.SUCCESS));
        Assert.assertFalse(CdrLineFormatter.finalizeRecord(state, RecordStatus.ABORT_APP));
    }
}
