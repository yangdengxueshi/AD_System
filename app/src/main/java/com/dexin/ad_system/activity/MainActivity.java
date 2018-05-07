package com.dexin.ad_system.activity;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
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
import com.vondear.rxtools.view.RxTextViewVerticalMore;
import com.vondear.rxtools.view.RxToast;
import com.zhouwei.mzbanner.MZBannerView;
import com.zhouwei.mzbanner.holder.MZHolderCreator;
import com.zhouwei.mzbanner.holder.MZViewHolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import butterknife.BindView;
import butterknife.ButterKnife;
import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.OnNeverAskAgain;
import permissions.dispatcher.OnPermissionDenied;
import permissions.dispatcher.OnShowRationale;
import permissions.dispatcher.PermissionRequest;
import permissions.dispatcher.RuntimePermissions;

@RuntimePermissions
public class MainActivity extends BaseActivity {
    private static final String TAG = "TAG_MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        initLanternSlideResourceInOnCreate();
        initMarqueeTextViewInOnCreate();
        initMediaPlayerInOnCreate();
        initVideoResourceInOnCreate();
        initDataTableReceiverResourceInOnCreate();

        MainActivityPermissionsDispatcher.requestSDCardPermissionAndLaunchAppWithPermissionCheck(MainActivity.this);//用"权限分发器"检查程序是否能读写SD卡然后决定是否开启程序
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLanternSlideInOnResume();
        startMarqueeTextViewInOnResume();
        registerForContextMenu(mVMenu);
    }

    @Override
    protected void onPause() {
        pauseLanternSlideInOnPause();
        stopMarqueeTextViewInOnPause();
        releaseVideoResourceInOnPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterForContextMenu(mVMenu);
        releaseLanternSlideResourceInOnDestroy();
        releaseMarqueeTextViewResourceInOnDestroy();
        releaseMusicPlayerResourceInOnDestroy();
        releaseDataTableResourceInOnDestroy();
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
            RxToast.warning("数据接收与解析服务已启动!");
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
                        RxToast.warning("数据接收与解析服务已启动!");
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

    @BindView(R.id.v_menu)
    View mVMenu;

    //------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------FIXME 幻灯片-------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓-------------------------------------------------------------------------------------------
    @BindView(R.id.mzbv_lantern_slide_view)
    MZBannerView<String> mMzbvLanternSlideView;

    private void initLanternSlideResourceInOnCreate() {
        mMzbvLanternSlideView.setDelayedTime(8000);
    }

    private void startLanternSlideInOnResume() {
        mMzbvLanternSlideView.start();
    }

    private void pauseLanternSlideInOnPause() {
        mMzbvLanternSlideView.pause();
    }

    private void releaseLanternSlideResourceInOnDestroy() {
        mMzbvLanternSlideView.removeAllViews();
    }

    //------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------FIXME MarqueeTextView---------------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓---------------------------------------------------------------------------------
    @BindView(R.id.tvvm_txt)
    RxTextViewVerticalMore mTvvmTxt;

    private void initMarqueeTextViewInOnCreate() {
        mTvvmTxt.setFlipInterval(8000);
    }

    private void startMarqueeTextViewInOnResume() {
        if (!mTvvmTxt.isFlipping()) mTvvmTxt.startFlipping();
    }

    private void stopMarqueeTextViewInOnPause() {
        if (mTvvmTxt.isFlipping()) mTvvmTxt.stopFlipping();
    }

    private void releaseMarqueeTextViewResourceInOnDestroy() {
        mTvvmTxt.removeAllViews();
    }

    //------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------FIXME MediaPlayer 音乐播放器----------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓----------------------------------------------------------------------------
    private static final Handler mMusicPlayerHandler = new Handler();
    private final MediaPlayer mMediaPlayer = new MediaPlayer();//音乐播放器

    private void initMediaPlayerInOnCreate() {
        mMediaPlayer.setOnPreparedListener(mediaPlayer -> {
            RxToast.info("开始播放 音频!");
            {//释放视频
                mVvVideo.suspend();
                if (mVvVideo.getVisibility() == View.VISIBLE) mVvVideo.setVisibility(View.GONE);
            }
            mediaPlayer.start();
        });
        mMediaPlayer.setOnCompletionListener(mediaPlayer -> RxToast.info("本段 音频 播放完毕!"));
    }

    private void releaseMusicPlayerResourceInOnDestroy() {
        mMusicPlayerHandler.removeCallbacksAndMessages(null);
        mMediaPlayer.stop();
        mMediaPlayer.release();
    }

    //------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------FIXME VideoView 视频播放器-----------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓-----------------------------------------------------------------------------
    @BindView(R.id.vv_video)
    VideoView mVvVideo;
    private static final Handler mVideoPlayerHandler = new Handler();

    private void initVideoResourceInOnCreate() {
        mVvVideo.setOnPreparedListener(mediaPlayer -> {
            RxToast.info("开始播放 视频!");
            mMediaPlayer.reset();//释放音频
            mVvVideo.start();
        });
        mVvVideo.setOnCompletionListener(mediaPlayer -> {
            mVvVideo.setVisibility(View.GONE);
            RxToast.info("本段 视频 播放完毕!");
        });
    }

    private void releaseVideoResourceInOnPause() {
        mVideoPlayerHandler.removeCallbacksAndMessages(null);
        mVvVideo.suspend();
    }

    //------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------FIXME 数据表广播接收器----------------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓----------------------------------------------------------------------------------
    private DataTableReceiver mDataTableReceiver;//FIXME 数据表接收器广播

    /**
     * 初始化 数据表接收器广播 资源
     */
    private void initDataTableReceiverResourceInOnCreate() {
        mDataTableReceiver = new DataTableReceiver();
        IntentFilter lIntentFilter = new IntentFilter();
        lIntentFilter.addAction(AppConfig.ACTION_RECEIVE_CONFIG_TABLE);//收到配置表
        lIntentFilter.addAction(AppConfig.ACTION_RECEIVE_ELEMENT_TABLE);//收到元素表
        AppConfig.getLocalBroadcastManager().registerReceiver(mDataTableReceiver, lIntentFilter);
    }

    /**
     * 释放 数据表接收器广播 资源
     */
    private void releaseDataTableResourceInOnDestroy() {
        AppConfig.getLocalBroadcastManager().unregisterReceiver(mDataTableReceiver);
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
        private final List<String> mMusicPathList = new ArrayList<>();//"音乐路径"列表
        private int mNextMusicIndex;//下一段音频路径
        private final List<String> mVideoPathList = new ArrayList<>();//"视频路径"列表
        private int mNextVideoIndex;//下一段视频路径
        private final MZHolderCreator mMZLanternSlideHolderCreator = MZBVLanternSlideViewHolder::new;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (AppConfig.isComponentAlive(MainActivity.this) && (intent != null)) {
                switch (Objects.requireNonNull(intent.getAction())) {
                    case AppConfig.ACTION_RECEIVE_CONFIG_TABLE://1.收到"新版本的配置表":
                        RxToast.warning("接收新数据中...,清除原数据!!!");
                        //①释放幻灯资源
                        mMzbvLanternSlideView.pause();
                        mMzbvLanternSlideView.removeAllViews();
                        mMzbvLanternSlideView.setVisibility(View.GONE);

                        //②释放文本资源
                        mTvvmTxt.stopFlipping();
                        mTvvmTxt.removeAllViews();
                        mTvvmTxt.setVisibility(View.GONE);

                        //③释放音乐资源
                        mMediaPlayer.reset();
                        mNextMusicIndex = 0;

                        //④释放视频资源
                        mVvVideo.suspend();
                        mVvVideo.setVisibility(View.GONE);
                        mNextVideoIndex = 0;

                        //⑤清空全部集合
                        mImagePathList.clear();
                        mTxtPathList.clear();
                        mMusicPathList.clear();
                        mVideoPathList.clear();
                        mFilePathList.clear();
                        break;
                    case AppConfig.ACTION_RECEIVE_ELEMENT_TABLE://2.收到"新版本的元素表":
                        if (!new File(mFilePath = MessageFormat.format("{0}/{1}", AppConfig.MEDIA_FILE_FOLDER, intent.getStringExtra(AppConfig.KEY_FILE_NAME))).exists()) break;
                        if (!mFilePathList.contains(mFilePath)) {
                            mFilePathList.add(mFilePath);
                        } else {
                            break;
                        }

                        if (mFilePath.endsWith(".png") || mFilePath.endsWith(".bmp") || mFilePath.endsWith(".jpg") || mFilePath.endsWith(".gif")) {     //①播放幻灯片
                            if (mMzbvLanternSlideView.getVisibility() == View.GONE) mMzbvLanternSlideView.setVisibility(View.VISIBLE);
                            mImagePathList.add(0, mFilePath);
                            mMzbvLanternSlideView.pause();
                            mMzbvLanternSlideView.setPages(mImagePathList, mMZLanternSlideHolderCreator);
                            mMzbvLanternSlideView.start();
                            RxToast.warning("收到    新图片");
                        } else if (mFilePath.endsWith(".txt")) {                                                                                        //②播放文字
                            mTvvmTxt.stopFlipping();//始终先释放资源
                            mTvvmTxt.removeAllViews();
                            if (mTvvmTxt.getVisibility() == View.GONE) mTvvmTxt.setVisibility(View.VISIBLE);
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
                            if (lViewList.size() <= 1) mTvvmTxt.stopFlipping();
                            RxToast.warning("收到    新文本");
                        } else if (mFilePath.endsWith(".mp3") || mFilePath.endsWith(".wav")) {                                                          //③播放音频
                            if (mMusicPathList.size() > 0) mNextMusicIndex = mNextMusicIndex % mMusicPathList.size();
                            mMusicPathList.add(mNextMusicIndex, mFilePath);//加入 新的音频文件 到当前正在播放的音频位置
                            {//重置 MediaPlayer 和 VideoView 之后,isPlaying == false
                                mMediaPlayer.reset();
                                mVvVideo.suspend();
                                if (mVvVideo.getVisibility() == View.VISIBLE) mVvVideo.setVisibility(View.GONE);
                            }

                            mMusicPlayerHandler.removeCallbacksAndMessages(null);
                            mMusicPlayerHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mMusicPlayerHandler.postDelayed(this, 30 * 1000);//放在首行,始终向后检查
                                    try {
                                        if (mMediaPlayer.isPlaying() || mVvVideo.isPlaying()) return;//音频 或 视频 正在播放,则终止本次逻辑
                                        mMediaPlayer.reset();
                                        mMediaPlayer.setDataSource(mMusicPathList.get(mNextMusicIndex++ % mMusicPathList.size()));
                                        mMediaPlayer.prepareAsync();
                                    } catch (Exception e) {
                                        Logger.t(TAG).e(e, "run: ");
                                    }
                                }
                            });
                            RxToast.warning("收到    新音频");
                        } else if (mFilePath.endsWith(".avi") || mFilePath.endsWith(".mp4") || mFilePath.endsWith(".rmvb")
                                || mFilePath.endsWith(".wmv") || mFilePath.endsWith(".3gp")) {                                                          //④播放视频
                            if (mVideoPathList.size() > 0) mNextVideoIndex = mNextVideoIndex % mVideoPathList.size();
                            mVideoPathList.add(mNextVideoIndex, mFilePath);//加入 新的视频文件 到当前正在播放的视频位置
                            {//重置 MediaPlayer 和 VideoView 之后,isPlaying == false
                                mMediaPlayer.reset();
                                mVvVideo.suspend();
                            }

                            mVideoPlayerHandler.removeCallbacksAndMessages(null);
                            mVideoPlayerHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mVideoPlayerHandler.postDelayed(this, 30 * 1000);
                                    try {
                                        if (mMediaPlayer.isPlaying() || mVvVideo.isPlaying()) return;//音频 或 视频 正在播放,则终止本次逻辑
                                        if (mVvVideo.getVisibility() == View.GONE) mVvVideo.setVisibility(View.VISIBLE);
                                        mVvVideo.setVideoPath(mVideoPathList.get(mNextVideoIndex++ % mVideoPathList.size()));
                                    } catch (Exception e) {
                                        Logger.t(TAG).e(e, "run: ");
                                    }
                                }
                            });
                            RxToast.warning("收到    新视频");
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
                lBufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(txtFilePath), AppConfig.UTF_8_CHAR_SET));
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

        private final class MZBVLanternSlideViewHolder implements MZViewHolder<String> {
            private ImageView mIvLanternSlideItem;

            @Override//FIXME 返回页面布局
            public View createView(Context context) {
                mIvLanternSlideItem = new ImageView(CustomApplication.getContext());
                mIvLanternSlideItem.setScaleType(ImageView.ScaleType.FIT_XY);
                return mIvLanternSlideItem;
            }

            @Override//FIXME 绑定数据
            public void onBind(Context context, int position, String imagePath) {
                Glide.with(context).load(imagePath).diskCacheStrategy(DiskCacheStrategy.RESULT).crossFade().skipMemoryCache(true).into(mIvLanternSlideItem);
            }
        }
    }
}
