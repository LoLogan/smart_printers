package com.qg.smpt.printer;

import com.qg.smpt.share.ShareMem;
import com.qg.smpt.util.Level;
import com.qg.smpt.util.Logger;
import com.qg.smpt.util.SqlSessionFactoryBuild;
import com.qg.smpt.web.model.Order;
import com.qg.smpt.web.model.Printer;
import com.qg.smpt.web.model.User;
import com.qg.smpt.web.repository.CompactMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.ArrayList;
import java.util.List;


/**
 * 委派订单
 * Created by logan on 2017/11/30.
 */
public class OrdersDispatcher implements Runnable{
    private final Logger LOGGER = Logger.getLogger(OrdersDispatcher.class);
    private final static Logger LOGGER_COMPACT = Logger.getLogger("compact");
    private int userId;

    private static final int MAX_TIME = 3;          //区分轻松状态和紧张状态的标准值，也可以做成动态值

    private  int MAX_NUM ;          //订单上限值，即一次最多能处理的订单数，动态值


    public boolean flag;

    public OrdersDispatcher(int userId){
        this.userId = userId;
        this.flag = true;
        this.MAX_NUM = 10;      //默认值
    }

    public void threadStart() {


        Thread thread = new Thread(this, this.userId+"");

        thread.setDaemon(true);

        thread.start();
    }

    public void run() {
        long lastTime = 0;
        int compactNumber = 0;     //初始化合同网序号
        //标准值
        int standard = 0;
        Compact compact = new Compact();
        User user = ShareMem.userIdMap.get(userId);
        List<Printer> printers = user.getPrinters();
        //信任度最佳打印机的打印速度，用来决定该用户处理能力，即订单上限值
        int speedSum = ShareMem.printerIdMap.get(compact.getMaxCreForBulkPrinter(userId)).getSpeed();

        if (speedSum != 0)
            //// TODO: 2017/12/11 MAX_NUM还需要再进行确定
            MAX_NUM = speedSum * Constants.ORDERS_FOR_A_CAPACITY;

        while (flag) {

            try{
                synchronized (ShareMem.userOrderBufferMap.get(userId)) {
                    //如果该商家已经用合同网分配订单了，则将新订单放置合同网处理
                    if (user.isCompact() && compactNumber!=-1){


                        //合同网内部的订单转移，将用户的订单队列转移到合同网队列里
                        List<Order> orders = ShareMem.userOrderBufferMap.get(userId);

                        synchronized (ShareMem.compactBulkMap.get((short)compactNumber)) {
                            List<Order> compactOrders = ShareMem.compactBulkMap.get((short)compactNumber);
                            if (compactOrders == null){
                                compactOrders = new ArrayList<Order>();
                                ShareMem.compactBulkMap.put((short)compactNumber,compactOrders);
                            }
                            compactOrders.addAll(orders);
                            ShareMem.compactBulkMap.get((short)compactNumber).notifyAll();
                        }
                        orders.clear();

                        lastTime = Compact.dynamicManage(lastTime, compactNumber);
                        if (ShareMem.compactOfPrinter.get((short)compactNumber).size()==0)
                            user.setCompact(false);

                    }else {
                        List<Order> orders = ShareMem.userOrderBufferMap.get(userId);
                        int number = orders.size();
                        LOGGER_COMPACT.log(Level.DEBUG, "当前订单数量为[{0}]", number);
                        if (number > MAX_NUM) {
                            //达到上限值，立即启用合同网
                            LOGGER_COMPACT.log(Level.DEBUG, "达到订单上限值，启用合同网");
                            compactNumber = compact.sendOrdersByCompact(userId, 0, orders);
                            user.setCompact(true);
                            orders.clear();
                        } else if (number <= MAX_NUM && number > MAX_NUM / 2) {
                            //处于紧张状态，直接委派打印机下单，如果长期处于这种状态则通过合同网进行下单
                            if (standard >= MAX_TIME) {

                                //合同网下单
                                LOGGER_COMPACT.log(Level.DEBUG, "紧张状态，启用合同网，当前standard的值为[{0}]", standard);
                                compactNumber =  compact.sendOrdersByCompact(userId, 0, orders);
                                user.setCompact(true);
                                standard = 0;
                            } else {
                                //直接委派打印机下单
                                LOGGER_COMPACT.log(Level.DEBUG, "紧张状态，直接委派打印机下单，当前standard的值为[{0}]", standard);
                                compact.sendBulkDitectly(userId, 0, orders);
                            }
                            standard++;
                            orders.clear();
                        } else if (number <= MAX_NUM / 2 && number > 0) {
                            //轻松状态，直接委派打印机下单
                            LOGGER_COMPACT.log(Level.DEBUG, "轻松状态，直接委派打印机下单，当前standard的值为[{0}]", standard);
                            compact.sendBulkDitectly(userId, 0, orders);
                            standard--;
                            orders.clear();
                        } else if (number == 0) {
                            //内存订单数为0，睡眠
                            try {
                                LOGGER_COMPACT.log(Level.DEBUG, "内存无订单，进入睡眠");
                                ShareMem.userOrderBufferMap.get(userId).wait();
                                LOGGER_COMPACT.log(Level.DEBUG, "内存有订单，唤醒线程");
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
                Thread.sleep(1000*2);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}
