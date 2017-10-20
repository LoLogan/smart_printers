package com.qg.smpt.util;

import com.sun.org.apache.bcel.internal.generic.L2D;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by logan on 2017/9/28.
 */
public class Test {
    public static void main(String []args){
        List l1 = new ArrayList();
        l1.add("111");
        l1.add("222");
        l1.add(0,"000");
        System.out.println(l1);
    }
}
