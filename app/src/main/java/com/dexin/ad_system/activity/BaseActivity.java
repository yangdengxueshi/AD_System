package com.dexin.ad_system.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;

import com.vondear.rxtools.RxBarTool;

/**
 * BaseActivity
 */
@SuppressLint("Registered")
public class BaseActivity extends AppCompatActivity {
    private static final int FLAG_HOMEKEY_DISPATCHED = 0x80000000;//HOME键分发标志

    @NonNull
    public static Intent createIntent(Context context) {
        return new Intent(context, BaseActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(null);
        RxBarTool.hideStatusBar(this);//隐藏掉状态栏
        getWindow().setFlags(FLAG_HOMEKEY_DISPATCHED, FLAG_HOMEKEY_DISPATCHED);//设置为主屏幕的关键代码
    }
}
