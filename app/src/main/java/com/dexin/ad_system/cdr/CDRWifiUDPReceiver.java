package com.dexin.ad_system.cdr;

import android.support.annotation.Nullable;

import com.dexin.ad_system.util.AppConfig;
import com.dexin.ad_system.util.LogUtil;
import com.dexin.utilities.CopyIndex;
import com.dexin.utilities.arrayhelpers;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * CDRWifiUDPReceiver   接收CDR 发出的 Wifi UDP 数据包
 */
public class CDRWifiUDPReceiver {
    private static final String TAG = "TAG_接收CDR_Wifi_UDP包";
    private byte[] mUdpDataContainer;
    private DatagramSocket mDatagramSocket;          //单播套接字
    private DatagramPacket mDatagramPacket;
    private boolean isNeedReceiveUDP = false;       //是否需要接收UDP数据包（没有启动的时候不需要接收）

    /**
     * 接收CDR_Wifi_UDP数据包
     */
    public void receiveCDRWifiUDPPacket() {
        LogUtil.d(TAG, "################################################ 开始接收CDR_Wifi_UDP 数据包 ################################################");
        try {
            isNeedReceiveUDP = true;//标志"重新开始接受CDR_Wifi_UDP 数据包"
            if (mUdpDataContainer == null) mUdpDataContainer = new byte[AppConfig.UDP_PACKET_SIZE];//1460包长
            if (mDatagramSocket == null) {
                mDatagramSocket = new DatagramSocket(null);
                mDatagramSocket.setReuseAddress(true);//地址复用
                mDatagramSocket.bind(new InetSocketAddress(AppConfig.PORT));//端口
            }
            if (mDatagramPacket == null) mDatagramPacket = new DatagramPacket(mUdpDataContainer, mUdpDataContainer.length);//建立一个指定缓冲区大小的数据包
            while (isNeedReceiveUDP) {
//                mDatagramSocket.setSoTimeout(8000);
                mDatagramSocket.receive(mDatagramPacket);//将单播套接字收到的UDP数据包存放于datagramPacket中(会阻塞)
                mUdpDataContainer = mDatagramPacket.getData();//UDP数据包
//                LogUtil.d(TAG, "UDP原始数据包AAAAAAAAAAAAAAAAAAAAA：" + stringhelpers.bytesToHexString(mUdpDataContainer).toUpperCase());
                if ((mUdpDataContainer != null) && (mUdpDataContainer.length > 0)) {
                    mUdpDataContainer = parseUDPPacketToPayload(mUdpDataContainer);//解析UDP数据包后获得UDP净荷
//                    LogUtil.d(TAG, "ts净荷包AAAAAAAAAAAAAAAAAAAAA：" + stringhelpers.bytesToHexString(mUdpDataContainer).toUpperCase());
                    if ((mUdpDataContainer != null) && (mUdpDataContainer.length > 0)) {
                        CDRWifiUDPPayloadQueue.addIntoQueue(mUdpDataContainer);              //TODO 这里为了方便后面的解析工作，传入的是UDP净荷；可能引起掉包，再接收一轮
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (mDatagramSocket != null) {
                if (mDatagramSocket.isConnected()) mDatagramSocket.disconnect();
                if (!mDatagramSocket.isClosed()) mDatagramSocket.close();
                mDatagramSocket = null;
            }
            if (mDatagramPacket != null) {
                mDatagramPacket.setPort(0);
                mDatagramPacket.setLength(0);
                mDatagramPacket = null;
            }
            if (mUdpDataContainer != null) {
                mUdpDataContainer = null;//必须置null
            }
        }
    }

    public void stopReceiveCDRWifiUDPPacket() {
        isNeedReceiveUDP = false;
        LogUtil.d(TAG, "##################################### CDRWifiReceive 服务关闭，结束接收CDR_Wifi_UDP 数据包 #######################################");
    }

    /**
     * 解析接收到的"原始UDP数据包"进而获得"n个(0~6)TS净荷包"（TS包是顺序排放的），我们只需要 0x86 开头的UDP包(广科院协议)
     *
     * @param udpDataPacket 原始的UDP数据报
     * @return "6个TS包的净荷"有序拼接起来的字节数组（长度是 6 * 184 = 1104）
     */
    @Nullable
    private byte[] parseUDPPacketToPayload(byte[] udpDataPacket) {
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
