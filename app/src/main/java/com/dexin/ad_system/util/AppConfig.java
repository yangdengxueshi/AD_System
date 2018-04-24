package com.dexin.ad_system.util;

import android.support.v4.content.LocalBroadcastManager;

import com.blankj.utilcode.util.SPUtils;

import org.jetbrains.annotations.Contract;

/**
 * App配置文件
 */
public final class AppConfig {
    public static final int mPort = 8080;           //服务器端口
    public static final int mUDPPacketSize = 1460;  //UDP包的大小（广科院给的UDP原始包大小就是1460字节）

    private static final class LocalBroadcastManagerHolder {
        private static final LocalBroadcastManager LOCAL_BROADCAST_MANAGER = LocalBroadcastManager.getInstance(CustomApplication.getContext());
    }

    /**
     * 获取本地广播管理器
     *
     * @return 本地广播管理器
     */
    @Contract(pure = true)
    public static LocalBroadcastManager getLocalBroadcastManager() {
        return LocalBroadcastManagerHolder.LOCAL_BROADCAST_MANAGER;
    }

    private static final class SPUtilsHolder {
        private static final SPUtils SP_UTILS = SPUtils.getInstance();
    }

    /**
     * 获取SP工具
     *
     * @return SP工具
     */
    @Contract(pure = true)
    public static SPUtils getSPUtils() {
        return SPUtilsHolder.SP_UTILS;
    }
}
