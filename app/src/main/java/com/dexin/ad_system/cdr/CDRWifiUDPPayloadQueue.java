package com.dexin.ad_system.cdr;

import com.dexin.ad_system.util.LogUtil;

import java.util.LinkedList;
import java.util.Queue;

import static com.dexin.ad_system.util.Const.head_008888_array;
import static com.dexin.ad_system.util.Const.head_0x86_value;
import static com.dexin.ad_system.util.Const.head_0x87_value;

/**
 * CDR Wifi UDP净荷 缓冲队列
 * CDR Wifi UDP Payload Queue
 */
public class CDRWifiUDPPayloadQueue implements Runnable {
    private static final String TAG = "TAG_UDPPayloadQueue";
    private static final Queue<byte[]> payloadQueue = new LinkedList<>();                                      //存放 n*184净荷(n∈{0,1,2,3,4,5,6}) 的队列
    private static CDRWifiUDPPayloadQueue instance;
    private boolean isParsingPayloadData = false;

    /**
     * 构造方法私有化
     */
    private CDRWifiUDPPayloadQueue() {
    }

    /**
     * 懒汉模式获取单例
     *
     * @return 单例
     */
    public static CDRWifiUDPPayloadQueue getInstance() {
        if (instance == null) {
            instance = new CDRWifiUDPPayloadQueue();
        }
        return instance;
    }

    /**
     * 项缓冲池中添加原始UDP包解析后的净荷
     *
     * @param UDPPayload UDP包的净荷
     */
    static void addIntoQueue(byte[] UDPPayload) {
        synchronized (payloadQueue) {
            payloadQueue.offer(UDPPayload);
        }
    }

    /**
     * 读取第一个净荷包，并将其从原来的净荷队列中移除
     *
     * @return 队列中第一个净荷包（期望长度是184*n n∈{0,1,2,3,4,5,6}）
     */
    private static byte[] getNextPayload() {
        byte[] payloadArray = null;
        synchronized (payloadQueue) {
            if (payloadQueue.size() > 0) payloadArray = payloadQueue.poll();
        }
        return payloadArray;
    }

    /**
     * 获取下一个有效的净荷   TODO 这个方法非常关键，能够保证：从队列中取出的数据是有效数据(循环+线程休眠的意义也是很巧妙：一是保证取出的数据是一定有效的，二是保证第一次取数据一定取得到)
     *
     * @return 下一个有效的净荷
     */
    private static byte[] getNextValidPayload() {
        byte[] nextPayloadArray = getNextPayload();
        // 数据池下溢就会出现取出东西为空，那我就一直取，直到取出的非空
        while (nextPayloadArray == null || nextPayloadArray.length == 0) {                         //如果下一个净荷数据包是“空”（净荷队列中没有东西了），则睡0.1秒
            try {
                Thread.sleep(100);
            } catch (Exception e) {
                e.printStackTrace();
            }
            nextPayloadArray = getNextPayload();       //再取一次净荷数据包，看看能否取到
        }
//        LogUtil.d(TAG, "队列中有效的净荷包AAAAAAAAAAAAAAAAAAAAA：" + stringhelpers.bytesToHexString(nextPayloadArray).toUpperCase());
        return nextPayloadArray;
    }

    /**
     * 先判断 当前的净荷数组 是否还包含满足条件的头 TODO “008888”
     * <p>
     * 判断当前以 008888 开头的净荷数组 是否满足数据段长度：1.如果满足数据段长度，解析段并返回超出部分的净荷     2.如果不满足数据长度，就与下一段进行拼接，然后解析段的数据  并   返回超出部分的净荷
     *
     * @param currentPayloadArray 当前净荷数组(绝对是以 008888 开头的)
     * @return 超出部分的净荷数组    TODO    去作为再次寻找  008888  的数组
     */
    private static byte[] parsePayloadArrayAfterHead008888(byte[] currentPayloadArray) {        //TODO 有可能 currentPayloadArray 刚好能容下 00 88 88
        byte[] front1024OfCurrentPayloadArray = new byte[1024];                 //TODO 特别注意：由于文件完全有可能 本身就含有数据008888，所以不要在[3,1023]之间再找008888了
        byte[] overRangePayloadArray;

        while (true) {
            //TODO currentPayloadArray 的前1024（长度完全可能小于1024）中一定不含有 00 88 88，1024表示要找到我们一个段长的数据，段的长度就是1024
            if (currentPayloadArray.length >= 1024) {
                System.arraycopy(currentPayloadArray, 0, front1024OfCurrentPayloadArray, 0, front1024OfCurrentPayloadArray.length);
                //TODO 超出的长度不能丢，要保存起来
                overRangePayloadArray = cutOverRangePayloadArray(currentPayloadArray);
                break;
            } else if (currentPayloadArray.length < 1024) {//TODO 有可能 currentPayloadArray 刚好能容下 00 88 88
                byte[] nextPayloadArray = getNextValidPayload();    //TODO 长度也不一定是1104(完全有可能小于 1024)    184*n n∈{1,2,3,4,5,6}
                currentPayloadArray = joint(currentPayloadArray, nextPayloadArray, currentPayloadArray.length);//TODO 取出包括 00 88 88 之后的所有内容，与下一段数据 进行拼接 ,截取前1024        (拼接之后必须检查前 1024 是否还有一个 008888)
            }
        }
        //开始解析数据
        parse_1024Data_After008888(front1024OfCurrentPayloadArray);
        //返回超出 1024 部分的数据
        return overRangePayloadArray;
    }

    /**
     * 截取超出 1024(不排除小于 1024，传递参数时一定要严谨) 部分的净荷数组 TODO 一定要保证传递过来的参数数组长度 >= 1024
     *
     * @param currentPayloadArray 要返回超出 1024 部分净荷的数组
     * @return 超出 1024 部分的数组内容
     */
    private static byte[] cutOverRangePayloadArray(byte[] currentPayloadArray) {        //TODO 一定要保证传递过来的参数数组长度 >= 1024
        if (currentPayloadArray.length >= 1024) {
            //TODO 超出的长度不能丢，要保存起来
            byte[] overRangePayloadArray = new byte[currentPayloadArray.length - 1024];
            System.arraycopy(currentPayloadArray, 1024, overRangePayloadArray, 0, overRangePayloadArray.length);
            return overRangePayloadArray;
        } else if (currentPayloadArray.length < 1024) {
            LogUtil.d(TAG, "当前净荷数组长度小于 1024 ，不能截取到前 1024 ，会引起重大Bug！");
            return currentPayloadArray;
        }
        return currentPayloadArray;
    }

    /**
     * 解析 008888 之后的 1024字节 的数据
     *
     * @param front1024OfCurrentPayloadArray 拼接好的以 008888 开头的 1024字节 的数据
     */
    private static void parse_1024Data_After008888(byte[] front1024OfCurrentPayloadArray) {
        if (front1024OfCurrentPayloadArray[4] == head_0x87_value) {            // 获得配置表，开始解析配置表 TODO front1024OfCurrentPayloadArray 一定是00 88 88 xx 87开头
            CDRUtils.parseConfigTable(front1024OfCurrentPayloadArray, 4);
        } else if (front1024OfCurrentPayloadArray[4] == head_0x86_value) {     // 获得元素表，开始解析元素表 TODO front1024OfCurrentPayloadArray 一定是00 88 88 xx 86开头
            CDRUtils.parseSectionData(front1024OfCurrentPayloadArray, 4);
        }
    }

    /**
     * 两个数组按照：逆向偏移量 进行拼接
     *
     * @param currentBuffer     当前Buffer
     * @param nextBuffer        下一个Buffer
     * @param currentBackOffset currentBuffer 倒数个数
     * @return 拼接后的结果数组
     */
    private static byte[] joint(byte[] currentBuffer, byte[] nextBuffer, int currentBackOffset) {
        if (currentBackOffset < 0) {
            currentBackOffset = 0;
            if ((nextBuffer != null) && (nextBuffer.length >= 0)) {
                return nextBuffer;
            }
        }

        byte[] sumBuffer = new byte[0];

        if (((currentBuffer == null) || (currentBuffer.length == 0)) && ((nextBuffer == null) || (nextBuffer.length == 0))) {
            return new byte[0];
        } else if (((currentBuffer == null) || (currentBuffer.length == 0)) && ((nextBuffer != null) && (nextBuffer.length >= 0))) {
            return nextBuffer;
        } else if (((currentBuffer != null) && (currentBuffer.length >= 0)) && ((nextBuffer == null) || (nextBuffer.length == 0))) {
            if (currentBuffer.length <= currentBackOffset) {
                return currentBuffer;
            } else if (currentBuffer.length > currentBackOffset) {
                sumBuffer = new byte[currentBackOffset];
                System.arraycopy(currentBuffer, (currentBuffer.length - currentBackOffset), sumBuffer, 0, sumBuffer.length);
                return sumBuffer;
            }
        } else if (((currentBuffer != null) && (currentBuffer.length >= 0)) && ((nextBuffer != null) && (nextBuffer.length >= 0))) {
            if (currentBuffer.length <= currentBackOffset) {
                sumBuffer = new byte[currentBuffer.length + nextBuffer.length];
                System.arraycopy(currentBuffer, 0, sumBuffer, 0, currentBuffer.length);
                System.arraycopy(nextBuffer, 0, sumBuffer, currentBuffer.length, nextBuffer.length);
                return sumBuffer;
            } else if (currentBuffer.length > currentBackOffset) {
                sumBuffer = new byte[currentBackOffset + nextBuffer.length];
                System.arraycopy(currentBuffer, (currentBuffer.length - currentBackOffset), sumBuffer, 0, currentBackOffset);
                System.arraycopy(nextBuffer, 0, sumBuffer, currentBackOffset, nextBuffer.length);
                return sumBuffer;
            }
        }
        return sumBuffer;
    }

    /**
     * 重写Runnable的 run() 方法
     */
    @Override
    public void run() {
        isParsingPayloadData = true;
        int head_008888_index;
        byte[] currentPayloadArray = getNextValidPayload();     //一开始先从队列中取出一个 有效净荷包 作为当前净荷
        byte[] nextPayloadArray;

        while (isParsingPayloadData) {
            head_008888_index = CDRUtils.indexOf(0, currentPayloadArray.length, currentPayloadArray, head_008888_array);        //TODO 一、在当前净荷数组中寻找 00 88 88 的下标                        //TODO E: 主数组的长度 小于 子数组的长度，可能引起一个Bug!

            while (head_008888_index < 0) {//TODO （完全可能还是找不到，因为有大量的填充数据“0000000000000000” 和 “FFFFFFFFFFFFFFF”）,所以需要一直向后拼接寻找 008888 ，直到找到（head_008888_index>=0）为止
                nextPayloadArray = getNextValidPayload();           //再次到队列中找下一个净荷包     TODO 长度不一定是1104，因为6个ts包中的某个包可能不符合要求
                currentPayloadArray = joint(currentPayloadArray, nextPayloadArray, 2);      //TODO 基于上面的原因，长度也不一定是1106
                head_008888_index = CDRUtils.indexOf(0, currentPayloadArray.length, currentPayloadArray, head_008888_array);                                                                        //TODO E: 主数组的长度 小于 子数组的长度，可能引起一个Bug!
            }//TODO 经历了循环之后，一定可以找到 008888 ，找到了退出循环的时候 head_008888_index >= 0 一定成立

            //程序运行到此处一定可以在 currentPayloadArray 中找到 008888
            byte[] payloadAfterHead_008888 = new byte[currentPayloadArray.length - head_008888_index];      //TODO 注意：payloadAfterHead 的长度有可能刚好能容下 00 88 88
            System.arraycopy(currentPayloadArray, head_008888_index, payloadAfterHead_008888, 0, payloadAfterHead_008888.length);

            currentPayloadArray = parsePayloadArrayAfterHead008888(payloadAfterHead_008888);        //TODO 传递的是以“008888”头为起始的数组，返回的是“超出部分的净荷内容”来作为“当前的净荷内容”去继续查找拼接
        }
    }

    /**
     * 停止解析净荷数据
     */
    public void stopParsePayloadData() {
        isParsingPayloadData = false;
        LogUtil.d(TAG, "################################################停止解析净荷数据################################################");
    }
}
