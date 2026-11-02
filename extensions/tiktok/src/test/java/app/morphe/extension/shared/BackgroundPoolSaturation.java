package app.morphe.extension.shared;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Holds every shared worker and queue slot until a rejection path has been exercised. */
public final class BackgroundPoolSaturation implements AutoCloseable {
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Future<?>> held = new ArrayList<>();
    private boolean released;

    private BackgroundPoolSaturation() {
    }

    public static BackgroundPoolSaturation fill() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Field field = Utils.class.getDeclaredField("backgroundThreadPool");
        field.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(null);
        BackgroundPoolSaturation saturation = new BackgroundPoolSaturation();
        CountDownLatch entered = new CountDownLatch(pool.getMaximumPoolSize());
        java.util.concurrent.Callable<Void> blocker = () -> {
            entered.countDown();
            if (!saturation.release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release a held worker");
            }
            return null;
        };
        try {
            // Submit until the executor itself says it is full. A fixed capacity snapshot races
            // any producer that arrives between the snapshot and this loop, and the exception
            // used to escape before the accepted blockers could be released.
            while (true) {
                try {
                    saturation.held.add(Utils.submitOnBackgroundThread(blocker));
                } catch (RejectedExecutionException full) {
                    break;
                }
            }
            if (!entered.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("shared worker pool did not give every worker to a blocker");
            }
            // A racing task may have occupied a worker and then finished while the blockers
            // were entering, which opens a queue slot. Fill any such slot after all workers are
            // held by this fixture.
            while (pool.getQueue().remainingCapacity() != 0) {
                try {
                    saturation.held.add(Utils.submitOnBackgroundThread(blocker));
                } catch (RejectedExecutionException full) {
                    break;
                }
            }
            if (pool.getQueue().remainingCapacity() != 0) {
                throw new AssertionError("shared worker pool was not fully saturated");
            }
            return saturation;
        } catch (Throwable failure) {
            try {
                saturation.release();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new AssertionError(failure);
        }
    }

    public void release() throws Exception {
        if (released) return;
        released = true;
        release.countDown();
        Exception failure = null;
        for (Future<?> task : held) {
            try {
                task.get(5, TimeUnit.SECONDS);
            } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        try {
            Utils.awaitBackgroundTasksForTests();
        } catch (Exception error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    @Override public void close() throws Exception {
        release();
    }
}
