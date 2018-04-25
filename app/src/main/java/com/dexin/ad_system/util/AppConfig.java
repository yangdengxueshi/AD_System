package com.dexin.ad_system.util;

import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;

import com.blankj.utilcode.util.SPUtils;
import com.dexin.utilities.CopyIndex;
import com.dexin.utilities.arrayhelpers;

import org.jetbrains.annotations.Contract;

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


    /**
     * 解析接收到的"原始UDP数据包"进而获得"n个(0~6)TS净荷包"（TS包是顺序排放的），我们只需要 0x86 开头的UDP包(广科院协议)
     *
     * @param udpDataPacket 原始的UDP数据报
     * @return "6个TS包的净荷"有序拼接起来的字节数组（长度是 6 * 184 = 1104）
     */
    @Nullable
    public static byte[] parseUDPPacketToPayload(byte[] udpDataPacket) {
//        if (udpDataPacket == null || udpDataPacket.length < (AppConfig.UDP_PACKET_HEADER_SIZE + AppConfig.UDP_PACKET_TAIL_SIZE)) {
//            LogUtil.d(TAG, "UDP原始数据包为 null 或 长度小于 21+311,数据不符合要求进而被丢弃!");
//            return null;
//        } else if (udpDataPacket.length != AppConfig.UDP_PACKET_SIZE) {
//            LogUtil.i(TAG, "UDP原始数据包长不是1460,原始数据有问题!");
//        }
        {//①原始UDP数据正确性检验
            if (udpDataPacket == null || udpDataPacket.length != AppConfig.UDP_PACKET_SIZE) {
                LogUtil.e(TAG, "UDP原始数据包为 null 或 长度不是1460,数据不符合要求进而被丢弃!");
                return null;
            }
            if (udpDataPacket[0] != AppConfig.UDP_HEAD_0x86_VALUE) {//这个0x86是广科院的头，注意与自定义 head_0x86_value 区分开   //TODO 5.筛选出 广科院0x86头 UDP数据报,(如果不是0x86开头的UDP包则抛弃掉)
                LogUtil.e(TAG, "UDP原始数据包头不是广科院协议头0x86,不符合要求进而被舍弃!");
                return null;
            }
        }

        int lTsPacketAbandonedCount = 0;//被抛弃的ts包的数量
        byte[] lTsPayloadBuffer = new byte[AppConfig.TS_PAYLOAD_NO * AppConfig.TS_PAYLOAD_SIZE];//将用于承载TS净荷的字节数组Buffer 6*184
        CopyIndex lCopyIndex = new CopyIndex(AppConfig.UDP_PACKET_HEADER_SIZE);//TODO 跳过前21字节头;UDP包以 0x86开头,前21字节是协议头(收到后可以删除)，（1460-21-311=1128字节有效）    1128/6=188（每个TS包）
        byte lTSHead, lTSPid1, lTSPid2;//TODO byte[]     取出当前同步字节，看是否等于0x 47 0F FE xx
        for (int i = 0; i < AppConfig.TS_PAYLOAD_NO; i++) {//TODO 循环解析一个UDP包中的6个TS包
            lTSHead = arrayhelpers.GetInt8(udpDataPacket, lCopyIndex);
            lTSPid1 = arrayhelpers.GetInt8(udpDataPacket, lCopyIndex);
            lTSPid2 = arrayhelpers.GetInt8(udpDataPacket, lCopyIndex);
            lCopyIndex.AddIndex(1);//前面已经 +1 +1 +1,现在 +1,跳过每个TS包的0x47前4个字节的协议头
            if ((lTSHead != AppConfig.TS_HEAD_0x47_VALUE) || ((lTSPid1 & (byte) 0x1F) != (byte) 0x0F) || (lTSPid2 != (byte) 0xFE)) {//三个条件有一个不满足都要将当前ts包抛掉(即不满足TS包协议头)
                lCopyIndex.AddIndex(AppConfig.TS_PAYLOAD_SIZE);//TODO TS包以  0x47开头，前4个字节是协议头(0x47 xF FE xx)，其后是184字节净荷     4+184=188
                lTsPacketAbandonedCount++;
                continue;
            }
            System.arraycopy(arrayhelpers.GetBytes(udpDataPacket, AppConfig.TS_PAYLOAD_SIZE, lCopyIndex), 0, lTsPayloadBuffer, (i - lTsPacketAbandonedCount) * AppConfig.TS_PAYLOAD_SIZE, AppConfig.TS_PAYLOAD_SIZE);
        }
//        LogUtil.d(TAG, "一个UDP包中全部有效净荷拼接结果:" + stringhelpers.bytesToHexString(Arrays.copyOf(lTsPayloadBuffer, (AppConfig.TS_PAYLOAD_NO - lTsPacketAbandonedCount) * AppConfig.TS_PAYLOAD_SIZE)));
        return Arrays.copyOf(lTsPayloadBuffer, (AppConfig.TS_PAYLOAD_NO - lTsPacketAbandonedCount) * AppConfig.TS_PAYLOAD_SIZE);//TODO 返回每个UDP包中n个有效TS包中的净荷拼接起来的字节数组
    }
}
