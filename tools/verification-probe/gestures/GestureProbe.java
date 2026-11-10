package app.hushfeed.verification.gestures;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Xml;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.xmlpull.v1.XmlSerializer;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Separate, self-targeted native input probe. Start only while holding the whole-device lease.
 * Modes: inspect, pinch, photo-x. No tap target except the observed native photo close control.
 * Pinch starts on the observed comment button, whose failed click can only open the comments.
 * Native state/alpha evidence comes from the existing target-process fadestate/fadewatch probe.
 */
public final class GestureProbe extends Instrumentation {
    private static final String TARGET = "com.zhiliaoapp.musically";
    private Bundle arguments;
    private UiAutomation ui;
    private int expectedWindow, injected;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : args;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        AudioManager audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        GestureAudio.Port audioState = null;
        GestureAudio.Snapshot baseline = null;
        boolean attemptedInput = false;
        int code = 1;
        try {
            for (String name : arguments.keySet()) {
                if (!Arrays.asList("mode", "rail-pointer", "scale", "steps", "duration").contains(name)) {
                    throw new IllegalArgumentException("Unknown gesture argument: " + name);
                }
            }
            String mode = arguments.getString("mode", "inspect");
            if (!mode.equals("inspect") && !mode.equals("pinch") && !mode.equals("photo-x")) {
                throw new IllegalArgumentException("Supported modes: inspect, pinch, photo-x");
            }
            String railPointer = arguments.getString("rail-pointer", "first");
            if (!railPointer.equals("first") && !railPointer.equals("second")) {
                throw new IllegalArgumentException("rail-pointer must be first or second");
            }
            if (!"47.4.3".equals(getContext().getPackageManager().getPackageInfo(TARGET, 0).versionName)) {
                throw new IllegalStateException("The resource anchors require TikTok 47.4.3");
            }
            if (audio == null) throw new IllegalStateException("No audio service for restoration");
            audioState = new GestureAudio.Port() {
                @Override public int volume() { return audio.getStreamVolume(AudioManager.STREAM_MUSIC); }
                @Override public boolean muted() { return audio.isStreamMute(AudioManager.STREAM_MUSIC); }
                @Override public void setVolume(int value) { audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); }
                @Override public void unmute() { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0); }
            };
            baseline = new GestureAudio.Snapshot(audioState);
            result.putInt("volumeBefore", baseline.volume);
            result.putBoolean("muteBefore", baseline.muted);
            if (!mode.equals("inspect")) baseline.requireInputAllowed();
            ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            ui.setServiceInfo(info);
            AccessibilityNodeInfo root = foreground();
            expectedWindow = root.getWindowId();
            Rect bounds = new Rect();
            root.getBoundsInScreen(bounds);
            saveBounds(root);
            result.putString("mode", mode);
            result.putString("bounds", bounds.toShortString());
            List<GestureSequence.Event> events = null;
            if (mode.equals("pinch")) {
                Rect rail = anchor(root, "ljn"), comment = anchor(root, "eq2");
                if (!rail.contains(comment)) throw new IllegalStateException("Comment button is not on the observed rail");
                float x = comment.exactCenterX(), y = comment.exactCenterY();
                float otherX = bounds.exactCenterX();
                float scale = Float.parseFloat(arguments.getString("scale", "0.35"));
                int steps = Integer.parseInt(arguments.getString("steps", "16"));
                int duration = Integer.parseInt(arguments.getString("duration", "400"));
                boolean first = railPointer.equals("first");
                events = GestureSequence.pinch(bounds.left, bounds.top, bounds.right, bounds.bottom,
                        first ? x : otherX, y, first ? otherX : x, y, scale, steps, duration);
                result.putString("railAnchor", "eq5 " + comment.toShortString());
                result.putString("railPointer", railPointer);
                result.putFloat("scale", scale);
            } else if (mode.equals("photo-x")) {
                Rect close = anchor(root, "uym");
                events = GestureSequence.tap(bounds.left, bounds.top, bounds.right, bounds.bottom,
                        close.exactCenterX(), close.exactCenterY());
                result.putString("photoAnchor", "v1c " + close.toShortString());
            }
            if (events != null) {
                attemptedInput = true;
                inject(events, result);
            }
            result.putString("result", events == null ? "observed_only_acceptance_unverified"
                    : "input_complete_acceptance_unverified");
            code = 0;
        } catch (Exception error) {
            result.putString("result", "failed");
            result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            result.putInt("injectedEvents", injected);
            if (baseline != null) {
                try {
                    result.putInt("volumeAfterInput", audioState.volume());
                    result.putBoolean("muteAfterInput", audioState.muted());
                    baseline.restore(audioState, attemptedInput);
                    int restored = audioState.volume();
                    boolean restoredMute = audioState.muted();
                    result.putInt("volumeRestored", restored);
                    result.putBoolean("muteRestored", restoredMute);
                    result.putBoolean("audioRestoredExactly", restored == baseline.volume && restoredMute == baseline.muted);
                    if (restored != baseline.volume || restoredMute != baseline.muted) code = 1;
                } catch (RuntimeException error) {
                    result.putBoolean("audioRestoredExactly", false);
                    result.putString("restorationError", error.getClass().getSimpleName());
                    code = 1;
                }
            }
            finish(code, result);
        }
    }

    private AccessibilityNodeInfo foreground() {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root == null || !TARGET.contentEquals(root.getPackageName())) {
            throw new IllegalStateException("Target package is not the active native window");
        }
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            if (window.getId() == root.getWindowId() && window.isActive()
                    && window.getDisplayId() == Display.DEFAULT_DISPLAY) return root;
        }
        throw new IllegalStateException("The active target window is not on display zero");
    }

    private Rect anchor(AccessibilityNodeInfo root, String id) {
        List<Rect> matches = new ArrayList<>();
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(TARGET + ":id/" + id)) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.isVisibleToUser() && node.isEnabled() && !bounds.isEmpty()) matches.add(bounds);
        }
        if (matches.size() != 1) throw new IllegalStateException("Expected exactly one visible native anchor: " + id);
        return matches.get(0);
    }

    private void inject(List<GestureSequence.Event> events, Bundle result) {
        GestureDriver.Outcome outcome = new GestureDriver.Outcome();
        try {
            GestureDriver.inject(events, new GestureDriver.Host() {
                @Override public long now() { return SystemClock.uptimeMillis(); }
                @Override public void sleep(long delay) { SystemClock.sleep(delay); }
                @Override public boolean ownsWindow() { return foreground().getWindowId() == expectedWindow; }
                @Override public void send(GestureSequence.Event event, long down) {
                    GestureProbe.this.send(event.action, down, event.xs, event.ys);
                }
            }, outcome);
        } finally {
            if (outcome.cancel != null) result.putString("cancel", outcome.cancel);
        }
    }

    private void send(int action, long down, float[] xs, float[] ys) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[xs.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[xs.length];
        for (int index = 0; index < xs.length; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = index;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].x = xs[index];
            coordinates[index].y = ys[index];
            int masked = action & MotionEvent.ACTION_MASK;
            boolean lifted = masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL
                    || masked == MotionEvent.ACTION_POINTER_UP
                    && index == (action >> MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            coordinates[index].pressure = lifted ? 0f : 1f;
            coordinates[index].size = 0.1f;
        }
        MotionEvent motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, xs.length,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN,
                Display.DEFAULT_DISPLAY, 0, MotionEvent.CLASSIFICATION_NONE);
        try {
            if (!ui.injectInputEvent(motion, true)) throw new IllegalStateException("Native input injection was rejected");
            injected++;
        } finally {
            motion.recycle();
        }
    }

    private void saveBounds(AccessibilityNodeInfo root) throws Exception {
        try (FileOutputStream stream = getContext().openFileOutput("gesture-before.xml", 0)) {
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(stream, "UTF-8");
            xml.startDocument("UTF-8", true);
            xml.startTag(null, "hierarchy");
            xml.attribute(null, "captured-uptime", Long.toString(SystemClock.uptimeMillis()));
            int[] count = {0};
            writeBounds(xml, root, 0, count);
            xml.endTag(null, "hierarchy");
            xml.endDocument();
        }
    }

    private void writeBounds(XmlSerializer xml, AccessibilityNodeInfo node, int depth, int[] count) throws Exception {
        if (++count[0] > 3000 || depth > 50) throw new IllegalStateException("Native hierarchy exceeded probe bounds");
        xml.startTag(null, "node");
        xml.attribute(null, "package", String.valueOf(node.getPackageName()));
        xml.attribute(null, "resource-id", String.valueOf(node.getViewIdResourceName()));
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        xml.attribute(null, "bounds", bounds.toShortString());
        xml.attribute(null, "visible", Boolean.toString(node.isVisibleToUser()));
        for (int child = 0; child < node.getChildCount(); child++) {
            AccessibilityNodeInfo next = node.getChild(child);
            if (next != null) writeBounds(xml, next, depth + 1, count);
        }
        xml.endTag(null, "node");
    }
}
