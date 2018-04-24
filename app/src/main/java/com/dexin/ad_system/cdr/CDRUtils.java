package com.dexin.ad_system.cdr;

import android.content.Intent;
import android.support.v4.util.LongSparseArray;
import android.util.Log;

import com.blankj.utilcode.util.FileUtils;
import com.dexin.ad_system.util.AppConfig;
import com.dexin.ad_system.util.Const;
import com.dexin.ad_system.util.LogUtil;
import com.dexin.utilities.CopyIndex;
import com.dexin.utilities.arrayhelpers;
import com.dexin.utilities.stringhelpers;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import static com.dexin.ad_system.util.Const.head_0x86_value_gky;
import static com.dexin.utilities.arrayhelpers.GetInt8;

/**
 * CDR 工具
 */
public class CDRUtils {
    private static final String TAG = "CDRUtils";
    private static int configTableVersionNumber = -1;                           //配置表 的 版本号
    //文件数据模型（guid）
    private static List<Long> guidList = new ArrayList<>();                     //配置表：“元素guid数组”
    private static LongSparseArray<CDRElement> mCDRElementLongSparseArray = new LongSparseArray<>();       //TODO 存放 GUID 和 元素项
    //定时器
    private static Timer mTimer = new Timer();
    //本地广播
    private static String[] elementFormat = new String[]{".txt", ".png", ".bmp", ".jpg", ".gif", ".avi", ".mp3", ".mp4"};      //.3gp  .wav    .mkv    .mov    .mpeg   .flv

    /**
     * 找出子数组subBuffer在主数组buffer中的起始位置 TODO 本方法进行了深度验证，没有问题
     *
     * @param start      开始查找的位置
     * @param end        结束查找的位置
     * @param mainBuffer 主数组mainBuffer
     * @param subBuffer  子数组subBuffer
     * @return 找到的下标（为-1表示没有找到）
     */
    public static int indexOf(int start, int end, byte[] mainBuffer, byte[] subBuffer) {            //end 一般传递的是 mainBuffer.length
        if (start < 0) {
            start = 0;
        }

        if (start > mainBuffer.length) {
            LogUtil.d(TAG, "start位置超出主数组长度！");
            return -1;
        }

        if (end < start) {
            LogUtil.d(TAG, "主数组中查找的开始位置大于结束位置！");
            return -1;
        }

        if (end > mainBuffer.length) {
            end = mainBuffer.length;
        }

        if (mainBuffer.length < subBuffer.length) {
//            LogUtil.i(TAG, "主数组的长度 小于 子数组的长度，这种情况经过分析也是正常情形!");
            return -1;
        }

        boolean isFound;        //子数组被找到
        for (int i = start; i < end; i++) {
            if (i <= end - subBuffer.length) {
                isFound = true;
                for (int j = 0; j < subBuffer.length; j++) {
                    if (subBuffer[j] != mainBuffer[i + j]) {
                        isFound = false;
                        break;
                    }
                }
            } else {
                isFound = false;
            }
            if (isFound) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解析接收到的原始UDP数据包获得6个TS包（TS包是顺序排放的），我们只需要 0x86 开头的UDP包
     *
     * @param udpDataPacket 收到的字节数组：原始的UDP数据报
     * @return "6个TS包的净荷"有序拼接起来的字节数组（长度是1104）
     */
    public static byte[] parseUDPPacketToPayload(byte[] udpDataPacket) {
        if (udpDataPacket == null || udpDataPacket.length < 21 + 311) {
            LogUtil.d(TAG, "UDP原始数据包为 null 或 长度小于 21+311，可能引起一个Bug！");
            return new byte[0];
        } else if (udpDataPacket.length != 1460) {
            LogUtil.i(TAG, "UDP包长不是1460，可能引起Bug!");
        }

        if (udpDataPacket[0] != head_0x86_value_gky) {//这个0x86是广科院的头，注意与自定义 head_0x86_value 区分开   //TODO 5.筛选出 0x86 UDP数据报,(如果不是0x86开头的UDP包则抛弃掉)
            LogUtil.i(TAG, "UDP包头不是0x86，可能引起Bug!");
            return new byte[0];
        }

        int tsPacketAbandonedCount = 0;                 //被抛弃的ts包的数量
        byte[] tsPayloadBuffer = new byte[6 * 184];
        CopyIndex index = new CopyIndex(21);            //TODO UDP包以 0x86开头，前21字节是协议头(收到后可以删除)，（1460-21-311=1128字节有效）    1128/6=188（每个TS包）
        //TODO 循环解析一个UDP包中的6个TS包
        for (int i = 0; i < 6; i++) {
            byte head_0x47 = GetInt8(udpDataPacket, index);        //TODO byte[]     取出当前同步字节，看是否等于0x47 0F FE xx
            byte pid_1 = GetInt8(udpDataPacket, index);
            byte pid_2 = GetInt8(udpDataPacket, index);
            index.AddIndex(1);          //前面已经+1 +1 +1 ，现在+1，跳过每个TS包的0x47前4个字节的协议头

            if ((head_0x47 != (byte) 0x47) || ((pid_1 & (byte) 0x1F) != (byte) 0x0F) || (pid_2 != (byte) 0xFE)) {   // 三个条件有一个不满足都要将当前ts包抛掉 //TODO TS包以  0x47开头，前4个字节是协议头(0x47 xF FE xx)，其后是184字节净荷     (188-4)
                index.AddIndex(184);
                tsPacketAbandonedCount++;
                continue;
            }

            byte[] payload184 = arrayhelpers.GetBytes(udpDataPacket, 184, index);        //减去协议头 0x47xxxxxx,得到184字节净荷
            System.arraycopy(payload184, 0, tsPayloadBuffer, (i - tsPacketAbandonedCount) * 184, 184);
        }
        byte[] tsPayloadBufferValid = new byte[(6 - tsPacketAbandonedCount) * 184];
        System.arraycopy(tsPayloadBuffer, 0, tsPayloadBufferValid, 0, tsPayloadBufferValid.length);

        LogUtil.d(TAG, "124211数据 ts净荷中有效Buffer：" + stringhelpers.bytesToHexString(tsPayloadBufferValid).toUpperCase());

        return tsPayloadBufferValid;                        //TODO 返回每个UDP包中的6个TS包中的净荷拼接起来的字节数组
    }

    /**
     * 解析配置表的方法，TODO 传递过来的参数一定就是 008888 开头的1024长度 数组
     * <p>
     * TODO 相同版本号的配置表在解析成功的前提下我们只解析一次
     *
     * @param configTableBuffer 已经拼接好的配置表Buffer（008888xx87 开头，1024长度）
     * @param position_87       已经查找到的0x87所在位置
     */
    public static void parseConfigTable(byte[] configTableBuffer, int position_87) {
        LogUtil.i(TAG, "开始做解析配置表的工作 -->");
        {//TODO 1.先期判断
            if (configTableBuffer == null || configTableBuffer.length != 1024) {
                LogUtil.e(TAG, "配置表为null 或 配置表长度不符(不等于1024)！退出配置表解析操作。");
                System.gc();
                return;
            }
            if (position_87 != 4) {
                LogUtil.e(TAG, "配置表表头索引错误！退出配置表解析操作。");
                System.gc();
                return;
            }
        }

        CopyIndex parseIndex = new CopyIndex(position_87);                    //下标先偏移到 87 位置，才能开始做解析工作

        int table_id = GetInt8(configTableBuffer, parseIndex);                //1.配置表：table_id
        if (table_id != (byte) 0x87) {
            LogUtil.e(TAG, "配置表表头不符！退出配置表解析操作。");
            System.gc();
            return;         //根本就不是配置表，丢掉配置表Buffer
        }

        int version_number = GetInt8(configTableBuffer, parseIndex);          //2.配置表：“版本号”
        if (version_number < 0) {
            LogUtil.e(TAG, "配置表版本号为负！退出配置表解析操作。");
            System.gc();
            return;
        } else {
            if (configTableVersionNumber != version_number) {                 //程序第一次（包括被杀掉后）启动时接收到了数据，开始更新版本号，表示重新接收新文件
                clearMediaListAndDeleteMediaFolder();                   //TODO 先是发送安卓广播 清空分类文件集合（接着根据广播设置UI）    ；   然后  删除本程序的媒体文件夹下的文件
                guidList.clear();                                       //清空原来的guidList，重新接收新文件
                configTableVersionNumber = version_number;              //更新版本号
            } else {//服务器发送的是同一版本的文件，退出配置表解析操作
                LogUtil.d(TAG, "已经成功解析过相同版本号的配置表，不再重复解析配置表Buffer！");
                System.gc();
                return;
            }
        }

        int section_length = arrayhelpers.GetInt16(configTableBuffer, parseIndex);         //3.配置表：“段长度”
        if (section_length < 2 + 2 + 1 + 1 + 1 + 4) {
            LogUtil.e(TAG, "配置表段长度不够，退出配置表解析操作。");
            clearGuidListAndResetVersionNumber();
            System.gc();
            return;
        }

        int section_number = arrayhelpers.GetInt16(configTableBuffer, parseIndex);         //4.配置表：“当前段号”
        if (section_number < 0) {
            LogUtil.d(TAG, "配置表当前段号小于0，退出配置表解析操作。");
            clearGuidListAndResetVersionNumber();
            System.gc();
            return;
        }

        int section_count = arrayhelpers.GetInt16(configTableBuffer, parseIndex);          //5.配置表：“段数量”
        if (section_count < 0) {
            LogUtil.d(TAG, "配置表中解析出段的数量小于0，退出配置表解析操作。");
            clearGuidListAndResetVersionNumber();
            System.gc();
            return;
        }

        parseIndex.AddIndex(1);
        parseIndex.AddIndex(1);

        int element_count = GetInt8(configTableBuffer, parseIndex);             //配置表：“元素个数”
        if (element_count < 0) {
            LogUtil.d(TAG, "配置表解析出元素个数小于0，退出配置表解析操作。");
            clearGuidListAndResetVersionNumber();
            System.gc();
            return;
        }

        //TODO 根据配置表解析出要接收那些文件(为了便于判断，将 “有效4字节的元素id数组” 转换成集合)
        List<Long> guid_list_parsed = new ArrayList<>();        //通过解析所得的 guid 集合
        for (int i = 0; i < element_count; i++) {
            long guid = arrayhelpers.GetInt32(configTableBuffer, parseIndex);
            if (guid_list_parsed.contains(guid)) {
                parseIndex.AddIndex(1);
                parseIndex.AddIndex(1);
                parseIndex.AddIndex(1);
                parseIndex.AddIndex(1);
            } else {
                int element_type = GetInt8(configTableBuffer, parseIndex);
                int element_format = GetInt8(configTableBuffer, parseIndex);
                parseIndex.AddIndex(1);
                parseIndex.AddIndex(1);
                guid_list_parsed.add(guid);
            }
        }

        //获取CRCBuffer
        parseIndex.Reset();
        parseIndex.AddIndex((position_87 + 4));
        byte[] CRCBuffer = arrayhelpers.GetBytes(configTableBuffer, section_length - 4, parseIndex);
        int calcCRC = calculateCRC(CRCBuffer);

        int crc = arrayhelpers.GetInt32(configTableBuffer, parseIndex);             //TODO CRC 放到此处获取是因为： for循环之后有大量填充数据

        //校验CRC
        if (calcCRC != crc) {                           //CRC不相等，表示数据不对
            Log.d(TAG, "配置表CRC校验失败:" + stringhelpers.bytesToHexString(configTableBuffer).toUpperCase());
            clearGuidListAndResetVersionNumber();
            System.gc();
            return;
        } else {
            LogUtil.i(TAG, "配置表CRC校验成功");
        }

        {
            if (element_count != guid_list_parsed.size()) {
                LogUtil.e(TAG, "解析所得文件数量不等于for循环中获取的文件数量。");
                clearGuidListAndResetVersionNumber();
                System.gc();
                return;
            } else {
                mCDRElementLongSparseArray.clear();
                guidList = guid_list_parsed;
                for (long guid : guidList) {
                    LogUtil.i(TAG, "文件guid:" + guid);
                    CDRElement cdrElement = new CDRElement();
                    cdrElement.setElementGUID(guid);
                    cdrElement.setVersionNumber(version_number);
                    mCDRElementLongSparseArray.put(guid, cdrElement);
                }
            }
        }

        LogUtil.e(TAG, "解析配置表成功！" + guid_list_parsed.size() + "/" + element_count);
    }

    /**
     * 解析段的数据，并写入文件; 长度已经事先拼接好了，不用再考虑解析完成后剩余内容的拼接问题
     *
     * @param sectionBuffer 段的Buffer字节数组
     * @param position_86   已经查找到的0x86所在位置
     */
    public static void parseSectionData(byte[] sectionBuffer, int position_86) {
        LogUtil.d(TAG, "原始数据找68-------》" + stringhelpers.bytesToHexString(sectionBuffer).toUpperCase());
        LogUtil.d(TAG, "开始做解析段的工作 -->");
        if ((mCDRElementLongSparseArray.size() <= 0)) {
            LogUtil.d(TAG, "收到段数据，但程序启动后还未成功解析过配置表，暂不解析。");
            System.gc();
            return;
        }

        CopyIndex parseIndex = new CopyIndex(position_86);

        int table_id = GetInt8(sectionBuffer, parseIndex);
        if (table_id != (byte) 0x86) {
            LogUtil.e(TAG, "元素表表头不符！退出元素表解析操作。");
            System.gc();
            return;         //根本就不是配置表，丢掉配置表Buffer
        }

        int version_number = GetInt8(sectionBuffer, parseIndex);
        if (version_number < 0) {
            LogUtil.e(TAG, "元素表版本号为负！退出元素表解析操作。");
            System.gc();
            return;
        } else if (configTableVersionNumber != version_number) {
            LogUtil.e(TAG, "接收到的元素表版本号与配置表版本号不一致，退出元素表解析工作。");
            System.gc();
            return;
        }

        int section_length = arrayhelpers.GetInt16(sectionBuffer, parseIndex);
        if (section_length < 2 + 2 + 4 + 1 + 1 + 4 + 4) {
            LogUtil.e(TAG, "元素表段长度不够，退出元素表解析操作。");
            System.gc();
            return;
        }

        int section_number = arrayhelpers.GetInt16(sectionBuffer, parseIndex);
        if (section_number < 0) {
            LogUtil.e(TAG, "元素表当前段号小于0，退出元素表解析操作。");
            System.gc();
            return;
        }
        LogUtil.d(TAG, "段号-->：" + section_number);

        if (65 <= section_number && section_number <= 69) {
            LogUtil.d(TAG, "-------》段号：" + section_number + " [65,69]净荷数据：" + stringhelpers.bytesToHexString(sectionBuffer).toUpperCase());
        }

        int section_count = arrayhelpers.GetInt16(sectionBuffer, parseIndex);
        if (section_count < 0) {
            LogUtil.e(TAG, "元素表中解析出段的数量小于0，退出元素表解析操作。");
            System.gc();
            return;
        }
        if (section_number >= section_count) {
            LogUtil.e(TAG, "元素表中解析出段号大于等于段数量，退出元素表解析操作。");
            System.gc();
            return;
        }

        long element_guid = arrayhelpers.GetInt32(sectionBuffer, parseIndex);
        if (element_guid < 0) {
            LogUtil.e(TAG, "元素表解析出元素guid小于0，退出元素表解析操作。");
            System.gc();
            return;
        }
        if (mCDRElementLongSparseArray.indexOfKey(element_guid) < 0) {                             //解析出的配置表中 没有要接收 当前GUID 的文件
            LogUtil.d(TAG, "收到 新版本的数据段 但是没有收到 新版本的配置表，不做解析！");
            System.gc();
            return;
        }

        List<Integer> sectionsNumberList = null;
        CDRElement cdrElement = mCDRElementLongSparseArray.get(element_guid);        //根据 元素GUID 获取元素段
        if (cdrElement != null) {
            if (cdrElement.getVersionNumber() != version_number) {
                LogUtil.d(TAG, "解析所得的版本号与CDR元素项版本号不符，退出元素表解析操作。");
                System.gc();
                return;
            }
            sectionsNumberList = cdrElement.getSectionsNumberList();
        }
        if (sectionsNumberList != null && (sectionsNumberList.contains(section_number))) {                                                      // 如果“一个文件中已经收取过当前段号”，就不再收取
            LogUtil.d(TAG, "已经接收过当前段号的数据，不再重复解析，退出元素表解析操作。");
            System.gc();
            return;
        }
        if (sectionsNumberList != null) {
            LogUtil.d(TAG, "段号-->列表Size：" + sectionsNumberList.size() + " 段号-->列表：" + sectionsNumberList.toString());
        }
        int element_type = GetInt8(sectionBuffer, parseIndex);
        if (element_type < 0) {
            LogUtil.d(TAG, "元素表解析出元素类型小于0，退出元素表解析操作！");
            System.gc();
            return;
        }

        int element_format = GetInt8(sectionBuffer, parseIndex);
        if (element_format < 0) {
            LogUtil.d(TAG, "元素表解析出元素格式小于0，退出元素表解析操作！");
            System.gc();
            return;
        }

        int section_data_length = arrayhelpers.GetInt32(sectionBuffer, parseIndex);
        if (section_data_length <= 0 || section_data_length > 998) {
            LogUtil.d(TAG, "元素表解析出元素大小不符合要求(0,998]，退出元素表解析操作！");
            System.gc();
            return;
        }
        byte[] section_data = arrayhelpers.GetBytes(sectionBuffer, section_data_length, parseIndex);
        if (section_data.length <= 0) {
            LogUtil.d(TAG, "元素表解析出元素数据长度为负，退出元素表解析操作！");
            System.gc();
            return;
        }

        //获取CRCBuffer
        parseIndex.Reset();
        parseIndex.AddIndex(position_86 + 4);
        byte[] CRCBuffer = arrayhelpers.GetBytes(sectionBuffer, section_length - 4, parseIndex);
        int calcCRC = calculateCRC(CRCBuffer);

        int crc = arrayhelpers.GetInt32(sectionBuffer, parseIndex);

        //校验CRC
        if (calcCRC != crc) {
            LogUtil.e(TAG, "CRC校验错误的段落->   GUID=" + element_guid + "\t" + "段号=" + section_number);
            LogUtil.d(TAG, "CRC校验错误的Buffer：" + stringhelpers.bytesToHexString(sectionBuffer).toUpperCase());
            System.gc();
            return;
        }

        //TODO 将本段数据写入到磁盘
        if ((element_format < 0) || (element_format > elementFormat.length - 1)) {
            LogUtil.d(TAG, "元素格式下标越界，退出元素表解析。");
            System.gc();
            return;
        }
        String extention = elementFormat[element_format];
        //TODO 创建 当前文件
        File file = new File(Const.FILE_FOLDER, element_guid + extention);
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
                            Intent intent = new Intent(Const.LOAD_FILE_OR_DELETE_MEDIA_LIST);                                                                                                               //TODO 删除多媒体文件夹 和 清除分类文件集合 的逻辑
                            intent.putExtra("filePath", file.getName());
                            AppConfig.getLocalBroadcastManager().sendBroadcast(intent);
                        }
                    }, 500);    //延迟1.5秒发送安卓广播去更新UI
                }
            }
            randomAccessFile.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 清空guid集合 并 重置版本号
     */
    private static void clearGuidListAndResetVersionNumber() {
        guidList.clear();
        configTableVersionNumber = -1;
    }

    /**
     * 自定义的计算CRC的方法
     *
     * @param buffer 要计算CRC的Buffer字节数组
     * @return 自定义的CRC值
     */
    private static int calculateCRC(byte[] buffer) {
        byte[] crcBuffer = new byte[4];
        crcBuffer[0] = (byte) 0x12;
        crcBuffer[1] = (byte) 0x34;
        crcBuffer[2] = buffer[0];
        crcBuffer[3] = buffer[1];

        CopyIndex index = new CopyIndex(0);
        return arrayhelpers.GetInt32(crcBuffer, index);
    }

    /**
     * 删除存放本程序多媒体文件的文件夹 并 发送广播清空分类多媒体集合
     */
    private static void clearMediaListAndDeleteMediaFolder() {                                                                                                                                      //TODO 删除多媒体文件夹 和 清除分类文件集合 的逻辑
        Intent intent = new Intent(Const.LOAD_FILE_OR_DELETE_MEDIA_LIST);       //1.应该先发送广播请求清空分类文件集合
        intent.putExtra("deleteMediaList", true);
        AppConfig.getLocalBroadcastManager().sendBroadcast(intent);

        FileUtils.deleteFilesInDir(Const.FILE_FOLDER);                          //2.再删除本程序多媒体文件夹下的文件     Environment.getExternalStorageDirectory().getPath() + "/AD_System"  ==  /mnt/internal_sd/AD_System
    }
}
