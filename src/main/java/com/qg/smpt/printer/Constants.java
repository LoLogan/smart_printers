package com.qg.smpt.printer;

/**
 * 打印机所属常亮
 */
public class Constants {
    public static final int MAX_TRANSFER_SIZE = 1024 * 10;       //批次最大容量为7k

    public static final short SEND_INTERVAL = 5000;         //两次发送间隔

    public static final int ORDERS_FOR_A_CAPACITY = 10;     //一个打印能力对应的订单份数

    public static final int DYNAMICS_CYCLE = 5;     //取平均数的周期

}
