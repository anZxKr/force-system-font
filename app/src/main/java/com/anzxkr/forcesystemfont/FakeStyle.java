package com.anzxkr.forcesystemfont;

import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class FakeStyle {

    private static final Map<Paint, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<Paint, State>());
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();
    private static final Map<Typeface, Boolean> ICONS =
            Collections.synchronizedMap(new WeakHashMap<Typeface, Boolean>());

    private static final class State {
        Typeface base;
        boolean bold;
        boolean italic;
    }

    static void install() {
        hook("setFakeBoldText", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (busy()) return;
                if (change((Paint) param.thisObject, true, (Boolean) param.args[0])) {
                    param.setResult(null);
                }
            }
        }, boolean.class);

        hook("setTextSkewX", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (busy()) return;
                float skew = (Float) param.args[0];
                if (skew > 0f) return;
                if (change((Paint) param.thisObject, false, skew < 0f)) {
                    param.setResult(null);
                }
            }
        }, float.class);

        hook("setFlags", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (busy()) return;
                int flags = (Integer) param.args[0];
                if ((flags & Paint.FAKE_BOLD_TEXT_FLAG) == 0) return;
                if (change((Paint) param.thisObject, true, true)) {
                    param.args[0] = flags & ~Paint.FAKE_BOLD_TEXT_FLAG;
                }
            }
        }, int.class);

        hook("setTypeface", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (busy()) return;
                Paint paint = (Paint) param.thisObject;
                State s = get(paint);
                if (s == null) return;
                s.base = (Typeface) param.args[0];
                apply(paint, s);
            }
        }, Typeface.class);

        hook("reset", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                put((Paint) param.thisObject, null);
            }
        });

        hook("setClassVariablesFrom", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                State src = get((Paint) param.args[0]);
                State copy = null;
                if (src != null) {
                    copy = new State();
                    copy.base = src.base;
                    copy.bold = src.bold;
                    copy.italic = src.italic;
                }
                put((Paint) param.thisObject, copy);
            }
        }, Paint.class);
    }

    private static void hook(String name, XC_MethodHook callback, Object... params) {
        Object[] args = new Object[params.length + 1];
        System.arraycopy(params, 0, args, 0, params.length);
        args[params.length] = callback;
        try {
            XposedHelpers.findAndHookMethod(Paint.class, name, args);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static boolean change(Paint paint, boolean bold, boolean on) {
        try {
            State s = get(paint);
            if (s == null) {
                if (!on) return false;
                Typeface tf = paint.getTypeface();
                if (isIcon(tf)) return false;
                s = new State();
                s.base = tf;
                put(paint, s);
            }
            if (bold) s.bold = on; else s.italic = on;
            apply(paint, s);
            if (!s.bold && !s.italic) put(paint, null);
            return on;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
    }

    private static void apply(Paint paint, State s) {
        Typeface tf = s.base;
        if (s.bold || s.italic) {
            Typeface from = tf != null ? tf : Typeface.DEFAULT;
            int weight = Math.max(1, from.getWeight());
            if (s.bold && weight < 600) weight = Math.min(weight + 300, 1000);
            tf = Typeface.create(from, weight, s.italic || from.isItalic());
        }
        BUSY.set(Boolean.TRUE);
        try {
            paint.setTypeface(tf);
        } finally {
            BUSY.remove();
        }
    }

    private static boolean isIcon(Typeface tf) {
        if (tf == null) return false;
        Boolean cached = ICONS.get(tf);
        if (cached == null) {
            cached = MainHook.isIconFont(tf);
            ICONS.put(tf, cached);
        }
        return cached;
    }

    private static boolean busy() {
        return BUSY.get() != null;
    }

    private static State get(Paint paint) {
        return STATES.get(paint);
    }

    private static void put(Paint paint, State s) {
        if (s == null) STATES.remove(paint); else STATES.put(paint, s);
    }
}
