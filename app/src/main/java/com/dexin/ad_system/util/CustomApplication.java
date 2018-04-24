package com.dexin.ad_system.util;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;

import com.blankj.utilcode.util.Utils;
import com.squareup.leakcanary.LeakCanary;
import com.vondear.rxtools.RxTool;

import org.jetbrains.annotations.Contract;

import java.util.List;
import java.util.Objects;

public class CustomApplication extends Application {
    private static Context context;//TODO 这里static所修饰的context并不会引起内存泄漏，因为static数据与单例的context同生命周期

    /**
     * 全局获取 Context对象
     *
     * @return 全局的 context对象
     */
    @Contract(pure = true)
    public static Context getContext() {
        return context;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Objects.equals(getCurrentProcessName(), "com.dexin.ad_system") && !LeakCanary.isInAnalyzerProcess(this)) {//如果不在分析器进程中:此进程专注于LeakCanary堆分析,你不应该在此进程中初始化App
            LeakCanary.install(this);
            RxTool.init(this);
            Utils.init(this);
            context = getApplicationContext();
        }
    }

    /**
     * 获取当前进程的名字
     *
     * @return 当前进程名
     */
    private String getCurrentProcessName() {
        String currentProcessName = "";
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            List<ActivityManager.RunningAppProcessInfo> runningAppProcessInfoList = activityManager.getRunningAppProcesses();
            if ((runningAppProcessInfoList != null) && !runningAppProcessInfoList.isEmpty()) {
                for (ActivityManager.RunningAppProcessInfo runningAppProcessInfo : runningAppProcessInfoList) {
                    if ((runningAppProcessInfo != null) && (runningAppProcessInfo.pid == android.os.Process.myPid())) {
                        currentProcessName = runningAppProcessInfo.processName;
                        break;
                    }
                }
            }
        }
        return currentProcessName;
    }
}
