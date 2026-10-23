package app.hushfeed.verification.api23;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Deliberately unavailable API24 members. A native API23 run must reject both. */
final class Api24NegativeControl {
    static void mapDefault() {
        Map<Object, Object> map = new WeakHashMap<>();
        map.remove(new Object(), new Object());
    }
    static void listSort() {
        List<String> list = new ArrayList<>();
        list.add("second");
        list.add("first");
        list.sort(null);
    }
    private Api24NegativeControl() { }
}
