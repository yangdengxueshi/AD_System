package com.dexin.ad_system.app;

import android.support.v4.content.LocalBroadcastManager;

import com.blankj.utilcode.util.SPUtils;
import com.dexin.ad_system.util.LogUtil;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.text.MessageFormat;
import java.util.Arrays;

/**
 * App配置文件
 */
public final class AppConfig {
    private static final String TAG = "TAG_AppConfig";
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
    //    public static final String FILE_FOLDER = Environment.getExternalStorageDirectory().getPath() + "/AD_System";        //多媒体文件夹：/mnt/internal_sd/AD_System
    public static final String FILE_FOLDER = CustomApplication.getContext().getExternalCacheDir().getAbsolutePath();        //多媒体文件夹：/mnt/internal_sd/AD_System
    public static final byte[] sHead008888Array = {(byte) 0x00, (byte) 0x88, (byte) 0x88};                         //自定义协议头0x 008888 头


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


    //------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------TODO 封装的处理字节数组Buffer的函数----------------------------------------------------------------
    //------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓----------------------------------------------------------------

    /**
     * 两个数组按照：逆向偏移量 进行拼接
     *
     * @param currentBuffer     当前Buffer
     * @param nextBuffer        下一Buffer
     * @param currentBackOffset currentBuffer 逆向偏移量
     * @return 拼接后的结果数组
     */
    public static byte[] jointBuffer(byte[] currentBuffer, byte[] nextBuffer, int currentBackOffset) {//其实已经保证了 currentBuffer非null   nextBuffer非null
        byte[] lEmptyBuffer = new byte[0];
        if (currentBackOffset <= 0) {
            return (nextBuffer != null) ? nextBuffer : lEmptyBuffer;
        } else if (((currentBuffer == null) || (currentBuffer.length <= 0)) && ((nextBuffer == null) || (nextBuffer.length <= 0))) {
            return lEmptyBuffer;
        } else if ((currentBuffer == null) || (currentBuffer.length <= 0)) {
            return nextBuffer;
        } else if ((nextBuffer == null) || (nextBuffer.length <= 0)) {
            return (currentBuffer.length <= currentBackOffset) ? currentBuffer : Arrays.copyOfRange(currentBuffer, (currentBuffer.length - currentBackOffset), currentBuffer.length);
        } else {
            return (currentBuffer.length <= currentBackOffset) ? concatAllArray(currentBuffer, nextBuffer) : concatAllArray(Arrays.copyOfRange(currentBuffer, (currentBuffer.length - currentBackOffset), currentBuffer.length), nextBuffer);
        }
    }

    /**
     * 多个数组合并
     *
     * @param first 第一个数组
     * @param rest  其余数组
     * @return 合并后数组
     */
    private static byte[] concatAllArray(@NotNull byte[] first, @NotNull byte[]... rest) {
        int totalLength = first.length;
        for (byte[] array : rest) totalLength += array.length;
        byte[] result = Arrays.copyOf(first, totalLength);//增长部分会以"默认值"填充
        int offset = first.length;
        for (byte[] array : rest) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }


    /**
     * 找出子数组subBuffer在主数组mainBuffer中的起始索引
     * TODO 本方法进行了深度验证,没有问题
     *
     * @param start      开始查找的位置
     * @param end        结束查找的位置
     * @param mainBuffer 主数组mainBuffer
     * @param subBuffer  子数组subBuffer
     * @return 找到的起始索引（为-1表示没有找到）
     */
    public static int indexOfSubBuffer(int start, int end, byte[] mainBuffer, byte[] subBuffer) {//end 一般传递的是 mainBuffer.length
        int failure = -1;
        if ((subBuffer == null) || (mainBuffer == null) || (subBuffer.length > mainBuffer.length)) return failure;//正常
        if (start < 0) start = 0;
        if (start >= Math.min(end, mainBuffer.length)) {//
            LogUtil.e(TAG, MessageFormat.format("start查找位置超出 Math.min(end, mainBuffer.length)!{0}:{1}:{2}", start, end, mainBuffer.length));
            return failure;
        }
        if (end > mainBuffer.length) end = mainBuffer.length;

        boolean isFound;//子数组被找到
        for (int i = start; i < end; i++) {
            if (i <= (end - subBuffer.length)) {
                isFound = true;
                for (int j = 0; j < subBuffer.length; j++) {
                    if (mainBuffer[i + j] != subBuffer[j]) {
                        isFound = false;
                        break;
                    }
                }
            } else {
                isFound = false;
            }
            if (isFound) return i;
        }
        return failure;
    }
}
