package com.dexin.ad_system.service;

import android.app.Service;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.util.LongSparseArray;

import com.blankj.utilcode.util.FileUtils;
import com.dexin.ad_system.R;
import com.dexin.ad_system.app.AppConfig;
import com.dexin.ad_system.app.CustomApplication;
import com.dexin.ad_system.cdr.CDRElement;
import com.dexin.ad_system.util.LogUtil;
import com.dexin.utilities.CopyIndex;
import com.dexin.utilities.arrayhelpers;
import com.dexin.utilities.stringhelpers;
import com.orhanobut.logger.Logger;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;

public final class LongRunningUDPService extends Service {
    private PayloadProducerThread mPayloadProducerThread;
    private static final ArrayBlockingQueue<byte[]> mPayloadArrayBlockingQueue = new ArrayBlockingQueue<>(AppConfig.ARRAY_BLOCKING_QUEUE_CAPACITY);//存放"(0~6)*184净荷"的阻塞队列
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
            mPayloadProducerThread.stopPayloadProducerThreadSafely();
            mPayloadProducerThread = null;
        }
        mPayloadArrayBlockingQueue.clear();
        if (mPayloadConsumerThread != null) {
            mPayloadConsumerThread.stopPayloadConsumerThreadSafely();
            mPayloadConsumerThread = null;
        }
        mCusDataArrayBlockingQueue.clear();
        if (mCusDataConsumerThread != null) {
            mCusDataConsumerThread.stopCusDataConsumerThreadSafely();
            mCusDataConsumerThread = null;
        }
        super.onDestroy();
    }

    /**
     * -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------TODO 净荷生产者线程------------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓------------------------------------------------------------------------------------------
     */
    private static final class PayloadProducerThread extends Thread {
        private static final String TAG = "TAG_PayloadProducerThread";
        private volatile boolean isNeedReceiveUDP;//是否需要接收UDP数据包（没有启动的时候不需要接收）
        private byte[] mUdpDataContainer;
        private DatagramSocket mDatagramSocket;//单播套接字
        private DatagramPacket mDatagramPacket;//单播数据报

        @Override
        public void run() {
            LogUtil.e(TAG, "################################################ 开始接收CDR_Wifi_UDP数据包 ##################################################");
            isNeedReceiveUDP = true;//标志"重新开始接收CDR_Wifi_UDP数据包"
            try {
                if (mUdpDataContainer == null) mUdpDataContainer = new byte[AppConfig.UDP_PACKET_SIZE];//1460包长
                if (mDatagramSocket == null) {
                    mDatagramSocket = new DatagramSocket(null);
                    mDatagramSocket.setReuseAddress(true);//地址复用
                    mDatagramSocket.bind(new InetSocketAddress(AppConfig.PORT));//绑定端口
                }
                if (mDatagramPacket == null) mDatagramPacket = new DatagramPacket(mUdpDataContainer, mUdpDataContainer.length);//建立一个指定缓冲区大小的数据包
                while (isNeedReceiveUDP) {
                    try {
//                        mDatagramSocket.setSoTimeout(8000);
                        mDatagramSocket.receive(mDatagramPacket);//将单播套接字收到的UDP数据包存放于datagramPacket中(会阻塞)
                        mUdpDataContainer = mDatagramPacket.getData();//UDP数据包
//                        LogUtil.d(TAG, "UDP原始数据包AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:\t\t" + stringhelpers.bytesToHexString(mUdpDataContainer).toUpperCase());
                        if ((mUdpDataContainer != null) && (mUdpDataContainer.length > 0)) {
                            mUdpDataContainer = parseUDPPacketToPayload(mUdpDataContainer);//解析UDP数据包后获得UDP净荷
//                            LogUtil.d(TAG, "TS净荷包     AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:\t\t" + stringhelpers.bytesToHexString(mUdpDataContainer).toUpperCase());
                            if ((mUdpDataContainer != null) && (mUdpDataContainer.length > 0)) {
                                mPayloadArrayBlockingQueue.put(mUdpDataContainer);//FIXME 是否应该考虑将过滤净荷的逻辑放于出队时
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
                mUdpDataContainer = null;//必须置null
            }
        }

        /**
         * 安全地 停止"净荷生产者线程"
         */
        private void stopPayloadProducerThreadSafely() {
            isNeedReceiveUDP = false;
            mPayloadArrayBlockingQueue.clear();
            LogUtil.e(TAG, "##################################### CDRWifiReceive 服务关闭,结束接收CDR_Wifi_UDP数据包 #######################################");
        }

        /**
         * 解析接收到的"原始UDP数据包"进而获得"n个(0~6)TS净荷包"（TS包是顺序排放的），我们只需要 0x86 开头的UDP包(广科院协议)
         * FIXME 已经"分析数据验证,函数严谨"
         *
         * @param udpDataPacket 原始的UDP数据报
         * @return "n个TS包的净荷"有序拼接起来的字节数组（长度是 n * 184 n∈[0,6]）
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
                if (udpDataPacket[0] != AppConfig.UDP_HEAD_0x86_VALUE) {//这个0x86是广科院的头，注意与自定义 ELEMENT_TABLE_DISCRIMINATOR 区分开   //TODO 5.筛选出 广科院0x86头 UDP数据报,(如果不是0x86开头的UDP包则抛弃掉)
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
     * ------------------------------------------------------------------------------------TODO 净荷消费者线程------------------------------------------------------------------------------------
     * ------------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓------------------------------------------------------------------------------------
     */
    private static final class PayloadConsumerThread extends Thread {
        private static final String TAG = "TAG_PayloadConsumerThread";
        private volatile boolean isNeedParsePayloadData;
        private int mHead008888Index;//记录"自定义协议头 008888"的下标
        private byte[] mCurrentCusDataBuffer = {};//程序刚启动时无数据

        @Override
        public void run() {
            LogUtil.e(TAG, "################################################ 开始解析净荷数据 ################################################");
            isNeedParsePayloadData = true;
            try {
                while (isNeedParsePayloadData) {
                    mHead008888Index = AppConfig.indexOfSubBuffer(0, mCurrentCusDataBuffer.length, mCurrentCusDataBuffer, AppConfig.sHead008888Array);//TODO 一、在当前净荷数组中寻找 008888 的下标
                    while (mHead008888Index < 0) {//FIXME （完全可能还是找不到,因为有大量的填充数据"0000000000000000" 和 "FFFFFFFFFFFFFFF"）,所以需要一直向后拼接寻找 008888 ,直到找到（mHead008888Index >= 0）为止
                        mCurrentCusDataBuffer = AppConfig.jointBuffer(mCurrentCusDataBuffer, getNextValidPayload(), AppConfig.sHead008888Array.length - 1);//FIXME 基于上面的原因，长度也不一定是1106
                        mHead008888Index = AppConfig.indexOfSubBuffer(0, mCurrentCusDataBuffer.length, mCurrentCusDataBuffer, AppConfig.sHead008888Array);
                    }//TODO 经历了循环之后，一定可以在 mCurrentCusDataBuffer 中找到 008888 ,退出循环的时候 mHead008888Index >= 0 一定成立
                    mCurrentCusDataBuffer = cutOutPayloadArrayAfterHead008888(Arrays.copyOfRange(mCurrentCusDataBuffer, mHead008888Index, mCurrentCusDataBuffer.length));//TODO 传递的是以"008888"头为起始的数组作为参数，返回的是"超出部分的净荷内容"来作为"当前的净荷内容"去继续查找,拼接
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
            mPayloadArrayBlockingQueue.clear();
            mCusDataArrayBlockingQueue.clear();
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
         * 判断当前以 008888 开头的净荷数组 是否满足数据段长度：1.如果满足数据段长度，解析段并返回超出部分的净荷     2.如果不满足数据长度，就与下一段净荷进行拼接，然后截取出自定义数据段 并 返回超出部分的净荷
         *
         * @param currentCusDataArray 当前自定义协议数据(绝对是以 008888 开头的)
         * @return 超出部分的净荷数组 FIXME 去作为再次寻找 008888 的字节数组
         */
        @NonNull
        private static byte[] cutOutPayloadArrayAfterHead008888(@NotNull byte[] currentCusDataArray) {
            while (true) {//TODO currentCusDataArray 的前1024（长度完全可能小于1024）可能含有文件本身数据 008888(是否可以不用考虑呢?)，1024表示要找到我们一个段长的数据，段的长度就是1024
                if (currentCusDataArray.length >= AppConfig.CUS_DATA_SIZE) {
                    try {
                        mCusDataArrayBlockingQueue.put(Arrays.copyOfRange(currentCusDataArray, 0, AppConfig.CUS_DATA_SIZE));
                    } catch (InterruptedException e) {
                        Logger.t(TAG).e(e, "cutOutPayloadArrayAfterHead008888: ");
                    }
                    return Arrays.copyOfRange(currentCusDataArray, AppConfig.CUS_DATA_SIZE, currentCusDataArray.length);//返回超出 1024 部分的数据
                } else {//FIXME 拼接之后是否应该检查前 1024 中的 008888
                    currentCusDataArray = AppConfig.jointBuffer(currentCusDataArray, getNextValidPayload(), currentCusDataArray.length);//TODO 取出包括 00 88 88 之后的所有内容，与下一段净荷 进行拼接 ,截取前1024
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
                    parseCustomDataWithHead008888(mCusDataArrayBlockingQueue.take());
                } catch (InterruptedException e) {
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
                case AppConfig.CONFIG_TABLE_DISCRIMINATOR://TODO 获得配置表,开始解析配置表  front1024OfCurrentPayloadArray 一定是00 88 88 xx 87开头
                    parseConfigTable(front1024OfCurrentPayloadArray);
                    break;
                case AppConfig.ELEMENT_TABLE_DISCRIMINATOR://TODO 获得元素表,开始解析元素表 front1024OfCurrentPayloadArray 一定是00 88 88 xx 86开头
//                    parseSectionData(front1024OfCurrentPayloadArray);
                    break;
                default:
            }
        }


        private static int sVersionNumberInConfigTable = -1;//接收到的配置表中解析出的 版本号
        private static List<Long> sGuidList = new ArrayList<>();                     //配置表：“元素guid数组”    文件数据模型（guid）
        private static final LongSparseArray<CDRElement> mCDRElementLongSparseArray = new LongSparseArray<>();       //TODO 存放 GUID 和 元素项
        private static final Timer mTimer = new Timer();//定时器
        private static final String[] elementFormat = {".txt", ".png", ".bmp", ".jpg", ".gif", ".avi", ".mp3", ".mp4"};      //.3gp  .wav    .mkv    .mov    .mpeg   .flv       //本地广播

        private static long sElementGuid;//元素GUID
        private static final List<Long> sElementGuidList = new ArrayList<>();//元素GUID列表

        /**
         * 解析配置表(table_id 一定是 0x87)函数(传递过来的参数一定就是 008888 开头的1024长度 数组) FIXME 相同版本号的配置表在解析成功后我们不再解析
         *
         * @param configTableBuffer 已经拼接好的配置表Buffer（008888xx87 开头,1024长度）
         */
        private static void parseConfigTable(byte[] configTableBuffer) {
            LogUtil.e(TAG, "收到配置表数据段,开始解析====>");
            LogUtil.d(TAG, MessageFormat.format("配置表AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:\t\t{0}", stringhelpers.bytesToHexString(configTableBuffer).toUpperCase(Locale.getDefault())));

            CopyIndex lCopyIndex = new CopyIndex(AppConfig.TABLE_DISCRIMINATOR_INDEX + 1);//下标先偏移到 87位置 之后,再开始做解析工作
            int version_number = arrayhelpers.GetInt8(configTableBuffer, lCopyIndex);          //2.配置表：“版本号”
//            if (sVersionNumberInConfigTable != version_number) {//通过配置表解出服务端发送的数据 有变化---------------------------------------TODO 应该放到最后----------------------------------
//                sVersionNumberInConfigTable = version_number;//更新接收到的配置表版本号
//                clearMediaListAndDeleteMediaFolder();//FIXME 先是发送安卓广播 清空分类文件集合（接着根据广播设置UI）    ；   然后  删除本程序的媒体文件夹下的文件
//                sGuidList.clear();//清空原guidList,表示是要重新接收新文件
//            } else {//根据配置表解出的是相同版本号,停止解析
//                LogUtil.e(TAG, "已经成功解析过相同版本号的配置表数据段,退出当前解析!");
//                return;
//            }
            int section_length = arrayhelpers.GetInt16(configTableBuffer, lCopyIndex);         //3.配置表：“段长度”
            if (section_length != (AppConfig.CUS_DATA_SIZE - AppConfig.TABLE_DISCRIMINATOR_INDEX - 1 - 1 - 2)) {//段长度必定是 1024 - 4 - 1 - 1 - 2 = 1016
                LogUtil.e(TAG, "根据配置表中解析出 段长度 不是1024 - 4 - 1 - 1 - 2 = 1016,退出配置表解析操作!");
                return;
            }
            int section_number = arrayhelpers.GetInt16(configTableBuffer, lCopyIndex);         //4.配置表：“当前段号”
//            if (section_number != 0) {
//                LogUtil.e(TAG, "根据配置表中解析出 当前段号 不是0x 0000,退出配置表解析操作!");
//                return;
//            }
            int section_count = arrayhelpers.GetInt16(configTableBuffer, lCopyIndex);          //5.配置表：“段数量”
//            if (section_count != 1) {
//                LogUtil.e(TAG, "根据配置表中解析出 段数量 不是0x 0001,退出配置表解析操作!");
//                return;
//            }
            lCopyIndex.AddIndex(1 + 1);//保留
            int element_count = arrayhelpers.GetInt8(configTableBuffer, lCopyIndex);           //6.配置表：“元素个数”s
            if (element_count == 0) {
                LogUtil.e(TAG, "根据配置表中解析出 元素个数 为0,没有必要接收数据,退出配置表解析操作!");
                return;
            }
            sElementGuidList.clear();
            for (int i = 0; i < element_count; i++) {
                sElementGuid = arrayhelpers.GetInt32(configTableBuffer, lCopyIndex);
                if (!sElementGuidList.contains(sElementGuid)) sElementGuidList.add(sElementGuid);
                lCopyIndex.AddIndex(1 + 1 + 1 + 1);
            }
            if (element_count != sElementGuidList.size()) {
                LogUtil.e(TAG, "根据配置表中解析出 元素个数 不等于for循环中获取的 元素个数,退出配置表解析操作!");
                return;
            }

            //校验CRC
            lCopyIndex.Reset();
            lCopyIndex.AddIndex((AppConfig.TABLE_DISCRIMINATOR_INDEX + 1 + 1 + 2));
            int calcCRC = calculateCRC(arrayhelpers.GetBytes(configTableBuffer, section_length - 4, lCopyIndex));//计算CRC
            int readCRC = arrayhelpers.GetInt32(configTableBuffer, lCopyIndex);//读取CRC
            if (calcCRC != readCRC) {//两处CRC不一致,校验失败
                LogUtil.e(TAG, "根据配置表中解析出 CRC 与 根据配置表中读出 CRC 不一致,退出配置表解析操作!");
                return;
            }

            mCDRElementLongSparseArray.clear();
            sGuidList = sElementGuidList;
            for (long guid : sGuidList) {
                LogUtil.i(TAG, MessageFormat.format("文件GUID:{0}", guid));
                CDRElement cdrElement = new CDRElement();
                cdrElement.setVersionNumber(version_number);
                mCDRElementLongSparseArray.put(guid, cdrElement);
            }

            if (sVersionNumberInConfigTable != version_number) {//通过配置表解出服务端发送的数据 有变化---------------------------------------TODO 应该放到最后----------------------------------
                sVersionNumberInConfigTable = version_number;//更新接收到的配置表版本号
                clearMediaListAndDeleteMediaFolder();//FIXME 先是发送安卓广播 清空分类文件集合（接着根据广播设置UI）    ；   然后  删除本程序的媒体文件夹下的文件
                sGuidList.clear();//清空原guidList,表示是要重新接收新文件
                LogUtil.e(TAG, MessageFormat.format("解析配置表成功!\t\t{0}={1}?", sElementGuidList.size(), element_count));
            } else {//根据配置表解出的是相同版本号,停止解析
                LogUtil.e(TAG, "已经成功解析过相同版本号的配置表数据段,退出当前解析!");
            }
        }

        /**
         * 解析段的数据,并写入文件; 长度已经事先拼接好了,不用再考虑解析完成后剩余内容的拼接问题
         *
         * @param sectionBuffer 段的Buffer字节数组
         */
        private static void parseSectionData(byte[] sectionBuffer) {
            LogUtil.e(TAG, "接收到元素表数据段,开始解析====>");
            if (mCDRElementLongSparseArray.size() <= 0) {
                LogUtil.d(TAG, "接收到元素表数据段,但程序启动后还未成功解析过配置表,等待接收解析配置表,暂不解析数据段直接丢弃.");
                return;
            }

            CopyIndex lCopyIndex = new CopyIndex(AppConfig.TABLE_DISCRIMINATOR_INDEX + 1);//下标先偏移到 86位置 之后,再开始做解析工作

            int version_number = arrayhelpers.GetInt8(sectionBuffer, lCopyIndex);
            if (version_number != sVersionNumberInConfigTable) {
                LogUtil.e(TAG, MessageFormat.format("接收到的元素表中解析出 版本号 与配置表中解出的版本号不一致,退出元素表解析工作.{0}!={1}", version_number, sVersionNumberInConfigTable));
                return;
            }

            int section_length = arrayhelpers.GetInt16(sectionBuffer, lCopyIndex);
            if (section_length <= (2 + 2 + 4 + 1 + 1 + 4 + 4)) {
                LogUtil.e(TAG, MessageFormat.format("接收到的元素表段长度不足 2 + 2 + 4 + 1 + 1 + 4 + 4 = 18,退出元素表解析操作:{0}", stringhelpers.bytesToHexString(sectionBuffer).toUpperCase(Locale.getDefault())));
                return;
            }

            int section_number = arrayhelpers.GetInt16(sectionBuffer, lCopyIndex);
            if (section_number < 0) {
                LogUtil.e(TAG, "接收到的元素表中解析出 当前段号 小于0,退出元素表解析操作.");
                return;
            }

            int section_count = arrayhelpers.GetInt16(sectionBuffer, lCopyIndex);
            if (section_count < 0) {
                LogUtil.e(TAG, "接收到的元素表中解析出 段的数量 小于0，退出元素表解析操作.");
                return;
            }
            if (section_number >= section_count) {
                LogUtil.e(TAG, "接收到的元素表中解析出 段号 大于等于 段数量,退出元素表解析操作.");
                return;
            }

            long element_guid = arrayhelpers.GetInt32(sectionBuffer, lCopyIndex);
            if (element_guid < 0) {
                LogUtil.e(TAG, MessageFormat.format("接收到的元素表中解析出 元素表解析出元素guid小于0,退出元素表解析操作.GUID:{0}元素段:{1}", element_guid, stringhelpers.bytesToHexString(sectionBuffer).toUpperCase(Locale.getDefault())));
                return;
            }
//            if (mCDRElementLongSparseArray.indexOfKey(element_guid) < 0) {//FIXME 前面已经有 version_number 过滤条件
//                LogUtil.d(TAG, "收到 新版本的数据段 但是没有收到 新版本的配置表，不做解析！");
//                return;
//            }

            List<Integer> sectionsNumberList = null;
            CDRElement cdrElement = mCDRElementLongSparseArray.get(element_guid);        //根据 元素GUID 获取元素段
            if (cdrElement != null) {
                if (cdrElement.getVersionNumber() != version_number) {
                    LogUtil.d(TAG, "解析所得的版本号与CDR元素项版本号不符，退出元素表解析操作。");
                    return;
                }
                sectionsNumberList = cdrElement.getSectionsNumberList();
            }
            if ((sectionsNumberList != null) && (sectionsNumberList.contains(section_number))) {                                                      // 如果“一个文件中已经收取过当前段号”，就不再收取
                LogUtil.d(TAG, "已经接收过当前段号的数据，不再重复解析，退出元素表解析操作。");
                return;
            }
            if (sectionsNumberList != null) {
                LogUtil.d(TAG, MessageFormat.format("段号-->列表Size：{0} 段号-->列表：{1}", sectionsNumberList.size(), sectionsNumberList));
            }
            int element_type = arrayhelpers.GetInt8(sectionBuffer, lCopyIndex);
            if (element_type < 0) {
                LogUtil.d(TAG, "元素表解析出元素类型小于0，退出元素表解析操作！");
                return;
            }

            int element_format = arrayhelpers.GetInt8(sectionBuffer, lCopyIndex);
            if (element_format < 0) {
                LogUtil.d(TAG, "元素表解析出元素格式小于0，退出元素表解析操作！");
                return;
            }

            int section_data_length = arrayhelpers.GetInt32(sectionBuffer, lCopyIndex);
            if ((section_data_length <= 0) || (section_data_length > 998)) {
                LogUtil.d(TAG, "元素表解析出元素大小不符合要求(0,998]，退出元素表解析操作！");
                return;
            }
            byte[] section_data = arrayhelpers.GetBytes(sectionBuffer, section_data_length, lCopyIndex);
            if (section_data.length <= 0) {
                LogUtil.d(TAG, "元素表解析出元素数据长度为负，退出元素表解析操作！");
                return;
            }

            //获取CRCBuffer
            lCopyIndex.Reset();
            lCopyIndex.AddIndex(4 + 4);
            byte[] CRCBuffer = arrayhelpers.GetBytes(sectionBuffer, section_length - 4, lCopyIndex);
            int calcCRC = calculateCRC(CRCBuffer);

            int crc = arrayhelpers.GetInt32(sectionBuffer, lCopyIndex);

            //校验CRC
            if (calcCRC != crc) {
                LogUtil.e(TAG, MessageFormat.format("CRC校验错误的段落->   GUID={0}\t段号={1}", element_guid, section_number));
                LogUtil.d(TAG, MessageFormat.format("CRC校验错误的Buffer：{0}", stringhelpers.bytesToHexString(sectionBuffer).toUpperCase()));
                return;
            }

            //TODO 将本段数据写入到磁盘
            if ((element_format < 0) || (element_format > (elementFormat.length - 1))) {
                LogUtil.d(TAG, "元素格式下标越界，退出元素表解析。");
                return;
            }
            String extention = elementFormat[element_format];
            //TODO 创建 当前文件
            File file = new File(AppConfig.FILE_FOLDER, element_guid + extention);
            FileUtils.createOrExistsFile(file);

            RandomAccessFile randomAccessFile;
            try {
                randomAccessFile = new RandomAccessFile(file, "rw");
                randomAccessFile.seek(section_number * 998);                            //偏移工作    1024-4-22=998
                randomAccessFile.write(section_data, 0, section_data_length);           //TODO 当前数据长度要改动

                if (sectionsNumberList != null) {
                    sectionsNumberList.add(section_number);
                    LogUtil.d(TAG, "写文件添加段号-->" + section_number);
                    LogUtil.e(TAG, "GUID：" + element_guid + "，接收比例：" + sectionsNumberList.size() + "/" + section_count);

                    if (sectionsNumberList.size() == section_count) {
                        LogUtil.i(TAG, "######################################################################当前文件\t\t" + element_guid + extention + "\t\t接收完成#####################################################################");
                        mTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                Intent intent = new Intent(AppConfig.LOAD_FILE_OR_DELETE_MEDIA_LIST);                                                                                                               //TODO 删除多媒体文件夹 和 清除分类文件集合 的逻辑
                                intent.putExtra("filePath", file.getName());
                                AppConfig.getLocalBroadcastManager().sendBroadcast(intent);
                            }
                        }, 500);    //延迟1.5秒发送安卓广播去更新UI
                    }
                }
                randomAccessFile.close();
            } catch (Exception e) {
                Logger.t(TAG).e(e, "parseSectionData: ");
            }
        }

        private static final CopyIndex sCopyIndex = new CopyIndex(0);

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

        /**
         * 发送广播清空分类多媒体集合  并  清空存放本程序多媒体文件的文件夹
         */
        private static void clearMediaListAndDeleteMediaFolder() {//TODO 删除多媒体文件夹 和 清除分类文件集合 的逻辑
            AppConfig.getLocalBroadcastManager().sendBroadcast(new Intent(AppConfig.LOAD_FILE_OR_DELETE_MEDIA_LIST).putExtra(AppConfig.KEY_DELETE_MEDIA_LIST, true));//1.应该先发送广播请求清空分类文件集合
            FileUtils.deleteFilesInDir(AppConfig.FILE_FOLDER);//2.再清空本程序多媒体文件夹下的文件
        }
    }
}
