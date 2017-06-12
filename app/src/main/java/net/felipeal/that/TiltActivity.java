/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.felipeal.that;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay;
import com.google.android.things.contrib.driver.ht16k33.Ht16k33;
import com.google.android.things.contrib.driver.rainbowhat.RainbowHat;
import com.google.android.things.pio.Gpio;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Uses the Rainbow HAT to monitor the progress of a
 * <a href="https://tilthydrometer.com">Tilt Hydrometer</a>.
 *
 * <p>Each button has multiple purporses:
 *
 * <ul>
 *    <li>Button A: displays gravity; turns off display.
 *    <li>Button B: displays wort temperature in F; displays wort temperature in C.
 *    <li>Button C: displays seconds since last update; displays Tilt color
 * </ul>
 *
 * <p>You can also use {@code dumpsys} to check the status of the activity:
 * <pre>
 *     adb shell dumpsys activity net.felipeal.that
 * </pre>
 *
 * <p>Or to send commands (run {@code adb shell dumpsys activity net.felipeal.that -h} for help).
 *
 */
public class TiltActivity extends Activity {

    private static final String TAG = "Tilt";

    private static final int MODE_GRAVITY = 0;
    private static final int MODE_IDLE = 1;
    private static final int MODE_TEMP_F = 2;
    private static final int MODE_TEMP_C = 3;
    private static final int MODE_LAST_POLL = 4;
    private static final int MODE_TILT_COLOR = 5;

    private static final int MSG_BUTTON_PRESSED = 1;
    private static final int MSG_LED_ON = 2;
    private static final int MSG_LED_OFF = 3;
    private static final int MSG_DISPLAY_GRAVITY = 4;
    private static final int MSG_DISPLAY_TEMPERATURE = 5;
    private static final int MSG_DISPLAY_LAST_POLL = 6;
    private static final int MSG_DISPLAY_MESSAGE = 7;

    private static final int DISPLAY_UPDATE_FREQUENCY_MS = 1000;
    private static final int LED_ON_DURATION_MS = 200;

    private Button mButtonA;
    private Button mButtonB;
    private Button mButtonC;
    private Gpio mLedA;
    private Gpio mLedB;
    private Gpio mLedC;
    private AlphanumericDisplay mSegment;

    // TODO: remove initial value
    private float mGravity = 1.083F;

    // TODO: remove initial value
    private float mWortTempF = 65;

    private int mMode = MODE_GRAVITY;
    private String mTiltColor = "RED";
    private long mLastPoll;

    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_BUTTON_PRESSED:
                    handleButton((Button) msg.obj);
                    return;
                case MSG_LED_ON:
                    handleLed((Gpio) msg.obj, true);
                    sendMessageDelayed(obtainMessage(MSG_LED_OFF, msg.obj), LED_ON_DURATION_MS);
                    return;
                case MSG_LED_OFF:
                    handleLed((Gpio) msg.obj, false);
                    return;
                case MSG_DISPLAY_GRAVITY:
                    handleDisplayGravity();
                    return;
                case MSG_DISPLAY_TEMPERATURE:
                    handleDisplayTemperature();
                    return;
                case MSG_DISPLAY_LAST_POLL:
                    handleDisplayLastPoll();
                    return;
                case MSG_DISPLAY_MESSAGE:
                    displayMessage((String) msg.obj);
                    return;
                default:
                    Log.w(TAG, "invalid message on handler: " + msg);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            mSegment = RainbowHat.openDisplay();
            mLedA = RainbowHat.openLedRed();
            mLedB = RainbowHat.openLedGreen();
            mLedC = RainbowHat.openLedBlue();
            mButtonA = RainbowHat.openButtonA();
            mButtonB = RainbowHat.openButtonB();
            mButtonC = RainbowHat.openButtonC();

            Button.OnButtonEventListener listener = (button, pressed) -> {
                if (!pressed) return;
                mHandler.dispatchMessage(mHandler.obtainMessage(MSG_BUTTON_PRESSED, button));
            };
            mButtonA.setOnButtonEventListener(listener);
            mButtonB.setOnButtonEventListener(listener);
            mButtonC.setOnButtonEventListener(listener);

            pollTilt();
            handleDisplayGravity();

            // TODO: use rainbow to track final gravity as it approaches expected FG

        } catch (IOException e) {
            Log.w(TAG, "D'OH!", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        close(mLedA);
        close(mLedB);
        close(mLedC);
        close(mButtonA);
        close(mButtonB);
        close(mButtonC);
        close(mSegment);
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter pw, String[] args) {
        if (args.length > 0) {
            runCommand(pw, args);
            return;
        }
        pw.printf("Tilt color: %s\n", mTiltColor);
        pw.printf("Gravity: %.3f\n", mGravity);
        pw.printf("Temperature: %.2fF (%.2fC)\n", mWortTempF, toCelsius(mWortTempF));
        pw.printf("Display mode: %s\n", modeAsString());
        pw.printf("Last poll: %s\n", getLastPoll());
        pw.printf("Display update frequency: %dms\n", DISPLAY_UPDATE_FREQUENCY_MS);
        pw.printf("Led duration: %dms\n", LED_ON_DURATION_MS);
    }

    private void runCommand(PrintWriter pw, String[] args) {
        String cmd = args[0];
        try {
            switch (cmd) {
                case "-g":
                    mGravity = Float.parseFloat(args[1]);
                    mLastPoll = System.currentTimeMillis();
                    pw.printf("Set gravity to %.3f\n", mGravity);
                    return;
                case "-c":
                    mWortTempF = Float.parseFloat(args[1]);
                    mLastPoll = System.currentTimeMillis();
                    pw.printf("Set temperature to %.3fC\n", mWortTempF);
                    return;
                case "-h":
                    pw.println("Usage:");
                    pw.println("  -g GRAVITY");
                    pw.println("     Sets the current gravity. Example: -g 1.050");
                    pw.println("  -c TEMPERATURE");
                    pw.println("     Sets the current temperature, in Fahrenheit. Example: -c 65");
                    return;
            }
        } catch (Exception e) {
        }
        pw.printf("Invalid commands: %s\n", Arrays.toString(args));
    }

    private void handleButton(Button button) {
        if (button == mButtonA) {
            handleButtonA();
        }
        if (button == mButtonB) {
            handleButtonB();
        }
        if (button == mButtonC) {
            handleButtonC();
        }
    }

    /**
     * First tap displays Gravity; second turns off display.
     */
    private void handleButtonA() {
        turnOnLed(mLedA);
        if (mMode != MODE_GRAVITY) {
            mMode = MODE_GRAVITY;
            handleDisplayGravity();
        } else {
            mMode = MODE_IDLE;
            handleDisplayIdle();
        }
    }

    /**
     * First tap display temperature in F; second in C.
     */
    private void handleButtonB() {
        turnOnLed(mLedB);
        if (mMode != MODE_TEMP_F) {
            mMode = MODE_TEMP_F;
        } else {
            mMode = MODE_TEMP_C;
        }
        handleDisplayTemperature();
    }

    /**
     * First tap display polling timer; second tap displays Tilt color.
     */
    private void handleButtonC() {
        turnOnLed(mLedC);
        if (mMode != MODE_LAST_POLL) {
            mMode = MODE_LAST_POLL;
            handleDisplayLastPoll();
        } else {
            mMode = MODE_TILT_COLOR;
            displayTiltColor();
        }
    }

    private void handleDisplayGravity() {
        if (mMode != MODE_GRAVITY ) {
            // Another button was pressed; ignore.
            return;
        }

        // displayMessage("GRAV");
        displayMessage(String.format("%.3f", mGravity));
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_DISPLAY_GRAVITY),
                DISPLAY_UPDATE_FREQUENCY_MS);
    }

    private void handleDisplayIdle() {
        if (mMode != MODE_IDLE) {
            // Another button was pressed; ignore.
            return;
        }

        displayMessage("GBYE");
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_DISPLAY_MESSAGE, ""),
                DISPLAY_UPDATE_FREQUENCY_MS);
    }

    private void handleDisplayTemperature() {
        String temp;
        switch (mMode) {
            case MODE_TEMP_F:
                temp = String.format("%.1fC", mWortTempF);
                break;
            case MODE_TEMP_C:
                temp = String.format("%.1fF", toCelsius(mWortTempF));
                break;
            default:
                // Another button was pressed; ignore.
                return;
        }
        // displayMessage("TEMP");
        displayMessage(temp);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_DISPLAY_TEMPERATURE),
                DISPLAY_UPDATE_FREQUENCY_MS);
    }

    private void displayTiltColor() {
        if (mMode == MODE_TILT_COLOR) {
            displayMessage(mTiltColor);
        }
    }

    private void handleDisplayLastPoll() {
        if (mMode != MODE_LAST_POLL) {
            // Another button was pressed; ignore.
            return;
        }

        displayMessage(getLastPoll());
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_DISPLAY_LAST_POLL),
                DISPLAY_UPDATE_FREQUENCY_MS);
    }

    private String getLastPoll() {
        int delta = (int) ((System.currentTimeMillis() - mLastPoll) / 1000);
        // TODO: different message if it's more than 999 seconds
        return String.format("%ds", delta);
    }

    private void pollTilt() {
        Log.d(TAG, "polling Tilt...");
        mLastPoll = System.currentTimeMillis();
    }

    private void turnOnLed(Gpio led) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_LED_ON, led));
    }

    private void handleLed(Gpio led, boolean on) {
        try {
            led.setValue(on);
        } catch (IOException e) {
            Log.w(TAG, "error turning led " + led + " to " + on);
        }
    }

    private float toFahrenheit(float tempC) {
        return (tempC * 1.8F) + 32;
    }

    private float toCelsius(float tempF) {
        return (tempF - 32) * 0.5556F;
    }

    private String modeAsString() {
        switch (mMode) {
            case MODE_GRAVITY:
                return "MODE_GRAVITY";
            case MODE_IDLE:
                return "MODE_IDLE";
            case MODE_TEMP_F:
                return "MODE_TEMP_F";
            case MODE_TEMP_C:
                return "MODE_TEMP_C";
            case MODE_LAST_POLL:
                return "MODE_LAST_POLL";
            case MODE_TILT_COLOR:
                return "MODE_TILT_COLOR";
            default:
                return "UNKNOWN";
        }
    }

    private void close(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing " + c, e);
            }
        }
    }

    private void displayMessage(String msg) {
        // Log.d(TAG, "displayMessage: " + msg);
        try {
            mSegment.setBrightness(Ht16k33.HT16K33_BRIGHTNESS_MAX);
            mSegment.display(msg);
            mSegment.setEnabled(true);
        } catch (IOException e) {
            Log.e(TAG, "error displaying '" + msg + ": ", e);
        }
    }
}
