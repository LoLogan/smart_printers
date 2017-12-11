package com.qg.smpt.printer;

import com.qg.smpt.util.DebugUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by logan on 2017/11/30.
 */
public class test {
    public static void main(String[] args) {


        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            b[i] = (byte) ( Float.floatToIntBits(50) >> (24 - i * 8));
        }
        DebugUtil.printBytes(b);
    }
}
