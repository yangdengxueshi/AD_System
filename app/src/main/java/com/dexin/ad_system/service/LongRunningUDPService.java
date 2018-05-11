package com.dexin.ad_system.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.util.LongSparseArray;

import com.blankj.utilcode.util.FileUtils;
import com.dexin.ad_system.R;
import com.dexin.ad_system.app.AppConfig;
import com.dexin.ad_system.app.CustomApplication;
import com.dexin.ad_system.cdr.Element;
import com.dexin.ad_system.util.LogUtil;
import com.dexin.utilities.CopyIndex;
import com.dexin.utilities.arrayhelpers;
import com.dexin.utilities.stringhelpers;
import com.orhanobut.logger.Logger;
import com.vondear.rxtools.view.RxToast;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

public final class LongRunningUDPService extends Service {
    private UDPPackProducerThread mUDPPackProducerThread;
    private static final ArrayBlockingQueue<byte[]> mUDPPackArrayBlockingQueue = new ArrayBlockingQueue<>(AppConfig.ARRAY_BLOCKING_QUEUE_CAPACITY);//存放"原始UDP数据报"的阻塞队列
    private PayloadConsumerThread mPayloadConsumerThread;
    private static final ArrayBlockingQueue<byte[]> mCusDataArrayBlockingQueue = new ArrayBlockingQueue<>(AppConfig.ARRAY_BLOCKING_QUEUE_CAPACITY);//存放"自定义1024数据"的阻塞队列
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
        startForeground(1, new NotificationCompat.Builder(CustomApplication.getContext(), "ForegroundService").setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher)).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("CDR广告系统").setContentText("请求网络数据的前台服务.").setWhen(System.currentTimeMillis()).build());
        {
            FileUtils.createOrExistsDir(AppConfig.MEDIA_FILE_FOLDER);
            AppConfig.getSPUtils().put(AppConfig.KEY_DATA_RECEIVE_OR_NOT, true);
            RxToast.success("已启动数据接收与解析服务!");
        }
        initWifiLockAndMulticastLockInOnCreate();//初始化 Wifi锁 和 多播锁

        if (mUDPPackProducerThread == null) mUDPPackProducerThread = new UDPPackProducerThread();
        if (mPayloadConsumerThread == null) mPayloadConsumerThread = new PayloadConsumerThread();
        if (mCusDataConsumerThread == null) mCusDataConsumerThread = new CusDataConsumerThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mUDPPackProducerThread.start();
        mPayloadConsumerThread.start();
        mCusDataConsumerThread.start();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        {
            AppConfig.getSPUtils().put(AppConfig.KEY_DATA_RECEIVE_OR_NOT, false);
            RxToast.error("已停止数据接收与解析服务!");
        }
        if (mUDPPackProducerThread != null) {
            mUDPPackProducerThread.stopUDPPackProducerThreadSafely();
            mUDPPackProducerThread = null;
        }
        mUDPPackArrayBlockingQueue.clear();
        if (mPayloadConsumerThread != null) {
            mPayloadConsumerThread.stopPayloadConsumerThreadSafely();
            mPayloadConsumerThread = null;
        }
        mCusDataArrayBlockingQueue.clear();
        if (mCusDataConsumerThread != null) {
            mCusDataConsumerThread.stopCusDataConsumerThreadSafely();
            mCusDataConsumerThread = null;
        }
        releaseWifiLockAndMulticastLockInOnDestroy();//释放 Wifi锁 和 多播锁
        super.onDestroy();
    }


    /**
     * -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------FIXME WiFi锁 和 多播锁--------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓------------------------------------------------------------------------------------------
     */
    private WifiManager.WifiLock mWifiLock;
    private WifiManager.MulticastLock mMulticastLock;

    /**
     * 初始化 WiFi锁 和 多播锁
     */
    private void initWifiLockAndMulticastLockInOnCreate() {
        WifiManager manager = (WifiManager) (getApplicationContext().getSystemService(Context.WIFI_SERVICE));
        if (manager != null) {
            mWifiLock = manager.createWifiLock("Wifi_Lock");
            mMulticastLock = manager.createMulticastLock("Multicast_Lock");
            mWifiLock.acquire();//获得WifiLock和MulticastLock
            mMulticastLock.acquire();
        }
    }

    private void releaseWifiLockAndMulticastLockInOnDestroy() {
        if ((mWifiLock != null) && mWifiLock.isHeld()) mWifiLock.release();
        if ((mMulticastLock != null) && mMulticastLock.isHeld()) mMulticastLock.release();
    }


    /**
     * -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------FIXME UDP包生产者线程----------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓----------------------------------------------------------------------------------------
     */
    private static final class UDPPackProducerThread extends Thread {
        private static final String TAG = "TAG_PayloadProducerThread";
        private volatile boolean isNeedReceiveUDP;//是否需要接收UDP数据包（没有启动的时候不需要接收）
        private byte[] mUdpPackContainer;
        private DatagramSocket mDatagramSocket;//单播套接字
        private DatagramPacket mDatagramPacket;//单播数据报

        @Override
        public void run() {
            LogUtil.i(TAG, "################################################ 开始接收CDR_Wifi_UDP数据包 ##################################################");
            isNeedReceiveUDP = true;//标志"重新开始接收CDR_Wifi_UDP数据包"
            try {
                if (mUdpPackContainer == null) mUdpPackContainer = new byte[AppConfig.UDP_PACKET_SIZE];//1460包长
                mDatagramSocket = new DatagramSocket(AppConfig.getSPUtils().getInt(AppConfig.KEY_DATA_RECEIVE_PORT, AppConfig.DEFAULT_DATA_RECEIVE_PORT));
//                mDatagramSocket.setReuseAddress(true);
                if (mDatagramPacket == null) mDatagramPacket = new DatagramPacket(mUdpPackContainer, mUdpPackContainer.length);//建立一个指定缓冲区大小的数据包
                while (isNeedReceiveUDP) {
                    try {
//                        mDatagramSocket.setSoTimeout(8000);
                        mDatagramSocket.receive(mDatagramPacket);//将单播套接字收到的UDP数据包存放于datagramPacket中(会阻塞)
                        mUdpPackContainer = mDatagramPacket.getData();//UDP数据包
                        if ((mUdpPackContainer != null) && (mUdpPackContainer.length > 0)) {
//                            LogUtil.d(TAG, MessageFormat.format("UDP原始数据包-入队前-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABBBBBB:\t\t{0}", stringhelpers.bytesToHexString(mUdpPackContainer).toUpperCase(Locale.getDefault())));
                            mUDPPackArrayBlockingQueue.offer(mUdpPackContainer);
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
                mUdpPackContainer = null;//必须置null
            }
        }

        /**
         * 安全地 停止"UDP包生产者线程"
         */
        private void stopUDPPackProducerThreadSafely() {
            isNeedReceiveUDP = false;
            mUDPPackArrayBlockingQueue.clear();
            if (mDatagramSocket != null) {
                if (mDatagramSocket.isConnected()) mDatagramSocket.disconnect();
                if (!mDatagramSocket.isClosed()) mDatagramSocket.close();
                mDatagramSocket = null;
            }
            LogUtil.e(TAG, "##################################### CDRWifiReceive 服务关闭,结束接收CDR_Wifi_UDP数据包 #######################################");
        }
    }


    /**
     * -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------------FIXME 净荷消费者线程-----------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓-----------------------------------------------------------------------------------
     */
    private static final class PayloadConsumerThread extends Thread {
        private static final String TAG = "TAG_PayloadConsumerThread";
        private volatile boolean isNeedParsePayloadData;
        private int mHead008888Index;//记录"自定义协议头 008888"的下标
        private byte[] mCurrentCusDataBuffer = {};//程序刚启动时无数据

        @Override
        public void run() {
            LogUtil.i(TAG, "################################################ 开始解析净荷数据 ################################################");
            isNeedParsePayloadData = true;
            try {
                while (isNeedParsePayloadData) {
                    mHead008888Index = AppConfig.indexOfSubBuffer(0, mCurrentCusDataBuffer.length, mCurrentCusDataBuffer, AppConfig.sHead008888Array);//FIXME 一、在当前净荷数组中寻找 008888 的下标
                    while (mHead008888Index < 0) {//FIXME （完全可能还是找不到,因为有大量的填充数据"0000000000000000" 和 "FFFFFFFFFFFFFFF"）,所以需要一直向后拼接寻找 008888 ,直到找到（mHead008888Index >= 0）为止
                        mCurrentCusDataBuffer = AppConfig.jointBuffer(mCurrentCusDataBuffer, getNextValidPayload(), AppConfig.sHead008888Array.length - 1);//FIXME 基于上面的原因，长度也不一定是1106
                        mHead008888Index = AppConfig.indexOfSubBuffer(0, mCurrentCusDataBuffer.length, mCurrentCusDataBuffer, AppConfig.sHead008888Array);
                    }//FIXME 经历了循环之后，一定可以在 mCurrentCusDataBuffer 中找到 008888 ,退出循环的时候 mHead008888Index >= 0 一定成立
                    mCurrentCusDataBuffer = cutOutPayloadArrayAfterHead008888(Arrays.copyOfRange(mCurrentCusDataBuffer, mHead008888Index, mCurrentCusDataBuffer.length));//FIXME 传递的是以"008888"头为起始的数组作为参数，返回的是"超出部分的净荷内容"来作为"当前的净荷内容"去继续查找,拼接
                }
            } catch (Exception e) {
                Logger.t(TAG).e(e, "run: ");
            } finally {
                mCurrentCusDataBuffer = new byte[0];
            }
        }

        /**
         * 安全地 停止"净荷消费者线程"
         */
        private void stopPayloadConsumerThreadSafely() {
            isNeedParsePayloadData = false;
            mUDPPackArrayBlockingQueue.clear();
            mCusDataArrayBlockingQueue.clear();
            LogUtil.e(TAG, "################################################ 停止解析净荷数据 ################################################");
        }

        /**
         * 获取下一个有效的净荷   FIXME 长度不一定是1104，因为6个ts包中的某个包可能不符合要求
         *
         * @return 下一个有效的净荷
         */
        @Nullable
        private static byte[] getNextValidPayload() {//FIXME 返回值被用于"Buffer数据拼接"
            try {
                if (!mUDPPackArrayBlockingQueue.isEmpty()) {
                    byte[] mUdpPackContainer = mUDPPackArrayBlockingQueue.poll();
                    if ((mUdpPackContainer != null) && (mUdpPackContainer.length > 0)) {
//                        LogUtil.d(TAG, MessageFormat.format("UDP原始数据包-出队后-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABBBBBB:\t\t{0}", stringhelpers.bytesToHexString(mUdpPackContainer).toUpperCase(Locale.getDefault())));
                        return parseUDPPacketToPayload(mUdpPackContainer);//FIXME 取出原始UDP包,解析成"(0~6)*184净荷"并返回
                    }
                }
            } catch (Exception e) {
                Logger.t(TAG).e(e, "getNextValidPayload: ");
            }
            return null;
        }

        /**
         * 判断当前以 008888 开头的净荷数组 是否满足数据段长度：1.如果满足数据段长度，解析段并返回超出部分的净荷     2.如果不满足数据长度，就与下一段净荷进行拼接，然后截取出自定义数据段 并 返回超出部分的净荷
         *
         * @param currentCusDataArray 当前自定义协议数据(绝对是以 008888 开头的)
         * @return 超出部分的净荷数组 FIXME 去作为再次寻找 008888 的字节数组
         */
        @NonNull
        private static byte[] cutOutPayloadArrayAfterHead008888(@NotNull byte[] currentCusDataArray) {
            while (true) {//FIXME currentCusDataArray 的前1024（长度完全可能小于1024）可能含有文件本身数据 008888(是否可以不用考虑呢?)，1024表示要找到我们一个段长的数据，段的长度就是1024
                if (currentCusDataArray.length >= AppConfig.CUS_DATA_SIZE) {
                    try {
                        mCusDataArrayBlockingQueue.offer(Arrays.copyOfRange(currentCusDataArray, 0, AppConfig.CUS_DATA_SIZE));
                    } catch (Exception e) {
                        Logger.t(TAG).e(e, "cutOutPayloadArrayAfterHead008888: ");
                    }
                    return Arrays.copyOfRange(currentCusDataArray, AppConfig.CUS_DATA_SIZE, currentCusDataArray.length);//返回超出 1024 部分的数据
                } else {//FIXME 拼接之后是否应该检查前 1024 中的 008888
                    currentCusDataArray = AppConfig.jointBuffer(currentCusDataArray, getNextValidPayload(), currentCusDataArray.length);//FIXME 取出包括 00 88 88 之后的所有内容，与下一段净荷 进行拼接 ,截取前1024
                }
            }
        }

        /**
         * 解析接收到的"原始UDP数据包"进而获得"n个(0~6)TS净荷包"（TS包是顺序排放的），我们只需要 0x86 开头的UDP包(广科院协议)  FIXME 已经"分析数据验证,函数严谨"
         *
         * @param udpDataPacket 原始的UDP数据报
         * @return "n个TS包的净荷"有序拼接起来的字节数组（长度是 n * 184 n∈[0,6]）
         */
        @Nullable
        private static byte[] parseUDPPacketToPayload(byte[] udpDataPacket) {
            if ((udpDataPacket == null) || (udpDataPacket.length != AppConfig.UDP_PACKET_SIZE) || (udpDataPacket[0] != AppConfig.UDP_HEAD_0x86_VALUE)) {//FIXME 5.筛选出 广科院0x86头 UDP数据报
                LogUtil.e(TAG, "UDP原始数据包为 null 或 长度不是1460 或 不是广科院0x86协议头,数据不符合要求进而被丢弃!");
                return null;
            }
            int lTsPacketAbandonedCount = 0;//被抛弃的ts包的数量
            byte[] lTsPayloadBuffer = new byte[AppConfig.TS_PAYLOAD_NO * AppConfig.TS_PAYLOAD_SIZE];//将用于承载TS净荷的字节数组Buffer 6*184
            CopyIndex lCopyIndex = new CopyIndex(AppConfig.UDP_PACKET_HEADER_SIZE);//FIXME 跳过前21字节头;UDP包以 0x86开头,前21字节是协议头(收到后可以删除),（1460-21-311=1128字节有效）    1128/6=188（每个TS包）-----------------------------------------------------------------------提取为常量必须深度测试
            byte lTSHead, lTSPid1, lTSPid2;//FIXME byte[]     取出当前同步字节，看是否等于0x 47 0F FE xx
            for (int i = 0; i < AppConfig.TS_PAYLOAD_NO; i++) {//FIXME 循环解析一个UDP包中的6个TS包
                lTSHead = arrayhelpers.GetInt8(udpDataPacket, lCopyIndex);
                lTSPid1 = arrayhelpers.GetInt8(udpDataPacket, lCopyIndex);
                lTSPid2 = arrayhelpers.GetInt8(udpDataPacket, lCopyIndex);
                lCopyIndex.AddIndex(1);//前面已经 +1 +1 +1,现在 +1,跳过每个TS包的0x47前4个字节的协议头
                if ((lTSHead != AppConfig.TS_HEAD_0x47_VALUE) || ((lTSPid1 & (byte) 0x1F) != (byte) 0x0F) || (lTSPid2 != (byte) 0xFE)) {//三个条件有一个不满足都要将当前ts包抛掉(即不满足TS包协议头)
                    lCopyIndex.AddIndex(AppConfig.TS_PAYLOAD_SIZE);//FIXME TS包以  0x47开头，前4个字节是协议头(0x47 xF FE xx)，其后是184字节净荷     4+184=188
                    lTsPacketAbandonedCount++;
                    continue;
                }
                System.arraycopy(arrayhelpers.GetBytes(udpDataPacket, AppConfig.TS_PAYLOAD_SIZE, lCopyIndex), 0, lTsPayloadBuffer, (i - lTsPacketAbandonedCount) * AppConfig.TS_PAYLOAD_SIZE, AppConfig.TS_PAYLOAD_SIZE);
            }
//            LogUtil.d(TAG, "一个UDP包中全部有效净荷拼接结果:" + stringhelpers.bytesToHexString(Arrays.copyOf(lTsPayloadBuffer, (AppConfig.TS_PAYLOAD_NO - lTsPacketAbandonedCount) * AppConfig.TS_PAYLOAD_SIZE)));
            return Arrays.copyOf(lTsPayloadBuffer, (AppConfig.TS_PAYLOAD_NO - lTsPacketAbandonedCount) * AppConfig.TS_PAYLOAD_SIZE);//FIXME 返回每个UDP包中n个有效TS包中的净荷拼接起来的字节数组
        }
    }


    /**
     * -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------FIXME "自定义数据消费者"线程----------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓----------------------------------------------------------------------------------
     */
    private static final class CusDataConsumerThread extends Thread {
        private static final String TAG = "TAG_CusDataConsumerThread";
        private static final Intent S_DATA_RECEIVE_INFO_INTENT = new Intent(AppConfig.ACTION_RECEIVE_DATA_INFO);
        private static final HashMap<String, String> FILE_RECEIVE_PROPORTION_MAP = new HashMap<>();
        private volatile boolean isNeedParsePayloadData;

        @Override
        public void run() {
            LogUtil.i(TAG, "################################################ 开始解析自定义协议数据 ##################################################");
            isNeedParsePayloadData = true;
            while (isNeedParsePayloadData) {
                try {
                    if (!mCusDataArrayBlockingQueue.isEmpty()) {
                        byte[] lCusDataBuffer = mCusDataArrayBlockingQueue.poll();
                        if ((lCusDataBuffer != null) && (lCusDataBuffer.length > 0)) {
                            parseCustomDataWithHead008888(lCusDataBuffer);
                        }
                    }
                } catch (Exception e) {
                    Logger.t(TAG).e(e, "run: ");
                }
            }
        }

        /**
         * 安全地 停止"自定义数据消费者线程"
         */
        private void stopCusDataConsumerThreadSafely() {
            isNeedParsePayloadData = false;
            mCusDataArrayBlockingQueue.clear();
            {//FIXME 必须 重置版本号 和 清空现在已接收集合
                sVersionNumberInConfigTable = -1;
                mCDRElementLongSparseArray.clear();
            }
            LogUtil.e(TAG, "################################################ 结束解析自定义协议数据 ################################################");
        }

        /**
         * 解析带有自定义协议头 008888 之后的 1024字节 的数据
         *
         * @param front1024OfCurrentPayloadArray 拼接好的以 008888 为头的 1024字节 的数据
         */
        private static void parseCustomDataWithHead008888(@NotNull byte[] front1024OfCurrentPayloadArray) {
//            LogUtil.d(TAG, "自定义协议数据AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:\t\t" + stringhelpers.bytesToHexString(front1024OfCurrentPayloadArray).toUpperCase());
            switch (front1024OfCurrentPayloadArray[AppConfig.TABLE_DISCRIMINATOR_INDEX]) {
                case AppConfig.CONFIG_TABLE_DISCRIMINATOR://FIXME 获得配置表,开始解析配置表  front1024OfCurrentPayloadArray 一定是00 88 88 xx 87开头
                    parseConfigTable(front1024OfCurrentPayloadArray);
                    break;
                case AppConfig.ELEMENT_TABLE_DISCRIMINATOR://FIXME 获得元素表,开始解析元素表 front1024OfCurrentPayloadArray 一定是00 88 88 xx 86开头
                    parseSectionData(front1024OfCurrentPayloadArray);
                    break;
                default:
            }
        }


        private static int sVersionNumberInConfigTable = -1;//接收到的配置表中解析出的 版本号
        private static final LongSparseArray<Element> mCDRElementLongSparseArray = new LongSparseArray<>();       //FIXME 存放 GUID 和 元素项

        private static final Intent S_CONFIG_TABLE_INTENT = new Intent(AppConfig.ACTION_RECEIVE_CONFIG_TABLE);

        /**
         * 解析配置表(table_id 一定是 0x87)函数(传递过来的参数一定就是 008888 开头的1024长度 数组) FIXME 相同版本号的配置表在解析成功后我们不再解析
         *
         * @param configTableBuffer 已经拼接好的配置表Buffer（008888xx87 开头,1024长度）
         */
        private static void parseConfigTable(byte[] configTableBuffer) {
            LogUtil.i(TAG, "收到配置表数据段,开始解析====>");
//            LogUtil.d(TAG, MessageFormat.format("配置表AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:\t\t{0}", stringhelpers.bytesToHexString(configTableBuffer).toUpperCase(Locale.getDefault())));
            CopyIndex lCopyIndex = new CopyIndex(AppConfig.TABLE_DISCRIMINATOR_INDEX + 1);//下标先偏移到 87位置 之后,再开始做解析工作
            int version_number = arrayhelpers.GetInt8(configTableBuffer, lCopyIndex);          //2.配置表：“版本号”
            if (sVersionNumberInConfigTable == version_number) {
                LogUtil.i(TAG, "已经成功解析过相同版本号的配置表数据段,退出当前解析!");
                return;
            }
            int section_length = arrayhelpers.GetInt16(configTableBuffer, lCopyIndex);         //3.配置表：“段长度”
//            if (section_length != (AppConfig.CUS_DATA_SIZE - AppConfig.TABLE_DISCRIMINATOR_INDEX - 1 - 1 - 2)) {//段长度必定是 1024 - 4 - 1 - 1 - 2 = 1016
//                LogUtil.e(TAG, "根据配置表中解析出 段长度 是 " + section_length + ",不是1024 - 4 - 1 - 1 - 2 = 1016,退出配置表解析操作!");
//                return;
//            }
            int section_number = arrayhelpers.GetInt16(configTableBuffer, lCopyIndex);         //4.配置表：“当前段号”
//            if (section_number != 0) {                LogUtil.e(TAG, "根据配置表中解析出 当前段号 不是0x 0000,退出配置表解析操作!");                return;            }
            int section_count = arrayhelpers.GetInt16(configTableBuffer, lCopyIndex);          //5.配置表：“段数量”
//            if (section_count != 1) {                LogUtil.e(TAG, "根据配置表中解析出 段数量 不是0x 0001,退出配置表解析操作!");                return;            }
            if (section_number >= section_count) {
                LogUtil.e(TAG, "根据配置表中解析出 当前段号 >= 段数量,退出配置表解析操作!");
                return;
            }
            lCopyIndex.AddIndex(1 + 1);//保留
            int element_count = arrayhelpers.GetInt8(configTableBuffer, lCopyIndex);           //6.配置表：“元素个数”s
            if (element_count <= 0) {
                LogUtil.e(TAG, "根据配置表中解析出 元素个数 <=0,没有必要接收数据,退出配置表解析操作!");
                return;
            }
            //校验CRC
            lCopyIndex.setIndex(AppConfig.TABLE_DISCRIMINATOR_INDEX + 1 + 1 + 2);
            int calcCRC = calculateCRC(arrayhelpers.GetBytes(configTableBuffer, section_length - 4, lCopyIndex));//计算CRC
            int readCRC = arrayhelpers.GetInt32(configTableBuffer, lCopyIndex);//读取CRC
            if (calcCRC != readCRC) {//两处CRC不一致,校验失败
                LogUtil.e(TAG, "根据配置表中解析出 CRC 与 根据配置表中读出 CRC 不一致,退出配置表解析操作!");
                return;
            }
            //一切条件都满足:先更新版本号,接着向Map中写入"待接收的元素信息"从而开始解析"元素表"
            sVersionNumberInConfigTable = version_number;//FIXME 在前面全部解析通过后 更新接收到的配置表版本号
            AppConfig.getLocalBroadcastManager().sendBroadcast(S_CONFIG_TABLE_INTENT);//1.应该先发送广播"请求清空分类文件集合"
            FileUtils.deleteFilesInDir(AppConfig.MEDIA_FILE_FOLDER);//2.再清空本程序多媒体文件夹下的文件

            lCopyIndex.setIndex(AppConfig.TABLE_DISCRIMINATOR_INDEX + 1 + 1 + 2 + 2 + 2 + 1 + 1 + 1);
            long lElementGuid;
            int element_format;
            mCDRElementLongSparseArray.clear();
            FILE_RECEIVE_PROPORTION_MAP.clear();
            for (int i = 0; i < element_count; i++) {
                lElementGuid = arrayhelpers.GetInt32(configTableBuffer, lCopyIndex);
                lCopyIndex.AddIndex(1);//类型
                element_format = arrayhelpers.GetInt8(configTableBuffer, lCopyIndex);
                if (mCDRElementLongSparseArray.indexOfKey(lElementGuid) < 0) {
                    Element lElement = new Element();
                    lElement.setVersionNumber(version_number);
                    if (element_format < ELEMENT_FORMAT.length) lElement.setFileName(MessageFormat.format("{0}{1}", lElementGuid, ELEMENT_FORMAT[element_format]));
                    mCDRElementLongSparseArray.put(lElementGuid, lElement);
                    FILE_RECEIVE_PROPORTION_MAP.put(lElement.getFileName(), "0");
                    LogUtil.i(TAG, MessageFormat.format("根据配置表中解析出 待接收文件:{0}", lElement.getFileName()));
                }
                lCopyIndex.AddIndex(1 + 1);//保留位
            }//不用判断 element_count == mCDRElementLongSparseArray.size() ?
            AppConfig.getLocalBroadcastManager().sendBroadcast(S_DATA_RECEIVE_INFO_INTENT.putExtra(AppConfig.KEY_DATA_RECEIVE_INFO, MessageFormat.format("成功接收到'配置表({0})',接收文件中...", version_number)));//成功接收到'配置表(10)'
            LogUtil.i(TAG, "################################################ 解析配置表成功! ################################################");
        }


        private static final String[] ELEMENT_FORMAT = {".txt", ".png", ".bmp", ".jpg", ".gif", ".avi", ".mp3", ".mp4"};//.3gp  .wav    .mkv    .mov    .mpeg   .flv       //本地广播
        private static final Intent S_ELEMENT_TABLE_INTENT = new Intent(AppConfig.ACTION_RECEIVE_ELEMENT_TABLE);

        /**
         * 解析段的数据,并写入文件; 长度已经事先拼接好了,不用再考虑解析完成后剩余内容的拼接问题
         *
         * @param sectionBuffer 段的Buffer字节数组
         */
        private static void parseSectionData(byte[] sectionBuffer) {
//            LogUtil.d(TAG, MessageFormat.format("元素表AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:\t\t{0}", stringhelpers.bytesToHexString(sectionBuffer).toUpperCase(Locale.getDefault())));
            if (mCDRElementLongSparseArray.size() <= 0) {
                LogUtil.d(TAG, "接收到元素表数据段,但程序启动后还未成功解析过配置表,直接将当前数据段丢弃.");
                return;
            }

            CopyIndex lCopyIndex = new CopyIndex(AppConfig.TABLE_DISCRIMINATOR_INDEX + 1);//下标先偏移到 86位置 之后,再开始做解析工作
            int version_number = arrayhelpers.GetInt8(sectionBuffer, lCopyIndex);
            if (version_number != sVersionNumberInConfigTable) {
                LogUtil.e(TAG, MessageFormat.format("接收到的元素表中解析出 版本号 与配置表中解出的版本号不一致,退出元素表解析工作.版本号 {0} != {1}", version_number, sVersionNumberInConfigTable));
                return;
            }
            int section_length = arrayhelpers.GetInt16(sectionBuffer, lCopyIndex);
//            if (section_length != (AppConfig.CUS_DATA_SIZE - AppConfig.TABLE_DISCRIMINATOR_INDEX - 1 - 1 - 2)) {//段长度必定是 1024 - 4 - 1 - 1 - 2 = 1016
//                LogUtil.e(TAG, "根据元素表中解析出 段长度 是 " + section_length + ",不是1024 - 4 - 1 - 1 - 2 = 1016,退出元素表解析操作!");
//                return;
//            }
            int section_number = arrayhelpers.GetInt16(sectionBuffer, lCopyIndex);
//            if (section_number == 20) LogUtil.d(TAG, MessageFormat.format("元素表AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABBBBBBBBBBBBBBBBBBB:\t\t{0}\t{1}", mCusDataArrayBlockingQueue.size(), stringhelpers.bytesToHexString(sectionBuffer).toUpperCase(Locale.getDefault())));
            int section_count = arrayhelpers.GetInt16(sectionBuffer, lCopyIndex);
            if (section_number >= section_count) {
                LogUtil.e(TAG, "接收到的元素表中解析出 当前段号 大于等于 段数量,退出元素表解析操作.");
                return;
            }
            long element_guid = arrayhelpers.GetInt32(sectionBuffer, lCopyIndex);
            Element lElement = mCDRElementLongSparseArray.get(element_guid);//根据 元素GUID 获取元素对象
            List<Integer> sSectionsNumberList;//FIXME 用于存放当前数据段所对应元素的"所有段号"
            if (lElement != null) {
                if (version_number != lElement.getVersionNumber()) {
                    LogUtil.e(TAG, "根据元素表中解析出 版本号与元素版本号不一致,退出元素段解析工作.");
                    return;
                } else {
                    sSectionsNumberList = lElement.getSectionsNumberList();//------期望逻辑------
                    if (sSectionsNumberList.contains(section_number)) {
                        LogUtil.d(TAG, MessageFormat.format("已经成功接收过当前段号的数据,不再重复解析,退出元素表解析操作.GUID:{0},当前段号:{1}", element_guid, String.valueOf(section_number)));
                        return;
                    }
                }
            } else {
                LogUtil.e(TAG, "在映射表的Key中找不到 通过当前元素段解析出的元素GUID,退出元素段的解析工作!");
                return;
            }
//            int element_type = arrayhelpers.GetInt8(sectionBuffer, lCopyIndex);
            lCopyIndex.AddIndex(1);//跳过 element_type 元素类型
            int element_format = arrayhelpers.GetInt8(sectionBuffer, lCopyIndex);
            if ((element_format > (ELEMENT_FORMAT.length - 1))) {
                LogUtil.e(TAG, MessageFormat.format("根据元素表中解析出 元素格式 不是预定义文件数据格式{0}", Arrays.toString(ELEMENT_FORMAT)));
                return;
            }
            int section_data_length = arrayhelpers.GetInt32(sectionBuffer, lCopyIndex);
            if ((section_data_length <= 0) || (section_data_length > (AppConfig.CUS_DATA_SIZE - (AppConfig.TABLE_DISCRIMINATOR_INDEX + 1 + 1 + 2 + 2 + 2 + 4 + 1 + 1 + 4 + 4)))) {//有效文件数据只有 998 字节
                LogUtil.e(TAG, "根据元素表中解析出 其中有效文件数据大小不符合(0,998]字节要求,退出元素表解析操作!");
                return;
            }
            //校验CRC
            lCopyIndex.setIndex(AppConfig.TABLE_DISCRIMINATOR_INDEX + 1 + 1 + 2);
            int calcCRC = calculateCRC(arrayhelpers.GetBytes(sectionBuffer, section_length - 4, lCopyIndex));//计算所得CRC
            int readCRC = arrayhelpers.GetInt32(sectionBuffer, lCopyIndex);//读取所得CRC
            if (calcCRC != readCRC) {//计算所得CRC 与 读取所得CRC 不一致
                LogUtil.e(TAG, MessageFormat.format("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABBBBBB CRC校验错误.GUID = {0} 当前段号 = {1} 段落数据:{2}", element_guid, section_number, stringhelpers.bytesToHexString(sectionBuffer).toUpperCase(Locale.getDefault())));
                return;
            }

            //FIXME 将当前元素段中解析出的文件数据写入磁盘
            final File lFile = new File(AppConfig.MEDIA_FILE_FOLDER, MessageFormat.format("{0}{1}", element_guid, ELEMENT_FORMAT[element_format]));
            FileUtils.createOrExistsFile(lFile);//判断文件是否存在,不存在则创建文件
            try (RandomAccessFile lRandomAccessFile = new RandomAccessFile(lFile, "rw")) {
                lRandomAccessFile.seek(section_number * 998L);//偏移工作(断点续写):(AppConfig.CUS_DATA_SIZE - (AppConfig.TABLE_DISCRIMINATOR_INDEX + 1 + 1 + 2 + 2 + 2 + 4 + 1 + 1 + 4 + 4)) = 998
                lCopyIndex.setIndex(AppConfig.TABLE_DISCRIMINATOR_INDEX + 1 + 1 + 2 + 2 + 2 + 4 + 1 + 1 + 4);
                lRandomAccessFile.write(arrayhelpers.GetBytes(sectionBuffer, section_data_length, lCopyIndex));//FIXME 当前数据长度要改动
                lRandomAccessFile.close();
                sSectionsNumberList.add(section_number);
                LogUtil.e(TAG, MessageFormat.format("GUID:{0},当前段号:{1},接收比例:{2}/{3}", String.valueOf(element_guid), String.valueOf(section_number), String.valueOf(sSectionsNumberList.size()), String.valueOf(section_count)));
                {//FIXME 发送广播"更新文件接收信息"
                    FILE_RECEIVE_PROPORTION_MAP.put(lFile.getName(), MessageFormat.format("{0}/{1}", String.valueOf(sSectionsNumberList.size()), String.valueOf(section_count)));
                    StringBuilder lAllFileReceiveProportionInfo = new StringBuilder();
                    Set<Map.Entry<String, String>> lFileReceiveProportionEntrySet = FILE_RECEIVE_PROPORTION_MAP.entrySet();
                    for (Map.Entry<String, String> fileReceiveProportionMapEntry : lFileReceiveProportionEntrySet) {
                        lAllFileReceiveProportionInfo.append(fileReceiveProportionMapEntry.getKey()).append("\t\t\t").append(fileReceiveProportionMapEntry.getValue()).append("\n");
                    }
                    AppConfig.getLocalBroadcastManager().sendBroadcast(S_DATA_RECEIVE_INFO_INTENT.putExtra(AppConfig.KEY_DATA_RECEIVE_INFO, lAllFileReceiveProportionInfo.toString()));//发送"数据接收比例"广播
                }
                if (sSectionsNumberList.size() == section_count) {
                    LogUtil.i(TAG, MessageFormat.format("########################################################## 当前文件\t\t{0}\t\t接收完成 ##########################################################", lFile.getName()));
                    AppConfig.getLocalBroadcastManager().sendBroadcast(S_ELEMENT_TABLE_INTENT.putExtra(AppConfig.KEY_FILE_NAME, lFile.getName()));
                }
            } catch (Exception e) {
                Logger.t(TAG).e(e, "parseSectionData0: ");
            }
        }

        private static final CopyIndex sCopyIndex = new CopyIndex(0);//FIXME 被参考将上面解析UDP成净荷的 CopyIndex 改造成常量

        /**
         * 自定义的计算CRC的方法
         *
         * @param bufferToCalcCRC 要计算CRC的Buffer字节数组
         * @return 自定义的CRC值
         */
        private static int calculateCRC(@NotNull byte[] bufferToCalcCRC) {
            byte[] crcBuffer = {(byte) 0x12, (byte) 0x34, bufferToCalcCRC[0], bufferToCalcCRC[1]};
            sCopyIndex.Reset();
            return arrayhelpers.GetInt32(crcBuffer, sCopyIndex);
        }
    }
}
