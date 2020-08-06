package com.example.dreamapplication;

import android.annotation.SuppressLint;
import android.service.dreams.DreamService;
import android.util.Log;

/**
 * Created by wangxiaoyan on 2020/7/14.
 */
@SuppressLint("NewApi")
public class MyDreamService extends DreamService {
    private static final String TAG = "MyDreamService";

    @Override
    public void onCreate() {
        Log.i(TAG,"onCreate");
        super.onCreate();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.i(TAG,"onAttachedToWindow");
        setContentView(R.layout.layout);
    }

    @Override
    public void onDreamingStarted() {
        Log.i(TAG,"onDreamingStarted");
        super.onDreamingStarted();
    }

    @Override
    public void onDreamingStopped() {
        Log.i(TAG,"onDreamingStopped");
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        Log.i(TAG,"onDetachedFromWindow");
        super.onDetachedFromWindow();
    }
}
