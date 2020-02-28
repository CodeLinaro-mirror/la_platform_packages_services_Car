/*
 * Copyright (c) 2019, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.car;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.hardware.power.CarPowerManager.CarPowerStateListenerWithCompletion;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Log;

import android.os.Message;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AirplaneModeService implements CarServiceBase,
        CarPowerStateListenerWithCompletion {
    private static final String TAG = "AirplaneModeService";
    private final Context mContext;
    private CarPowerManagementService mCarPowerManagementService = null;
    private CarPowerManager mCarPowerManager = null;
    private HandlerThread mThread = null;
    private AirplaneModeHandler mAirplaneModeHandler = null;
    private boolean mEnablePowerManager = false;
    // Used internally for synchronization
    private final Object mLock = new Object();
    private CompletableFuture<Void> mFuture;
    private static final boolean DBG = true;

    public AirplaneModeService(Context context) {
        mContext = context;
        mEnablePowerManager = mContext.getResources().getBoolean(R.bool.enableAirplaneModeService);
        logd("mEnablePowerManager: " + mEnablePowerManager);
    }

    @Override
    public void init() {
        logd("init ");
        if (mEnablePowerManager) {
            mCarPowerManager = CarLocalServices.createCarPowerManager(mContext);
            if (mCarPowerManager != null) {
                logd("Listen car power state ");
                mCarPowerManager.setListenerWithCompletion(AirplaneModeService.this);

                mThread = new HandlerThread(TAG);
                mThread.start();
                mAirplaneModeHandler = new AirplaneModeHandler(mThread.getLooper());
            } else {
                loge("Can't get CarPowerManager");
            }
        }
    }

    @Override
    public synchronized void release() {
        logd("release ");
        if (mCarPowerManager != null) {
            mCarPowerManager.clearListener();
            mCarPowerManager = null;
        }
    }

    @Override
    public synchronized void dump(PrintWriter writer) {
        writer.println(TAG + this.toString());
    }

    @Override
    public void onStateChanged(int state, CompletableFuture<Void> future) {
        logd("onStateChanged: " + state);
        switch (state) {
            case CarPowerStateListener.SHUTDOWN_PREPARE:
                notifyPowerOff(future);
                // Postpone to notify CarPowerManager after RF (e.g. BT/Wifi) is turned off.
                break;
            case CarPowerStateListener.SUSPEND_EXIT:
                notifyPowerOn();
                if (future != null) {
                    future.complete(null);
                }
                break;
            default:
                if (future != null) {
                    future.complete(null);
                }
                break;
        }
    }

    private void notifyPowerOff(CompletableFuture<Void> future) {
        logd("notifyPowerOff");
        if (mAirplaneModeHandler != null) {
            setFuture(future);
            mAirplaneModeHandler.notifyPowerOff();
        } else {
            if (future != null) {
                future.complete(null);
            }
        }
    }

    private void notifyPowerOn() {
        logd("notifyPowerOn");
        if (mAirplaneModeHandler != null) {
            mAirplaneModeHandler.notifyPowerOn();
        }
    }

    private void notifyPowerEventProcessingCompletion() {
        logd("notifyPowerEventProcessingCompletion");
        completeFuture();
    }

    private void setFuture(CompletableFuture<Void> future) {
        logd("setFuture: " + future);
        synchronized (mLock) {
            mFuture = future;
        }
    }

    private void completeFuture() {
        logd("completeFuture: " + mFuture);
        synchronized (mLock) {
            if (mFuture != null) {
                mFuture.complete(null);
                mFuture = null;
            }
        }
    }

    private void logd(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }

    private void logw(String msg) {
        Log.w(TAG, msg);
    }

    private void loge(String msg) {
        Log.e(TAG, msg);
    }

    private class AirplaneModeHandler extends Handler {
        private static final String TAG = "AirplaneModeHandler";
        private static final boolean DBG = true;

        private static final int MSG_POWER_OFF = 100;
        private static final int MSG_POWER_ON = 101;

        private static final int MSG_AIRPLANE_MODE_CHANGED = 200;
        private static final int MSG_RF_STATE_CHANGED = 201;
        private static final int MSG_POWER_EVENT_PROCESSING_COMPLETE = 202;
        private static final int MSG_TIMEOUT = 203;

        private static final int MAX_SHUTDOWN_TIME = 10000; // 10s

        private static final int MAX_NOTIFY_DELAY = 8000;  // ms
        private static final int DEFAULT_NOTIFY_DELAY = 5000;  // ms

        // Airplane mode
        private static final int AIRPLANE_MODE_OFF = 0;
        private static final int AIRPLANE_MODE_TURNING_ON = 1;
        private static final int AIRPLANE_MODE_ON = 2;
        private static final int AIRPLANE_MODE_TURNING_OFF = 3;

        private class RfState {
            // RF (e.g. Bluetooth/Wifi) state
            public static final int RF_STATE_UNKNOWN = 0;
            public static final int RF_STATE_ON = 1;
            public static final int RF_STATE_OFF = 2;

            // RF ID
            public static final int RF_ID_BLUETOOTH = 1;
            public static final int RF_ID_WIFI = 2;

            private String name;
            private int id;
            private int state;
            private Context context;

            public RfState(int id, int state, String name, Context context) {
                this.id = id;
                this.state = state;
                this.name = name;
                this.context = context;
            }

            public void init() {
            }

            public Context getContext() {
                return context;
            }

            public String getName() {
                return name;
            }

            public int getId() {
                return id;
            }

            public int getRfState() {
                return state;
            }

            public void setRfState(int state) {
                this.state = state;
            }

            public boolean isOn() {
                return (state == RF_STATE_ON);
            }

            public String getRfStateString(int state) {
                switch (state) {
                    case RF_STATE_ON:
                        return "on";
                    case RF_STATE_OFF:
                        return "off";
                    default:
                        return "unknown";
                }
            }
        }

        private class BluetoothState extends RfState {
            private final BluetoothAdapter mBluetoothAdapter;

            public BluetoothState(Context context) {
                super(RfState.RF_ID_BLUETOOTH, RfState.RF_STATE_OFF,
                        "Bluetooth", context);

                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            }

            @Override
            public void init() {
                int state = getBluetoothState();
                int curRfState = mapBluetoothState2RfState(state);

                setRfState(curRfState);
            }

            public int mapBluetoothState2RfState(int state) {
                int curRfState = 0;

                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        curRfState = RfState.RF_STATE_OFF;
                        break;
                    case BluetoothAdapter.STATE_ON:
                        curRfState = RfState.RF_STATE_ON;
                        break;
                    default:
                        curRfState = RfState.RF_STATE_UNKNOWN;
                        break;
                }

                return curRfState;
            }

            public int getBluetoothState() {
                if (mBluetoothAdapter != null) {
                    return mBluetoothAdapter.getState();
                } else {
                    return BluetoothAdapter.ERROR;
                }
            }
        }

        private class WifiState extends RfState {
            private final WifiManager mWifiManager;

            public WifiState(Context context) {
                super(RfState.RF_ID_WIFI, RfState.RF_STATE_OFF,
                        "Wifi", context);

                mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            }

            @Override
            public void init() {
                int state = getWifiState();
                int curRfState = mapWifiState2RfState(state);

                setRfState(curRfState);
            }

            public int mapWifiState2RfState(int state) {
                int curRfState = 0;

                switch (state) {
                    case WifiManager.WIFI_STATE_DISABLED:
                        curRfState = RfState.RF_STATE_OFF;
                        break;
                    case WifiManager.WIFI_STATE_ENABLED:
                        curRfState = RfState.RF_STATE_ON;
                        break;
                    default:
                        curRfState = RfState.RF_STATE_UNKNOWN;
                        break;
                }

                return curRfState;
            }

            public int getWifiState() {
                if (mWifiManager != null) {
                    return mWifiManager.getWifiState();
                } else {
                    return WifiManager.WIFI_STATE_UNKNOWN;
                }
            }
        }

        private IntentFilter mBluetoothIntentFilter;
        private IntentFilter mWifiIntentFilter;

        private BluetoothState mBluetoothState;
        private WifiState mWifiState;
        private RfState[] mRfStates;

        private boolean mPowerOn = true;
        private int mAirplaneMode = AIRPLANE_MODE_OFF;

        private ContentObserver mAirplaneModeObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                notifyAirplaneModeChanged();
            }
        };

        private BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);

                if (state == BluetoothAdapter.STATE_ON) {
                    notifyBluetoothStateChanged(true);
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    notifyBluetoothStateChanged(false);
                }
            }
        };

        private BroadcastReceiver mWifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);

                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    notifyWifiStateChanged(true);
                } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                    notifyWifiStateChanged(false);
                }
            }
        };

        public AirplaneModeHandler(Looper looper) {
            super(looper);

            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), true,
                    mAirplaneModeObserver);

            mAirplaneMode = getAirplaneMode();
            logd("AirplaneModeHandler mAirplaneMode: " + mAirplaneMode +
                    " (" + mapAirplaneMode2String(mAirplaneMode) + ")");

            initIntentFilter();

            initRfStates();
        }

        private void initIntentFilter() {
            mBluetoothIntentFilter = new IntentFilter();
            mBluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            mContext.registerReceiver(mBluetoothReceiver, mBluetoothIntentFilter);

            mWifiIntentFilter = new IntentFilter();
            mWifiIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            mContext.registerReceiver(mWifiReceiver, mWifiIntentFilter);
        }

        private void initRfStates() {
            mBluetoothState = new BluetoothState(mContext);
            mWifiState = new WifiState(mContext);

            List<RfState> allStates = new ArrayList<>(Arrays.asList(
                    mBluetoothState,
                    mWifiState
            ));
            mRfStates = allStates.toArray(new RfState[0]);

            for (RfState state : mRfStates) {
                state.init();
            }
        }

        public void notifyPowerOff() {
            sendEmptyMessage(MSG_POWER_OFF);
        }

        public void notifyPowerOn() {
            sendEmptyMessage(MSG_POWER_ON);
        }

        private void notifyAirplaneModeChanged() {
            logd("notifyAirplaneModeChanged ");
            sendEmptyMessage(MSG_AIRPLANE_MODE_CHANGED);
        }

        private void notifyRfStateChanged(int id, int state) {
            Message msg = obtainMessage(MSG_RF_STATE_CHANGED, id, state);
            sendMessage(msg);
        }

        private void postponePowerEventProcessingCompletion(int delay) {
            sendEmptyMessageDelayed(MSG_POWER_EVENT_PROCESSING_COMPLETE, delay);
        }

        public void notifyBluetoothStateChanged(boolean on) {
            notifyRfStateChanged(RfState.RF_ID_BLUETOOTH,
                    on ? RfState.RF_STATE_ON : RfState.RF_STATE_OFF);
        }

        public void notifyWifiStateChanged(boolean on) {
            notifyRfStateChanged(RfState.RF_ID_WIFI,
                    on ? RfState.RF_STATE_ON : RfState.RF_STATE_OFF);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_POWER_OFF:
                    handlePowerOff();
                    break;
                case MSG_POWER_ON:
                    handlePowerOn();
                    break;
                case MSG_AIRPLANE_MODE_CHANGED:
                    handleAirplaneModeChanged();
                    break;
                case MSG_RF_STATE_CHANGED:
                    handleRfStateChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_POWER_EVENT_PROCESSING_COMPLETE:
                    handlePowerEventProcessingComplete();
                    break;
                case MSG_TIMEOUT:
                    handleTimeout();
                    break;
                default:
                    break;
            }
        }

        private void handlePowerOff() {
            logd("handlePowerOff airplane mode: " + mAirplaneMode +
                    " (" + mapAirplaneMode2String(mAirplaneMode) + ")");

            if (!mPowerOn) {
                logw("handlePowerOff already power off");
                postponePowerEventProcessingCompletion(DEFAULT_NOTIFY_DELAY);
                return;
            }

            mPowerOn = false;

            if (mAirplaneMode != AIRPLANE_MODE_OFF) {
                loge("handlePowerOff ignore due to invalid airplane mode");
                postponePowerEventProcessingCompletion(DEFAULT_NOTIFY_DELAY);
                return;
            }

            if (isRfOff()) {
                postponePowerEventProcessingCompletion(DEFAULT_NOTIFY_DELAY);
                return;
            }

            setAirplaneModeOn(true);
            mAirplaneMode = AIRPLANE_MODE_TURNING_ON;

            sendEmptyMessageDelayed(MSG_TIMEOUT, MAX_SHUTDOWN_TIME);
        }

        private void handlePowerOn() {
            logd("handlePowerOn airplane mode: " + mAirplaneMode +
                    " (" + mapAirplaneMode2String(mAirplaneMode) + ")");

            if (mPowerOn) {
                logw("handlePowerOn already power on");
                return;
            }

            mPowerOn = true;

            if (mAirplaneMode != AIRPLANE_MODE_ON) {
                loge("handlePowerOn ignore due to invalid airplane mode");
                return;
            }

            setAirplaneModeOn(false);
            mAirplaneMode = AIRPLANE_MODE_TURNING_OFF;
        }

        private void handleAirplaneModeChanged() {
            int newMode = getAirplaneMode();
            logd("handleAirplaneModeChanged " + mapAirplaneMode2String(newMode));

            removeMessages(MSG_TIMEOUT);

            // Postpone to notify power event completion. The reason is that if
            // airplane mode is changed to on, it only means RF (e.g. Bluetooth)
            // begins to turn off. So it's necessary to wait until RF has been
            // turned off fully.

            mAirplaneMode = newMode;
        }

        private void handleRfStateChanged(int id, int state) {
            updateRfState(id, state);

            if (!mPowerOn && isRfOff()) {
                postponePowerEventProcessingCompletion(MAX_NOTIFY_DELAY);
            }
        }

        private void handlePowerEventProcessingComplete() {
            logd("handlePowerEventProcessingComplete");
            if (!mPowerOn && isRfOff()) {
                notifyPowerEventProcessingCompletion();
            }
        }

        private void handleTimeout() {
            loge("handleTimeout ");
            notifyPowerEventProcessingCompletion();
        }

        private void updateRfState(int id, int state) {
            if (id == RfState.RF_ID_BLUETOOTH) {
                logd("Bluetooth " + mBluetoothState.getRfStateString(state));
                mBluetoothState.setRfState(state);
            } else if (id == RfState.RF_ID_WIFI) {
                logd("Wifi " + mWifiState.getRfStateString(state));
                mWifiState.setRfState(state);
            } else {
                loge("updateRfState unknown id: " + id + ", state: " + state);
            }
        }

        private boolean isRfOff() {
            boolean allRfOff = true;

            for (RfState state : mRfStates) {
                if (state.isOn()) {
                    allRfOff = false;
                    break;
                }
            }

            return allRfOff;
        }

        private void setAirplaneModeOn(boolean enable) {
            logd("set airplane mode " + (enable ? "on" : "off"));

            // Change the system setting
            Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON,
                    enable ? 1 : 0);

            // Post the intent
            Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intent.putExtra("state", enable);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        private int getAirplaneMode() {
            return isAirplaneModeOn() ? AIRPLANE_MODE_ON : AIRPLANE_MODE_OFF;
        }

        public boolean isAirplaneModeOn() {
            return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        }

        private String mapAirplaneMode2String(int mode) {
            switch (mode) {
                case AIRPLANE_MODE_OFF:
                    return "off";
                case AIRPLANE_MODE_TURNING_ON:
                    return "turning on";
                case AIRPLANE_MODE_ON:
                    return "on";
                case AIRPLANE_MODE_TURNING_OFF:
                    return "turning off";
                default:
                    return "unknown";
            }
        }

        public void dump(PrintWriter writer) {
            writer.println(TAG + this.toString());
        }
    }
}
