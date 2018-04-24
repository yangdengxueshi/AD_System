package com.dexin.ad_system.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;

import com.dexin.ad_system.R;
import com.dexin.ad_system.cdr.CDRWifiUDPPayloadQueue;
import com.dexin.ad_system.cdr.CDRWifiUDPReceiver;
import com.dexin.ad_system.util.CustomApplication;

public class LongRunningUDPService extends Service {
    private CDRWifiUDPPayloadQueue mCDRWifiUDPPayloadQueue = CDRWifiUDPPayloadQueue.getInstance();
    private CDRWifiUDPReceiver mCDRWifiUDPReceiver = new CDRWifiUDPReceiver();

    @Override
    public void onCreate() {
        super.onCreate();
        startServiceWithForegroundMode();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(mCDRWifiUDPPayloadQueue).start();           //处理数据报的线程先开启

        //TODO 主线程上不能进行联网操作     ( ① 开启接收数据的线程 )
        new Thread(() -> {                                                      //接收数据包的线程后开启
            try {
                mCDRWifiUDPReceiver.receiveCDRWifiUDPPacket();                  //网络操作必须在子线程中进行
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            mCDRWifiUDPPayloadQueue.stopParsePayloadData();                 //服务停止后不用再解析数据
            mCDRWifiUDPReceiver.stopReceiveCDRWifiUDPPacket();              //服务停止就不再接收数据报
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * TODO 以前台服务模式启动Service
     */
    private void startServiceWithForegroundMode() {
        Notification notification = new NotificationCompat.Builder(CustomApplication.getContext(), "ForgroundService")
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("CDR广告系统")
                .setContentText("请求网络数据的前台服务。")
                .setWhen(System.currentTimeMillis())
                .build();
        startForeground(1, notification);
    }
}
