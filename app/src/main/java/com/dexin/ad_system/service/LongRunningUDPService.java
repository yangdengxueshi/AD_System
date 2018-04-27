package com.dexin.ad_system.service;

import android.app.Service;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;

import com.dexin.ad_system.R;
import com.dexin.ad_system.app.AppConfig;
import com.dexin.ad_system.app.CustomApplication;
import com.dexin.ad_system.cdr.CDRUtils;
import com.dexin.ad_system.util.LogUtil;
import com.dexin.utilities.CopyIndex;
import com.dexin.utilities.arrayhelpers;
import com.orhanobut.logger.Logger;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

public final class LongRunningUDPService extends Service {
    private PayloadProducerThread mPayloadProducerThread;
    private static final ArrayBlockingQueue<byte[]> mPayloadArrayBlockingQueue = new ArrayBlockingQueue<>(1000);//存放"(0~6)*184净荷"的阻塞队列
    private PayloadConsumerThread mPayloadConsumerThread;
    private static final ArrayBlockingQueue<byte[]> mCusDataArrayBlockingQueue = new ArrayBlockingQueue<>(1000);//存放"自定义1024数据"的阻塞队列
    private CusDataConsumerThread mCusDataConsumerThread;

    @Nullable
    @Contract(pure = true)
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, new NotificationCompat.Builder(CustomApplication.getContext(), "ForegroundService")
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("CDR广告系统")
                .setContentText("请求网络数据的前台服务。")
                .setWhen(System.currentTimeMillis())
                .build()
        );

        if (mPayloadProducerThread == null) mPayloadProducerThread = new PayloadProducerThread();
        if (mPayloadConsumerThread == null) mPayloadConsumerThread = new PayloadConsumerThread();
        if (mCusDataConsumerThread == null) mCusDataConsumerThread = new CusDataConsumerThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mPayloadProducerThread.start();
        mPayloadConsumerThread.start();
        mCusDataConsumerThread.start();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        if (mPayloadProducerThread != null) {
            mPayloadProducerThread.stopReceiveUDPPacket();
            mPayloadProducerThread = null;
        }
        if (mPayloadConsumerThread != null) {
            mPayloadConsumerThread.stopParsePayloadData();
            mPayloadConsumerThread = null;
        }
        if (mCusDataConsumerThread != null) {
            mCusDataConsumerThread.stopParseCustomData();
            mCusDataConsumerThread = null;
        }
        super.onDestroy();
    }

    /**
     * -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------TODO 净荷"生产者"线程----------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓----------------------------------------------------------------------------------------
     */
    private static final class PayloadProducerThread extends Thread {
        private static final String TAG = "TAG_PayloadProducerThread";
        private volatile boolean isNeedReceiveUDP;       //是否需要接收UDP数据包（没有启动的时候不需要接收）
        private byte[] mUdpDataContainer;
        private DatagramSocket mDatagramSocket;          //单播套接字
        private DatagramPacket mDatagramPacket;

        @Override
        public void run() {
            LogUtil.e(TAG, "################################################ 开始接收CDR_Wifi_UDP数据包 ##################################################");
            isNeedReceiveUDP = true;//标志"重新开始接收CDR_Wifi_UDP 数据包"
            try {
                if (mUdpDataContainer == null) mUdpDataContainer = new byte[AppConfig.UDP_PACKET_SIZE];//1460包长
                if (mDatagramSocket == null) {
                    mDatagramSocket = new DatagramSocket(null);
                    mDatagramSocket.setReuseAddress(true);//地址复用
                    mDatagramSocket.bind(new InetSocketAddress(AppConfig.PORT));//端口
                }
                if (mDatagramPacket == null) mDatagramPacket = new DatagramPacket(mUdpDataContainer, mUdpDataContainer.length);//建立一个指定缓冲区大小的数据包
                while (isNeedReceiveUDP) {
                    try {
//                        mDatagramSocket.setSoTimeout(8000);
                        mDatagramSocket.receive(mDatagramPacket);//将单播套接字收到的UDP数据包存放于datagramPacket中(会阻塞)
                        mUdpDataContainer = mDatagramPacket.getData();//UDP数据包
//                        LogUtil.d(TAG, "UDP原始数据包AAAAAAAAAAAAAAAAAAAAA：" + stringhelpers.bytesToHexString(mUdpDataContainer).toUpperCase());
                        if ((mUdpDataContainer != null) && (mUdpDataContainer.length > 0)) {
                            mUdpDataContainer = parseUDPPacketToPayload(mUdpDataContainer);//解析UDP数据包后获得UDP净荷
//                            LogUtil.d(TAG, "ts净荷包AAAAAAAAAAAAAAAAAAAAA：" + stringhelpers.bytesToHexString(mUdpDataContainer).toUpperCase());
                            if ((mUdpDataContainer != null) && (mUdpDataContainer.length > 0)) {
                                mPayloadArrayBlockingQueue.put(mUdpDataContainer);//TODO 这里为了方便后面的解析工作，传入的是UDP净荷；可能引起掉包，再接收一轮
                            }
                        }
                    } catch (Exception e) {
                        Logger.t(TAG).e(e, "run: ");
                    }
                }
            } catch (Exception e) {
                Logger.t(TAG).e(e, "run: ");
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

        private void stopReceiveUDPPacket() {
            isNeedReceiveUDP = false;
            LogUtil.e(TAG, "##################################### CDRWifiReceive 服务关闭，结束接收CDR_Wifi_UDP 数据包 #######################################");
        }

        /**
         * 解析接收到的"原始UDP数据包"进而获得"n个(0~6)TS净荷包"（TS包是顺序排放的），我们只需要 0x86 开头的UDP包(广科院协议)
         *
         * @param udpDataPacket 原始的UDP数据报
         * @return "6个TS包的净荷"有序拼接起来的字节数组（长度是 6 * 184 = 1104）
         */
        @Nullable
        private static byte[] parseUDPPacketToPayload(byte[] udpDataPacket) {
//        if (udpDataPacket == null || udpDataPacket.length < (AppConfig.UDP_PACKET_HEADER_SIZE + AppConfig.UDP_PACKET_TAIL_SIZE)) {
//            LogUtil.d(TAG, "UDP原始数据包为 null 或 长度小于 21+311,数据不符合要求进而被丢弃!");
//            return null;
//        } else if (udpDataPacket.length != AppConfig.UDP_PACKET_SIZE) {
//            LogUtil.i(TAG, "UDP原始数据包长不是1460,原始数据有问题!");
//        }
            {//①原始UDP数据正确性检验
                if ((udpDataPacket == null) || (udpDataPacket.length != AppConfig.UDP_PACKET_SIZE)) {
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

    /**
     * -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------------TODO 净荷"消费者"线程----------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓----------------------------------------------------------------------------------
     */
    private static final class PayloadConsumerThread extends Thread {
        private static final String TAG = "TAG_PayloadConsumerThread";
        private volatile boolean isNeedParsePayloadData;
        private int mHead008888Index;//记录"自定义协议头 008888"的下标
        private byte[] mCurrentPayloadArray = {};//程序刚启动时无数据

        @Override
        public void run() {
            LogUtil.e(TAG, "################################################ 开始解析净荷数据 ################################################");
            isNeedParsePayloadData = true;
            try {
                while (isNeedParsePayloadData) {
                    mHead008888Index = AppConfig.indexOfSubBuffer(0, mCurrentPayloadArray.length, mCurrentPayloadArray, AppConfig.sHead008888Array);//TODO 一、在当前净荷数组中寻找 008888 的下标
                    while (mHead008888Index < 0) {//FIXME （完全可能还是找不到,因为有大量的填充数据"0000000000000000" 和 "FFFFFFFFFFFFFFF"）,所以需要一直向后拼接寻找 008888 ,直到找到（mHead008888Index >= 0）为止
                        mCurrentPayloadArray = AppConfig.jointBuffer(mCurrentPayloadArray, getNextValidPayload(), AppConfig.sHead008888Array.length - 1);//FIXME 基于上面的原因，长度也不一定是1106
                        mHead008888Index = AppConfig.indexOfSubBuffer(0, mCurrentPayloadArray.length, mCurrentPayloadArray, AppConfig.sHead008888Array);
                    }//TODO 经历了循环之后，一定可以在 mCurrentPayloadArray 中找到 008888 ,找到了退出循环的时候 mHead008888Index >= 0 一定成立
                    mCurrentPayloadArray = parsePayloadArrayAfterHead008888(Arrays.copyOfRange(mCurrentPayloadArray, mHead008888Index, mCurrentPayloadArray.length));//TODO 传递的是以"008888"头为起始的数组，返回的是"超出部分的净荷内容"来作为"当前的净荷内容"去继续查找拼接
                }
            } catch (Exception e) {
                Logger.t(TAG).e(e, "run: ");
            }
        }

        /**
         * 停止解析净荷数据
         */
        private void stopParsePayloadData() {
            isNeedParsePayloadData = false;
            LogUtil.e(TAG, "################################################ 停止解析净荷数据 ################################################");
        }

        /**
         * 获取下一个有效的净荷   TODO 长度不一定是1104，因为6个ts包中的某个包可能不符合要求
         *
         * @return 下一个有效的净荷
         */
        private static byte[] getNextValidPayload() {
            try {
                return mPayloadArrayBlockingQueue.take();//FIXME 遇到队列中没有元素的时候,线程会阻塞
            } catch (InterruptedException e) {
                Logger.t(TAG).e(e, "getNextValidPayload: ");
                return new byte[0];//上一个return语句会阻塞,不会走到这一步!
            }
        }

        /**
         * 先判断 当前的净荷数组 是否还包含满足条件的头 TODO “008888”
         * <p>
         * 判断当前以 008888 开头的净荷数组 是否满足数据段长度：1.如果满足数据段长度，解析段并返回超出部分的净荷     2.如果不满足数据长度，就与下一段进行拼接，然后解析段的数据  并   返回超出部分的净荷
         *
         * @param currentPayloadArray 当前净荷数组(绝对是以 008888 开头的)
         * @return 超出部分的净荷数组    TODO    去作为再次寻找  008888  的数组
         */
        @NonNull
        private static byte[] parsePayloadArrayAfterHead008888(@NotNull byte[] currentPayloadArray) {//FIXME 有可能 currentPayloadArray 刚好能容下 00 88 88
            while (true) {//TODO currentPayloadArray 的前1024（长度完全可能小于1024）中一定不含有 00 88 88，1024表示要找到我们一个段长的数据，段的长度就是1024
                if (currentPayloadArray.length >= AppConfig.CUS_DATA_SIZE) {
                    try {
                        mCusDataArrayBlockingQueue.put(Arrays.copyOfRange(currentPayloadArray, 0, AppConfig.CUS_DATA_SIZE));
                    } catch (InterruptedException e) {
                        Logger.t(TAG).e(e, "parsePayloadArrayAfterHead008888: ");
                    }
                    return Arrays.copyOfRange(currentPayloadArray, AppConfig.CUS_DATA_SIZE, currentPayloadArray.length);//返回超出 1024 部分的数据
                } else {//TODO 有可能 currentPayloadArray 刚好能容下 00 88 88
                    currentPayloadArray = AppConfig.jointBuffer(currentPayloadArray, getNextValidPayload(), currentPayloadArray.length);//TODO 取出包括 00 88 88 之后的所有内容，与下一段数据 进行拼接 ,截取前1024        (拼接之后必须检查前 1024 是否还有一个 008888)
                }
            }
        }
    }


    /**
     * -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------TODO "自定义数据消费者"线程-----------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓-----------------------------------------------------------------------------------
     */
    private static final class CusDataConsumerThread extends Thread {
        private static final String TAG = "TAG_CusDataConsumerThread";
        private volatile boolean isNeedParsePayloadData;

        @Override
        public void run() {
            LogUtil.e(TAG, "################################################ 开始解析自定义协议数据 ##################################################");
            isNeedParsePayloadData = true;
            while (isNeedParsePayloadData) {
                try {
                    parseCustomDataAfter008888(mCusDataArrayBlockingQueue.take());
                } catch (InterruptedException e) {
                    Logger.t(TAG).e(e, "run: ");
                }
            }
        }

        /**
         * 停止解析净荷数据
         */
        private void stopParseCustomData() {
            isNeedParsePayloadData = false;
            LogUtil.e(TAG, "################################################ 结束解析自定义协议数据 ################################################");
        }

        /**
         * 解析 008888 之后的 1024字节 的数据
         *
         * @param front1024OfCurrentPayloadArray 拼接好的以 008888 开头的 1024字节 的数据
         */
        private static void parseCustomDataAfter008888(@NotNull byte[] front1024OfCurrentPayloadArray) {
            switch (front1024OfCurrentPayloadArray[4]) {
                case AppConfig.head_0x87_value://TODO 获得配置表，开始解析配置表 front1024OfCurrentPayloadArray 一定是00 88 88 xx 87开头
                    CDRUtils.parseConfigTable(front1024OfCurrentPayloadArray, 4);
                    break;
                case AppConfig.head_0x86_value://TODO 获得元素表，开始解析元素表 front1024OfCurrentPayloadArray 一定是00 88 88 xx 86开头
                    CDRUtils.parseSectionData(front1024OfCurrentPayloadArray, 4);
                    break;
                default:
            }
        }
    }
}
