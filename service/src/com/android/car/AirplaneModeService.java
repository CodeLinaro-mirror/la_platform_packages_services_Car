/*
 * Copyright (c) 2018, The Linux Foundation. All rights reserved.
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.provider.Settings;
import android.util.Log;

import android.os.Message;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;
import android.os.SystemProperties;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AirplaneModeService implements CarServiceBase,
        CarPowerManagementService.PowerEventProcessingHandler,
        CarPowerManagementService.PowerServiceEventListener {

    private static final String TAG = "AirplaneModeService";
    private final Context mContext;
    private CarPowerManagementService mCarPowerManagementService = null;
    private HandlerThread mThread = null;
    private AirplaneModeHandler mAirplaneModeHandler = null;
    private boolean mEnablePowerManager = false;
    private static final boolean DBG = true;

    public AirplaneModeService(Context context,
            CarPowerManagementService carPowerManagementService) {
        mContext = context;

        mCarPowerManagementService = carPowerManagementService;

        if ((mCarPowerManagementService != null) &&
                mCarPowerManagementService.isCarPowerManagerEnabled()) {
            mThread = new HandlerThread(TAG);
            mThread.start();
            mAirplaneModeHandler = new AirplaneModeHandler(mThread.getLooper());
        }

        mEnablePowerManager = (mAirplaneModeHandler != null);

        logd("mEnablePowerManager: " + mEnablePowerManager);
    }

    @Override
    public void init() {
       if (mEnablePowerManager) {
            mCarPowerManagementService.registerPowerEventListener(this);
            mCarPowerManagementService.registerPowerEventProcessingHandler(this);
        }
    }

    @Override
    public synchronized void release() {
        logd("release ");
    }

    @Override
    public synchronized void dump(PrintWriter writer) {
        writer.println(TAG + this.toString());
    }

    @Override
    public long onPrepareShutdown(boolean shuttingDown) {
        long shutdownTime = 0;
        logd("onPrepareShutdown shuttingDown: " + shuttingDown);

        if (mEnablePowerManager) {
            shutdownTime = mAirplaneModeHandler.getShutdownTime();
            mAirplaneModeHandler.notifyPowerOff(shuttingDown);
        }

        logd("shutdownTime " + shutdownTime);
        return shutdownTime;
    }

    @Override
    public void onPowerOn(boolean displayOn) {
        logd("onPowerOn displayOn: " + displayOn);
        if (mEnablePowerManager) {
            mAirplaneModeHandler.notifyPowerOn();
        }
    }

    @Override
    public int getWakeupTime() {
        return 0;
    }

    @Override
    public void onShutdown() {
        logd("onShutdown");
    }

    @Override
    public void onSleepEntry() {
        logd("onSleepEntry");
    }

    @Override
    public void onSleepExit() {
        logd("onSleepExit");
    }

    private void notifyPowerEventProcessingCompletion() {
        logd("notifyPowerEventProcessingCompletion");
        if (mEnablePowerManager) {
            mCarPowerManagementService.notifyPowerEventProcessingCompletion(this);
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


        // in ms
        private static final String PROP_AIRPLANE_MODE_DURATION =
                "android.car.airplane_mode_duration";

        private static final int MAX_NOTIFY_DELAY = 10_000; // 10 seconds

        private static final int MIN_NOTIFY_DELAY = 500;  // ms

        private static final int MIN_BLUETOOTH_OFF_TIMEOUT = 400;  // ms

        private static final int MIN_WIFI_OFF_TIMEOUT = 1_500;  // 1.5 second

        private static final int NORMAL_NOTIFY_DELAY = 2_500;  // 2.5 second

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
            public static final int RF_ID_WIFI = 2;  // Wifi station
            public static final int RF_ID_WIFI_AP = 3;  // Wifi AP
            public static final int RF_ID_WIFI_P2P = 4;  // Wifi P2P

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

            public void close() {
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

            @Override
            public void close() {
                if (mBluetoothAdapter != null) {
                    logd("disable Bluetooth");
                    mBluetoothAdapter.disable();
                }
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

            @Override
            public void close() {
                if (mWifiManager != null) {
                    logd("disable Wifi");
                    mWifiManager.setWifiEnabled(false);
                }
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

        private class WifiApState extends RfState {
            private final WifiManager mWifiManager;

            public WifiApState(Context context) {
                super(RfState.RF_ID_WIFI_AP, RfState.RF_STATE_OFF,
                        "WifiAp", context);

                mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            }

            @Override
            public void init() {
                int state = getWifiApState();
                int curRfState = mapWifiApState2RfState(state);

                setRfState(curRfState);
            }

            @Override
            public void close() {
                if (mWifiManager != null) {
                    logd("disable WifiAp");
                    mWifiManager.stopSoftAp();
                }
            }

            public int mapWifiApState2RfState(int state) {
                int curRfState = 0;

                switch (state) {
                    case WifiManager.WIFI_AP_STATE_DISABLED:
                        curRfState = RfState.RF_STATE_OFF;
                        break;
                    case WifiManager.WIFI_AP_STATE_ENABLED:
                        curRfState = RfState.RF_STATE_ON;
                        break;
                    default:
                        curRfState = RfState.RF_STATE_UNKNOWN;
                        break;
                }

                return curRfState;
            }

            public int getWifiApState() {
                if (mWifiManager != null) {
                    return mWifiManager.getWifiApState();
                } else {
                    return WifiManager.WIFI_AP_STATE_DISABLED;
                }
            }
        }

        private class WifiP2pState extends RfState {
            private final WifiP2pManager mWifiP2pManager;

            public WifiP2pState(Context context) {
                super(RfState.RF_ID_WIFI_P2P, RfState.RF_STATE_OFF,
                        "WifiP2p", context);

                mWifiP2pManager = (WifiP2pManager)
                        context.getSystemService(Context.WIFI_P2P_SERVICE);
            }

            @Override
            public void init() {
                int state = WifiP2pManager.WIFI_P2P_STATE_DISABLED;
                int curRfState = mapWifiP2pState2RfState(state);

                setRfState(curRfState);
            }

            @Override
            public void close() {
                // TODO
            }

            public int mapWifiP2pState2RfState(int state) {
                int curRfState = 0;

                switch (state) {
                    case WifiP2pManager.WIFI_P2P_STATE_DISABLED:
                        curRfState = RfState.RF_STATE_OFF;
                        break;
                    case WifiP2pManager.WIFI_P2P_STATE_ENABLED:
                        curRfState = RfState.RF_STATE_ON;
                        break;
                    default:
                        curRfState = RfState.RF_STATE_UNKNOWN;
                        break;
                }

                return curRfState;
            }
        }

        private IntentFilter mBluetoothIntentFilter;
        private IntentFilter mWifiIntentFilter;
        private IntentFilter mWifiApIntentFilter;
        private IntentFilter mWifiP2pIntentFilter;

        private BluetoothState mBluetoothState;
        private WifiState mWifiState;
        private WifiApState mWifiApState;
        private WifiP2pState mWifiP2pState;
        private RfState[] mRfStates;

        private boolean mPowerOn = true;
        private int mAirplaneMode = AIRPLANE_MODE_OFF;
        // Define airplane mode duration in ms.
        // AirplaneModeService is used to turn on AirplaneMode when CPMS notifies
        // to sleep, and turn off AirplaneMode when CPMS notifies to wakeup.
        // (1) If AirplaneMode is on, RF (e.g. BT, Wifi) is turned off.
        // (2) However AirplaneMode on doesn't mean BT/Wifi is turned off at once.
        //     This is asynchronous operation.If AirplaneModeService notifies the
        //     completion early before BT/Wifi is fully turned off when to sleep,
        //     this may result into kernel suspend error.
        // (3) Normally, it takes about 0.5 second to turn BT off fully,
        //     while about 1.5 sec to turn Wifi off fully.
        // (4) If other CPMS client (e.g. GarageModeService) is disabled, this
        //     service still needs to wait some time for display off, before CPMS
        //     forces suspend.
        private int mDuration = MIN_NOTIFY_DELAY;

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

        private BroadcastReceiver mWifiApReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_AP_STATE,
                        WifiManager.WIFI_AP_STATE_DISABLED);

                if (state == WifiManager.WIFI_AP_STATE_ENABLED) {
                    notifyWifiApStateChanged(true);
                } else if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
                    notifyWifiApStateChanged(false);
                }
            }
        };

        private BroadcastReceiver mWifiP2pReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);

                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    notifyWifiP2pStateChanged(true);
                } else if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                    notifyWifiP2pStateChanged(false);
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

            mWifiApIntentFilter = new IntentFilter();
            mWifiApIntentFilter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
            mContext.registerReceiver(mWifiApReceiver, mWifiApIntentFilter);

            mWifiP2pIntentFilter = new IntentFilter();
            mWifiP2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            mContext.registerReceiver(mWifiP2pReceiver, mWifiP2pIntentFilter);
        }

        private void initRfStates() {
            mBluetoothState = new BluetoothState(mContext);
            mWifiState = new WifiState(mContext);
            mWifiApState = new WifiApState(mContext);
            mWifiP2pState = new WifiP2pState(mContext);

            List<RfState> allStates = new ArrayList<>(Arrays.asList(
                    mBluetoothState,
                    mWifiState,
                    mWifiApState,
                    mWifiP2pState
            ));
            mRfStates = allStates.toArray(new RfState[0]);

            for (RfState state : mRfStates) {
                state.init();
            }
        }

        public long getShutdownTime() {
            return MAX_NOTIFY_DELAY;
        }

        public void notifyPowerOff(boolean shuttingDown) {
            Message msg = obtainMessage(MSG_POWER_OFF, Boolean.valueOf(shuttingDown));
            sendMessage(msg);
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

        private void postponePowerEventProcessingCompletion() {
            sendEmptyMessageDelayed(MSG_POWER_EVENT_PROCESSING_COMPLETE, NORMAL_NOTIFY_DELAY);
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

        public void notifyWifiApStateChanged(boolean on) {
            notifyRfStateChanged(RfState.RF_ID_WIFI_AP,
                    on ? RfState.RF_STATE_ON : RfState.RF_STATE_OFF);
        }

        public void notifyWifiP2pStateChanged(boolean on) {
            notifyRfStateChanged(RfState.RF_ID_WIFI_P2P,
                    on ? RfState.RF_STATE_ON : RfState.RF_STATE_OFF);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_POWER_OFF:
                    handlePowerOff((Boolean) msg.obj);
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

        private void handlePowerOff(boolean shuttingDown) {
            logd("handlePowerOff airplane mode: " + mAirplaneMode +
                    " (" + mapAirplaneMode2String(mAirplaneMode) + ")");

            mDuration = calculateDuration();

            if (!mPowerOn) {
                logw("handlePowerOff already power off");
                postponePowerEventProcessingCompletion(mDuration);

                return;
            }

            mPowerOn = false;

            if (mAirplaneMode != AIRPLANE_MODE_OFF) {
                if (isRfOff()) {
                    logw("handlePowerOff ignore since airplane is on and RF is off");
                    postponePowerEventProcessingCompletion(mDuration);
                } else {
                    loge("handlePowerOff invalid airplane on and RF on");
                    sendEmptyMessageDelayed(MSG_TIMEOUT, MAX_NOTIFY_DELAY);
                    closeRf();
                }
                return;
            }

            if (isRfOff()) {
                postponePowerEventProcessingCompletion(mDuration);
                return;
            }

            setAirplaneModeOn(true);
            mAirplaneMode = AIRPLANE_MODE_TURNING_ON;
            sendEmptyMessageDelayed(MSG_TIMEOUT, MAX_NOTIFY_DELAY);
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

            // Postpone to notify power event completion. The reason is that if
            // airplane mode is changed to on, it only means RF (e.g. Bluetooth)
            // begins to turn off. So it's necessary to wait until RF has been
            // turned off fully.

            mAirplaneMode = newMode;
        }

        private void handleRfStateChanged(int id, int state) {
            updateRfState(id, state);

            if (!mPowerOn && isRfOff()) {
                removeMessages(MSG_TIMEOUT);
                logd("postpone notifying completion, duration: " + mDuration + " ms");
                postponePowerEventProcessingCompletion(mDuration);
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
            // TODO
        }

        private int calculateDuration() {
            int maxDuration = MAX_NOTIFY_DELAY - MIN_NOTIFY_DELAY;
            int duration = SystemProperties.getInt(PROP_AIRPLANE_MODE_DURATION,
                    MIN_NOTIFY_DELAY);

            if (duration > maxDuration) {
                duration = maxDuration;
            }

            if (mBluetoothState.isOn()) {
                duration = Math.max(duration, MIN_BLUETOOTH_OFF_TIMEOUT);
            }

            if (mWifiState.isOn()) {
                duration = Math.max(duration, MIN_WIFI_OFF_TIMEOUT);
            }

            if (mWifiApState.isOn()) {
                duration = Math.max(duration, MIN_WIFI_OFF_TIMEOUT);
            }

            if (mWifiP2pState.isOn()) {
                duration = Math.max(duration, MIN_WIFI_OFF_TIMEOUT);
            }

            logd("calculateDuration: " + duration + " ms");
            return duration;
        }

        private void updateRfState(int id, int state) {
            switch (id) {
                case RfState.RF_ID_BLUETOOTH:
                    logd("Bluetooth " + mBluetoothState.getRfStateString(state));
                    mBluetoothState.setRfState(state);
                    break;

                case RfState.RF_ID_WIFI:
                    logd("Wifi " + mWifiState.getRfStateString(state));
                    mWifiState.setRfState(state);
                    break;

                case RfState.RF_ID_WIFI_AP:
                    logd("WifiAp " + mWifiApState.getRfStateString(state));
                    mWifiApState.setRfState(state);
                    break;

                case RfState.RF_ID_WIFI_P2P:
                    logd("WifiP2p " + mWifiP2pState.getRfStateString(state));
                    mWifiP2pState.setRfState(state);
                    break;

                default:
                    loge("updateRfState unknown id: " + id + ", state: " + state);
                    break;
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

        private void closeRf() {
            logd("close RF");
            for (RfState state : mRfStates) {
                state.close();
            }
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
