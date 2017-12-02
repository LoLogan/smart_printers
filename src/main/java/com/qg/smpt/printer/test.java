package com.qg.smpt.printer;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by logan on 2017/11/30.
 */
public class test {
    public static void main(String[] args) {
        List a = new ArrayList();
        a.add(1);
        a.add(2);
        List b = new ArrayList();
        b.add(3);
        b.add(4);

        a.addAll(b);
        a.removeAll(a.subList(a.size()-2,a.size()));

        System.out.println(a);
    }
}
