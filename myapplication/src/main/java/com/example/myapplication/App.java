package com.example.myapplication;

import android.app.Application;
import android.content.Context;

/**
 * Created by wangxiaoyan on 2020/7/14.
 */
public class App extends Application {
    public static Context mContext;
    @Override
    public void onCreate() {
        super.onCreate();
        mContext = this;
    }
}
