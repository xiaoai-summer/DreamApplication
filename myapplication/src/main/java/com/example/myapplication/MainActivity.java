package com.example.myapplication;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.dreamapplication.IMyAidlInterface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private Button mBtn;
    private IMyAidlInterface myAidlInterface;
    private Map mMap;
    private List mList = new ArrayList();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mBtn = findViewById(R.id.btn_open_dream);
        mMap = new HashMap();
        mMap.put(0,0);
        mList.add(0,0);

        mBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "mMap.get(1)= " + mMap.get(1));
                Log.i(TAG, "mList.size = " + mList.size());
//                Log.i(TAG, "mList.get(1) = " + mList.get(1));

                Intent intent = new Intent();
                intent.setAction("com.example.dreamapplication.AidlService");
                intent.setPackage("com.example.dreamapplication");
                bindService(intent, mConn, MainActivity.BIND_AUTO_CREATE);
            }
        });
    }

    private ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "onServiceConnected() called");
            myAidlInterface = IMyAidlInterface.Stub.asInterface(service);
            try {
                if (myAidlInterface != null) {
                    Toast.makeText(App.mContext, myAidlInterface.startDream(), Toast.LENGTH_LONG).show();
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "onServiceDisconnected() called");
            myAidlInterface = null;
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mConn != null) {
            unbindService(mConn);
        }
    }
}
