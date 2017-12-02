package com.qg.smpt.printer;

import com.qg.smpt.share.ShareMem;
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

    private static final int MAX_TIME = 3;

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

        while (flag) {
            //标准值
            int standard = 0;
            synchronized (ShareMem.userOrderBufferMap.get(userId)) {
                List<Order> orders = ShareMem.userOrderBufferMap.get(userId);
                int number = orders.size();

                if (number > 20) {
                    //达到上限值，立即启用合同网
                } else if (number <= 20 && number > 10) {
                    //处于紧张状态，直接委派打印机下单，如果长期处于这种状态则通过合同网进行下单
                    if (standard >= MAX_TIME) {
                        //合同网下单
                    } else {
                        //直接委派打印机下单
                    }

                    standard++;
                } else if(number <= 10 && number > 0) {
                    //轻松状态，直接委派打印机下单
                    standard--;
                } else if (number == 0){
                    //内存订单数为0，睡眠
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

            try {
                Thread.sleep(5*60);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}
