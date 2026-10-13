package com.battlesbudz.jarvis.v2.voice;

import kotlin.jvm.functions.Function1;

/** Sherpa 1.13.7 JNI looks up invoke([F)Ljava/lang/Integer; literally. */
public final class SherpaPcmCallback implements Function1<float[], Integer> {
    private final Function1<float[], Integer> delegate;
    private Throwable failure;
    public SherpaPcmCallback(Function1<float[], Integer> delegate) { this.delegate = delegate; }
    @Override public Integer invoke(float[] samples) {
        if (failure != null) return 0;
        try { return delegate.invoke(samples); }
        catch (Throwable error) { failure = error; return 0; }
    }
    // Rethrow only after native generation returns, never through its callback boundary.
    public Throwable getFailure() { return failure; }
}
