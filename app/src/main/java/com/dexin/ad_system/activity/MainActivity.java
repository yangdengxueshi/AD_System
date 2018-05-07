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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import com.blankj.utilcode.util.FileUtils;
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

public class MainActivity extends BaseActivity {

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

        if (AppConfig.getSPUtils().getBoolean(AppConfig.KEY_FIRST_LAUNCH, true)) {
            configAppParams();
        } else {
            FileUtils.createOrExistsDir(AppConfig.MEDIA_FILE_FOLDER);
            stopService(new Intent(CustomApplication.getContext(), LongRunningUDPService.class));
            startService(new Intent(CustomApplication.getContext(), LongRunningUDPService.class));
            RxToast.warning("数据接收与解析服务已启动!");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLanternSlideInOnResume();
        startMarqueeTextViewInOnResume();
        registerForContextMenuInOnResume();
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
        unregisterForContextMenuInOnDestroy();
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

    private void registerForContextMenuInOnResume() {
        registerForContextMenu(mVMenu);
    }

    private void unregisterForContextMenuInOnDestroy() {
        unregisterForContextMenu(mVMenu);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo contextMenuInfo) {
        getMenuInflater().inflate(R.menu.menu_context, menu);
        menu.setHeaderTitle("程序设置");
        menu.setHeaderIcon(R.drawable.icon_settings);
        super.onCreateContextMenu(menu, view, contextMenuInfo);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.local_wifi_net_set://FIXME 本地Wifi网络设置:
                startActivity(WIFI_SETTINGS_INTENT);
                break;
            case R.id.server_port_set://FIXME 设置"端口号"
                configAppParams();
                break;
        }
        return super.onContextItemSelected(item);
    }

    /**
     * 配置App参数
     */
    private void configAppParams() {
        final int[] lPortValueArr = {AppConfig.getSPUtils().getInt(AppConfig.KEY_DATA_RECEIVE_PORT, AppConfig.DEFAULT_DATA_RECEIVE_PORT)};
        View lConfigLayout = getLayoutInflater().inflate(R.layout.layout_config, null);
        EditText lEtPort = lConfigLayout.findViewById(R.id.et_port);
        lEtPort.setText(String.valueOf(lPortValueArr[0]));
        lEtPort.setSelection(lEtPort.getText().toString().length());
        new AlertDialog.Builder(MainActivity.this).setIcon(R.drawable.icon_settings).setTitle(R.string.data_receive_port_config).setView(lConfigLayout)
                .setPositiveButton("确定", (dialog, which) -> {
                    String lPortValueEdited = lEtPort.getText().toString();
                    if (TextUtils.isEmpty(lPortValueEdited)) {
                        RxToast.warning("端口号不能为空!");
                        configAppParams();
                        return;
                    }
                    lPortValueArr[0] = Integer.valueOf(lPortValueEdited);
                    if (0 <= lPortValueArr[0] && lPortValueArr[0] <= 65535) {
                        AppConfig.getSPUtils().put(AppConfig.KEY_FIRST_LAUNCH, false);
                        AppConfig.getSPUtils().put(AppConfig.KEY_DATA_RECEIVE_PORT, lPortValueArr[0]);
                        stopService(new Intent(CustomApplication.getContext(), LongRunningUDPService.class));
                        startService(new Intent(CustomApplication.getContext(), LongRunningUDPService.class));
                        RxToast.warning("数据接收与解析服务已启动!");
                    } else {
                        RxToast.error("端口范围有误(0~65535),请重新输入!");
                        configAppParams();
                    }
                }).show();
    }

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
                                lTvFlipping.setTextSize(20F);
                                lTvFlipping.setTextColor(Color.GREEN);
                                lTvFlipping.setText(loadText(txtPath));
                                lViewList.add(lTvFlipping);
                            }
                            mTvvmTxt.setViews(lViewList);
                            if (lViewList.size() <= 1) mTvvmTxt.stopFlipping();
                            RxToast.warning("收到    新文本");
                        } else if (mFilePath.endsWith(".mp3") || mFilePath.endsWith(".wav")) {                                                          //③播放音频
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
                                    mMusicPlayerHandler.postDelayed(this, 30 * 1000);//放在首行,始终向后检查
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
                            RxToast.warning("收到    新音频");
                        } else if (mFilePath.endsWith(".avi") || mFilePath.endsWith(".mp4") || mFilePath.endsWith(".rmvb")
                                || mFilePath.endsWith(".wmv") || mFilePath.endsWith(".3gp")) {                                                          //④播放视频
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
                                    mVideoPlayerHandler.postDelayed(this, 30 * 1000);//放在首行,始终向后检查
                                    try {
                                        if (mVideoPathList.isEmpty() || mMediaPlayer.isPlaying() || mVvVideo.isPlaying()) return;//列表无视频,音频 或 视频 正在播放,则终止本次逻辑
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
                Glide.with(CustomApplication.getContext()).load(imagePath).diskCacheStrategy(DiskCacheStrategy.RESULT).skipMemoryCache(true).into(mIvLanternSlideItem);
            }
        }
    }
}
