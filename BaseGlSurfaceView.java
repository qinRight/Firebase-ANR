package com.right.view;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.SurfaceHolder;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class BaseGlSurfaceView extends GLSurfaceView {
    public BaseGlSurfaceView(Context context) {
        super(context);
    }

    public BaseGlSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public static final boolean WORKAROUND_ENABLED = true;
    private final SurfaceRedrawNeededAsyncWorkaround mRedrawWorkaround = new SurfaceRedrawNeededAsyncWorkaround();
    @Override
    public void surfaceRedrawNeededAsync(SurfaceHolder holder, Runnable finishDrawing) {
        if (WORKAROUND_ENABLED) {
            super.surfaceRedrawNeededAsync(holder, mRedrawWorkaround.register(finishDrawing));
        } else {
            super.surfaceRedrawNeededAsync(holder, finishDrawing);
        }
    }

    static class SurfaceRedrawNeededAsyncWorkaround {

        @GuardedBy("mFinishDrawingBuffer")
        private final List<StampedRunnable> mFinishDrawingBuffer = new ArrayList<>();

        @GuardedBy("mFinishDrawingBuffer")
        private long mGeneration = 0;

        /**
         * Registers a `finishDrawing` Runnable for future execution and returns a new Runnable which,
         * upon its execution, will execute the original `finishDrawing` Runnable along with any
         * other Runnables registered before it but not yet executed, in order of their registration.
         * The returned Runnable is suitable for passing to `GLSurfaceView.surfaceRedrawNeededAsync()`.
         */
        @AnyThread // This is typically the main thread, but we don't rely on it
        public @NonNull Runnable register(@NonNull Runnable finishDrawing) {
            final long generation;
            synchronized (mFinishDrawingBuffer) {
                mGeneration++;
                generation = mGeneration;
                mFinishDrawingBuffer.add(new StampedRunnable(finishDrawing, generation));
            }
            return new Commit(generation);
        }

        private static final class StampedRunnable implements Runnable {
            private final @NonNull Runnable mWrapped;
            public final long stamp;

            StampedRunnable(@NonNull Runnable runnable, long stamp) {
                this.mWrapped = runnable;
                this.stamp = stamp;
            }

            @WorkerThread // Typically the GLThread
            @Override
            public void run() {
                mWrapped.run();
            }
        }

        /**
         * Removes from the buffer all registered Runnables with the same or younger generation.  The
         * removed Runnables are then executed in the order of their registration.
         */
        private final class Commit implements Runnable {

            private final long mGeneration;

            Commit(long generation) {
                mGeneration = generation;
            }

            @WorkerThread // Typically the GLThread
            @Override
            public void run() {
                final List<StampedRunnable> matchingRunnables = new ArrayList<>();
                synchronized (mFinishDrawingBuffer) {
                    // Extract the Runnables stamped with the same or earlier generation, retaining
                    // original order
                    for (Iterator<StampedRunnable> i = mFinishDrawingBuffer.iterator(); i.hasNext();) {
                        final @NonNull StampedRunnable runnable = i.next();
                        if (runnable.stamp <= mGeneration) {
                            matchingRunnables.add(runnable);
                            i.remove();
                        } else {
                            break;
                        }
                    }
                }
                for (Runnable callback : matchingRunnables) {
                    callback.run();
                }
            }
        }
    }
}
