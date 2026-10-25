package app.morphe.extension.tiktok;

import javax.annotation.Priority;
import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader;

@Priority(0)
public final class SerializedNativeRuntimeLoader extends DefaultNativeRuntimeLoader {
    @Override public void ensureLoaded() {
        // Robolectric #10116/#11496: loaders are sandbox-local, but the font ZIP registry
        // belongs to the JVM. Take a shared lock before the superclass's instance lock.
        synchronized ("hushfeed.robolectric.native-runtime".intern()) {
            super.ensureLoaded();
        }
    }
}
