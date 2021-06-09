/*
 * Copyright (c) 2019-2021, The Linux Foundation. All rights reserved.
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

import android.bluetooth.BluetoothAdapter;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.hardware.power.CarPowerManager.CarPowerStateListenerWithCompletion;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Message;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.PrintWriter;
import java.util.concurrent.CompletableFuture;

public class CarBluetoothPowerManager implements CarPowerStateListenerWithCompletion {
    private static final String TAG = "CAR.BT.POWER";
    private Context mContext;
    private BluetoothAdapter mBluetoothAdapter;
    private IntentFilter mBluetoothIntentFilter = null;
    private CarPowerManager mCarPowerManager = null;
    private boolean mIsSuspend = false;
    private final Object mLock = new Object();
    private CompletableFuture<Void> mFuture;
    private HandlerThread mThread = null;
    private BluetoothHandler mHandler = null;
    private static CarBluetoothPowerManager sBluetoothPowerManager = null;
    private static final boolean DBG = true;

    private BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction()) ||
                BluetoothAdapter.ACTION_BLE_STATE_CHANGED.equals(intent.getAction())) {
                int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                int prevState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE,
                        BluetoothAdapter.ERROR);
                logd("Bluetooth newState: " + newState + " ("
                        + BluetoothAdapter.nameForState(newState) + ")"
                        + ", prevState: " + prevState + " ("
                        + BluetoothAdapter.nameForState(prevState) + ")");
                if (mHandler != null) {
                    mHandler.notifyStateChanged(newState, prevState);
                }
            }
        }
    };

    public static CarBluetoothPowerManager createInstance(Context context) {
        if (sBluetoothPowerManager == null) {
            sBluetoothPowerManager = new CarBluetoothPowerManager(context);
        }
        return sBluetoothPowerManager;
    }

    public CarBluetoothPowerManager(Context context) {
        mContext = context;
        init();
    }

    private void init() {
        logd("init");
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new BluetoothHandler(mThread.getLooper());

        // Listen Bluetooth state
        mBluetoothIntentFilter = new IntentFilter();
        // Monitor 'ACTION_BLE_STATE_CHANGED' instead of 'ACTION_STATE_CHANGED'.
        mBluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_BLE_STATE_CHANGED);
        mContext.registerReceiver(mBluetoothReceiver, mBluetoothIntentFilter);

        // Listen car power state
        mCarPowerManager = CarLocalServices.createCarPowerManager(mContext);
        if (mCarPowerManager != null) {
            logd("listen car power state");
            mCarPowerManager.setListenerWithCompletion(CarBluetoothPowerManager.this);
        } else {
            loge("can't get CarPowerManager");
        }
    }

    private synchronized void release() {
        logd("release ");
        if (mCarPowerManager != null) {
            mCarPowerManager.clearListener();
            mCarPowerManager = null;
        }

        mContext.unregisterReceiver(mBluetoothReceiver);
    }

    private int getBluetoothState() {
        if (mBluetoothAdapter != null) {
            return mBluetoothAdapter.getState();
        } else {
            return BluetoothAdapter.ERROR;
        }
    }

    /**
     * Get the persisted Bluetooth state from Settings
     *
     * @return True if the persisted Bluetooth state is on, false otherwise
     */
    private boolean isBluetoothPersistedOn() {
        return (Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.BLUETOOTH_ON, -1) != 0);
    }

    private void setBluetoothPersistedOn() {
        Settings.Global.putInt(
                mContext.getContentResolver(), Settings.Global.BLUETOOTH_ON, 1);
    }

    private boolean enableBluetooth() {
        logd("enable Bluetooth");
        if (mBluetoothAdapter != null) {
            return mBluetoothAdapter.enable();
        } else {
            loge("can't enable Bluetooth due to null BluetoothAdapter");
            return false;
        }
    }

    private boolean disableBluetooth() {
        logd("disable Bluetooth");
        if (mBluetoothAdapter != null) {
            return mBluetoothAdapter.disable();
        } else {
            loge("can't disable Bluetooth due to null BluetoothAdapter");
            return false;
        }
    }

    @Override
    public void onStateChanged(int state, CompletableFuture<Void> future) {
        logd("onStateChanged: power state " + state + " ("
                + mapCarPowerState2String(state) + ")");
        switch (state) {
            case CarPowerStateListener.SHUTDOWN_PREPARE:
                handleSuspendPrepare(future);
                break;
            case CarPowerStateListener.SUSPEND_ENTER:
                handleSuspendEnter(future);
                break;
            case CarPowerStateListener.SHUTDOWN_CANCELLED:
                // Pass-through
            case CarPowerStateListener.SUSPEND_EXIT:
                handleSuspendExit(future);
                break;
            default:
                completeFuture(future);
                break;
        }
    }

    private void handleSuspendPrepare(CompletableFuture<Void> future) {
        logd("handleSuspendPrepare");
        int state = getBluetoothState();
        logd("bluetooth state: " + state + " (" +
                BluetoothAdapter.nameForState(state) + ")");
        if (isBluetoothOff(state)) {
            // Notify the completion to CPMS, if Bluetooth is off.
            completeFuture(future);
            return;
        }

        if (mHandler != null) {
            synchronized (mLock) {
                mIsSuspend = true;
                // Postpone to notify the completion to CPMS, until Bluetooth is fully turned off.
                mFuture = future;
            }
            logd("mFuture: " + mFuture);
            mHandler.notifyOffReq();
        } else {
            completeFuture(future);
        }
    }

    private void handleSuspendEnter(CompletableFuture<Void> future) {
        logd("handleSuspendEnter");
        // NOT handle
        completeFuture(future);
    }

    private void handleSuspendExit(CompletableFuture<Void> future) {
        logd("handleSuspendExit");
        synchronized (mLock) {
            mIsSuspend = false;
        }

        if (isBluetoothPersistedOn()) {
            enableBluetooth();
        }

        // NO need to wait Bluetooth on and connect remote device.
        // Leave Bluetooth automatic connection to other application/service.
        completeFuture(future);
    }

    private void completeFuture(CompletableFuture<Void> future) {
        if (future != null) {
            future.complete(null);
        }
    }

    private void completeFuture() {
        synchronized (mLock) {
            if (mFuture != null) {
                logd("completeFuture: " + mFuture);
                mFuture.complete(null);
                mFuture = null;
            }
        }
    }

    private boolean isBluetoothOff(int state) {
        return state == BluetoothAdapter.STATE_OFF;
    }

    private boolean isBluetoothOn(int state) {
        return state == BluetoothAdapter.STATE_ON;
    }

    private String mapCarPowerState2String(int state) {
        switch (state) {
            case CarPowerStateListener.INVALID:
                return "INVALID";
            case CarPowerStateListener.WAIT_FOR_VHAL:
                return "WAIT_FOR_VHAL";
            case CarPowerStateListener.SUSPEND_ENTER:
                return "SUSPEND_ENTER";
            case CarPowerStateListener.SUSPEND_EXIT:
                return "SUSPEND_EXIT";
            case CarPowerStateListener.SHUTDOWN_ENTER:
                return "SHUTDOWN_ENTER";
            case CarPowerStateListener.ON:
                return "ON";
            case CarPowerStateListener.SHUTDOWN_PREPARE:
                return "SHUTDOWN_PREPARE";
            case CarPowerStateListener.SHUTDOWN_CANCELLED:
                return "SHUTDOWN_CANCELLED";
            default:
                return "UNNKNOWN";
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

    private class BluetoothHandler extends Handler {
        private static final String TAG = "BluetoothHandler";
        private static final boolean DBG = true;

        private static final int MSG_OFF_REQ = 100;

        private static final int MSG_STATE_CHANGED = 200;
        private static final int MSG_TIMEOUT = 201;

        private static final int MAX_OFF_TIMEOUT = 5000;  // ms

        public BluetoothHandler(Looper looper) {
            super(looper);
        }

        public void notifyOffReq() {
            sendEmptyMessage(MSG_OFF_REQ);
        }

        public void notifyStateChanged(int newState, int prevState) {
            Message msg = obtainMessage(MSG_STATE_CHANGED, newState, prevState);
            sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_OFF_REQ:
                    handleOffReq();
                    break;
                case MSG_STATE_CHANGED:
                    handleStateChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_TIMEOUT:
                    handleTimeout();
                    break;
                default:
                    break;
            }
        }

        private void handleOffReq() {
            logd("handleOffReq");
            if (disableBluetooth()) {
                sendEmptyMessageDelayed(MSG_TIMEOUT, MAX_OFF_TIMEOUT);
            } else {
                // Can't disable Bluetooth. Still notify to CPMS so as to unblock system suspend.
                completeFuture();
            }
        }

        private void handleStateChanged(int newState, int prevState) {
            if (isBluetoothOff(newState)) {
                logd("handleStateChanged: Bluetooth off");
                // Notify the completion if state is changed from BLE_TURNING_OFF into OFF
                if (prevState == BluetoothAdapter.STATE_BLE_TURNING_OFF) {
                    if (mIsSuspend) {
                        removeMessages(MSG_TIMEOUT);
                        // Restore Bluetooth after system resume
                        setBluetoothPersistedOn();
                        completeFuture();
                    }
                }
            } else if (isBluetoothOn(newState)) {
                logd("handleStateChanged: Bluetooth on");
                // No handling
            }
        }

        private void handleTimeout() {
            loge("handleTimeout");
            completeFuture();
        }

        public void dump(PrintWriter writer) {
            writer.println(TAG + this.toString());
        }
    }
}
