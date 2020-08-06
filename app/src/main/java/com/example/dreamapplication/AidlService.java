package com.example.dreamapplication;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;


/**
 * Created by wangxiaoyan on 2020/7/14.
 */
public class AidlService extends Service {
    private static final String TAG = "AidlService";
    @Override
    public IBinder onBind(Intent intent) {
        return mStub;
    }

    IMyAidlInterface.Stub mStub = new IMyAidlInterface.Stub(){

        @Override
        public String startDream() throws RemoteException {
            Log.i(TAG,"开启屏保");
            Intent intent = new Intent();
            intent.setAction("android.service.dreams.DreamService");
            intent.setClassName("com.example.dreamapplication","com.example.dreamapplication.MyDreamService");
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startService(intent);
            return "test";
        }
    };
}
