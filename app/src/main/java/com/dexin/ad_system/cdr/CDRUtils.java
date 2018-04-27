package com.dexin.ad_system.cdr;

import android.content.Intent;
import android.support.v4.util.LongSparseArray;
import android.util.Log;

import com.blankj.utilcode.util.FileUtils;
import com.dexin.ad_system.app.AppConfig;
import com.dexin.ad_system.util.LogUtil;
import com.dexin.utilities.CopyIndex;
import com.dexin.utilities.arrayhelpers;
import com.dexin.utilities.stringhelpers;
import com.orhanobut.logger.Logger;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * CDR 工具
 */
public final class CDRUtils {
    private static final String TAG = "TAG_CDRUtils";
    private static int configTableVersionNumber = -1;                           //配置表 的 版本号
    //文件数据模型（guid）
    private static List<Long> guidList = new ArrayList<>();                     //配置表：“元素guid数组”
    private static final LongSparseArray<CDRElement> mCDRElementLongSparseArray = new LongSparseArray<>();       //TODO 存放 GUID 和 元素项
    //定时器
    private static final Timer mTimer = new Timer();
    //本地广播
    private static final String[] elementFormat = {".txt", ".png", ".bmp", ".jpg", ".gif", ".avi", ".mp3", ".mp4"};      //.3gp  .wav    .mkv    .mov    .mpeg   .flv

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
            if ((configTableBuffer == null) || (configTableBuffer.length != AppConfig.CUS_DATA_SIZE)) {
                LogUtil.e(TAG, "配置表为null 或 配置表长度不符(不等于1024)！退出配置表解析操作。");
                return;
            }
            if (position_87 != 4) {
                LogUtil.e(TAG, "配置表表头索引错误！退出配置表解析操作。");
                return;
            }
        }

        CopyIndex parseIndex = new CopyIndex(position_87);                    //下标先偏移到 87 位置，才能开始做解析工作

        int table_id = arrayhelpers.GetInt8(configTableBuffer, parseIndex);                //1.配置表：table_id
        if (table_id != (byte) 0x87) {
            LogUtil.e(TAG, "配置表表头不符！退出配置表解析操作。");
            return;         //根本就不是配置表，丢掉配置表Buffer
        }

        int version_number = arrayhelpers.GetInt8(configTableBuffer, parseIndex);          //2.配置表：“版本号”
        if (version_number < 0) {
            LogUtil.e(TAG, "配置表版本号为负！退出配置表解析操作。");
            return;
        } else {
            if (configTableVersionNumber != version_number) {                 //程序第一次（包括被杀掉后）启动时接收到了数据，开始更新版本号，表示重新接收新文件
                clearMediaListAndDeleteMediaFolder();                   //TODO 先是发送安卓广播 清空分类文件集合（接着根据广播设置UI）    ；   然后  删除本程序的媒体文件夹下的文件
                guidList.clear();                                       //清空原来的guidList，重新接收新文件
                configTableVersionNumber = version_number;              //更新版本号
            } else {//服务器发送的是同一版本的文件，退出配置表解析操作
                LogUtil.d(TAG, "已经成功解析过相同版本号的配置表，不再重复解析配置表Buffer！");
                return;
            }
        }

        int section_length = arrayhelpers.GetInt16(configTableBuffer, parseIndex);         //3.配置表：“段长度”
        if (section_length < (2 + 2 + 1 + 1 + 1 + 4)) {
            LogUtil.e(TAG, "配置表段长度不够，退出配置表解析操作。");
            clearGuidListAndResetVersionNumber();
            return;
        }

        int section_number = arrayhelpers.GetInt16(configTableBuffer, parseIndex);         //4.配置表：“当前段号”
        if (section_number < 0) {
            LogUtil.d(TAG, "配置表当前段号小于0，退出配置表解析操作。");
            clearGuidListAndResetVersionNumber();
            return;
        }

        int section_count = arrayhelpers.GetInt16(configTableBuffer, parseIndex);          //5.配置表：“段数量”
        if (section_count < 0) {
            LogUtil.d(TAG, "配置表中解析出段的数量小于0，退出配置表解析操作。");
            clearGuidListAndResetVersionNumber();
            return;
        }

        parseIndex.AddIndex(1);
        parseIndex.AddIndex(1);

        int element_count = arrayhelpers.GetInt8(configTableBuffer, parseIndex);             //配置表：“元素个数”
        if (element_count < 0) {
            LogUtil.d(TAG, "配置表解析出元素个数小于0，退出配置表解析操作。");
            clearGuidListAndResetVersionNumber();
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
                int element_type = arrayhelpers.GetInt8(configTableBuffer, parseIndex);
                int element_format = arrayhelpers.GetInt8(configTableBuffer, parseIndex);
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
            return;
        } else {
            LogUtil.i(TAG, "配置表CRC校验成功");
        }

        {
            if (element_count != guid_list_parsed.size()) {
                LogUtil.e(TAG, "解析所得文件数量不等于for循环中获取的文件数量。");
                clearGuidListAndResetVersionNumber();
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
        LogUtil.d(TAG, "原始数据找86-------》" + stringhelpers.bytesToHexString(sectionBuffer).toUpperCase());
        LogUtil.d(TAG, "开始做解析段的工作 -->");
        if ((mCDRElementLongSparseArray.size() <= 0)) {
            LogUtil.d(TAG, "收到段数据，但程序启动后还未成功解析过配置表，暂不解析。");
            return;
        }

        CopyIndex parseIndex = new CopyIndex(position_86);

        int table_id = arrayhelpers.GetInt8(sectionBuffer, parseIndex);
        if (table_id != (byte) 0x86) {
            LogUtil.e(TAG, "元素表表头不符！退出元素表解析操作。");
            return;         //根本就不是配置表，丢掉配置表Buffer
        }

        int version_number = arrayhelpers.GetInt8(sectionBuffer, parseIndex);
        if (version_number < 0) {
            LogUtil.e(TAG, "元素表版本号为负！退出元素表解析操作。");
            return;
        } else if (configTableVersionNumber != version_number) {
            LogUtil.e(TAG, "接收到的元素表版本号与配置表版本号不一致，退出元素表解析工作。");
            return;
        }

        int section_length = arrayhelpers.GetInt16(sectionBuffer, parseIndex);
        if (section_length < (2 + 2 + 4 + 1 + 1 + 4 + 4)) {
            LogUtil.e(TAG, "元素表段长度不够，退出元素表解析操作。");
            return;
        }

        int section_number = arrayhelpers.GetInt16(sectionBuffer, parseIndex);
        if (section_number < 0) {
            LogUtil.e(TAG, "元素表当前段号小于0，退出元素表解析操作。");
            return;
        }
        LogUtil.d(TAG, "段号-->：" + section_number);

        if ((65 <= section_number) && (section_number <= 69)) {
            LogUtil.d(TAG, "-------》段号：" + section_number + " [65,69]净荷数据：" + stringhelpers.bytesToHexString(sectionBuffer).toUpperCase());
        }

        int section_count = arrayhelpers.GetInt16(sectionBuffer, parseIndex);
        if (section_count < 0) {
            LogUtil.e(TAG, "元素表中解析出段的数量小于0，退出元素表解析操作。");
            return;
        }
        if (section_number >= section_count) {
            LogUtil.e(TAG, "元素表中解析出段号大于等于段数量，退出元素表解析操作。");
            return;
        }

        long element_guid = arrayhelpers.GetInt32(sectionBuffer, parseIndex);
        if (element_guid < 0) {
            LogUtil.e(TAG, "元素表解析出元素guid小于0，退出元素表解析操作。");
            return;
        }
        if (mCDRElementLongSparseArray.indexOfKey(element_guid) < 0) {                             //解析出的配置表中 没有要接收 当前GUID 的文件
            LogUtil.d(TAG, "收到 新版本的数据段 但是没有收到 新版本的配置表，不做解析！");
            return;
        }

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
            LogUtil.d(TAG, "段号-->列表Size：" + sectionsNumberList.size() + " 段号-->列表：" + sectionsNumberList);
        }
        int element_type = arrayhelpers.GetInt8(sectionBuffer, parseIndex);
        if (element_type < 0) {
            LogUtil.d(TAG, "元素表解析出元素类型小于0，退出元素表解析操作！");
            return;
        }

        int element_format = arrayhelpers.GetInt8(sectionBuffer, parseIndex);
        if (element_format < 0) {
            LogUtil.d(TAG, "元素表解析出元素格式小于0，退出元素表解析操作！");
            return;
        }

        int section_data_length = arrayhelpers.GetInt32(sectionBuffer, parseIndex);
        if ((section_data_length <= 0) || (section_data_length > 998)) {
            LogUtil.d(TAG, "元素表解析出元素大小不符合要求(0,998]，退出元素表解析操作！");
            return;
        }
        byte[] section_data = arrayhelpers.GetBytes(sectionBuffer, section_data_length, parseIndex);
        if (section_data.length <= 0) {
            LogUtil.d(TAG, "元素表解析出元素数据长度为负，退出元素表解析操作！");
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
    private static int calculateCRC(@NotNull byte[] buffer) {
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
        Intent intent = new Intent(AppConfig.LOAD_FILE_OR_DELETE_MEDIA_LIST);       //1.应该先发送广播请求清空分类文件集合
        intent.putExtra("deleteMediaList", true);
        AppConfig.getLocalBroadcastManager().sendBroadcast(intent);

        FileUtils.deleteFilesInDir(AppConfig.FILE_FOLDER);                          //2.再删除本程序多媒体文件夹下的文件     Environment.getExternalStorageDirectory().getPath() + "/AD_System"  ==  /mnt/internal_sd/AD_System
    }
}
