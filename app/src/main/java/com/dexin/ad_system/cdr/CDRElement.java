package com.dexin.ad_system.cdr;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置表中的某一元素项
 */
class CDRElement {
    private long elementGUID = 0;          //元素GUID
    private int versionNumber = -1;        //版本号
    private List<Integer> sectionsNumberList = new ArrayList<>();       //存放段号的集合(最多65536个段)。

    public long getElementGUID() {
        return elementGUID;
    }

    public void setElementGUID(long elementGUID) {
        this.elementGUID = elementGUID;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public List<Integer> getSectionsNumberList() {
        return sectionsNumberList;
    }

    public void setSectionsNumberList(List<Integer> sectionsNumberList) {
        this.sectionsNumberList = sectionsNumberList;
    }
}
