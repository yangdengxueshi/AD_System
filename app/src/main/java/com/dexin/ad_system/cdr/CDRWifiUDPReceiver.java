package com.dexin.ad_system.cdr;

import com.dexin.ad_system.util.AppConfig;
import com.dexin.ad_system.util.LogUtil;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

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
            if (mUdpDataContainer == null) mUdpDataContainer = new byte[AppConfig.mUDPPacketSize];//1460包长
            if (mDatagramSocket == null) {
                mDatagramSocket = new DatagramSocket(null);
                mDatagramSocket.setReuseAddress(true);//地址复用
                mDatagramSocket.bind(new InetSocketAddress(AppConfig.mPort));//端口
            }
            if (mDatagramPacket == null) mDatagramPacket = new DatagramPacket(mUdpDataContainer, mUdpDataContainer.length);//建立一个指定缓冲区大小的数据包
            while (isNeedReceiveUDP) {
//                mDatagramSocket.setSoTimeout(8000);
                mDatagramSocket.receive(mDatagramPacket);//将单播套接字收到的UDP数据包存放于datagramPacket中(会阻塞)
                mUdpDataContainer = mDatagramPacket.getData();//UDP数据包
//                LogUtil.d(TAG, "UDP原始数据包AAAAAAAAAAAAAAAAAAAAA：" + stringhelpers.bytesToHexString(mUdpDataContainer).toUpperCase());
                if ((mUdpDataContainer != null) && (mUdpDataContainer.length > 0)) {
                    mUdpDataContainer = CDRUtils.parseUDPPacketToPayload(mUdpDataContainer);//解析UDP数据包后获得UDP净荷
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
}
