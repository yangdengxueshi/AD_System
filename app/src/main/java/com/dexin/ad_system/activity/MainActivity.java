package com.dexin.ad_system.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.text.method.NumberKeyListener;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.RegexUtils;
import com.dexin.ad_system.R;
import com.dexin.ad_system.service.LongRunningUDPService;
import com.dexin.ad_system.util.AppConfig;
import com.dexin.ad_system.util.Const;
import com.dexin.ad_system.util.CustomApplication;
import com.vondear.rxtools.RxBarTool;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import butterknife.BindView;
import butterknife.ButterKnife;

//TODO IP地址和端口号设置在SP中，当SP发生改变的时候执行onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)方法
public class MainActivity extends AppCompatActivity {
    private static final int FLAG_HOMEKEY_DISPATCHED = 0x80000000;//HOME键分发标志

    private static final int PLAY_LANTERN_SLIDE_IMAGE = 921;//播放幻灯片图像
    private static final int HIDE_VIDEO_VIEW = 441;//隐藏VideoView

    @BindView(R.id.iv_show)
    ImageView mIvShow;
    @BindView(R.id.tv_detail)
    TextView mTvDetail;
    @BindView(R.id.vv_video)
    VideoView mVvVideo;
    @BindView(R.id.v_menu)
    View mVMenu;
    //TODO 二、UI
    //    private VerticalMarqueeTextView vmtvDetail;

    //TODO 三、广播
    private LocalCDRBroadcastReceiver mLocalCDRBroadcastReceiver;      //TODO UDP接收器广播,当缓存完成时调用

    //TODO 五、创建WifiLock和MulticastLock
    private WifiManager.WifiLock mWifiLock;
    private WifiManager.MulticastLock mMulticastLock;

    //TODO 六、自定义变量
    //1.图
    private List<String> imageList = new ArrayList<>();
    private List<String> txtList = new ArrayList<>();
    private Timer mTimer = new Timer();
    private int currentImageIndex = 0;
    private boolean isSlideShowImage = false;   //程序一加载并没有正在播放幻灯片(图片)
    private MediaPlayer mMediaPlayer = new MediaPlayer();

    //TODO 七、自定义Handler
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PLAY_LANTERN_SLIDE_IMAGE:
                    if (imageList.size() > 0) {
                        Bitmap bitmap = BitmapFactory.decodeFile(imageList.get(currentImageIndex));
                        mIvShow.setImageBitmap(bitmap);
                    }
                    break;
                case HIDE_VIDEO_VIEW:
                    if (!mVvVideo.isPlaying()) {             //没有正在播放视频，就将vvVideoView销毁
                        mVvVideo.setVisibility(View.GONE);
                    }
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RxBarTool.hideStatusBar(this);//隐藏掉状态栏
        this.getWindow().setFlags(FLAG_HOMEKEY_DISPATCHED, FLAG_HOMEKEY_DISPATCHED);    //设置为主屏幕的关键代码
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        //初始化成员变量
        initMemberVar();

        //执行逻辑
        launchADSystemApp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerForContextMenu(mVMenu);
    }

    @Override
    protected void onPause() {
        mVvVideo.suspend();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        //注销工作
        unregisterForContextMenu(mVMenu);
        AppConfig.mLocalBroadcastManager.unregisterReceiver(mLocalCDRBroadcastReceiver);

        //释放WifiLock和MulticastLock
        if (mWifiLock.isHeld()) mWifiLock.release();
        if (mMulticastLock.isHeld()) mMulticastLock.release();

        //释放MediaPlayer
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
        }

        //暂停视频
        if (mVvVideo != null) {
            mVvVideo.suspend();
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //屏蔽掉 Back键 和 Home键
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                return true;
            case KeyEvent.KEYCODE_HOME:
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 初始化成员变量
     */
    private void initMemberVar() {
/*
        {
            vmtvDetail = findViewById(R.id.vm_tv_detail);
            vmtvDetail.setFadingEdgeLength(3);
            ((TextView) vmtvDetail.getChildAt(0)).setGravity(View.TEXT_ALIGNMENT_TEXT_START);
            ((TextView) vmtvDetail.getChildAt(0)).setTextSize(52);                                  //TODO 触控一体机设置52,手机设置32
        }
*/
        //TODO 三、广播
        mLocalCDRBroadcastReceiver = new LocalCDRBroadcastReceiver();
        AppConfig.mLocalBroadcastManager.registerReceiver(mLocalCDRBroadcastReceiver, new IntentFilter(Const.LOAD_FILE_OR_DELETE_MEDIA_LIST));

        //TODO 五、创建WifiLock和MulticastLock
        WifiManager manager = (WifiManager) (getApplicationContext().getSystemService(Context.WIFI_SERVICE));
        mWifiLock = Objects.requireNonNull(manager).createWifiLock("UDP_Wifi_Lock");
        mMulticastLock = manager.createMulticastLock("UDP_Multicast_Lock");
        //获得WifiLock和MulticastLock
        mWifiLock.acquire();
        mMulticastLock.acquire();
    }

    /**
     * 启动广告系统App
     */
    private void launchADSystemApp() {
        if (AppConfig.getSPUtils().getBoolean("isFirstLaunch", true)) {        //第一次启动App程序
            setServerIP_Port();                             //设置IP地址和端口号后（再开启服务）
        } else {                                                //直接开启服务
            //先创建本程序的媒体文件夹
            FileUtils.createOrExistsDir(Const.FILE_FOLDER);
            //先停止、再启动Service
            stopService(new Intent(MainActivity.this, LongRunningUDPService.class));
            startService(new Intent(MainActivity.this, LongRunningUDPService.class));
        }
    }

    /**
     * 设置服务器的“IP”与“端口”
     */
    public void setServerIP_Port() {
        // 装载自定义的xml文件到对话框中
        View tlConfig = getLayoutInflater().inflate(R.layout.ip_port, null);
        EditText etIP = tlConfig.findViewById(R.id.et_ip);
        etIP.setKeyListener(new NumberKeyListener() {
            @Override
            public int getInputType() {
                return InputType.TYPE_CLASS_TEXT;
            }

            @NonNull
            @Override
            protected char[] getAcceptedChars() {
                return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.'};
            }
        });
        etIP.setText(AppConfig.getSPUtils().getString("ip", ""));

        EditText etPort = tlConfig.findViewById(R.id.et_port);
        if (AppConfig.getSPUtils().contains("port")) {     //SP中存有port就将其取出设置给控件，不存有就给控件设置空字符串
            etPort.setText(String.valueOf(AppConfig.getSPUtils().getInt("port", -1)));
        } else {
            etPort.setText("");
        }
        new AlertDialog.Builder(this).setIcon(R.drawable.icon_settings).setTitle("服务器IP与端口配置").setView(tlConfig).setNegativeButton("取消", (dialog, which) -> {
        })
                //TODO 此处可执行逻辑处理
                .setPositiveButton("确定", (dialog, which) -> {
                    String ipStr = etIP.getText().toString();
                    int portValue = Integer.parseInt(etPort.getText().toString());

                    //判断IP输入和端口输入的格式是否正确,如果正确，将其写入SP，如果错误，给出提示。
                    if (RegexUtils.isIP(ipStr) && (0 <= portValue && portValue <= 65535)) {
                        AppConfig.getSPUtils().put("isFirstLaunch", false);
                        AppConfig.getSPUtils().put("ip", ipStr);
                        AppConfig.getSPUtils().put("port", portValue);             //点击了“确定”才认为APP不再是第一次启动了，并且设置了IP和端口号
                        stopService(new Intent(MainActivity.this, LongRunningUDPService.class));
                        startService(new Intent(MainActivity.this, LongRunningUDPService.class));
                    } else {
                        Toast.makeText(CustomApplication.getContext(), "您输入的 IP 或 端口 不符合格式要求。\n请长按屏幕重新设置。", Toast.LENGTH_LONG).show();
                        setServerIP_Port();                     //TODO 弹出对话框请求重新输入
                    }
                })
                .create().show();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        getMenuInflater().inflate(R.menu.menu_context, menu);

        menu.setHeaderTitle("程序设置");
        menu.setHeaderIcon(R.drawable.icon_settings);

        super.onCreateContextMenu(menu, v, menuInfo);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        item.setChecked(true);
        switch (item.getItemId()) {
            case R.id.server_ip_port_set:
                setServerIP_Port();         //TODO 设置IP地址 和 端口号
                break;
        }
        return super.onContextItemSelected(item);
    }

    /**
     * 加载幻灯片(图片)
     */
    private void loadLanternSlideImage() {
        if (imageList.size() > 0) {
            mTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    currentImageIndex++;
                    if (currentImageIndex >= imageList.size()) currentImageIndex = 0;
                    mHandler.sendEmptyMessage(PLAY_LANTERN_SLIDE_IMAGE);
                }
            }, 8000, 8000);
        }
    }

    /**
     * 根据文本文件路径的列表加载文字
     *
     * @param txtFilePathList 文本文件路径列表
     * @return 所有文本文件中文字的拼接
     */
    private String loadText(List<String> txtFilePathList) {
        StringBuilder content = new StringBuilder();
        try {
            if (txtFilePathList.size() > 0) {
                for (int i = 0; i < txtFilePathList.size(); i++) {
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(txtFilePathList.get(i))));
                    String line;
                    while ((line = bufferedReader.readLine()) != null) {
                        content.append(line);
                    }
                    if (i != txtFilePathList.size() - 1) {
                        content.append(Const.FORM_FEED_CHARACTER + Const.LINE_HEAD);
                    }
                    bufferedReader.close();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return content.toString();
    }

    /**
     * 初始化MediaPlayer 并且 播放音乐
     *
     * @param filePath 音乐文件路径
     */
    private void initMediaPlayerAndPlayMusic(String filePath) {
        if (mMediaPlayer.isPlaying()) {
            return;
        }
        File musicFile = new File(filePath);
        if (musicFile.exists()) {                  //不是文件夹，而是音乐文件
            try {
                mMediaPlayer.reset();
                mMediaPlayer.setDataSource(filePath);
                mMediaPlayer.prepare();
                mMediaPlayer.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 播放视频
     *
     * @param filePath 视频文件路径
     */
    private void playVideo(String filePath) {
        //此时没有在播放视频
        if (!mVvVideo.isPlaying()) {
            mVvVideo.setVisibility(View.VISIBLE);
            mVvVideo.setVideoPath(filePath);
            mVvVideo.start();
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    mHandler.sendEmptyMessage(HIDE_VIDEO_VIEW);
                }
            }, mVvVideo.getDuration() + 1500, 2000);         //周期性（2秒）地检查是否还在播放视频
        } else {
            //此时正在播放视频,当前视频播放完毕后再播放新的视频
        }
    }

    /**
     * TODO UDP接收器广播,当缓存完成时调用
     */
    public class LocalCDRBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean deleteMediaList = intent.getBooleanExtra("deleteMediaList", false);
            String filePath = "";

            if (deleteMediaList) {                  //判断    是否需要将当前分类多媒体文件集合清空
                imageList.clear();
                mIvShow.setImageResource(R.drawable.bg_main);

                txtList.clear();
//                vmtvDetail.setText("");
                mTvDetail.setText("");

                mMediaPlayer.reset();
            } else {                                //否则    传递的是文件路径，在这里获取文件路径
                filePath = Const.FILE_FOLDER + "/" + intent.getStringExtra("filePath");
            }

            if (filePath.endsWith(".png") || filePath.endsWith(".bmp") || filePath.endsWith(".jpg") || filePath.endsWith(".gif")) {     //1.显示图片
                //1.显示图片
                Bitmap bitmap = BitmapFactory.decodeFile(filePath);
                mIvShow.setImageBitmap(bitmap);

                imageList.add(filePath);
                currentImageIndex = imageList.indexOf(filePath);
                if (!isSlideShowImage) {                //没有播放幻灯片(图片)，则开始播放幻灯片
                    loadLanternSlideImage();            //加载幻灯片,只用调用一次也只能调用一次
                    isSlideShowImage = true;
                }

                Toast.makeText(CustomApplication.getContext(), "收到    图片", Toast.LENGTH_LONG).show();
            } else if (filePath.endsWith(".txt")) {
                //2.显示文字
                txtList.add(filePath);
//                vmtvDetail.setText(Const.LINE_HEAD + loadText(txtList));
                mTvDetail.setText(MessageFormat.format("{0}{1}", Const.LINE_HEAD, loadText(txtList)));

                Toast.makeText(CustomApplication.getContext(), "收到    文字", Toast.LENGTH_LONG).show();
            } else if (filePath.endsWith(".mp3") || filePath.endsWith(".wav")) {                                                        //4.播放音乐
                initMediaPlayerAndPlayMusic(filePath);

                String finalFilePath = filePath;
                mTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (!mMediaPlayer.isPlaying()) {
                            initMediaPlayerAndPlayMusic(finalFilePath);
                        }
                    }
                }, 5000, 10000);

                Toast.makeText(CustomApplication.getContext(), "收到    音频", Toast.LENGTH_LONG).show();
            } else if (filePath.endsWith(".avi") || filePath.endsWith(".mp4") || filePath.endsWith(".rmvb") || filePath.endsWith(".wmv") || filePath.endsWith(".3gp")) {    //3.显示视频

                Toast.makeText(CustomApplication.getContext(), "收到    视频", Toast.LENGTH_LONG).show();
            }
        }
    }
}
