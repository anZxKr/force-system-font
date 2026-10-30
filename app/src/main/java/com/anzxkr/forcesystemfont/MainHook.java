package com.anzxkr.forcesystemfont;

import android.graphics.Paint;
import android.graphics.Typeface;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Forces apps to use the system font.
 *
 * Every font an app brings itself (createFromAsset, createFromFile, Typeface.Builder,
 * res/font XML, downloadable fonts, Compose fonts) ends in
 * Typeface.CustomFallbackBuilder.build() on API 29+. We hook only that, and swap the
 * result for the system typeface of the same weight/italic (system monospace for
 * monospace fonts). Typefaces derived later (Typeface.create(tf, style),
 * create(tf, weight, italic)) then inherit the system font.
 *
 * Kept as-is: icon fonts and other fonts with private-use-area glyphs (this includes
 * Nerd/Powerline terminal fonts, which would lose their symbols).
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String SELF = "com.anzxkr.forcesystemfont";

    // Private Use Area probes: U+E000..U+F8FF every 8, and plane 15 U+F0000..U+F1FFF every 32.
    private static final String[] PUA_PROBES = buildProbes();

    private static String[] buildProbes() {
        int n = (0xF8FF - 0xE000) / 8 + 1 + (0x1FFF / 32) + 1;
        String[] out = new String[n];
        int i = 0;
        for (int cp = 0xE000; cp <= 0xF8FF; cp += 8) out[i++] = new String(Character.toChars(cp));
        for (int cp = 0xF0000; cp <= 0xF1FFF; cp += 32) out[i++] = new String(Character.toChars(cp));
        if (i == n) return out;
        String[] trimmed = new String[i];
        System.arraycopy(out, 0, trimmed, 0, i);
        return trimmed;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (SELF.equals(lpparam.packageName)) return;

        FakeStyle.install();
        final boolean forceMono = forceMono();

        final Class<?> builder = XposedHelpers.findClassIfExists(
                "android.graphics.Typeface$CustomFallbackBuilder", lpparam.classLoader);
        if (builder == null) {
            log("CustomFallbackBuilder missing (needs Android 10+), skipping " + lpparam.packageName);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(builder, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Typeface original = (Typeface) param.getResult();
                        if (original == null) return;

                        if (isIconFont(original)) {
                            if (BuildConfig.DEBUG) log("kept (icon font) in " + lpparam.packageName);
                            return;
                        }

                        int weight = Math.max(1, original.getWeight());
                        boolean italic = original.isItalic();
                        boolean mono = isMonospace(original);
                        if (mono && !forceMono) return;
                        Typeface base = mono ? Typeface.MONOSPACE : Typeface.DEFAULT;
                        param.setResult(Typeface.create(base, weight, italic));
                        if (BuildConfig.DEBUG) log("replaced " + (mono ? "mono" : "text")
                                + " w=" + weight + " italic=" + italic + " in " + lpparam.packageName);
                    } catch (Throwable t) {
                        // On any failure keep the app's own font rather than break the app.
                        XposedBridge.log(t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
            return;
        }

        // The framework may have inlined build() into its own callers, which would skip the
        // hook. Best effort: ask LSPosed to deoptimize those callers (needs an LSPosed that
        // exposes XposedBridge.deoptimizeMethod; silently ignored if it does not).
        deoptimizeAll(XposedHelpers.findClassIfExists(
                "android.graphics.Typeface$Builder", lpparam.classLoader), "build");
        deoptimizeAll(XposedHelpers.findClassIfExists(
                "android.graphics.Typeface", lpparam.classLoader), "createFromResources");
        deoptimizeAll(XposedHelpers.findClassIfExists(
                "android.provider.FontsContract", lpparam.classLoader), "buildTypeface");
    }

    // ── Detection ────────────────────────────────────────────────────────────────

    /** Fixed-pitch font: "i", "m" and "W" all have the same advance. */
    private static boolean isMonospace(Typeface tf) {
        Paint p = new Paint();
        p.setTypeface(tf);
        p.setTextSize(100f);
        float i = p.measureText("i");
        float m = p.measureText("m");
        float w = p.measureText("W");
        return i > 0f && Math.abs(i - m) < 0.5f && Math.abs(i - w) < 0.5f;
    }

    /** Icon font: has glyphs in the Private Use Area that the system font does not have. */
    static boolean isIconFont(Typeface tf) {
        Paint app = new Paint();
        app.setTypeface(tf);
        Paint sys = new Paint();
        sys.setTypeface(Typeface.DEFAULT);
        for (String probe : PUA_PROBES) {
            if (app.hasGlyph(probe) && !sys.hasGlyph(probe)) return true;
        }
        return false;
    }

    private static boolean forceMono() {
        try {
            return new XSharedPreferences(SELF, SettingsActivity.PREFS)
                    .getBoolean(SettingsActivity.KEY_MONO, true);
        } catch (Throwable t) {
            return true;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static void deoptimizeAll(Class<?> cls, String name) {
        if (cls == null) return;
        try {
            Method deopt = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name)) deopt.invoke(null, m);
            }
        } catch (Throwable ignored) {
            // not available on this framework
        }
    }

    private static void log(String msg) {
        XposedBridge.log("ForceSystemFont: " + msg);
    }
}
