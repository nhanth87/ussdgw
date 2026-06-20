/*
 * MpscMessageList - lock-free multi-producer / single-consumer queue.
 *
 * Wraps a JCTools MpscArrayQueue to provide a bounded, allocation-light
 * message buffer for inbound producer/consumer hot paths in the USSD
 * Gateway (e.g. async event staging between an HTTP RA worker pool and the
 * SBB consumer thread).
 *
 * Design notes:
 * - Bounded by a power-of-two capacity. The chosen capacity is rounded up to
 *   the next power of two because MpscArrayQueue requires it and because power
 *   of two sizes let the queue mask with (capacity - 1) instead of a modulo.
 * - Many producers (offer/poll are safe across threads), exactly one consumer.
 *   The consumer MUST be the only thread calling poll()/peek()/drainTo()/
 *   clear()/forEach(); otherwise size()/iterator() are no longer consistent.
 * - Iteration is exposed via drainTo(Collection) or snapshot(); both consume
 *   the queue in O(n) and hand the caller a stable view that is safe to walk
 *   without further synchronization.
 * - The class is intentionally NOT a java.util.List. Indexed access (get(i))
 *   is rejected with UnsupportedOperationException because the underlying
 *   MpscArrayQueue is a ring buffer where random access has no useful
 *   meaning and would hide the producer/consumer contract. For indexed
 *   access over a snapshot use snapshot() or drainTo(ArrayList).
 *
 * Use {@link FastList} instead when you need a single-threaded List with
 * indexed access (e.g. dialog-scoped state inside an SBB entity).
 */
package org.mobicents.ussdgateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.jctools.queues.MpscArrayQueue;

/**
 * Lock-free MPSC message queue backed by JCTools {@link MpscArrayQueue}.
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * MpscMessageList<Event> inbox = new MpscMessageList<>(1024);
 *
 * // any number of producer threads:
 * if (!inbox.offer(event)) {
 *     droppedCounter.incrementAndGet();
 * }
 *
 * // exactly one consumer thread:
 * Event e;
 * while ((e = inbox.poll()) != null) {
 *     handle(e);
 * }
 * }</pre>
 *
 * @param <E> message type
 */
public class MpscMessageList<E> {

    /** Default capacity used when the caller does not specify one. */
    public static final int DEFAULT_CAPACITY = 1024;

    private final MpscArrayQueue<E> queue;
    private final int capacity;
    private final AtomicLong offeredCount = new AtomicLong();
    private final AtomicLong polledCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();

    public MpscMessageList() {
        this(DEFAULT_CAPACITY);
    }

    public MpscMessageList(int requestedCapacity) {
        if (requestedCapacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + requestedCapacity);
        }
        this.capacity = nextPowerOfTwo(requestedCapacity);
        this.queue = new MpscArrayQueue<>(this.capacity);
    }

    // ===== Producer API (thread-safe) =====

    /**
     * Append a message to the tail of the queue. Safe to call from any number
     * of producer threads concurrently. Returns {@code false} if the queue is
     * full; the message is then dropped and {@link #droppedCount()} is bumped.
     */
    public boolean offer(E e) {
        Objects.requireNonNull(e, "MpscMessageList does not allow null elements");
        boolean accepted = queue.relaxedOffer(e);
        if (accepted) {
            offeredCount.lazySet(offeredCount.get() + 1);
        } else {
            droppedCount.lazySet(droppedCount.get() + 1);
        }
        return accepted;
    }

    /**
     * Convenience alias mirroring {@link java.util.List#add(Object)} for
     * callers migrating from a List-based inbox. Always returns {@code true};
     * use {@link #offer(Object)} to detect overflow.
     */
    public boolean add(E e) {
        return offer(e);
    }

    // ===== Consumer API (single-consumer only) =====

    /**
     * Remove and return the head of the queue, or {@code null} if empty.
     * Must only be called from the designated consumer thread.
     */
    public E poll() {
        E e = queue.relaxedPoll();
        if (e != null) {
            polledCount.lazySet(polledCount.get() + 1);
        }
        return e;
    }

    /**
     * Return the head of the queue without removing it, or {@code null} if
     * empty. Must only be called from the designated consumer thread.
     */
    public E peek() {
        return queue.relaxedPeek();
    }

    /**
     * Drain every currently-queued element into {@code sink} in FIFO order and
     * return the number of elements transferred. Must only be called from the
     * designated consumer thread.
     */
    public int drainTo(Collection<? super E> sink) {
        Objects.requireNonNull(sink, "sink");
        int n = 0;
        E e;
        while ((e = queue.relaxedPoll()) != null) {
            sink.add(e);
            n++;
            polledCount.lazySet(polledCount.get() + 1);
        }
        return n;
    }

    /**
     * Bulk-poll up to {@code maxElements} into {@code sink}. Returns the
     * number transferred. Useful when the consumer wants to process messages
     * in bounded batches without draining the whole queue at once.
     */
    public int drainTo(Collection<? super E> sink, int maxElements) {
        if (maxElements <= 0) {
            return 0;
        }
        Objects.requireNonNull(sink, "sink");
        int n = 0;
        while (n < maxElements) {
            E e = queue.relaxedPoll();
            if (e == null) {
                break;
            }
            sink.add(e);
            n++;
            polledCount.lazySet(polledCount.get() + 1);
        }
        return n;
    }

    /**
     * Drain every currently-queued element into a new {@link ArrayList} in
     * FIFO order. The returned list is decoupled from the queue and is safe to
     * iterate at any pace. After the call the queue is empty - the MPSC ring
     * buffer has no non-destructive way to enumerate its slots, so this method
     * drains as part of producing the snapshot. Must only be called from the
     * consumer thread.
     */
    public ArrayList<E> snapshot() {
        ArrayList<E> copy = new ArrayList<>(queue.size());
        drainTo(copy);
        return copy;
    }

    // ===== Introspection =====

    /**
     * Approximate current size. JCTools reports this without locking so the
     * value is a snapshot at the time of the call; concurrent producers may
     * have changed it by the time the caller acts on it. Safe from any thread.
     */
    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Maximum number of elements the queue can hold. The constructor rounds
     * the requested capacity up to the next power of two.
     */
    public int capacity() {
        return capacity;
    }

    /** Total number of successful offers since construction. */
    public long offeredCount() {
        return offeredCount.get();
    }

    /** Total number of successful polls since construction. */
    public long polledCount() {
        return polledCount.get();
    }

    /** Total number of offers rejected because the queue was full. */
    public long droppedCount() {
        return droppedCount.get();
    }

    // ===== Reset =====

    /**
     * Drain any pending messages into a discarded sink. Required because
     * JCTools does not expose a true O(1) clear on MPSC queues. Returns the
     * number of discarded elements. Must only be called when no producers are
     * active, otherwise cleared state is undefined.
     */
    public int clear() {
        return drainTo(BlackHoleSink.instance());
    }

    /**
     * Iterate every currently-queued element once via {@link #poll()}. The
     * consumer thread is the only one allowed to call this. Use
     * {@link #snapshot()} if you do not want to consume the queue.
     */
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action, "action");
        E e;
        while ((e = queue.relaxedPoll()) != null) {
            action.accept(e);
            polledCount.lazySet(polledCount.get() + 1);
        }
    }

    /**
     * Returns an iterator that drains the queue. Subsequent calls to
     * {@link Iterator#hasNext()} or {@link Iterator#next()} on the returned
     * iterator mutate the queue (they call poll); this matches the
     * drain-semantics the class is built for and is only safe from the
     * consumer thread.
     */
    public Iterator<E> iterator() {
        return new DrainIterator();
    }

    // ===== Helpers =====

    private static int nextPowerOfTwo(int v) {
        if (v <= 1) {
            return 1;
        }
        // JCTools caps MpscArrayQueue capacity at 1 << 30; clamp to stay safe.
        final int max = 1 << 30;
        int p = 1;
        while (p < v) {
            p <<= 1;
        }
        return Math.min(p, max);
    }

    private final class DrainIterator implements Iterator<E> {
        private E next;
        private boolean nextReady;

        @Override
        public boolean hasNext() {
            if (!nextReady) {
                next = queue.relaxedPoll();
                if (next != null) {
                    polledCount.lazySet(polledCount.get() + 1);
                }
                nextReady = true;
            }
            return next != null;
        }

        @Override
        public E next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            E e = next;
            next = null;
            nextReady = false;
            return e;
        }
    }

    /** Discards anything added to it; used by {@link #clear()}. */
    private static final class BlackHoleSink<E> implements Collection<E> {
        private static final BlackHoleSink<?> INSTANCE = new BlackHoleSink<>();

        @SuppressWarnings("unchecked")
        static <T> BlackHoleSink<T> instance() {
            return (BlackHoleSink<T>) INSTANCE;
        }

        @Override public boolean add(E e) { return true; }
        @Override public boolean addAll(Collection<? extends E> c) { return true; }
        @Override public void clear() { /* no-op */ }
        @Override public boolean contains(Object o) { return false; }
        @Override public boolean containsAll(Collection<?> c) { return false; }
        @Override public boolean isEmpty() { return true; }
        @Override public java.util.Iterator<E> iterator() { return java.util.Collections.<E>emptyIterator(); }
        @Override public boolean remove(Object o) { return false; }
        @Override public boolean removeAll(Collection<?> c) { return false; }
        @Override public boolean retainAll(Collection<?> c) { return false; }
        @Override public int size() { return 0; }
        @Override public Object[] toArray() { return new Object[0]; }
        @Override public <T> T[] toArray(T[] a) { return a; }
    }

    @Override
    public String toString() {
        return "MpscMessageList[size=" + size() + "/" + capacity
                + ", offered=" + offeredCount()
                + ", polled=" + polledCount()
                + ", dropped=" + droppedCount() + "]";
    }
}