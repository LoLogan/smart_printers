package com.qg.smpt.printer;


import com.qg.smpt.printer.model.BBulkOrder;
import com.qg.smpt.printer.model.BConstants;
import com.qg.smpt.printer.model.BOrder;
import com.qg.smpt.printer.model.CompactModel;
import com.qg.smpt.share.ShareMem;
import com.qg.smpt.util.DebugUtil;
import com.qg.smpt.util.Level;
import com.qg.smpt.util.Logger;
import com.qg.smpt.web.model.BulkOrder;
import com.qg.smpt.web.model.Order;
import com.qg.smpt.web.model.Printer;

import com.qg.smpt.web.repository.CompactMapper;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.Resource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static java.lang.Thread.sleep;

/**
 * Created by logan on 2017/11/4.
 */
public class Compact {

    @Resource
    private  CompactMapper compactMapper;

    private final Logger LOGGER = Logger.getLogger(PrinterProcessor.class);

    public static void main(String[] args){
        List a = new ArrayList();
        a.add(1);
        a.add(2);
        a.add(3);
        a.add(4);
        a.add(5);
        a.add(6);
        a.add(7);
        a.add(8);
        a.add(9);
        a.add(10);
        a.add(11);
        a.add(12);
        int pos = 0;
        double sumPrice = 0;
        ShareMem.priPriceMap = new HashMap<Integer, Double>();
        ShareMem.priPriceMap.put(1,3.6);
        ShareMem.priPriceMap.put(2,4.2);
        ShareMem.priPriceMap.put(3,5.5);
        ShareMem.priPriceMap.put(4,8.4);
        for (Map.Entry<Integer, Double> entry : ShareMem.priPriceMap.entrySet()){
            sumPrice += entry.getValue();
        }

        for (Map.Entry<Integer, Double> entry : ShareMem.priPriceMap.entrySet()) {

            double orderNumOfDouble =  (entry.getValue() / sumPrice * a.size());
            int orderNumber = new BigDecimal(orderNumOfDouble).setScale(0, BigDecimal.ROUND_HALF_UP).intValue();
            System.out.println( orderNumber);
            List<Order> smallOrders = a.subList(pos,pos+orderNumber);
            pos +=orderNumber;
            System.out.println(smallOrders.toString());
            System.out.println("--------------");
        }


    }

    /***
     * 合同网发送订单
     * @param urg
     */
    public void sendOrders(int urg, List<Order> orders){
        callForBid(urg);

        try {
            sleep(2000);
        } catch (InterruptedException e) {
            LOGGER.log(Level.DEBUG, "[招标]等待投标时出现了错误");
        }

        judge(orders,urg);



    }

    /***
     * 进行招标，向所有已连接的主控板发送合同网报文
     * @param urg 加急标志 0-不加急 1-加急
     */
    public void callForBid(int urg){
        //合同网数据报文的装配
        CompactModel compactModel = new CompactModel();
        compactModel.setType(BConstants.publishTask);
        compactModel.setUrg((byte) urg);
        compactModel.setSeconds((int) System.currentTimeMillis());
        compactModel.setCheckSum((short)0);


        byte[] compactBytes = CompactModel.compactToBytes(compactModel);

        if (compactBytes.length % 4 != 0) {
            LOGGER.log(Level.ERROR, "[招标]合同网报文字节并未对齐");
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(compactBytes);

        for (Map.Entry<Printer, SocketChannel> entry : ShareMem.priSocketMap.entrySet()){
            //当该主控板处于闲时状态时可向其发送合同网报文
            if (!entry.getKey().isBusy()) {
                SocketChannel socketChannel = entry.getValue();
                try {
                    socketChannel.write(byteBuffer);
                } catch (IOException e) {
                    LOGGER.log(Level.ERROR, "[招标]发送合同网报文发生错误");
                }
            }
        }

    }

    /***
     * 进行标书的评审和中标操作
     */
    public void judge(List<Order> orders,int urg){
        LOGGER.log(Level.DEBUG, "[标书评审]主控板已响应标书，开始进行标书评审");
        int compactNumber = compactMapper.selectMaxCompact()+1;
        int allOrdersNum = orders.size();

        if(orders.size() < 10){
            LOGGER.log(Level.DEBUG, "[中标]订单过少，只指派一台主控板进行打印");
            int printerId = getPrinterIdByMaxCre();                      //获取信任度最大的打印机编号
            Printer printer = ShareMem.printerIdMap.get(printerId);      //获取打印机对象
            BulkOrder bOrders = ordersToBulk(orders,printer);            //订单组装成一个批次
            printer.increaseBulkId();                                    //打印机打印批次加一
            bOrders.setId(printer.getCurrentBulk());                     //设置当前批次编号，即该批次是上述打印机对应的第几个打印批次


            synchronized (ShareMem.priBulkMap) {
                ShareMem.priBulkMap.put(printer,bOrders);
            }

            int bulkLength = bOrders.getDataSize()+32;

            //构建合同网中标报文
            CompactModel compactModel = new CompactModel();
            compactModel.setType(BConstants.winABid);
            compactModel.setUrg((byte) urg);
            compactModel.setCompactNumber((short) compactNumber);
            compactModel.setSeq((short) 1);
            compactModel.setOrderNumber((short) orders.size());
            compactModel.setBulklength((short) bulkLength);
            compactModel.setCheckSum((short)0);
            //记录在数据库中
            compactMapper.addCompact(compactModel);

            byte[] compactBytes = CompactModel.compactToBytes(compactModel);
            ByteBuffer byteBuffer = ByteBuffer.wrap(compactBytes);

            SocketChannel socketChannel = ShareMem.priSocketMap.get(printer);
            try {
                socketChannel.write(byteBuffer);
            } catch (IOException e) {
                LOGGER.log(Level.ERROR, "[中标]发送合同网报文发生错误");
            }
            return;
        }else{
            double sumPrice = 0;
            for (Map.Entry<Integer, Double> entry : ShareMem.priPriceMap.entrySet()){
                sumPrice += entry.getValue();
            }
            int pos = 0;
            int seq = 1;
            for (Map.Entry<Integer, Double> entry : ShareMem.priPriceMap.entrySet()){
                double orderNumOfDouble =  (entry.getValue() / sumPrice * allOrdersNum);
                int orderNumber = new BigDecimal(orderNumOfDouble).setScale(0, BigDecimal.ROUND_HALF_UP).intValue();
                List<Order> smallOrders = orders.subList(pos,pos+orderNumber);    //
                pos += orderNumber;
                int printerId = entry.getKey();
                Printer printer = ShareMem.printerIdMap.get(printerId);      //获取打印机
                BulkOrder bOrders = ordersToBulk(smallOrders,printer);       //组装一个批次
                printer.increaseBulkId();                                    //打印机打印批次加一
                bOrders.setId(printer.getCurrentBulk());                     //设置当前批次编号，即该批次是上述打印机对应的第几个打印批次


                synchronized (ShareMem.priBulkMap) {
                    ShareMem.priBulkMap.put(printer,bOrders);
                }

                int bulkLength = bOrders.getDataSize()+32;


                //构建合同网中标报文
                CompactModel compactModel = new CompactModel();
                compactModel.setType(BConstants.winABid);
                compactModel.setUrg((byte) urg);
                compactModel.setCompactNumber((short) compactNumber);
                compactModel.setSeq((short) seq);
                compactModel.setOrderNumber((short) smallOrders.size());
                compactModel.setBulklength((short) bulkLength);
                compactModel.setCheckSum((short)0);
                //记录在数据库中
                compactMapper.addCompact(compactModel);

                byte[] compactBytes = CompactModel.compactToBytes(compactModel);
                ByteBuffer byteBuffer = ByteBuffer.wrap(compactBytes);

                SocketChannel socketChannel = ShareMem.priSocketMap.get(printer);
                try {
                    socketChannel.write(byteBuffer);
                } catch (IOException e) {
                    LOGGER.log(Level.ERROR, "[中标]发送合同网报文发生错误");
                }
               seq++;
            }
            return;
        }


    }

    /***
     * 求出最大信任度的打印机的编号
     * @return
     */
    public int getPrinterIdByMaxCre(){

        int id = 0;
        double cre = 0;

        for (Map.Entry<Integer, Double> entry : ShareMem.priCreMap.entrySet()){
            if (entry.getValue() > cre) {
                id = entry.getKey();
                cre = entry.getValue();
            }
        }
        return id;
    }

    /***
     * 将订单集合组装成批次
     * @param orders
     * @param printer
     * @return
     */
    public BulkOrder ordersToBulk(List<Order> orders, Printer printer){
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

}
