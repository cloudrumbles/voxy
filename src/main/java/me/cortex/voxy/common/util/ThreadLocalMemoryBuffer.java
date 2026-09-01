package me.cortex.voxy.common.util;

import static me.cortex.voxy.common.util.GlobalCleaner.CLEANER;

public class ThreadLocalMemoryBuffer {
    // The Cleanable returned by CLEANER.register is intentionally dropped: this
    // class has no resize path, so there is no eager-free site that would call
    // cleanable.clean(). When the owning thread dies the ThreadLocalMap entry
    // becomes unreachable, the Cleaner's internal WeakReference to `ref` clears,
    // and the registered buffer::free action fires from the cleaner thread.
    // Functionally equivalent to ResizingThreadLocalMemoryBuffer's retain-and-
    // store-in-Pair pattern at thread-death time; the only difference is the
    // resizing variant exposes the Cleanable so it can free-on-resize.
    private static MemoryBuffer createMemoryBuffer(long size) {
        var buffer = new MemoryBuffer(size);
        var ref = MemoryBuffer.createUntrackedUnfreeableRawFrom(buffer.address, buffer.size);
        CLEANER.register(ref, buffer::free);
        return ref;
    }

    //TODO: make this much better
    private final ThreadLocal<MemoryBuffer> threadLocal;

    public ThreadLocalMemoryBuffer(long size) {
        this.threadLocal = ThreadLocal.withInitial(()->createMemoryBuffer(size));
    }

    public static MemoryBuffer create(long size) {
        return createMemoryBuffer(size);
    }

    public MemoryBuffer get() {
        return this.threadLocal.get();
    }
}
