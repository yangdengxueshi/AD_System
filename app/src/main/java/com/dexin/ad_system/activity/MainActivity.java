package com.dexin.ad_system.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterViewFlipper;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.VideoView;

import com.dexin.ad_system.R;
import com.dexin.ad_system.adapter.LanternSlideAdapter;
import com.dexin.ad_system.app.AppConfig;
import com.dexin.ad_system.app.CustomApplication;
import com.dexin.ad_system.service.LongRunningUDPService;
import com.orhanobut.logger.Logger;
import com.vondear.rxtools.RxNetTool;
import com.vondear.rxtools.view.RxTextViewVerticalMore;
import com.vondear.rxtools.view.RxToast;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import butterknife.BindView;
import butterknife.ButterKnife;

public class MainActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        registerForContextMenuInOnCreate();
        initMarqueeTextViewInOnCreate();
        initMusicPlayerResourceInOnCreate();
        initVideoResourceInOnCreate();
        initDataTableReceiverResourceInOnCreate();
        startDataReceiveServiceInOnCreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLanternSlideInOnResume();
        startMarqueeTextViewInOnResume();
    }

    @Override
    protected void onPause() {
        stopLanternSlideInOnPause();
        stopMarqueeTextViewInOnPause();
        releaseVideoResourceInOnPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterForContextMenuInOnDestroy();
        stopDataReceiveService();
        releaseMarqueeTextViewResourceInOnDestroy();
        releaseMusicPlayerResourceInOnDestroy();
        releaseDataTableResourceInOnDestroy();
        super.onDestroy();
    }

    @NonNull
    public static Intent createIntent(Context context) {
        return new Intent(context, MainActivity.class);
    }


    //------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------FIXME 数据接收服务操作逻辑-------------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓-------------------------------------------------------------------------------
    private static final Intent lLongRunningUDPServiceIntent = new Intent(CustomApplication.getContext(), LongRunningUDPService.class);

    private void startDataReceiveService() {
        stopService(lLongRunningUDPServiceIntent);
        //TODO --------------------------------------------------------------------------------------------- Ping 服务器 并执行后续逻辑---------------------------------------------------------------------------------------------
        startService(lLongRunningUDPServiceIntent);
    }

    private void stopDataReceiveService() {
        stopService(lLongRunningUDPServiceIntent);
    }

    /**
     * 启动"数据接收服务"
     */
    private void startDataReceiveServiceInOnCreate() {
        if (!RxNetTool.isWifiConnected(CustomApplication.getContext())) {
            RxToast.warning(AppConfig.TIP_CONNECT_TO_WIFI);
        } else {
            if (AppConfig.getSPUtils().getBoolean(AppConfig.KEY_FIRST_CONFIG, true)) {
                configApplication();
            } else {
                startDataReceiveService();
            }
        }
    }

    //------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------FIXME 返回键被点击--------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓--------------------------------------------------------------------------------------
    private long TIME_BACK_PRESSED;//Back键被按下的时间间隔

    @Override
    public void onBackPressed() {
        if (TIME_BACK_PRESSED + 4 * 500 > System.currentTimeMillis()) {
            super.onBackPressed();
            return;
        } else {
            RxToast.warning("再次点击返回键退出");
        }
        TIME_BACK_PRESSED = System.currentTimeMillis();
    }

    //------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------FIXME 上下文菜单---------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓---------------------------------------------------------------------------------------
    private static final Intent WIFI_SETTINGS_INTENT = new Intent(Settings.ACTION_WIFI_SETTINGS);
    @BindView(R.id.v_menu)
    View mVMenu;

    private void registerForContextMenuInOnCreate() {
        registerForContextMenu(mVMenu);
    }

    private void unregisterForContextMenuInOnDestroy() {
        unregisterForContextMenu(mVMenu);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo contextMenuInfo) {
        if (!RxNetTool.isWifiConnected(CustomApplication.getContext())) {
            RxToast.warning(AppConfig.TIP_CONNECT_TO_WIFI);
            startActivity(WIFI_SETTINGS_INTENT);
        } else {
            getMenuInflater().inflate(R.menu.menu_context, menu);
            menu.setHeaderTitle("程序设置");
            menu.setHeaderIcon(R.drawable.icon_settings);
            menu.findItem(R.id.data_receive_set).setChecked(AppConfig.getSPUtils().getBoolean(AppConfig.KEY_DATA_RECEIVE_OR_NOT));
            super.onCreateContextMenu(menu, view, contextMenuInfo);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.local_wifi_net_set:
                startActivity(WIFI_SETTINGS_INTENT);
                break;
            case R.id.app_set:
                configApplication();
                break;
            case R.id.data_receive_set:
                if (item.isChecked()) {//原先被选中,现在再次点击关闭"数据接收"
                    stopDataReceiveService();
                } else {//原先未选中,现在再次点击开启"数据接收"
                    startDataReceiveService();
                }
                break;
        }
        return super.onContextItemSelected(item);
    }

    /**
     * 配置应用程序
     */
    private void configApplication() {
        View lConfigLayout = getLayoutInflater().inflate(R.layout.layout_config, null);
        EditText lEtPort = lConfigLayout.findViewById(R.id.et_port);
        NumberPicker lNpAudioVideoInterval = lConfigLayout.findViewById(R.id.np_audio_video_interval);
        lEtPort.setText(String.valueOf(AppConfig.getSPUtils().getInt(AppConfig.KEY_DATA_RECEIVE_PORT, AppConfig.DEFAULT_DATA_RECEIVE_PORT)));
        lEtPort.setSelection(lEtPort.getText().toString().length());
        lNpAudioVideoInterval.setMinValue(30);
        lNpAudioVideoInterval.setMaxValue(300);
        lNpAudioVideoInterval.setValue(AppConfig.getSPUtils().getInt(AppConfig.KEY_AUDIO_VIDEO_INTERVAL, AppConfig.DEFAULT_AUDIO_VIDEO_INTERVAL_VALUE));
        new AlertDialog.Builder(MainActivity.this).setCancelable(false).setIcon(R.drawable.icon_settings).setTitle(R.string.app_config).setView(lConfigLayout)
                .setPositiveButton("确定", (dialog, which) -> {
                    int lPortValueParsed = (TextUtils.isEmpty(lEtPort.getText().toString())) ? -1 : Integer.valueOf(lEtPort.getText().toString());
                    if ((0 <= lPortValueParsed) && (lPortValueParsed <= 65535)) {
                        if (AppConfig.getSPUtils().getBoolean(AppConfig.KEY_FIRST_CONFIG, true)) {
                            AppConfig.getSPUtils().put(AppConfig.KEY_FIRST_CONFIG, false);//缓存"程序从此不再是第一次启动"了
                        }
                        boolean configChanged = false;
                        if (AppConfig.getSPUtils().getInt(AppConfig.KEY_AUDIO_VIDEO_INTERVAL, AppConfig.DEFAULT_AUDIO_VIDEO_INTERVAL_VALUE) != lNpAudioVideoInterval.getValue()) {
                            configChanged = true;
                            AppConfig.getSPUtils().put(AppConfig.KEY_AUDIO_VIDEO_INTERVAL, lNpAudioVideoInterval.getValue());//缓存"音视频播放间隔"
                        }
                        if (AppConfig.getSPUtils().getInt(AppConfig.KEY_DATA_RECEIVE_PORT) != lPortValueParsed) {//"数据接收端口"已更改
                            configChanged = true;
                            AppConfig.getSPUtils().put(AppConfig.KEY_DATA_RECEIVE_PORT, lPortValueParsed);
                            startDataReceiveService();
                        }
                        RxToast.info(configChanged ? "新配置已生效!" : "您未对配置作出修改!");
                    } else {
                        RxToast.error("原端口输入有误(0~65535),请重新输入!");
                        configApplication();
                    }
                }).show();
    }

    //------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------FIXME 幻灯片-------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓-------------------------------------------------------------------------------------------
    @BindView(R.id.avf_lantern_slide_view)
    AdapterViewFlipper mAvfLanternSlideView;

    private void startLanternSlideInOnResume() {
        if ((mAvfLanternSlideView.getVisibility() == View.VISIBLE) && !mAvfLanternSlideView.isFlipping()) {
            mAvfLanternSlideView.startFlipping();
        }
    }

    private void stopLanternSlideInOnPause() {
        mAvfLanternSlideView.stopFlipping();
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

    private void initMusicPlayerResourceInOnCreate() {
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
    //------------------------------------------------------------------------------FIXME 接收信息显示--------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓--------------------------------------------------------------------------------------
    @BindView(R.id.tv_receive_info)
    TextView mTvReceiveInfo;


    //------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------FIXME 数据表广播接收器----------------------------------------------------------------------------------
    //------------------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓----------------------------------------------------------------------------------
    private DataReceiver mDataReceiver;//FIXME 数据接收器

    /**
     * 初始化 数据表接收器广播 资源
     */
    private void initDataTableReceiverResourceInOnCreate() {
        mDataReceiver = new DataReceiver();
        IntentFilter lIntentFilter = new IntentFilter();
        lIntentFilter.addAction(AppConfig.ACTION_RECEIVE_CONFIG_TABLE);//收到配置表
        lIntentFilter.addAction(AppConfig.ACTION_RECEIVE_ELEMENT_TABLE);//收到元素表
        lIntentFilter.addAction(AppConfig.ACTION_RECEIVE_DATA_INFO);//数据接收信息
        AppConfig.getLocalBroadcastManager().registerReceiver(mDataReceiver, lIntentFilter);
    }

    /**
     * 释放 数据表接收器广播 资源
     */
    private void releaseDataTableResourceInOnDestroy() {
        AppConfig.getLocalBroadcastManager().unregisterReceiver(mDataReceiver);
    }

    /**
     * FIXME 数据表接收器
     */
    private final class DataReceiver extends BroadcastReceiver {
        private static final String TAG = "TAG_DataTableReceiver";
        private String mFilePath;
        private final List<String> mFilePathList = new ArrayList<>(); //"文件路径"列表
        private final List<String> mImagePathList = new ArrayList<>();//"图片路径"列表
        private final List<String> mTxtPathList = new ArrayList<>();  //"文本路径"列表
        private final List<String> mMusicPathList = new ArrayList<>();//"音乐路径"列表
        private int mNextMusicIndex;//下一段音频路径
        private final List<String> mVideoPathList = new ArrayList<>();//"视频路径"列表
        private int mNextVideoIndex;//下一段视频路径
        private LanternSlideAdapter mLanternSlideAdapter;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (AppConfig.isComponentAlive(MainActivity.this) && (intent != null)) {
                switch (Objects.requireNonNull(intent.getAction())) {
                    case AppConfig.ACTION_RECEIVE_CONFIG_TABLE://1.收到"新版本的配置表":
                        RxToast.warning("接收新数据中...,清除原数据!!!");
                        //①释放幻灯资源
                        if (mLanternSlideAdapter == null) {
                            mLanternSlideAdapter = new LanternSlideAdapter(CustomApplication.getContext(), R.layout.item_view_flipper, mImagePathList);
                            mAvfLanternSlideView.setAdapter(mLanternSlideAdapter);
                        }
                        mAvfLanternSlideView.stopFlipping();
                        mAvfLanternSlideView.setVisibility(View.GONE);

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

                        if (mFilePath.endsWith(".txt")) {                                                                                                       //①播放文字
                            mTvvmTxt.stopFlipping();//始终先释放资源
                            mTvvmTxt.removeAllViews();
                            if (mTvvmTxt.getVisibility() == View.GONE) mTvvmTxt.setVisibility(View.VISIBLE);
                            mTxtPathList.add(0, mFilePath);
                            if (mTxtPathList.isEmpty()) break;
                            List<View> lViewList = new ArrayList<>();
                            for (String txtPath : mTxtPathList) {
                                TextView lTvFlipping = new TextView(CustomApplication.getContext());
                                lTvFlipping.setTextSize(20F);
                                lTvFlipping.setTextColor(Color.GREEN);
                                lTvFlipping.setText(AppConfig.loadTextInFile(txtPath));
                                lViewList.add(lTvFlipping);
                            }
                            mTvvmTxt.setViews(lViewList);
                            if (lViewList.size() <= 1) mTvvmTxt.stopFlipping();
                            RxToast.info("收到    新文本");
                        } else if (mFilePath.endsWith(".png") || mFilePath.endsWith(".bmp") || mFilePath.endsWith(".jpg") || mFilePath.endsWith(".gif")) {      //②播放幻灯片
                            if (mAvfLanternSlideView.getVisibility() == View.GONE) mAvfLanternSlideView.setVisibility(View.VISIBLE);
                            mImagePathList.add(0, mFilePath);
                            if (mImagePathList.isEmpty()) break;
                            mLanternSlideAdapter.notifyDataSetChanged();
                            if (!mAvfLanternSlideView.isFlipping()) mAvfLanternSlideView.startFlipping();
                            RxToast.info("收到    新图片");
                        } else if (mFilePath.endsWith(".mp3") || mFilePath.endsWith(".wav")) {                                                                  //③播放音频
                            if (!mMusicPathList.isEmpty()) mNextMusicIndex = mNextMusicIndex % mMusicPathList.size();
                            mMusicPathList.add(mNextMusicIndex, mFilePath);//加入 新的音频文件 到当前正在播放的音频位置
                            {//重置 MediaPlayer 和 VideoView 之后,isPlaying == false
                                mMediaPlayer.reset();
                                mVvVideo.suspend();
                            }

                            mMusicPlayerHandler.removeCallbacksAndMessages(null);
                            mMusicPlayerHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mMusicPlayerHandler.postDelayed(this, AppConfig.getSPUtils().getInt(AppConfig.KEY_AUDIO_VIDEO_INTERVAL, AppConfig.DEFAULT_AUDIO_VIDEO_INTERVAL_VALUE) * 1000L);//放在首行,始终向后检查
                                    try {
                                        if (mMusicPathList.isEmpty() || mMediaPlayer.isPlaying() || mVvVideo.isPlaying()) return;//列表无音频,音频 或 视频 正在播放,则终止本次逻辑
                                        mMediaPlayer.reset();
                                        mMediaPlayer.setDataSource(mMusicPathList.get(mNextMusicIndex++ % mMusicPathList.size()));
                                        mMediaPlayer.prepareAsync();
                                    } catch (Exception e) {
                                        Logger.t(TAG).e(e, "run: ");
                                    }
                                }
                            });
                            RxToast.info("收到    新音频");
                        } else if (mFilePath.endsWith(".avi") || mFilePath.endsWith(".mp4") || mFilePath.endsWith(".rmvb")
                                || mFilePath.endsWith(".wmv") || mFilePath.endsWith(".3gp")) {                                                                  //④播放视频
                            if (!mVideoPathList.isEmpty()) mNextVideoIndex = mNextVideoIndex % mVideoPathList.size();
                            mVideoPathList.add(mNextVideoIndex, mFilePath);//加入 新的视频文件 到当前正在播放的视频位置
                            {//重置 MediaPlayer 和 VideoView 之后,isPlaying == false
                                mMediaPlayer.reset();
                                mVvVideo.suspend();
                            }

                            mVideoPlayerHandler.removeCallbacksAndMessages(null);
                            mVideoPlayerHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mVideoPlayerHandler.postDelayed(this, AppConfig.getSPUtils().getInt(AppConfig.KEY_AUDIO_VIDEO_INTERVAL, AppConfig.DEFAULT_AUDIO_VIDEO_INTERVAL_VALUE) * 1000L);//放在首行,始终向后检查
                                    try {
                                        if (mVideoPathList.isEmpty() || mMediaPlayer.isPlaying() || mVvVideo.isPlaying()) return;//列表无视频,音频 或 视频 正在播放,则终止本次逻辑
                                        if (mVvVideo.getVisibility() == View.GONE) mVvVideo.setVisibility(View.VISIBLE);
                                        mVvVideo.setVideoPath(mVideoPathList.get(mNextVideoIndex++ % mVideoPathList.size()));
                                    } catch (Exception e) {
                                        Logger.t(TAG).e(e, "run: ");
                                    }
                                }
                            });
                            RxToast.info("收到    新视频");
                        }
                        break;
                    case AppConfig.ACTION_RECEIVE_DATA_INFO:
                        mTvReceiveInfo.setText(intent.getStringExtra(AppConfig.KEY_DATA_RECEIVE_INFO));
                        break;
                    default:
                }
            }
        }
    }
}
