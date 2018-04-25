package com.dexin.ad_system.app;

import android.os.Environment;
import android.support.v4.content.LocalBroadcastManager;

import com.blankj.utilcode.util.SPUtils;

import org.jetbrains.annotations.Contract;

/**
 * App配置文件
 */
public final class AppConfig {
    public static final int PORT = 8080;//服务器端口
    // 21+188*6+311=1460
    public static final int UDP_PACKET_HEADER_SIZE = 21;//UDP包头长度
    public static final int TS_PAYLOAD_NO = 6;
    public static final int TS_PACKET_SIZE = 188;
    public static final int TS_PAYLOAD_SIZE = 184;
    public static final int UDP_PACKET_TAIL_SIZE = 311;
    public static final int UDP_PACKET_SIZE = 1460;//UDP包的大小（广科院给的UDP原始包大小就是1460字节）

    public static final byte UDP_HEAD_0x86_VALUE = (byte) 0x86;//广科院UDP头 0x86
    public static final byte TS_HEAD_0x47_VALUE = (byte) 0x47;//广科院TS头 0x47


    public static final byte head_0x86_value = (byte) 0x86;
    public static final byte head_0x87_value = (byte) 0x87;
    public static final String LINE_HEAD = "\t\t\t\t\t\t";
    public static final String FORM_FEED_CHARACTER = "\n\n\n\n\n";
    public static final String LOAD_FILE_OR_DELETE_MEDIA_LIST = "com.dexin.ad_system.LOAD_FILE_OR_DELETE_MEDIA_LIST";
    public static final String FILE_FOLDER = Environment.getExternalStorageDirectory().getPath() + "/AD_System";        //多媒体文件夹：/mnt/internal_sd/AD_System
    public static byte[] head_008888_array = new byte[]{(byte) 0x00, (byte) 0x88, (byte) 0x88};                         //自定义协议头0x 008888 头


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
