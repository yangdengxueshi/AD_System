package com.dexin.ad_system.util;

import android.app.Application;
import android.content.Context;

import com.blankj.utilcode.util.Utils;
import com.vondear.rxtools.RxTool;

public class MyApplication extends Application {
    private static Context context;//TODO 这里static所修饰的context并不会引起内存泄漏，因为static数据与单例的context同生命周期

    /**
     * 全局获取 Context对象
     *
     * @return 全局的 context对象
     */
    public static Context getContext() {
        return context;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RxTool.init(this);
        Utils.init(this);
        context = getApplicationContext();
    }
}
