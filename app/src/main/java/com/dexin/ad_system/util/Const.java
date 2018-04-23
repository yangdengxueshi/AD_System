package com.dexin.ad_system.util;

import android.os.Environment;

/**
 * 定义一些常量
 */
public class Const {
    public static final byte head_0x86_value_gky = (byte) 0x86;
    public static final byte head_0x86_value = (byte) 0x86;
    public static final byte head_0x87_value = (byte) 0x87;
    public static final String LINE_HEAD = "\t\t\t\t\t\t";
    public static final String FORM_FEED_CHARACTER = "\n\n\n\n\n";
    public static final String LOAD_FILE_OR_DELETE_MEDIA_LIST = "com.dexin.ad_system.LOAD_FILE_OR_DELETE_MEDIA_LIST";
    public static final String FILE_FOLDER = Environment.getExternalStorageDirectory().getPath() + "/AD_System";        //多媒体文件夹：/mnt/internal_sd/AD_System
    public static byte[] head_008888_array = new byte[]{(byte) 0x00, (byte) 0x88, (byte) 0x88};                         //自定义协议头0x 008888 头
}
