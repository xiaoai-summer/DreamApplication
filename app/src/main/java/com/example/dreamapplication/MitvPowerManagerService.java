package com.example.dreamapplication;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by wangxiaoyan on 2020/7/20.
 */
public class MitvPowerManagerService {
    private static final String TAG = "PowerManagerService";
    private static final boolean LOGV = false;
    /**
     * MITV add
     * Go to sleep reason code: Going to sleep by xiaoai mode, acquire wakelock
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_XIAOAI_MODE = 8;

    // minimum interval time between two fstrim. 30 minutes
    private static final long MINIMUM_FSTRIM_INTERVAL_TIME = 1 * 30 * 60 * 1000L;
    private static long sPreviousFstrimTimesnap = 0L;

    private static MitvPowerManagerService mInstance = null;
    public static synchronized MitvPowerManagerService getInstance() {
        if (mInstance == null) {
            mInstance = new MitvPowerManagerService();
        }
        return mInstance;
    }

    /*
     ** called from WindowManagerService
     */
    public boolean setKeepScreenOnFlag(boolean acquire, String name) {
        String reason = "KeepScreenOn " +
                (acquire ? "acquired by " : "released by ") + name;
        synchronized (mInstance) {
            mKeepScreenOnEnable = acquire;
            checkScreenSaverTimeoutLocked(reason);
            if (mContext.getPackageManager().hasSystemFeature("mitv.oled.burnin.protection")){
                notifyKeepScreenOnFlagChanged(acquire);
            }
        }
        return true;
    }
    public boolean onBootCompleted() {
        synchronized (mInstance) {
            mBootCompleted = true;
            checkScreenSaverTimeoutLocked("BOOT_COMPLETED");
            updateSettingsLocked();
        }
        return true;
    }
    // called when settings change
    public boolean updateScreenSaverState() {
        synchronized (mInstance) {
            getScreenSaverTimeoutLocked();
            checkScreenSaverTimeoutLocked("setting change");
        }
        Log.d(TAG,"screen saver delay:"+mScreenSaverDelay);
        return true;
    }
    public boolean checkScreenSaverTimeout(String reason) {
        synchronized (mInstance) {
            checkScreenSaverTimeoutLocked(reason);
            if("userActivity".equals(reason)){
                checkNoKeyInputTimeoutLocked(reason);
                if (mContext.getPackageManager().hasSystemFeature("mitv.oled.burnin.protection")){
                    notifyUserActivityChanged();
                }
            }
        }
        return true;
    }
    /*
     ** feature screen saver
     ** enter screen saver conditions:
     **  1. screen saver's user activity timeout
     **  2. no keep screen on wakelock
     **  3. screen saver's state is not showing
     ** exit screen saver conditions:
     **  1. screen saver application control
     ** start screen saver timer:
     **  1. screen saver is not showing
     **  2. user activity or keep screen on flag
     ** intefaces:
     **  1. database: timeout, state
     **  2. application: start activity intent
     */
    private static boolean DEBUG_SCREEN_SAVER = true;
    final static String SCREEN_SAVER_TIMEOUT = "screen_saver_timeout";
    private final static String SCREEN_SAVER_PACKAGE = "com.duokan.screensaver";
    private final static String SCREEN_SAVER_CLASS = "com.duokan.screensaver.ScreenSaverActivity";
    private final static String KEEP_SCREEN_ON_TAG = "WindowManager";
    //from start screen saver to database screen saver state change
    private final int SCREEN_SAVER_UPGRADE_TIMEOUT = 4*60*1000;
    private final int SCREEN_SAVER_TIMEOUT_DEFAULT = 2*60*1000;
    private final int NO_KEY_INPUT_TIMEOUT = 25*1000;
    private int mScreenSaverTimeoutSetting;
    private int mScreenSaverDelay;
    private boolean mScreenSaverTimeout;
    private boolean mScreenSaverEnable;
    private boolean mKeepScreenOnEnable;
    private int mNoKeyInputDelay;
    private boolean mNoKeyInputTimeout;
    private boolean mBootCompleted;
    private Handler mHandler;
    private Context mContext;
    private Context mUiContext;
    private PowerManager mPowerManager;
    public static final int NOT_GO_TO_SLEEP= -1;
    public static int sGotoSleepReason = NOT_GO_TO_SLEEP ;

    public void initScreenSaver(Context context, Handler handler, Context uicontext) {
        mHandler = handler;
        mScreenSaverTimeoutSetting = 0;
        mScreenSaverDelay = Integer.MAX_VALUE;
        mScreenSaverTimeout = false;
        mScreenSaverEnable = true;
        mKeepScreenOnEnable = false;
        mNoKeyInputDelay = NO_KEY_INPUT_TIMEOUT;
        mNoKeyInputTimeout = false;
        mBootCompleted = false;
        mContext = context;
        mUiContext = uicontext;
    }

    private void getScreenSaverTimeoutLocked() {
        int timeout = Settings.System.getIntForUser(mContext.getContentResolver(),
                SCREEN_SAVER_TIMEOUT, -1000,
                UserHandle.USER_CURRENT);
        if (timeout == -1000) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    SCREEN_SAVER_TIMEOUT, SCREEN_SAVER_TIMEOUT_DEFAULT,
                    UserHandle.USER_CURRENT_OR_SELF);
            timeout = SCREEN_SAVER_TIMEOUT_DEFAULT;
        }
        mScreenSaverTimeoutSetting = timeout;
        if (mScreenSaverTimeoutSetting == -1) {
            mScreenSaverDelay = SCREEN_SAVER_UPGRADE_TIMEOUT;
        } else if (mScreenSaverTimeoutSetting < 0 ) {
            mScreenSaverDelay = Integer.MAX_VALUE;
        } else {
            mScreenSaverDelay = mScreenSaverTimeoutSetting;
        }
        if (!mScreenSaverEnable) {
            mScreenSaverDelay = SCREEN_SAVER_UPGRADE_TIMEOUT;
        }
    }
    /*
     ** userActivity will call this
     ** ScreenSaverState change call this
     */
    private void checkScreenSaverTimeoutLocked(String reason) {
        Log.d(TAG,"checkScreenSaverTimeoutLocked come "+mScreenSaverDelay+" reason="+reason);
        mScreenSaverTimeout = false;
        mHandler.removeCallbacks(mScreenSaverTask);
        mHandler.postDelayed(mScreenSaverTask, mScreenSaverDelay);
    }
    private Runnable mScreenSaverTask = new Runnable() {
        public void run() {
            synchronized (mInstance) {
                mScreenSaverTimeout = true;
            }
            computeScreenSaver();
        }
    };

    /*
     ** keep screen on wakelock state change will call this
     ** screen saver timeout should call this
     ** only check wakelock state, screenSaverTimeout and screenSaverState
     */
    @RequiresApi(api = Build.VERSION_CODES.KITKAT_WATCH)
    private void computeScreenSaver() {
        synchronized (mInstance) {
            if (!mScreenSaverTimeout || !mBootCompleted ) {
                if (DEBUG_SCREEN_SAVER) {
                    Log.d(TAG,"computeScreenSaverLocked do nothing mScreenSaverTimeout="+mScreenSaverTimeout
                            +"mScreenSaverEnable="+mScreenSaverEnable);
                }
                //do nothing
                return;
            }
        }
        if (mPowerManager == null) {
            mPowerManager = (android.os.PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        }
        if (mPowerManager!=null && !mPowerManager.isInteractive()) {
            synchronized (mInstance) {
                mScreenSaverTimeout = false;
            }
            if (DEBUG_SCREEN_SAVER) {
                Log.d(TAG,"screen not on, ignore screen saver timeout");
            }
            return;
        }

        boolean isPlayingVideo = isPlayingVideo();
        if (isPlayingVideo) {
            Log.d(TAG,"video is playing, return");
            return;
        }

        if (CheckWhitelistOfForgroundPackage()) {
            Log.d(TAG,"WA for unknown lock state of "+ getCurrentForgroundPackage() + " when apks power-resume");
            return;
        }

        boolean panel_oled = mContext.getPackageManager().hasSystemFeature("mitv.hardware.panel.oled");
        //amlogic tv decoder资源设计有问题，容易发生播放器和4K图片解码的竞争
        boolean shouldShowScreenSaver = false;
        if (mInfo.isPausing && !panel_oled) {
            Log.d(TAG,"amlogic tv pausing, return");
            if (mScreenSaverTimeoutSetting < 0 || !mScreenSaverEnable) {
                //screen saver close
                Log.d(TAG, "sending update application intent");
                Intent appupdate = new Intent("com.duokan.intent.action.UPGRADE");
                appupdate.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                mContext.sendBroadcast(appupdate);
            }
            return;
        }

        if (!mKeepScreenOnEnable) {
            shouldShowScreenSaver = true;
        }

        Log.d(TAG,"mKeepScreenOnEnable " + mKeepScreenOnEnable);
        if (mScreenSaverTimeoutSetting < 0 || !mScreenSaverEnable) {
            //screen saver close
            Log.d(TAG, "sending update application intent");
            Intent appupdate = new Intent("com.duokan.intent.action.UPGRADE");
            appupdate.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            mContext.sendBroadcast(appupdate);
            return;
        }
        //screensaver enable and timeout>0 and it's time show screensaver
        if(shouldShowScreenSaver){
            Log.d(TAG,"it's time to start screen saver");
            try {
                Log.d(TAG, "before show screensaver, closeSystemDialogs");
                ActivityManagerNative.getDefault().closeSystemDialogs("");

                Intent intent = new Intent("com.duokan.intent.action.show.screensaver");
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                mContext.sendBroadcast(intent);

                final long now = SystemClock.uptimeMillis();
                if (sPreviousFstrimTimesnap == 0L
                        || sPreviousFstrimTimesnap + MINIMUM_FSTRIM_INTERVAL_TIME < now) {
                    try {
                        IStorageManager sm = PackageHelper.getStorageManager();
                        if (sm != null) {
                            sm.runMaintenance();
                        }
                    } catch (RemoteException e) {
                        // Can't happen; StorageManagerService is local
                    }

                    sPreviousFstrimTimesnap = now;
                }
            } catch (Exception e) {
                Log.e(TAG,"start screen saver find error "+e);
            }
        }
    }
    public void setScreenSaverEnable(boolean enable) {
        Log.d(TAG,"setScreenSaverEnable "+enable);
        synchronized (mInstance) {
            mScreenSaverEnable = enable;
            getScreenSaverTimeoutLocked();
            checkScreenSaverTimeoutLocked("screen saver enable "+enable);
        }
    }

    //////////////// WakeLock Hold Time Monitor begin ////////////////
    private final class WakeLock implements Cloneable {
        public final IBinder mLock;
        public final String mTag;
        public final String mPackageName;
        public final int mOwnerUid;
        public final int mOwnerPid;
        public final long mAcquireTime;
        public int mScreenState;

        public static final int SCREEN_ON_WHEN_ACQUIRED = 1;
        public static final int SCREEN_ON_WHEN_RELEASE  = 2;

        public WakeLock(IBinder lock, String tag,
                        String packageName, int uid, int pid,
                        boolean screenOn) {
            mLock = lock;
            mTag = tag;
            mPackageName = packageName;
            mOwnerUid = uid;
            mOwnerPid = pid;
            mAcquireTime = SystemClock.elapsedRealtime();
            if (screenOn) {
                mScreenState |= SCREEN_ON_WHEN_ACQUIRED;
            }
        }
    }

    private final Object mWakeLockLock = new Object();
    private final ArrayList<WakeLock> mWakeLocks = new ArrayList<WakeLock>();
    private final long WAKELOCK_EVENT_REPORT_THRESHOLD = 15 * DateUtils.MINUTE_IN_MILLIS;
    private static final String REPORTER_WAKELOCK_CATEGORY = "wakelock";
    private static final String REPORTER_WAKELOCK_WAKELOCK = "wakelock";

    private Runnable mPartialWakelockProcsKiller = new Runnable() {
        public void run() {
            Log.i(TAG, "kill procs which still holding partial wakelock");
            ArrayList<WakeLock> curWakeLocks;
            synchronized (mWakeLockLock) {
                curWakeLocks = (ArrayList<WakeLock>)mWakeLocks.clone();
            }

            int[] pids = new int[curWakeLocks.size()];
            int index = 0;
            for (WakeLock w : curWakeLocks) {
                // skip forground pkgs
                if (mCurrForgroundPackage != null && mCurrForgroundPackage.equals(w.mPackageName))
                    continue;

                if (w.mPackageName.equals("android"))
                    continue;

                String productName = android.os.SystemProperties.get("ro.build.product"," ");
                if ("missionimpossible".equals(productName) || "inception".equals(productName)){
                    if(w.mPackageName.equals("com.mi.umi")){
                        Log.i(TAG, "umi holding partial wakelock but skip it");
                        continue;
                    }
                }

                boolean inXiaiMode = sGotoSleepReason == GO_TO_SLEEP_REASON_XIAOAI_MODE;
                if ("waterlooBridge".equals(productName) || inXiaiMode) {
                    if(w.mPackageName.equals("com.xiaomi.wakeupservice")
                            || w.mPackageName.equals("com.xiaomi.voicecontrol")
                            || w.mPackageName.equals("com.xiaomi.miplay")) {
                        Log.i(TAG, "AI holding partial wakelock but skip it");
                        continue;
                    }
                }

                Log.i(TAG, "kill proc " + w.mOwnerPid + " pkg:" + w.mPackageName + " inXiaiMode = " + inXiaiMode);
                pids[index++] = w.mOwnerPid;
            }

            ActivityManagerService ams = (ActivityManagerService)
                    ServiceManager.getService("activity");
            if (ams != null) {
                ams.killPids(pids, "release partial wakelock", true);
            }
        }
    };

    public void onScreenOff() {
        mHandler.removeCallbacks(mPartialWakelockProcsKiller);
        mHandler.postDelayed(mPartialWakelockProcsKiller, 3000);  // 3s
    }

    public void onScreenOn() {
        mHandler.removeCallbacks(mPartialWakelockProcsKiller);
    }

    private String mCurrForgroundPackage = null;
    public void setCurrentForgroundPackage(String pkgName) {
        mCurrForgroundPackage = pkgName;
    }

    public String getCurrentForgroundPackage() {
        return mCurrForgroundPackage;
    }

    public boolean CheckWhitelistOfForgroundPackage(){
        String[] whitelist = {"com.xiaomi.mitv.tvplayer", "org.xbmc.kodi"};
        for (String pkg : whitelist) {
            if (pkg.equals(mCurrForgroundPackage))
                return true;
        }
        return false;
    }

    private int findWakeLockIndexLocked(IBinder lock) {
        final int count = mWakeLocks.size();
        for (int i = 0; i < count; i++) {
            if (mWakeLocks.get(i).mLock == lock) {
                return i;
            }
        }
        return -1;
    }

    public void addWakeLock(IBinder lock, String tag, String packageName, int uid, int pid,
                            boolean screenOn) {
        final String ADVERTISING_TAG = "advertising_on_shutdown";
        final String ADVERTISING_PKG = "com.mitv.care";

        if (ADVERTISING_TAG.equals(tag) && ADVERTISING_PKG.equals(packageName)) {
            Log.d(TAG, "advertising_on_shutdown wakelock is registered");
        }

        WakeLock wakeLock = new WakeLock(lock, tag, packageName, uid, pid, screenOn);
        if (LOGV) {
            Log.d(TAG, "addWakeLock: lock = " + lock
                    + ", tag = " + tag
                    + ", package = " + packageName
                    + ", owner uid = " + uid
                    + ", owner pid = " + pid
                    + ", screeenOn = " + screenOn
                    + ", time = " + wakeLock.mAcquireTime);
        }
        synchronized (mWakeLockLock) {
            mWakeLocks.add(wakeLock);
        }
    }

    public void removeWakeLock(IBinder lock, boolean screenOn) {
        if (LOGV) Log.d(TAG, "removeWakeLock: lock = " + lock);

        WakeLock wakeLock = null;
        synchronized (mWakeLockLock) {
            int index = findWakeLockIndexLocked(lock);
            if (index >= 0) {
                wakeLock = mWakeLocks.get(index);
                mWakeLocks.remove(index);
            }
        }

        if (wakeLock != null) {
            long currTime = SystemClock.elapsedRealtime();
            long holdTime = currTime - wakeLock.mAcquireTime;
            if (holdTime > WAKELOCK_EVENT_REPORT_THRESHOLD) {
                if (LOGV) {
                    Log.d(TAG, "removeWakeLock: lock = " + lock
                            + " exceed threshold, screenOn = " + screenOn);
                }

                if (screenOn) {
                    wakeLock.mScreenState |= WakeLock.SCREEN_ON_WHEN_RELEASE;
                }

                HashMap map = new HashMap();
                map.put("wakelock", wakeLock.mTag);
                map.put("packageName", wakeLock.mPackageName);
                map.put("screenState", wakeLock.mScreenState);
                map.put("holdTime", holdTime);

                MitvBriefEventReporter.reportCountEvent(
                        REPORTER_WAKELOCK_CATEGORY,
                        REPORTER_WAKELOCK_WAKELOCK, map);
            }
            wakeLock = null;
        }
    }

    public boolean isPlayingVideo() {
        synchronized (mInfo) {
            mInfo.resetData();
            AsyncTask.execute(new Runnable() {
                public void run() {
                    try {
                        IBinder playerServie = android.os.ServiceManager.getService("media.player");
                        if (playerServie != null) {
                            Parcel data = Parcel.obtain();
                            Parcel reply = Parcel.obtain();
                            data.writeInterfaceToken("android.media.IMediaPlayerService");
                            if (playerServie.transact(5000, data, reply, 0) ) {
                                mInfo.isPlayingWithSurface = (reply.readInt()==1);
                                mInfo.uid = reply.readInt();
                                mInfo.pid = reply.readInt();
                                mInfo.isPausing = (reply.readInt()==1);
                                Log.i(TAG,"get player data "+mInfo.isPlayingWithSurface+" uid="+mInfo.uid+" pid="+mInfo.pid+" pausing="+mInfo.isPausing);
                            }
                            data.recycle();
                            reply.recycle();
                            synchronized (mInfo.Lock) {
                                mInfo.workDone = true;
                                mInfo.Lock.notifyAll();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG,"query media player info fail "+e);
                    }
                }
            });
            synchronized (mInfo.Lock) {
                try {
                    mInfo.Lock.wait(200);
                    if (!mInfo.workDone) {
                        Log.d(TAG,"get data timeout ...");
                    }
                } catch (java.lang.InterruptedException e1) {
                }
            }
            if (!mInfo.isPlayingWithSurface) {
                Log.d(TAG,"return false because not PlayingWithSurface");
                return false;
            }
        }
        return true;
    }
    class ThirdPartyPlayerInfo {
        boolean isPlayingWithSurface;
        int uid;
        int pid;
        boolean isPausing;
        boolean workDone;
        //this lock is designed to thread synchronization
        Object Lock = new Object();
        public void resetData() {
            isPlayingWithSurface = false;
            uid = pid = -1;
            workDone = false;
        }
    }
    ThirdPartyPlayerInfo mInfo = new ThirdPartyPlayerInfo();

    //////////////// WakeLock Hold Time Monitor end ////////////////
    public void updateSettingsLocked() {
        boolean disableSuspend = mContext.getPackageManager().hasSystemFeature("mitv.disable.system.suspend");/*ConfigurationManager.FEATURE_DISABLE_SYSTEM_SUSPEND*/
        //filter out tv box
        String type = SystemProperties.get("ro.boot.mi.panel_buildin", "false");
        String product = android.os.Build.PRODUCT;
        //support new feature str, do not support sleep
        if (product.equals("jobs")  || product.equals("kingarthur") || product.equals("hugo")) {
            disableSuspend = true;
        }
        if (disableSuspend && "true".equals(type)) {
            Settings.System.putInt(mContext.getContentResolver(), SCREEN_OFF_TIMEOUT, 2147483647);//never sleep
        }
    }

    public void handleWakeLockDeath(String tag, String pkg, int flag, final Context ctx, Handler handler, final PowerManagerService pms) {
        final String ADVERTISING_TAG = "advertising_on_shutdown";
        final String ADVERTISING_PKG = "com.mitv.care";

        if ((flag & PowerManager.WAKE_LOCK_LEVEL_MASK) == PowerManager.PARTIAL_WAKE_LOCK
                && ADVERTISING_TAG.equals(tag) && ADVERTISING_PKG.equals(pkg)) {
            Log.d(TAG, "WakeLock (pkg: " + pkg + ", tag: " + tag + ", flag: " + flag + ") binder died, shutdown!");
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    synchronized (pms) {
                        ShutdownThread.shutdown(mUiContext, "", false);
                    }
                }
            };

            // ShutdownThread must run on a looper capable of displaying the UI.
            Message msg = Message.obtain(UiThread.getHandler(), runnable);
            msg.setAsynchronous(true);
            UiThread.getHandler().sendMessage(msg);
        }
    }

    public void goToSleep() {
        MitvShutdownThread.goToSleep();
    }

    public void wakeUp() {
        MitvShutdownThread.wakeUp();
    }


    //记录NoKeyInput mode中发送广播的次数
    private int mNoKeyInputTime = 0;
    //No Key Input
    private void checkNoKeyInputTimeoutLocked(String reason) {
        Log.d(TAG,"checkNoKeyInputTimeoutLocked come "+mNoKeyInputDelay+" reason="+reason);
        mNoKeyInputTime = 0;
        mHandler.removeCallbacks(mNoKeyInputTask);
        mHandler.postDelayed(mNoKeyInputTask, mNoKeyInputDelay);
    }
    private Runnable mNoKeyInputTask = new Runnable() {
        public void run() {
            synchronized (mInstance) {
                mNoKeyInputTimeout = true;
            }
            computeNoKeyInput();
            mHandler.postDelayed(mNoKeyInputTask, mNoKeyInputDelay);
        }
    };

    /*
     ** If no any key during 25s, system enter into NoKeyInput mode.
     ** Send broadcast to app to help app to complete some actions.
     */
    private void computeNoKeyInput() {
        synchronized (mInstance) {
            if (!mNoKeyInputTimeout || !mBootCompleted ) {
                if (DEBUG_SCREEN_SAVER) {
                    Log.d(TAG,"computeNoKeyInput do nothing mNoKeyInputDelay="+mNoKeyInputDelay
                    );
                }
                //do nothing
                return;
            }
        }
        if (mPowerManager == null) {
            mPowerManager = (android.os.PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        }
        if (mPowerManager!=null && !mPowerManager.isInteractive()) {
            synchronized (mInstance) {
                mNoKeyInputTimeout = false;
            }
            if (DEBUG_SCREEN_SAVER) {
                Log.d(TAG,"screen not on, ignore NoKeyInput timeout");
            }
            return;
        }


        if(true){
            Log.d(TAG,"it's time to start NoKeyInput mode");
            try {
                Intent intent = new Intent("com.duokan.intent.action.enter.nokeyinput");
                intent.putExtra("time",mNoKeyInputTime++);
                mContext.sendBroadcastAsUser(intent,UserHandle.ALL, "mitv.permission.ACCESS_INNER_APPLICATION");
            } catch (Exception e) {
                Log.e(TAG,"start NoKeyInput find error "+e);
            }
        }
    }


    //transact keepscreenonFlag to TvService for Oled burnin protection
    private void notifyKeepScreenOnFlagChanged(boolean acquire) {
        try {
            IBinder tvService = ServiceManager.getService("TvService");
            if (tvService != null) {
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken("mitv.internal.ITvService");
                data.writeBoolean(acquire);
                //6201:MITV_SERVICE_OLED_UPDATE_KEEP_SCREEN_ON_FLAG
                tvService.transact(6201, data, null, 0);
                data.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG,"transact fail",e);
        }
    }

    //transact userActivity to TvService for Oled burnin protection
    private void notifyUserActivityChanged() {
        try {
            IBinder tvService = ServiceManager.getService("TvService");
            if (tvService != null) {
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken("mitv.internal.ITvService");
                //6202:MITV_SERVICE_OLED_UPDATE_USER_ACTIVITY
                tvService.transact(6202, data, null, 0);
                data.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG,"transact fail",e);
        }
    }

    public void echoEntry(final String entry, String rts) {
        File file = new File(entry);

        if (!file.exists()) {
            Log.d(TAG,"echoEntry file not exist file:" + entry);
            return ;
        }
        OutputStreamWriter osw = null;
        try {
            osw = new OutputStreamWriter(new FileOutputStream(entry));
            osw.write(rts, 0, rts.length());
        } catch (Exception e) {
            Log.w(TAG , "echoEntry", e);
        } finally {
            if (osw != null) {
                try {
                    osw.close();
                } catch (IOException e) {
                }
            }
        }
        return ;
    }
}
