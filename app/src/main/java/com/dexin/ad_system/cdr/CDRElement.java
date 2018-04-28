package com.dexin.ad_system.cdr;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置表中的某一元素项
 */
public class CDRElement {
    private int mVersionNumber = -1;//版本号
    private final List<Integer> mSectionsNumberList = new ArrayList<>();//存放段号的集合(最多65536个段)。

    public int getVersionNumber() {
        return mVersionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        mVersionNumber = versionNumber;
    }

    public List<Integer> getSectionsNumberList() {
        return mSectionsNumberList;
    }
}
