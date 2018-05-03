package com.dexin.ad_system.activity;

import android.Manifest;
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
import android.text.TextUtils;
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
import com.dexin.ad_system.app.AppConfig;
import com.dexin.ad_system.app.CustomApplication;
import com.dexin.ad_system.service.LongRunningUDPService;
import com.vondear.rxtools.RxBarTool;
import com.vondear.rxtools.view.RxToast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import butterknife.BindView;
import butterknife.ButterKnife;
import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.OnNeverAskAgain;
import permissions.dispatcher.OnPermissionDenied;
import permissions.dispatcher.OnShowRationale;
import permissions.dispatcher.PermissionRequest;
import permissions.dispatcher.RuntimePermissions;

@RuntimePermissions
public class MainActivity extends AppCompatActivity {
    private static final int FLAG_HOMEKEY_DISPATCHED = 0x80000000;//HOME键分发标志

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
    private final List<String> imageList = new ArrayList<>();
    private final List<String> txtList = new ArrayList<>();
    private final Timer mTimer = new Timer();
    private int currentImageIndex;
    private boolean isSlideShowImage;   //程序一加载并没有正在播放幻灯片(图片)
    private final MediaPlayer mMediaPlayer = new MediaPlayer();

    //TODO 七、自定义Handler
    private final CustomHandler mCustomHandler = new CustomHandler(MainActivity.this);//声明为final

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RxBarTool.hideStatusBar(this);//隐藏掉状态栏
        getWindow().setFlags(FLAG_HOMEKEY_DISPATCHED, FLAG_HOMEKEY_DISPATCHED);    //设置为主屏幕的关键代码
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        //初始化成员变量
        initMemberVar();

        MainActivityPermissionsDispatcher.requestSDCardPermissionAndLaunchAppWithPermissionCheck(MainActivity.this);//用"权限分发器"检查程序是否能读写SD卡然后决定是否开启程序
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
        AppConfig.getLocalBroadcastManager().unregisterReceiver(mLocalCDRBroadcastReceiver);
        mCustomHandler.removeCallbacksAndMessages(null);


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

    @NonNull
    public static Intent createIntent(Context context) {
        return new Intent(context, MainActivity.class);
    }

    @NeedsPermission({Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE})
    void requestSDCardPermissionAndLaunchApp() {//在需要获取权限的地方注释
        if (AppConfig.getSPUtils().getBoolean("isFirstLaunch", true)) {//第一次启动App程序
            setServerIP_Port();//设置IP地址和端口号后（再开启服务）
        } else {//直接开启服务
            FileUtils.createOrExistsDir(AppConfig.FILE_FOLDER);//先创建本程序的媒体文件夹 "/AD_System"
            //先停止、再启动Service
            stopService(new Intent(CustomApplication.getContext(), LongRunningUDPService.class));
            startService(new Intent(CustomApplication.getContext(), LongRunningUDPService.class));
            RxToast.info("数据接收与解析服务已启动!");
        }
    }

    @OnShowRationale({Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE})
    void showSDCardPermissionRationale(PermissionRequest request) {//提示用户为何要开启SD卡读写权限
        new AlertDialog.Builder(MainActivity.this).setMessage("程序媒体文件操作依赖于对SD卡的读写!").setPositiveButton("确定", (dialog, which) -> request.proceed()).show();//再次执行权限请求
    }

    @OnPermissionDenied({Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE})
    void onSDCardPermissionDenied() {//用户选择了拒绝SD卡权限时的提示
        RxToast.warning("拒绝程序访问SD卡,程序将无法正常工作\n请到系统权限管理中重新开启!");
    }

    @OnNeverAskAgain({Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE})
    void onNeverAskAgainSDCardPermission() {//用户选择不再询问SD卡权限时的提示
        new AlertDialog.Builder(MainActivity.this).setMessage("拒绝程序访问SD卡,程序将无法正常工作\n请到系统权限管理中重新开启!").setPositiveButton("确定", (dialog, which) -> {
        }).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        MainActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    /**
     * 自定义Handler,防止延时消息导致内存泄漏
     */
    private static final class CustomHandler extends Handler {
        private final WeakReference<MainActivity> mCurActivityWeakReference;

        CustomHandler(MainActivity curActivity) {
            mCurActivityWeakReference = new WeakReference<>(curActivity);
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity curActivity = mCurActivityWeakReference.get();
            if (curActivity != null) {// + 判断curActivity的成员变量是否为 null
                switch (msg.what) {//TODO 执行消息业务逻辑
                    default:
                }
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {//屏蔽掉 Back键 和 Home键
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


        AppConfig.getLocalBroadcastManager().registerReceiver(mLocalCDRBroadcastReceiver, new IntentFilter(AppConfig.LOAD_FILE_OR_DELETE_MEDIA_LIST));

        //TODO 五、创建WifiLock和MulticastLock(Wifi锁 和 多播锁)
        WifiManager manager = (WifiManager) (getApplicationContext().getSystemService(Context.WIFI_SERVICE));
        if (manager != null) {
            mWifiLock = manager.createWifiLock("UDP_Wifi_Lock");
            mMulticastLock = manager.createMulticastLock("UDP_Multicast_Lock");
            //获得WifiLock和MulticastLock
            mWifiLock.acquire();
            mMulticastLock.acquire();
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
        new AlertDialog.Builder(this).setIcon(R.drawable.icon_settings).setTitle("服务器IP与端口配置").setView(tlConfig)
                .setNegativeButton("取消", (dialog, which) -> {
                })
                .setPositiveButton("确定", (dialog, which) -> {//TODO 此处可执行逻辑处理
                    String ipStr = etIP.getText().toString();
                    String portStr = etPort.getText().toString();
                    if (TextUtils.isEmpty(portStr)) {
                        RxToast.error("端口号不能为空!");
                        return;
                    }
                    int portValue = Integer.parseInt(portStr);

                    //判断IP输入和端口输入的格式是否正确,如果正确，将其写入SP，如果错误，给出提示。
                    if (RegexUtils.isIP(ipStr) && (0 <= portValue && portValue <= 65535)) {
                        AppConfig.getSPUtils().put("isFirstLaunch", false);
                        AppConfig.getSPUtils().put("ip", ipStr);
                        AppConfig.getSPUtils().put("port", portValue);             //点击了“确定”才认为APP不再是第一次启动了，并且设置了IP和端口号
                        stopService(new Intent(CustomApplication.getContext(), LongRunningUDPService.class));
                        startService(new Intent(CustomApplication.getContext(), LongRunningUDPService.class));
                        RxToast.info("数据接收与解析服务已启动!");
                    } else {
                        Toast.makeText(CustomApplication.getContext(), "您输入的 IP 或 端口 不符合格式要求。\n请长按屏幕重新设置。", Toast.LENGTH_LONG).show();
                        setServerIP_Port();//TODO 弹出对话框请求重新输入
                    }
                })
                .show();
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
                    mCustomHandler.post(() -> {
                        if (imageList.size() > 0) {
                            Bitmap bitmap = BitmapFactory.decodeFile(imageList.get(currentImageIndex));
                            mIvShow.setImageBitmap(bitmap);
                        }
                    });
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
    @NonNull
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
                        content.append(AppConfig.FORM_FEED_CHARACTER + AppConfig.LINE_HEAD);
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
                    mCustomHandler.post(() -> {
                        if (!mVvVideo.isPlaying()) {             //没有正在播放视频，就将vvVideoView销毁
                            mVvVideo.setVisibility(View.GONE);
                        }
                    });
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
            boolean deleteMediaList = intent.getBooleanExtra(AppConfig.KEY_DELETE_MEDIA_LIST, false);
            String filePath = "";

            if (deleteMediaList) {                  //判断    是否需要将当前分类多媒体文件集合清空
                imageList.clear();
                mIvShow.setImageResource(R.drawable.bg_main);

                txtList.clear();
//                vmtvDetail.setText("");
                mTvDetail.setText("");

                mMediaPlayer.reset();
            } else {                                //否则    传递的是文件路径，在这里获取文件路径
                filePath = AppConfig.FILE_FOLDER + "/" + intent.getStringExtra(AppConfig.KEY_FILE_NAME);
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
//                vmtvDetail.setText(AppConfig.LINE_HEAD + loadText(txtList));
                mTvDetail.setText(MessageFormat.format("{0}{1}", AppConfig.LINE_HEAD, loadText(txtList)));
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
