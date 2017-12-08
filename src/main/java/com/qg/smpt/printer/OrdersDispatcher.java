package com.qg.smpt.printer;

import com.qg.smpt.share.ShareMem;
import com.qg.smpt.util.Level;
import com.qg.smpt.util.Logger;
import com.qg.smpt.web.model.Order;

import java.util.List;


/**
 * 委派订单
 * Created by logan on 2017/11/30.
 */
public class OrdersDispatcher implements Runnable{
    private final Logger LOGGER = Logger.getLogger(OrdersDispatcher.class);

    private int userId;

    private static final int MAX_TIME = 3;          //区分轻松状态和紧张状态的标准值

    private static final int MAX_NUM = 10;          //订单上限值，即一次最多能处理的订单数


    public boolean flag;

    public OrdersDispatcher(int userId){
        this.userId = userId;
        this.flag = true;
    }

    public void threadStart() {


        Thread thread = new Thread(this, this.userId+"");

        thread.setDaemon(true);

        thread.start();
    }

    public void run() {
        //标准值
        int standard = 0;
        Compact compact = new Compact();
        while (flag) {
            synchronized (ShareMem.userOrderBufferMap.get(userId)) {
                List<Order> orders = ShareMem.userOrderBufferMap.get(userId);
                int number = orders.size();
                LOGGER.log(Level.DEBUG, "当前订单数量为[{0}]", number);
                if (number > MAX_NUM) {
                    //达到上限值，立即启用合同网
                    LOGGER.log(Level.DEBUG, "达到订单上限值，启用合同网");
                    List<Order> bulkOrders = orders.subList(number-MAX_NUM, number);
                    compact.sendOrdersByCompact(0,bulkOrders);
                    orders.removeAll(bulkOrders);
                } else if (number <= MAX_NUM && number > MAX_NUM/2) {
                    //处于紧张状态，直接委派打印机下单，如果长期处于这种状态则通过合同网进行下单
                    if (standard >= MAX_TIME) {
                        //合同网下单
                        LOGGER.log(Level.DEBUG, "紧张状态，启用合同网，当前standard的值为[{0}]", standard);
                        compact.sendOrdersByCompact(0,orders);
                    } else {
                        //直接委派打印机下单
                        LOGGER.log(Level.DEBUG, "紧张状态，直接委派打印机下单，当前standard的值为[{0}]", standard);
                        compact.sendBulkDitectly(userId,0,orders);
                    }
                    standard++;
                    orders.clear();
                } else if(number <= MAX_NUM/2 && number > 0) {
                    //轻松状态，直接委派打印机下单
                    LOGGER.log(Level.DEBUG, "轻松状态，直接委派打印机下单，当前standard的值为[{0}]", standard);
                    compact.sendBulkDitectly(userId,0,orders);
                    standard--;
                    orders.clear();
                } else if (number == 0){
                    //内存订单数为0，睡眠
                    try {
                        LOGGER.log(Level.DEBUG, "内存无订单，进入睡眠");
                        ShareMem.userOrderBufferMap.get(userId).wait();
                        LOGGER.log(Level.DEBUG, "内存有订单，唤醒线程");
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

            try {
                Thread.sleep(1000*2);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}
