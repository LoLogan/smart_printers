package com.qg.smpt.printer;


import com.qg.smpt.printer.model.BBulkOrder;
import com.qg.smpt.printer.model.BConstants;
import com.qg.smpt.printer.model.BOrder;
import com.qg.smpt.printer.model.CompactModel;
import com.qg.smpt.share.ShareMem;
import com.qg.smpt.util.*;
import com.qg.smpt.web.model.BulkOrder;
import com.qg.smpt.web.model.Order;
import com.qg.smpt.web.model.Printer;

import com.qg.smpt.web.model.User;
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

    private final Logger LOGGER = Logger.getLogger(PrinterProcessor.class);


    public static void main(String[] args){


    }



    /***
     * 合同网委派订单
     * 1. 服务端收到订单列表，即向已连接的空闲的主控板发送招标通知
     * 2. 服务端收到主控板的投标，计算并存储各主控板的信任度，打印速度，打印代价（在PrinterProcessor的parseBid方法）
     * 3. 进行标书的评审，与主控板进行签约，即按照策略将一些订单分发给投标的主控板
     * 4. 主控板进行签约确认并进行订单的下发（在PrinterProcessor的sign方法中体现）
     * @param urg
     */
    public void sendOrdersByCompact(int userId, int urg, List<Order> orders){

        SqlSessionFactory sqlSessionFactory = SqlSessionFactoryBuild.getSqlSessionFactory();
        SqlSession sqlSession = sqlSessionFactory.openSession();
        compactMapper = sqlSession.getMapper(CompactMapper.class);

        int compactNumber = compactMapper.selectMaxCompact()+1;
        List<Printer> printers = new ArrayList<Printer>();
        synchronized (ShareMem.compactPrinter) {
            ShareMem.compactPrinter.put((short) compactNumber, printers);
        }

        callForBid(userId,urg, (short) compactNumber);

        //投标的策略为：只限定主控板在某一时间内发送标书，逾期不候
        try {
            sleep(2000);
        } catch (InterruptedException e) {
            LOGGER.log(Level.DEBUG, "[招标]等待投标时出现了错误");
        }

        if (ShareMem.compactPrinter.get((short)compactNumber).size() == 0) return ;

        judge(orders,urg,compactNumber);

    }

    /***
     * 解约
     * @param compactNumber
     */
    public void removeSign(int compactNumber){
        List<Printer> printers = ShareMem.compactPrinter.get((short) compactNumber);
        CompactModel compactModel = new CompactModel();
        compactModel.setType(BConstants.removeSignRequest);
        compactModel.setCompactNumber((short)compactNumber);
        compactModel.setCheckSum((short)0);
        byte[] compactBytes = CompactModel.compactToBytes(compactModel);

        for (Printer printer : printers){
            SocketChannel socketChannel = ShareMem.priSocketMap.get(printer);
            try {
                socketChannel.write(ByteBuffer.wrap(compactBytes));
                LOGGER.log(Level.DEBUG, "[解约]成功向主控板[{0}]发送合同网解约报文",printer.getId());
            } catch (IOException e) {
                LOGGER.log(Level.ERROR, "[解约]发送合同网解约报文发生错误");
            }
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

        byte[] compactBytes = CompactModel.compactToBytes(compactModel);

        LOGGER.log(Level.ERROR, "----------------------[招标]合同网报文字节开始----------------------");
        DebugUtil.printBytes(compactBytes);
        LOGGER.log(Level.ERROR, "----------------------[招标]合同网报文字节结束-----------------------");

        if (compactBytes.length % 4 != 0) {
            LOGGER.log(Level.ERROR, "[招标]合同网报文字节并未对齐");
        }


        LOGGER.log(Level.DEBUG, "[招标]向主控板发送招标合同网报文");
        for (Map.Entry<Printer, SocketChannel> entry : ShareMem.priSocketMap.entrySet()){
            //当该主控板处于闲时状态时可向其发送合同网报文
            if (!entry.getKey().isBusy() && entry.getKey().getUserId()==userId) {
                try {
                    ByteBuffer byteBuffer = ByteBuffer.wrap(compactBytes);
                    entry.getValue().write(byteBuffer);
                    LOGGER.log(Level.DEBUG, "[招标]成功向主控板[{0}]发送合同网报文",entry.getKey().getId());
                } catch (IOException e) {
                    LOGGER.log(Level.ERROR, "[招标]发送合同网报文发生错误");
                }
            }
        }

    }

    /***
     * 进行标书的评审和中标操作
     */
    public void judge(List<Order> orders,int urg,int compactNumber){
        SqlSessionFactory sqlSessionFactory = SqlSessionFactoryBuild.getSqlSessionFactory();
        SqlSession sqlSession = sqlSessionFactory.openSession();
        compactMapper = sqlSession.getMapper(CompactMapper.class);

        LOGGER.log(Level.DEBUG, "[标书评审]主控板已响应标书，开始进行标书评审");
        int allOrdersNum = orders.size();

        List<Printer> printers = ShareMem.compactPrinter.get((short)compactNumber);
        double sumPrice = 0;
        for (Printer printer : printers){
            sumPrice += printer.getPrice();
        }
        //总代价为0说明当前主控板下无打印机连接 无法进行打印任务
        if (sumPrice==0) return;
        int pos = 0;
        int seq = 1;

        for (Printer printer : printers){
            sqlSession = sqlSessionFactory.openSession();
            compactMapper = sqlSession.getMapper(CompactMapper.class);
            double orderNumOfDouble =  (printer.getPrice() / sumPrice * allOrdersNum);
            int orderNumber = new BigDecimal(orderNumOfDouble).setScale(0, BigDecimal.ROUND_HALF_UP).intValue();

            List<Order> smallOrders = orders.subList(pos,pos+orderNumber);    //截取一个小批次
            pos += orderNumber;
            BulkOrder bOrders = ordersToBulk(smallOrders,printer);       //组装一个批次
            printer.increaseBulkId();                                    //打印机打印批次加一
            bOrders.setId(printer.getCurrentBulk());                     //设置当前批次编号，即该批次是上述打印机对应的第几个打印批次


            synchronized (ShareMem.priBulkMap) {
                ShareMem.priBulkMap.put(printer,bOrders);
            }

            //构建合同网中标报文
            CompactModel compactModel = new CompactModel();
            compactModel.setType(BConstants.winABid);
            compactModel.setUrg((byte) urg);
            compactModel.setCompactNumber((short) compactNumber);
            compactModel.setCheckSum((short)0);
            compactModel.setSeq((short) seq);
            compactModel.setOrderNumber((short) smallOrders.size());
            compactModel.setId(printer.getId());
            //记录在数据库中 // TODO: 2017/11/16
            try {
                compactMapper.addCompact(compactModel);
            }finally {
                sqlSession.commit();
                sqlSession.close();
            }

            byte[] compactBytes = CompactModel.compactToBytes(compactModel);

            SocketChannel socketChannel = ShareMem.priSocketMap.get(printer);
            try {
                socketChannel.write(ByteBuffer.wrap(compactBytes));
                LOGGER.log(Level.DEBUG, "[中标]成功向主控板[{0}]发送合同网报文,分配订单个数为[{1}]",printer.getId(),smallOrders.size());
            } catch (IOException e) {
                LOGGER.log(Level.ERROR, "[中标]发送合同网报文发生错误");
            }
            seq++;
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
            bOrder.bulkId = (short)bOrders.getId();

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
        updatePrinterMsg();
        LOGGER.log(Level.DEBUG, "[批次]直接发送批次订单数据");
        int printerId = getPrinterIdByMaxCreForBulk(userId);                //获取信任度最大的打印机编号
        Printer printer = ShareMem.printerIdMap.get(printerId);      //获取打印机对象
        BulkOrder bOrders = ordersToBulk(orders,printer);            //订单组装成一个批次
        printer.increaseBulkId();                                    //打印机打印批次加一
        bOrders.setId(printer.getCurrentBulk());                     //设置当前批次编号，即该批次是上述打印机对应的第几个打印批次


        List<BulkOrder> bulkOrderList = ShareMem.priSentQueueMap.get(printer);

        if (bulkOrderList == null) {
            bulkOrderList = new ArrayList<BulkOrder>();
            ShareMem.priSentQueueMap.put(printer, bulkOrderList);
        }
        bulkOrderList.add(bOrders);
        LOGGER.log(Level.DEBUG, "[批次]将批次[{0}] 存入已发送队列",bOrders.getId());

        BBulkOrder bBulkOrder = BulkOrder.convertBBulkOrder(bOrders, false);
        byte[] bBulkOrderBytes = BBulkOrder.bBulkOrderToBytes(bBulkOrder);
        DebugUtil.printBytes(bBulkOrderBytes);
        SocketChannel socketChannel = ShareMem.priSocketMap.get(printer);
        try {
            socketChannel.write(ByteBuffer.wrap(bBulkOrderBytes));
            LOGGER.log(Level.DEBUG, "[批次]直接发送批次订单数据成功");
        } catch (IOException e) {
            LOGGER.log(Level.ERROR, "[中标]直接发送批次订单数据失败");
        }
    }


    /***
     * 更新，根据当前已连接的主控板id获取对应的信任度和代价
     */
    private void updatePrinterMsg(){
        SqlSessionFactory sqlSessionFactory = SqlSessionFactoryBuild.getSqlSessionFactory();
        SqlSession sqlSession = sqlSessionFactory.openSession();
        compactMapper = sqlSession.getMapper(CompactMapper.class);

        for (Map.Entry<Integer, Printer> entry : ShareMem.printerIdMap.entrySet()){
            try {
                int id = entry.getKey();
                entry.getValue().setCre(compactMapper.getCreById(id));
                entry.getValue().setPrice(compactMapper.getPriById(id));
            }finally {
                sqlSession.commit();
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
            if (p.getCre() >= cre){
                printer = p;
                cre = p.getCre();
            }

        }

        return printer.getId();
    }
}
