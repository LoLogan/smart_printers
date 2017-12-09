package com.qg.smpt.util;

import com.qg.smpt.share.ShareMem;
import com.qg.smpt.web.model.Printer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by logan on 2017/12/9.
 */
public class SortList<E> {

    public static void main(String[] args){
        List<Printer> printers=new ArrayList<Printer>();

        Printer printer = new Printer();
        printer.setCre((double) 1);
        printers.add(printer);

        printer = new Printer();
        printer.setCre((double) 2);
        printers.add(printer);

        printer = new Printer();
        printer.setCre((double) 3);
        printers.add(printer);

        SortList<Printer> sortList = new SortList<Printer>();
        sortList.Sort(printers, "getCre", "desc");
        System.out.println(printers.get(0).getCre());
        System.out.println(printers.get(1).getCre());
        System.out.println(printers.get(2).getCre());

        ShareMem.printerIdMap.put(printer.getId(),printer);
        printer.setCre((double) 12);
        printer.setPrice((double) 12);


    }


    public  void Sort(List<E> list, final String method, final String sort) {
        Collections.sort(list, new Comparator() {
            public int compare(Object a, Object b) {
                int ret = 0;
                try {
                    Method m1 = ((E) a).getClass().getMethod(method, null);
                    Method m2 = ((E) b).getClass().getMethod(method, null);
                    if (sort != null && "desc".equals(sort))// 倒序
                        ret = m2.invoke(((E) b), null).toString()
                                .compareTo(m1.invoke(((E) a), null).toString());
                    else
                        // 正序
                        ret = m1.invoke(((E) a), null).toString()
                                .compareTo(m2.invoke(((E) b), null).toString());
                } catch (NoSuchMethodException ne) {
                    System.out.println(ne);
                } catch (IllegalAccessException ie) {
                    System.out.println(ie);
                } catch (InvocationTargetException it) {
                    System.out.println(it);
                }
                return ret;
            }
        });
    }
}
