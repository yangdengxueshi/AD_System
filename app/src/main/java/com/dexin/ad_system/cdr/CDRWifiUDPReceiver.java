package com.dexin.ad_system.cdr;

import com.dexin.ad_system.util.AppConfig;
import com.dexin.ad_system.util.LogUtil;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * 接收CDR 发出的 Wifi UDP 数据包
 */
public class CDRWifiUDPReceiver {
    private static final String TAG = "TAG_接收CDR Wifi UDP包";
    private DatagramSocket datagramSocket;          //单播套接字
    private DatagramPacket datagramPacket;
    private boolean isNeedReceiveUDP = false;       //是否需要接收UDP数据包（没有启动的时候不需要接收）

    public void receiveCDRWifiUDPPacket() {
        LogUtil.d(TAG, "################################################开始接收CDR Wifi UDP 数据包################################################");
        isNeedReceiveUDP = true;                    //开始接受CDR Wifi UDP 数据包
        try {
            if (datagramSocket == null) {
                datagramSocket = new DatagramSocket(null);
                datagramSocket.setReuseAddress(true);
                datagramSocket.bind(new InetSocketAddress(AppConfig.mPort));
            }
            byte[] udpContainer = new byte[AppConfig.mUDPPacketSize];
            while (isNeedReceiveUDP) {
                datagramPacket = new DatagramPacket(udpContainer, udpContainer.length);      //建立一个指定缓冲区大小的数据包
//                datagramSocket.setSoTimeout();
                datagramSocket.receive(datagramPacket);                         //将单播套接字收到的UDP数据包存放于datagramPacket中(会阻塞)
                byte[] udpDataPacket = datagramPacket.getData();                            //UDP数据包
//                LogUtil.d(TAG, "UDP原始数据包AAAAAAAAAAAAAAAAAAAAA：" + stringhelpers.bytesToHexString(udpDataPacket).toUpperCase());
                if ((udpDataPacket != null) && (udpDataPacket.length > 0)) {
                    byte[] udpPayload = CDRUtils.parseUDPPacketToPayload(udpDataPacket);      //解析UDP数据包后获得的UDP净荷
//                    LogUtil.d(TAG, "ts净荷包AAAAAAAAAAAAAAAAAAAAA：" + stringhelpers.bytesToHexString(udpPayload).toUpperCase());
                    if ((udpPayload != null) && (udpPayload.length > 0)) {
                        CDRWifiUDPPayloadQueue.addIntoQueue(udpPayload);              //TODO 这里为了方便后面的解析工作，传入的是UDP净荷；可能引起掉包，再接收一轮
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (datagramSocket != null) {
                datagramPacket.setPort(0);
                datagramSocket = null;
            }
            if (datagramPacket != null) {
                datagramPacket.setLength(0);
                datagramPacket = null;
            }
        }
    }

    public void stopReceiveCDRWifiUDPPacket() {
        isNeedReceiveUDP = false;
        LogUtil.d(TAG, "################################################CDRWifiReceive 服务关闭，结束接收单播################################################");
    }
}
