package ai.shieldlabs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** A clock that only moves when the code under test waits. Records every wait. */
final class FakeTimer implements Timer {
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<Duration> waits = new CopyOnWriteArrayList<>();
    private final long earlyNanos;

    FakeTimer() {
        this(Duration.ZERO);
    }

    /** A clock whose waits end {@code early} before the requested time, like a coarse system timer. */
    FakeTimer(Duration early) {
        this.earlyNanos = early.toNanos();
    }

    @Override
    public long nanoTime() {
        return now.get();
    }

    @Override
    public void sleep(Duration duration) {
        waits.add(duration);
        now.addAndGet(Math.max(0L, duration.toNanos() - earlyNanos));
    }

    @Override
    public CompletableFuture<Void> delay(Duration duration) {
        sleep(duration);
        return CompletableFuture.completedFuture(null);
    }

    /** Moves the clock without recording a wait, for example while a request is in flight. */
    void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }

    List<Duration> waits() {
        return new ArrayList<>(waits);
    }

    List<Long> waitMillis() {
        List<Long> millis = new ArrayList<>();
        for (Duration wait : waits) {
            millis.add(wait.toMillis());
        }
        return millis;
    }
}
