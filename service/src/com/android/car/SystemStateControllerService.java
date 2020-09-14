/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.car;

import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.hardware.power.CarPowerManager.CarPowerStateListenerWithCompletion;
import android.content.Context;
import android.content.res.Resources;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.car.audio.CarAudioService;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SystemStateControllerService implements CarServiceBase {
    private static String TAG = "SystemStateControllerService";
    private final CarAudioService mCarAudioService;

    private final ICarImpl mICarImpl;
    private final boolean mLockWhenMuting;
     private  Context mContext;
    private final Object mLock = new Object();
    private CompletableFuture<Void> mFuture;
    private SystemStateHandler mSystemStateHandler = null;
    private static final int MAINTENANCE_SERVICE_TURNOFF_PERIOD = 100;
    private static final int MSG_NOTIFY_PROCESSINGCOMPLETE_EARLY = 0;
    private CarPowerManager mCarPowerManager;

    public SystemStateControllerService(
            Context context, CarAudioService carAudioService, ICarImpl carImpl) {
        mContext = context;
        mCarAudioService = carAudioService;
        mICarImpl = carImpl;
        Resources res = context.getResources();
        mLockWhenMuting = res.getBoolean(R.bool.displayOffMuteLockAllAudio);
    }

    private final CarPowerStateListenerWithCompletion mCarPowerStateListener = new CarPowerStateListenerWithCompletion() {
        @Override
        public void onStateChanged(int state, CompletableFuture<Void> future) {
            switch (state) {
                case CarPowerStateListener.SHUTDOWN_PREPARE:
                    Log.d(CarLog.TAG_AUDIO,TAG + " SHUTDOWN_PREPARE ");
                    handlePowerOff(future);
                    break;
                default:
                    if (future != null) {
                        future.complete(null);
                    }
                    break;
            }
        }
    };

    private void handlePowerOff(CompletableFuture<Void> future) {
        setFuture(future);
        mSystemStateHandler.sendEmptyMessage(MSG_NOTIFY_PROCESSINGCOMPLETE_EARLY);
    }

    private void setFuture(CompletableFuture<Void> future) {
        synchronized (mLock) {
            mFuture = future;
        }
    }

    private void completeFuture() {
        synchronized (mLock) {
            if (mFuture != null) {
                mFuture.complete(null);
                mFuture = null;
            }
        }
    }

    @Override
    public void init() {
        mCarPowerManager = CarLocalServices.createCarPowerManager(mContext);
        // CarLocalServices can fail to return a service.
        if (mCarPowerManager != null) {
            mCarPowerManager.setListenerWithCompletion(mCarPowerStateListener);
            mSystemStateHandler = new SystemStateHandler();
        } else {
            Log.e(CarLog.TAG_AUDIO, "Failed to get car power manager");
        }
    }

    private class SystemStateHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_NOTIFY_PROCESSINGCOMPLETE_EARLY) {
                if(!mCarAudioService.getAudioStatus()) {
                    mSystemStateHandler.removeMessages(MSG_NOTIFY_PROCESSINGCOMPLETE_EARLY);
                    completeFuture();
                    Log.d(CarLog.TAG_AUDIO,TAG + " music is not active so calling completeFuture");
                } else {
                    mSystemStateHandler.removeMessages(MSG_NOTIFY_PROCESSINGCOMPLETE_EARLY);
                    mSystemStateHandler.sendEmptyMessageDelayed(MSG_NOTIFY_PROCESSINGCOMPLETE_EARLY, MAINTENANCE_SERVICE_TURNOFF_PERIOD);
                    Log.d(CarLog.TAG_AUDIO,TAG + " music is active");
                }
            }
        }
    }

    @Override
    public void release() {
        if (mCarPowerManager != null) {
            mCarPowerManager.clearListener();
            mCarPowerManager = null;
        }
        if(mSystemStateHandler != null)
            mSystemStateHandler = null;
    }

    @Override
    public void dump(PrintWriter writer) {
    }
}
