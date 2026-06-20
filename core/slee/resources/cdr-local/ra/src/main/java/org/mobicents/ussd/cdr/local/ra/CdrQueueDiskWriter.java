package org.mobicents.ussd.cdr.local.ra;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.slee.facilities.Tracer;

import org.jctools.queues.MpscArrayQueue;

/**
 * Single consumer: drains {@link MpscArrayQueue} in batches and appends to daily CDR files.
 */
final class CdrQueueDiskWriter implements Runnable {

    private final MpscArrayQueue<String> queue;
    private final Tracer tracer;
    private final File logDir;
    private final String filePrefix;
    private final int batchSize;
    private final long flushIntervalMs;
    private final long parkNanos;

    private volatile boolean running = true;
    private Thread writerThread;

    private String currentDate;
    private BufferedWriter currentWriter;
    private long lastFlushMs;

    CdrQueueDiskWriter(MpscArrayQueue<String> queue, Tracer tracer, File logDir, String filePrefix,
            int batchSize, long flushIntervalMs) {
        this.queue = queue;
        this.tracer = tracer;
        this.logDir = logDir;
        this.filePrefix = filePrefix;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.parkNanos = TimeUnit.MILLISECONDS.toNanos(Math.min(flushIntervalMs, 50L));
    }

    void start() {
        writerThread = new Thread(this, "ussd-cdr-local-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    void shutdownAndDrain() {
        running = false;
        Thread t = writerThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        drainRemaining();
        closeWriterQuietly();
    }

    boolean isRunning() {
        return running && writerThread != null && writerThread.isAlive();
    }

    @Override
    public void run() {
        List<String> batch = new ArrayList<String>(batchSize);
        lastFlushMs = System.currentTimeMillis();
        try {
            while (running || !queue.isEmpty()) {
                String line = queue.relaxedPoll();
                if (line != null) {
                    batch.add(line);
                    if (batch.size() >= batchSize) {
                        writeBatch(batch);
                    }
                    continue;
                }
                long now = System.currentTimeMillis();
                if (!batch.isEmpty() && now - lastFlushMs >= flushIntervalMs) {
                    writeBatch(batch);
                }
                if (running) {
                    LockSupport.parkNanos(parkNanos);
                }
            }
            if (!batch.isEmpty()) {
                writeBatch(batch);
            }
        } catch (Throwable t) {
            tracer.severe("CDR writer thread terminated with error", t);
        } finally {
            closeWriterQuietly();
        }
    }

    private void drainRemaining() {
        List<String> batch = new ArrayList<String>(batchSize);
        String line;
        while ((line = queue.relaxedPoll()) != null) {
            batch.add(line);
            if (batch.size() >= batchSize) {
                try {
                    writeBatch(batch);
                } catch (IOException e) {
                    tracer.severe("Failed to drain CDR queue on shutdown", e);
                }
            }
        }
        if (!batch.isEmpty()) {
            try {
                writeBatch(batch);
            } catch (IOException e) {
                tracer.severe("Failed to drain final CDR batch on shutdown", e);
            }
        }
    }

    private void writeBatch(List<String> batch) throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        ensureWriter(date);
        for (int i = 0; i < batch.size(); i++) {
            currentWriter.write(batch.get(i));
            currentWriter.newLine();
        }
        currentWriter.flush();
        lastFlushMs = System.currentTimeMillis();
        batch.clear();
    }

    private void ensureWriter(String date) throws IOException {
        if (date.equals(currentDate) && currentWriter != null) {
            return;
        }
        closeWriterQuietly();
        if (!logDir.exists() && !logDir.mkdirs()) {
            throw new IOException("Unable to create CDR log directory: " + logDir.getAbsolutePath());
        }
        File file = new File(logDir, filePrefix + "-" + date + ".log");
        currentWriter = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8));
        currentDate = date;
    }

    private void closeWriterQuietly() {
        if (currentWriter != null) {
            try {
                currentWriter.flush();
                currentWriter.close();
            } catch (IOException e) {
                tracer.warning("Error closing CDR writer", e);
            }
            currentWriter = null;
            currentDate = null;
        }
    }
}
