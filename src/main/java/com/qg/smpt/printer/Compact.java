package com.qg.smpt.printer;


import com.qg.smpt.printer.model.BConstants;
import com.qg.smpt.printer.model.CompactModel;
import com.qg.smpt.share.ShareMem;
import com.qg.smpt.util.DebugUtil;
import com.qg.smpt.util.Level;
import com.qg.smpt.util.Logger;
import com.qg.smpt.web.model.Printer;

import com.qg.smpt.web.repository.CompactMapper;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Created by logan on 2017/11/4.
 */
public class Compact {

    @Resource
    private  CompactMapper compactMapper;

    private final Logger LOGGER = Logger.getLogger(PrinterProcessor.class);

    public static void main(String[] args){

    }

    /***
     * 进行招标，向所有已连接的主控板发送合同网报文
     * @param urg 加急标志 0-不加急 1-加急
     */
    public void callForBid(int urg){
        int activePrinter = 0;

        //合同网数据报文的装配
        CompactModel compactModel = new CompactModel();
        compactModel.setType(BConstants.publishTask);
        if (urg==1)
            compactModel.setUrg((byte) 0x01);
        else compactModel.setUrg((byte) 0x00);
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
                activePrinter++;
                SocketChannel socketChannel = entry.getValue();
                try {
                    socketChannel.write(byteBuffer);
                } catch (IOException e) {
                    LOGGER.log(Level.ERROR, "[招标]发送合同网报文发生错误");
                }
            }
        }

        CountDownLatch countDownLatch = new CountDownLatch(activePrinter);
        if (ShareMem.countDownLatch!=null) {
            synchronized (ShareMem.countDownLatch) {
                ShareMem.countDownLatch = countDownLatch;
            }
        }
        LOGGER.log(Level.DEBUG, "[招标]启用闭锁，等待所有主控板响应标书后进行标书评审");
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            LOGGER.log(Level.DEBUG, "[招标]闭锁等待时发生错误");
        }

        LOGGER.log(Level.DEBUG, "[标书评审]所有主控板已响应标书，开始进行标书评审");


    }

}
