package ai.shieldlabs;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Clock and delays used for retries and polling. Replaceable in tests. */
interface Timer {
    /** The system implementation. */
    Timer SYSTEM =
            new Timer() {
                @Override
                public long nanoTime() {
                    return System.nanoTime();
                }

                @Override
                public void sleep(Duration duration) throws InterruptedException {
                    long millis = duration.toMillis();
                    if (millis > 0) {
                        Thread.sleep(millis);
                    }
                }

                @Override
                public CompletableFuture<Void> delay(Duration duration) {
                    long nanos = duration.toNanos();
                    if (nanos <= 0) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return CompletableFuture.runAsync(
                            () -> { }, CompletableFuture.delayedExecutor(nanos, TimeUnit.NANOSECONDS));
                }
            };

    long nanoTime();

    void sleep(Duration duration) throws InterruptedException;

    CompletableFuture<Void> delay(Duration duration);
}
