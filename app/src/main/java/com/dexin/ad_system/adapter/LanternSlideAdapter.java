package com.dexin.ad_system.adapter;

import android.content.Context;
import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.dexin.ad_system.R;

import java.util.List;

/**
 * 幻灯片Adapter
 */
public final class LanternSlideAdapter extends ArrayAdapter<String> {
    private int mItemLayoutResId;

    public LanternSlideAdapter(Context context, int itemLayoutResId, List<String> filePathList) {           //TODO 可以传入 多个List 进来，其中某一个囊括了全部数据
        super(context, itemLayoutResId, filePathList);
        mItemLayoutResId = itemLayoutResId;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        String lImagePath = getItem(position);
//        if (!TextUtils.isEmpty(lImagePath)) {//本程序没必要做这一步判断
        ViewHolder viewHolder;
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(mItemLayoutResId, parent, false);
            viewHolder = new ViewHolder();
            viewHolder.mIvLanternSlideImage = convertView.findViewById(R.id.iv_lantern_slide_image);
            convertView.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) convertView.getTag();
        }
        Glide.with(getContext()).load(lImagePath).diskCacheStrategy(DiskCacheStrategy.RESULT).skipMemoryCache(true).into(viewHolder.mIvLanternSlideImage);
//        }
        return convertView;
    }

    private static final class ViewHolder {
        ImageView mIvLanternSlideImage;
    }
}
