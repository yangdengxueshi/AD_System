package com.dexin.ad_system.activity;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
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
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.dexin.ad_system.R;
import com.dexin.ad_system.app.AppConfig;
import com.dexin.ad_system.app.CustomApplication;
import com.dexin.ad_system.service.LongRunningUDPService;
import com.orhanobut.logger.Logger;
import com.vondear.rxtools.RxBarTool;
import com.vondear.rxtools.view.RxTextViewVerticalMore;
import com.vondear.rxtools.view.RxToast;
import com.zhouwei.mzbanner.MZBannerView;
import com.zhouwei.mzbanner.holder.MZHolderCreator;
import com.zhouwei.mzbanner.holder.MZViewHolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    @BindView(R.id.mzbv_lantern_slide_view)
    MZBannerView mMzbvLanternSlideView;
    @BindView(R.id.tvvm_txt)
    RxTextViewVerticalMore mTvvmTxt;
    @BindView(R.id.vv_video)
    VideoView mVvVideo;
    @BindView(R.id.v_menu)
    View mVMenu;

    private DataTableReceiver mDataTableReceiver;//FIXME 数据表接收器广播

    //FIXME 五、创建 WiFi锁 和 多播锁
    private WifiManager.WifiLock mWifiLock;
    private WifiManager.MulticastLock mMulticastLock;

    private final MediaPlayer mMediaPlayer = new MediaPlayer();//音乐播放器
    private final CustomHandler mCustomHandler = new CustomHandler(MainActivity.this);//自定义Handler

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(null);
        getWindow().setFlags(FLAG_HOMEKEY_DISPATCHED, FLAG_HOMEKEY_DISPATCHED);    //设置为主屏幕的关键代码
        RxBarTool.hideStatusBar(this);//隐藏掉状态栏
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        //初始化成员变量
        initMemberVar();

        MainActivityPermissionsDispatcher.requestSDCardPermissionAndLaunchAppWithPermissionCheck(MainActivity.this);//用"权限分发器"检查程序是否能读写SD卡然后决定是否开启程序
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMzbvLanternSlideView.start();
        registerForContextMenu(mVMenu);
    }

    @Override
    protected void onPause() {
        mMzbvLanternSlideView.pause();
        mTvvmTxt.stopFlipping();
        mTvvmTxt.removeAllViews();
        mVvVideo.suspend();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        //注销工作
        unregisterForContextMenu(mVMenu);
        AppConfig.getLocalBroadcastManager().unregisterReceiver(mDataTableReceiver);
        mCustomHandler.removeCallbacksAndMessages(null);

        //释放WifiLock和MulticastLock
        if ((mWifiLock != null) && mWifiLock.isHeld()) mWifiLock.release();
        if ((mMulticastLock != null) && mMulticastLock.isHeld()) mMulticastLock.release();

        //释放 音乐播放器
        mMediaPlayer.stop();
        mMediaPlayer.release();

        if (mVvVideo != null) mVvVideo.suspend();//暂停视频
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
            FileUtils.createOrExistsDir(AppConfig.MEDIA_FILE_FOLDER);//先创建本程序的媒体文件夹 "/AD_System"
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
        //FIXME UI
        mMzbvLanternSlideView.setDelayedTime(8000);
        mTvvmTxt.setFlipInterval(8000);
        //FIXME 三、广播
        mDataTableReceiver = new DataTableReceiver();
        IntentFilter lIntentFilter = new IntentFilter();
        lIntentFilter.addAction(AppConfig.ACTION_RECEIVE_CONFIG_TABLE);//收到配置表
        lIntentFilter.addAction(AppConfig.ACTION_RECEIVE_ELEMENT_TABLE);//收到元素表
        AppConfig.getLocalBroadcastManager().registerReceiver(mDataTableReceiver, lIntentFilter);

        //TODO 五、创建 Wifi锁 和 多播锁
        WifiManager manager = (WifiManager) (getApplicationContext().getSystemService(Context.WIFI_SERVICE));
        if (manager != null) {
            mWifiLock = manager.createWifiLock("Wifi_Lock");
            mMulticastLock = manager.createMulticastLock("Multicast_Lock");
            mWifiLock.acquire();//获得WifiLock和MulticastLock
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
     * FIXME 数据表接收器
     */
    private final class DataTableReceiver extends BroadcastReceiver {
        private static final String TAG = "TAG_DataTableReceiver";
        private String mFilePath;
        private final List<String> mFilePathList = new ArrayList<>(); //"文件路径"列表
        private final List<String> mImagePathList = new ArrayList<>();//"图片路径"列表
        private final List<String> mTxtPathList = new ArrayList<>();  //"文本路径"列表
        private final MZHolderCreator mMZLanternSlideHolderCreator = MZBVLanternSlideViewHolder::new;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (AppConfig.isComponentAlive(MainActivity.this) && (intent != null)) {
                switch (Objects.requireNonNull(intent.getAction())) {
                    case AppConfig.ACTION_RECEIVE_CONFIG_TABLE://1.收到"新版本的配置表":
                        RxToast.info("接收新数据中,清除原数据!");
                        //①释放图片资源
                        mMzbvLanternSlideView.pause();
                        mMzbvLanternSlideView.setVisibility(View.GONE);
                        mImagePathList.clear();

                        //②释放文本资源
                        mTvvmTxt.stopFlipping();
                        mTvvmTxt.removeAllViews();
                        mTxtPathList.clear();

                        //③释放音乐资源
                        mMediaPlayer.reset();

                        //④释放视频资源

                        //⑤释放公共资源
                        mFilePathList.clear();
                        break;
                    case AppConfig.ACTION_RECEIVE_ELEMENT_TABLE://2.收到"新版本的元素表":
                        mFilePath = MessageFormat.format("{0}/{1}", AppConfig.MEDIA_FILE_FOLDER, intent.getStringExtra(AppConfig.KEY_FILE_NAME));
                        if (!isFileExists(mFilePath)) break;
                        if (!mFilePathList.contains(mFilePath)) {
                            mFilePathList.add(mFilePath);
                        } else {
                            break;
                        }

                        if (mFilePath.endsWith(".png") || mFilePath.endsWith(".bmp") || mFilePath.endsWith(".jpg") || mFilePath.endsWith(".gif")) {     //①播放幻灯片
                            mMzbvLanternSlideView.pause();//始终先释放资源
                            if (mMzbvLanternSlideView.getVisibility() == View.GONE) mMzbvLanternSlideView.setVisibility(View.VISIBLE);
                            mImagePathList.add(0, mFilePath);
                            mMzbvLanternSlideView.setPages(mImagePathList, mMZLanternSlideHolderCreator);
                            if (mImagePathList.size() > 1) mMzbvLanternSlideView.start();//再根据情况开启
                            RxToast.info("收到    图片");
                        } else if (mFilePath.endsWith(".txt")) {                                                                                        //②播放文字
                            mTvvmTxt.stopFlipping();//始终先释放资源
                            mTvvmTxt.removeAllViews();
                            mTxtPathList.add(0, mFilePath);
                            List<View> lViewList = new ArrayList<>();
                            for (String txtPath : mTxtPathList) {
                                TextView lTvFlipping = new TextView(CustomApplication.getContext());
                                lTvFlipping.setTextSize(60F);
                                lTvFlipping.setTextColor(Color.GREEN);
                                lTvFlipping.setText(loadText(txtPath));
                                lViewList.add(lTvFlipping);
                            }
                            mTvvmTxt.setViews(lViewList);
                            RxToast.info("收到    文字");
                        } else if (mFilePath.endsWith(".mp3") || mFilePath.endsWith(".wav")) {                                                          //③播放音乐
                            mCustomHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    initMediaPlayerAndPlayMusic(mFilePath);
                                    mCustomHandler.postDelayed(this, 10 * 1000);
                                }
                            });
                            RxToast.info("收到    音频");
                        } else if (mFilePath.endsWith(".avi") || mFilePath.endsWith(".mp4") || mFilePath.endsWith(".rmvb") || mFilePath.endsWith(".wmv") || mFilePath.endsWith(".3gp")) {    //④显示视频
                            RxToast.info("收到    视频");
                        }
                        break;
                    default:
                }
            }
        }

        /**
         * 根据文本文件路径加载文字
         *
         * @param txtFilePath 文本文件路径
         * @return 文本文件中的文字
         */
        @NonNull
        private String loadText(String txtFilePath) {
            StringBuilder lStringBuilder = new StringBuilder("        ");
            BufferedReader lBufferedReader = null;
            try {
                lBufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(txtFilePath)));
                String lLineTxt;
                while ((lLineTxt = lBufferedReader.readLine()) != null) {
                    lStringBuilder.append(lLineTxt);
                }
            } catch (Exception e) {
                Logger.t(TAG).e(e, "loadText: ");
            } finally {
                try {
                    if (lBufferedReader != null) lBufferedReader.close();
                } catch (Exception e) {
                    Logger.t(TAG).e(e, "loadText: ");
                }
            }
            return lStringBuilder.toString();
        }

        /**
         * 初始化MediaPlayer 并且 播放音乐
         *
         * @param musicFilePath 音乐文件路径
         */
        private void initMediaPlayerAndPlayMusic(String musicFilePath) {
            if (mMediaPlayer.isPlaying()) return;
            try {
                mMediaPlayer.reset();
                mMediaPlayer.setDataSource(musicFilePath);
                mMediaPlayer.prepare();
                mMediaPlayer.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private boolean isFileExists(String filePath) {
            File lFile = new File(filePath);
            return lFile.exists();
        }
    }

    private static final class MZBVLanternSlideViewHolder implements MZViewHolder<String> {
        private ImageView mIvLanternSlideItem;

        @Override//返回页面布局
        public View createView(Context context) {
            if (mIvLanternSlideItem == null) {
                mIvLanternSlideItem = new ImageView(CustomApplication.getContext());
                mIvLanternSlideItem.setScaleType(ImageView.ScaleType.FIT_XY);
            }
            return mIvLanternSlideItem;
        }

        @Override//绑定数据
        public void onBind(Context context, int position, String imagePath) {
            Glide.with(context).load(imagePath).diskCacheStrategy(DiskCacheStrategy.RESULT).skipMemoryCache(true).into(mIvLanternSlideItem);
        }
    }
}
