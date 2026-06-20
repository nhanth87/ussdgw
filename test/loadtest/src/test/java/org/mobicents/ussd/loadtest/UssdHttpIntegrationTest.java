package org.mobicents.ussd.loadtest;

import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mobicents.ussd.loadtest.stub.UssdHttpTestStub;

/**
 * Lightweight HTTP integration test: stub external USSD app + load generator.
 * Does not require WildFly; validates XmlMAPDialog round-trip under concurrent load.
 */
public class UssdHttpIntegrationTest {

    private static final int STUB_PORT = 19081;

    private UssdHttpTestStub stub;

    @Before
    public void startStub() throws Exception {
        stub = new UssdHttpTestStub(STUB_PORT);
        stub.start();
        Thread.sleep(200);
    }

    @After
    public void stopStub() {
        if (stub != null) {
            stub.stop();
        }
    }

    @Test
    public void stubHealthEndpointIsUp() throws Exception {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Response response = client.newCall(new okhttp3.Request.Builder()
                .url("http://localhost:" + STUB_PORT + "/health")
                .get()
                .build()).execute();
        try {
            assertTrue(response.isSuccessful());
            assertTrue(response.body().string().contains("OK"));
        } finally {
            response.close();
        }
    }

    @Test
    public void loadGeneratorRoundTripAgainstStub() throws Exception {
        LoadTestMetrics metrics = new LoadTestMetrics();
        UssdHttpLoadGenerator generator = new UssdHttpLoadGenerator(
                "http://localhost:" + STUB_PORT + "/",
                500,
                8,
                200,
                metrics);

        generator.start();
        Thread.sleep(4000);
        generator.stop();
        Thread.sleep(500);

        long responses = metrics.getTotalResponses();
        long errors = metrics.getTotalErrors();
        long requests = metrics.getTotalRequests();

        assertTrue("expected responses > 500, got " + responses, responses > 500);
        assertTrue("error rate too high: err=" + errors + " req=" + requests,
                errors < requests * 0.05);
    }
}
