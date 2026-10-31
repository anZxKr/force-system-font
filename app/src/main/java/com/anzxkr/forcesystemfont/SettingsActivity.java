package com.anzxkr.forcesystemfont;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    static final String PREFS = "settings";
    static final String KEY_MONO = "force_mono";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final SharedPreferences prefs = prefs();
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        Switch mono = new Switch(this);
        mono.setText("Force system monospace");
        mono.setTextSize(16);
        mono.setChecked(prefs.getBoolean(KEY_MONO, true));
        mono.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(KEY_MONO, on).apply());

        TextView note = new TextView(this);
        note.setText("Restart apps to apply changes.");
        note.setPadding(0, pad / 2, 0, 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.addView(mono);
        root.addView(note);
        setContentView(root);
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences prefs() {
        try {
            return getSharedPreferences(PREFS, MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return getSharedPreferences(PREFS, MODE_PRIVATE);
        }
    }
}
