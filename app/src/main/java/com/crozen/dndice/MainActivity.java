package com.crozen.dndice;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.MotionEvent;

public class MainActivity extends Activity {

    private DiceView view;
    private Sfx sfx;
    private Roller roller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        roller = new Roller(getSharedPreferences("dice", Context.MODE_PRIVATE));
        sfx = new Sfx(this);
        view = new DiceView(this, roller, sfx);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override
    protected void onPause() {
        view.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sfx.release();
        super.onDestroy();
    }

    /** Rotating bezel (Galaxy Watch) or crown. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (view.onRotary(delta)) return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (view.onBack()) return;
        super.onBackPressed();
    }
}
