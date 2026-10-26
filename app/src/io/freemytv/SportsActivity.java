package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/** The Sport button: every match, race and session of the day, grouped by competition (SportsView). */
public class SportsActivity extends Activity {
    private SportsView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new SportsView(this);
        setContentView(view);
        getWindow().setBackgroundDrawable(null);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view != null && view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
