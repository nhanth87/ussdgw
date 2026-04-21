package org.mobicents.ussd.loadtest;

import java.util.concurrent.atomic.LongAdder;

/**
 * High-performance metrics collector for USSD load testing.
 * Uses LongAdder to minimize contention in multi-threaded environment.
 * 
 * Target: 10k TPS
 */
public class LoadTestMetrics {

    private final LongAdder requestsSent = new LongAdder();
    private final LongAdder responsesReceived = new LongAdder();
    private final LongAdder errorsReceived = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();

    private volatile long lastReportTime = System.nanoTime();
    private volatile long lastRequests = 0;
    private volatile long lastResponses = 0;
    private volatile long lastErrors = 0;

    public void recordRequest() {
        requestsSent.increment();
    }

    public void recordResponse(long latencyNanos) {
        responsesReceived.increment();
        totalLatencyNanos.add(latencyNanos);
    }

    public void recordError() {
        errorsReceived.increment();
    }

    /**
     * Prints a TPS report and returns current stats.
     */
    public String report() {
        long now = System.nanoTime();
        long currentRequests = requestsSent.sum();
        long currentResponses = responsesReceived.sum();
        long currentErrors = errorsReceived.sum();
        long currentLatency = totalLatencyNanos.sum();

        double elapsedSec = (now - lastReportTime) / 1_000_000_000.0;
        long reqDelta = currentRequests - lastRequests;
        long respDelta = currentResponses - lastResponses;
        long errDelta = currentErrors - lastErrors;

        double requestTps = reqDelta / elapsedSec;
        double responseTps = respDelta / elapsedSec;
        double avgLatencyMs = (respDelta > 0 && currentResponses > 0) 
            ? (currentLatency / (double) currentResponses) / 1_000_000.0 
            : 0.0;

        lastReportTime = now;
        lastRequests = currentRequests;
        lastResponses = currentResponses;
        lastErrors = currentErrors;

        return String.format(
            "[LoadTest] TPS(req=%.0f|resp=%.0f) | Total(req=%d|resp=%d|err=%d) | AvgLatency=%.3fms | Errors/sec=%.0f",
            requestTps, responseTps, currentRequests, currentResponses, currentErrors, avgLatencyMs, errDelta / elapsedSec
        );
    }

    public long getTotalRequests() {
        return requestsSent.sum();
    }

    public long getTotalResponses() {
        return responsesReceived.sum();
    }

    public long getTotalErrors() {
        return errorsReceived.sum();
    }
}
