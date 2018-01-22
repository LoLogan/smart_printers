package com.qg.smpt.printer;


import com.qg.smpt.printer.model.BBulkOrder;
import com.qg.smpt.printer.model.BConstants;
import com.qg.smpt.printer.model.BOrder;
import com.qg.smpt.printer.model.CompactModel;
import com.qg.smpt.share.ShareMem;
import com.qg.smpt.util.*;
import com.qg.smpt.web.model.*;

import com.qg.smpt.web.repository.CompactMapper;
import com.qg.smpt.web.repository.UserMapper;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;


import static java.lang.Thread.sleep;

/**
 * Created by logan on 2017/11/4.
 */

public class Compact {

    private CompactMapper compactMapper;


    private final static Logger LOGGER_COMPACT = Logger.getLogger("compact");
    private final Logger LOGGER = Logger.getLogger(Compact.class);


    public static int capacitySum = 0;      //用于动态调控主控板时记录5次所需的打印能力的总和，以便求平均值

    public static int capacityRecord = 0;           //用于记录当前是一个周期中的第几次求当前打印能力



    /***
     * 合同网委派订单
     * 1. 服务端收到订单列表，即向已连接的空闲的主控板发送招标通知
     * 2. 服务端收到主控板的投标，计算并存储各主控板的信任度，打印速度，打印代价（在PrinterProcessor的parseBid方法）
     * 3. 进行标书的评审，与主控板进行签约，即按照策略将一些订单分发给投标的主控板
     * 4. 主控板进行签约确认并进行订单的下发（在PrinterProcessor的sign方法中体现）
     * @param urg
     */
    public int sendOrdersByCompact(int userId, int urg, List<Order> orders){


        SqlSessionFactory sqlSessionFactory = SqlSessionFactoryBuild.getSqlSessionFactory();
        SqlSession sqlSession = sqlSessionFactory.openSession();
        compactMapper = sqlSession.getMapper(CompactMapper.class);

        //缓存存入合同网即将进行投标的打印机
        int compactNumber = compactMapper.selectMaxCompact()+1;
        List<Printer> printers = new ArrayList<Printer>();
        synchronized (ShareMem.compactPrinter) {
            ShareMem.compactPrinter.put((short) compactNumber, printers);
        }
        //获取该份订单需要几个打印能力
        int printerCapacity = orders.size()/Constants.ORDERS_FOR_A_CAPACITY+1;
        sqlSession.commit();
        sqlSession.close();

        //进行招标
        callForBid(userId,urg, (short) compactNumber);

        //投标的策略为：只限定主控板在某一时间内发送标书，逾期不候
        try {
            sleep(2000);
        } catch (InterruptedException e) {
            LOGGER_COMPACT.log(Level.DEBUG, "[招标]等待投标时出现了错误");
        }

        if (ShareMem.compactPrinter.get((short)compactNumber).size() == 0) return -1 ;

        judge(orders,urg,compactNumber,printerCapacity);

        return compactNumber;
    }

    /***
     * 解约
     * @param compactNumber
     */
    public void removeSign(int compactNumber,Printer printer){

        CompactModel compactModel = new CompactModel();
        compactModel.setType(BConstants.removeSignRequest);
        compactModel.setCompactNumber((short)compactNumber);
        compactModel.setCheckSum((short)0);
        byte[] compactBytes = CompactModel.compactToBytes(compactModel);

        SocketChannel socketChannel = ShareMem.priSocketMap.get(ShareMem.printerIdMap.get(printer.getId()));
        try {
            socketChannel.write(ByteBuffer.wrap(compactBytes));
            LOGGER_COMPACT.log(Level.DEBUG, "[解约]成功向主控板[{0}]发送合同网解约报文",printer.getId());
        } catch (IOException e) {
            LOGGER_COMPACT.log(Level.ERROR, "[解约]发送合同网解约报文发生错误");
        }

    }

    /***
     * 进行招标，向所有已连接的主控板发送合同网报文
     * @param urg 加急标志 0-不加急 1-加急
     */
    public void callForBid(int userId, int urg,short compactNumber){

        //合同网数据报文的装配
        CompactModel compactModel = new CompactModel();
        compactModel.setType(BConstants.publishTask);
        compactModel.setCompactNumber(compactNumber);
        compactModel.setUrg((byte) urg);
        compactModel.setSeconds((int) System.currentTimeMillis());
        compactModel.setCheckSum((short)0);

        SqlSessionFactory sqlSessionFactory = SqlSessionFactoryBuild.getSqlSessionFactory();
        SqlSession sqlSession = sqlSessionFactory.openSession();
        compactMapper = sqlSession.getMapper(CompactMapper.class);
        try {
            compactMapper.addCompact(compactModel);
        } finally {
            sqlSession.commit();
            sqlSession.close();
        }

        byte[] compactBytes = CompactModel.compactToBytes(compactModel);

        LOGGER_COMPACT.log(Level.ERROR, "----------------------[招标]合同网报文字节开始----------------------");
        DebugUtil.printBytes(compactBytes);
        LOGGER_COMPACT.log(Level.ERROR, "----------------------[招标]合同网报文字节结束-----------------------");

        if (compactBytes.length % 4 != 0) {
            LOGGER_COMPACT.log(Level.ERROR, "[招标]合同网报文字节并未对齐");
        }


        LOGGER_COMPACT.log(Level.DEBUG, "[招标]向主控板发送招标合同网报文");
        for (Map.Entry<Printer, SocketChannel> entry : ShareMem.priSocketMap.entrySet()){
            //当该主控板处于闲时状态时可向其发送合同网报文
            if (!entry.getKey().isBusy() && entry.getKey().getUserId()==userId) {
                try {
                    ByteBuffer byteBuffer = ByteBuffer.wrap(compactBytes);
                    entry.getValue().write(byteBuffer);
                    LOGGER_COMPACT.log(Level.DEBUG, "[招标]成功向主控板[{0}]发送合同网报文",entry.getKey().getId());
                } catch (IOException e) {
                    LOGGER_COMPACT.log(Level.ERROR, "[招标]发送合同网报文发生错误");
                }
            }
        }

    }

    /***
     * 进行标书的评审和中标操作
     */
    public void judge(List<Order> orders,int urg,int compactNumber, int printerCapacity){
        LOGGER_COMPACT.log(Level.DEBUG, "[标书评审]主控板已响应标书，开始进行标书评审并对进行投标的打印机进行筛选");
        List<Printer> printers = ShareMem.compactPrinter.get((short)compactNumber);
        //进行信任度的降序排序
        SortList<Printer> sortList = new SortList<Printer>();
        sortList.Sort(printers, "getCre", "desc");
        //缓存存入经过筛选参与合同网的打印机
        List<Printer> compactOfPrinter = ShareMem.compactOfPrinter.get((short)compactNumber);
        if (compactOfPrinter==null) {
            compactOfPrinter = new ArrayList<Printer>();
            ShareMem.compactOfPrinter.put((short) compactNumber, compactOfPrinter);
        }
        int capacity = 0;
        //对投标的打印机进行筛选，选出参与合同网的打印机,即中标的打印机
        for (Printer p : printers){
            capacity += p.getSpeed();
            compactOfPrinter.add(p);
            if (capacity>=printerCapacity) break;
        }
        //将订单存入缓存
        synchronized (ShareMem.compactBulkMap) {
            List<Order> compactOrders = ShareMem.compactBulkMap.get((short)compactNumber);
            if (compactOrders == null){
                compactOrders = new ArrayList<Order>();
                ShareMem.compactBulkMap.put((short)compactNumber,compactOrders);
            }
            compactOrders.addAll(orders);
        }
        //对中标的打印机发送中标报文
        for (Printer p : compactOfPrinter){
            CompactModel compactModel = new CompactModel();
            compactModel.setType(BConstants.winABid);
            compactModel.setUrg((byte) urg);
            compactModel.setCompactNumber((short) compactNumber);
            compactModel.setCheckSum((short)0);
            compactModel.setId(p.getId());
            byte[] compactBytes = CompactModel.compactToBytes(compactModel);

            SocketChannel socketChannel = ShareMem.priSocketMap.get(p);
            try {
                socketChannel.write(ByteBuffer.wrap(compactBytes));
                LOGGER_COMPACT.log(Level.DEBUG, "[中标]成功向主控板[{0}]发送合同网报文",p.getId());
            } catch (IOException e) {
                LOGGER_COMPACT.log(Level.ERROR, "[中标]发送合同网报文发生错误");
            }

        }

        return;

    }

    /***
     * 求出合同网中最大信任度的打印机的编号
     * @return
     */
    private int getPrinterIdByMaxCreForCompact(int compactNumber){

        List<Printer> printers = ShareMem.compactPrinter.get((short)compactNumber);

        Printer p = printers.get(0);
        double cre = p.getCre();

        for (Printer printer : printers){
            if (printer.getCre() >= cre) {
                p = printer;
                cre = printer.getCre();
            }
        }
        return p.getId();
    }

    /***
     * 将订单集合组装成批次
     * @param orders
     * @param printer
     * @return
     */
    private BulkOrder ordersToBulk(List<Order> orders, Printer printer){
        BulkOrder bOrders = new BulkOrder(new ArrayList<BOrder>());  //批次对象
        for (Order order : orders){
            //为订单设置打印机
            order.setMpu(printer.getId());
            //将订单对象转换为打印机能够识别的订单报文对象
            BOrder bOrder = order.orderToBOrder((short) printer.getCurrentBulk(), (short) bOrders.getbOrders().size());
            //设置批次内的序号
            bOrder.inNumber = (short)bOrders.getOrders().size();
            //设置所属批次
            bOrder.bulkId = (short)printer.getCurrentBulk();

            bOrders.getbOrders().add(bOrder);
            bOrders.getOrders().add(order);
            //设置所有订单的字节数（不包括批次报文）
            bOrders.setDataSize(bOrder.size + bOrders.getDataSize());
        }
        return bOrders;
    }


    /***
     * 直接发送批次订单数据
     * @param orders
     * @param urg
     */
    public void sendBulkDitectly(int userId,int urg,List<Order> orders){
        updatePrinterMsg(userId);
        LOGGER_COMPACT.log(Level.DEBUG, "[直接批次下单]直接发送批次订单数据");
        int printerId = getPrinterIdByMaxCreForBulk(userId);                //获取信任度最大的打印机编号
        this.sendByPrinter(printerId,orders);
    }


    /***
     * 更新，根据当前已连接的主控板id获取对应的信任度和代价
     */
    private void updatePrinterMsg(int userId){
        SqlSessionFactory sqlSessionFactory = SqlSessionFactoryBuild.getSqlSessionFactory();
        SqlSession sqlSession = sqlSessionFactory.openSession();
        compactMapper = sqlSession.getMapper(CompactMapper.class);

        for (Map.Entry<Integer, Printer> entry : ShareMem.printerIdMap.entrySet()) {
            if (entry.getValue().getUserId() == userId) {
                try {
                    int id = entry.getKey();
                    entry.getValue().setCre(compactMapper.getCreById(id));
                    entry.getValue().setPrice(compactMapper.getPriById(id));
                } finally {
                    sqlSession.commit();
                }
            }
        }
        sqlSession.close();
    }

    /***
     * 求出批次发送中最大信任度的打印机的编号
     * @return
     */
    private int getPrinterIdByMaxCreForBulk(int userId){

        double cre;
        User user = ShareMem.userIdMap.get(userId);
        List<Printer> printers = user.getPrinters();
        Printer printer = printers.get(0);
        cre = printer.getCre();

        for (Printer p : printers){
            if (ShareMem.priSocketMap.get(ShareMem.printerIdMap.get(p.getId())) != null) {
                if (p.getCre() >= cre) {
                    printer = p;
                    cre = p.getCre();
                }
            }

        }

        return printer.getId();
    }

    /**
     * 通过用户id获得当前用户下信任度最大的打印机id
     * @param userId 用户id
     * @return 信任度最大的打印机id
     */
    public int getMaxCreForBulkPrinter(int userId) {
        if(userId < 0) {
            return -1;
        }
        return getPrinterIdByMaxCreForBulk(userId);
    }

    /***
     * 动态调控参与合同网的主控板(签约和解约)
     * @param lastTime
     * @param compactNumber
     * @return
     */
    public static long dynamicManage(long lastTime, int compactNumber){
        if (lastTime == 0) lastTime = System.currentTimeMillis();
        //4s为一个监控周期
        if (System.currentTimeMillis()-lastTime > 4 * 1000){

            //定期更新打印机的信任度
            List<Printer> printers = ShareMem.compactOfPrinter.get((short)compactNumber);
            for (Printer p : printers) {
                p.setCre((BConstants.initialCre + BConstants.alpha*p.getPrintSuccessNum()-BConstants.beta*p.getPrintErrorNum()));
            }


            if (capacityRecord != Constants.DYNAMICS_CYCLE) {

                //获取合同网中的订单缓存
                List<Order> compactOrders = ShareMem.compactBulkMap.get((short) compactNumber);
                //获取当前所需要的打印能力
                int printerCapacityNeed = 0;
                if (compactOrders.size()!=0)
                 printerCapacityNeed = compactOrders.size() / Constants.ORDERS_FOR_A_CAPACITY + 1;

                capacitySum += printerCapacityNeed;     //累加 求平均值

                capacityRecord++;
                if (compactOrders.size()!=0)
                 LOGGER_COMPACT.log(Level.DEBUG, "[动态监测]当前计算所需打印能力为[{0}],合计打印能力为[{1}],第[{2}]周期",printerCapacityNeed,capacitySum,capacityRecord);
            } else {
                int capacityAverage = 0;
                if (capacitySum!=0)
                capacityAverage = capacitySum / Constants.DYNAMICS_CYCLE + 1;

                //获取当前的打印能力
                int printerCapacity = 0;
                for (Printer p : printers) {
                    printerCapacity += p.getSpeed();
                }
                if (capacityAverage!=0)
                    LOGGER_COMPACT.log(Level.DEBUG, "[动态监测]周期为[{0}]的平均打印能力为[{1}],当前打印能力总计有[{2}]",capacityRecord,capacityAverage,printerCapacity);
                //如果平均打印能力为0，解约全部主控板
                if (capacityAverage == 0){
                    Compact compact = new Compact();
                    for (Printer p : printers) {
                        compact.removeSign(compactNumber, printers.get(0));
                        LOGGER_COMPACT.log(Level.DEBUG, "[动态监测]当前平均打印能力为0，与主控板[{0}]解约,", p.getId());
                    }
                }
                //如果当前的打印能力比所需的打印能力多2以上，则解约部分主控板
                if (printerCapacity - capacityAverage >= 2){
                    Compact compact = new Compact();
                    //首先对主控板的打印能力进行升序
                    SortList<Printer> sortList = new SortList<Printer>();
                    sortList.Sort(printers, "getSpeed", "asc");
                    while (printerCapacity - capacityAverage >= 2){
                        //如果解约部分主控板后依赖大于所需打印能力，则继续解约，否则退出循环
                        if (printerCapacity - printers.get(0).getSpeed() >= capacityAverage){
                            printerCapacity = printerCapacity - printers.get(0).getSpeed();
                            LOGGER_COMPACT.log(Level.DEBUG, "[动态监测]与主控板[{0}]解约,当前打印能力总计有[{1}]",printers.get(0).getId(),printerCapacity);
                            compact.removeSign(compactNumber,printers.get(0));
                        }else break;
                    }
                }
                //如果所需的打印能力比当前的打印能力大于2以上，则需要再进行签约
                if (capacityAverage - printerCapacity > 2){
                    //先获得之前投标了的主控板集合
                    List<Printer> compactPrinter = ShareMem.compactPrinter.get((short)compactNumber);
                    //进行降序排序
                    SortList<Printer> sortList = new SortList<Printer>();
                    sortList.Sort(compactPrinter, "getCre", "desc");

                    for (Printer p : compactPrinter){
                        //如果满足了需要的打印能力，则退出遍历
                        if (!(printerCapacity < capacityAverage)) break;
                        //判断此时是否已经参与合同网
                        if (printers.contains(p)) continue;
                        printers.add(p);
                        printerCapacity += p.getSpeed();
                        LOGGER_COMPACT.log(Level.DEBUG, "[动态监测]与主控板[{0}]签约,当前打印能力总计有[{1}]",p.getId(),printerCapacity);
                        CompactModel compactModel = new CompactModel();
                        compactModel.setType(BConstants.winABid);
                        compactModel.setCompactNumber((short) compactNumber);
                        compactModel.setCheckSum((short)0);
                        compactModel.setId(p.getId());
                        byte[] compactBytes = CompactModel.compactToBytes(compactModel);

                        SocketChannel socketChannel = ShareMem.priSocketMap.get(p);
                        try {
                            socketChannel.write(ByteBuffer.wrap(compactBytes));
                        } catch (IOException e) {
                        }
                    }
                }
                capacityRecord = 0;     //重置
                capacitySum = 0;
            }

            lastTime = System.currentTimeMillis();
        }

        return lastTime;
    }

    /***
     * 测试接口，多台主控板平均分配订单，用于测试订单跟踪
     * @param userId
     * @param orders
     */
    public void test(int userId,List<Order> orders){
        User user = ShareMem.userIdMap.get(userId);
        List<Printer> printers = user.getPrinters();
        int printerSize = 0;

        for (Printer p : printers){
            if (ShareMem.priSocketMap.containsKey(ShareMem.printerIdMap.get(p.getId())))printerSize++;
        }

        int ordersSize = orders.size();
        int pos = 0;
        if (printerSize == 0) return ;
        int number = ordersSize/printerSize;
        for (Printer p : printers) {
            if (ShareMem.priSocketMap.get(ShareMem.printerIdMap.get(p.getId())) != null) {
                SocketChannel socketChannel = ShareMem.priSocketMap.get(ShareMem.printerIdMap.get(p.getId()));
                try {
                    List<Order> smallOrders = orders.subList(pos, pos + number);
                    pos += number;
                    p.increaseBulkId();                                      //打印机打印批次加一
                    BulkOrder bOrders = ordersToBulk(smallOrders, p);            //订单组装成一个批次
                    bOrders.setId(p.getCurrentBulk());                     //设置当前批次编号，即该批次是上述打印机对应的第几个打印批次

                    List<BulkOrder> bulkOrderList = ShareMem.priSentQueueMap.get(p);

                    if (bulkOrderList == null) {
                        bulkOrderList = new ArrayList<BulkOrder>();
                        ShareMem.priSentQueueMap.put(p, bulkOrderList);
                    }
                    bulkOrderList.add(bOrders);
                    LOGGER.log(Level.DEBUG, "[批次]将批次[{0}] 存入已发送队列", bOrders.getId());

                    BBulkOrder bBulkOrder = BulkOrder.convertBBulkOrder(bOrders, false);
                    byte[] bBulkOrderBytes = BBulkOrder.bBulkOrderToBytes(bBulkOrder);

                    socketChannel.write(ByteBuffer.wrap(bBulkOrderBytes));
                } catch (IOException e) {
                }
            }
        }
    }

    /***
     * 测试接口，用于指定某台打印机进行打印任务
     * @param printerId
     * @param orders
     */
    public void sendByPrinter(int printerId,List<Order> orders) {
        LOGGER.log(Level.DEBUG, "[指定打印机下单]指定打印机下单");
        Printer printer = ShareMem.printerIdMap.get(printerId);      //获取打印机对象
        if (printer.getBufferSize() == null) printer.setBufferSize((short) 5120);

        while (orders.size() != 0) {

            BulkOrder bOrders = new BulkOrder(new ArrayList<BOrder>());


            synchronized (orders) {
                printer.increaseBulkId();
                bOrders.setId(printer.getCurrentBulk());
                List<Order> orderList = new ArrayList<Order>();
                for (Order order : orders) {
                    BOrder bOrder = order.orderToBOrder((short) printer.getCurrentBulk(), (short) bOrders.getbOrders().size());
                    if (bOrders.getDataSize() + bOrder.size > printer.getBufferSize()) break;

                    bOrders.getbOrders().add(bOrder);
                    bOrders.getOrders().add(order);
                    //更新所有订单的字节数（不包括批次报文）
                    bOrders.setDataSize(bOrders.getDataSize() + bOrder.size);
                    //设置所属批次
                    bOrder.bulkId = (short) printer.getCurrentBulk();
                    //设置批次内的序号
                    bOrder.inNumber = (short) bOrders.getOrders().size();
                    //为订单设置打印机
                    order.setMpu(printer.getId());
                    orderList.add(order);
                }
                orders.removeAll(orderList);
            }
            LOGGER.log(Level.DEBUG, "为打印机 [{0}] 分配任务, 订单缓冲队列 [{1}]，" +
                            "批次号为 [{2}], 最后批次订单容量 [{3}] byte", printer.getId(),
                    orders.size(), bOrders.getId(), bOrders.getDataSize());


            //存入已发送队列
            synchronized (ShareMem.priSentQueueMap.get(printer)) {
                List<BulkOrder> bulkOrderList = ShareMem.priSentQueueMap.get(printer);
                if (bulkOrderList == null) {
                    bulkOrderList = new ArrayList<BulkOrder>();
                    ShareMem.priSentQueueMap.put(printer, bulkOrderList);
                }
                bulkOrderList.add(bOrders);
            }

            //引用以前的批次报文，但是只用里边的data属性，data即是这个批次的订单报文数据
            BBulkOrder bBulkOrder = BulkOrder.convertBBulkOrder(bOrders, false);
            byte[] bBulkOrderBytes = BBulkOrder.bBulkOrderToBytes(bBulkOrder);

            try {
                SocketChannel socketChannel = ShareMem.priSocketMap.get(printer);
                socketChannel.write(ByteBuffer.wrap(bBulkOrderBytes));
                LOGGER.log(Level.DEBUG, "[直接批次下单]发放任务时成功");
            } catch (final IOException e) {
                LOGGER.log(Level.ERROR, "[直接批次下单]发放任务时发生错误");
            }

            //进入睡眠，
            synchronized (printer) {
                try {
                    printer.wait();
                    LOGGER.log(Level.DEBUG, "[直接批次下单]发放任务时成功，进入睡眠");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            LOGGER.log(Level.DEBUG, "[直接批次下单]唤醒，继续发送订单");
        }

    }

}