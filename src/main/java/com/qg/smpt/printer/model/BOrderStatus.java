package com.qg.smpt.printer.model;

import com.qg.smpt.util.BytesConvert;

import java.util.Arrays;

/**
 * 订单状态(打印机-服务器)
 */
public final class BOrderStatus extends AbstactStatus{

    // line1
    public int printerId;   // 主控板id
    // line2
    public int seconds;
    // line3
    public short bulkId;    // 批次id ; 低16bit
    public short inNumber;  // 批次内序号; 高16bit

    public int targetPrinterId; // 目标主控板id

    public static BOrderStatus bytesToOrderStatus(byte[] bytes) {

        AbstactStatus status = AbstactStatus.bytesToAbstractStatus(bytes);

        BOrderStatus bos = new BOrderStatus();

        bos.flag = status.flag;

        bos.printerId = status.line1;

        bos.seconds = status.line2;

        bos.bulkId = (short)( (status.line3 >> 16 ) & 0xFFFF) ;

        bos.inNumber = (short)(status.line3 & 0xFFFF);

        return bos;
    }

    /**
     * 为适应打印机订单转移新报文特的创建
     * 报文长度 24 个字节
     * @param bytes
     * @return
     */
    public static BOrderStatus bytesToOrderStatusInRemoving(byte[] bytes) {

        AbstactStatus status = AbstactStatus.bytesToAbstractStatus(bytes);

        BOrderStatus bos = new BOrderStatus();

        bos.flag = status.flag;

        bos.printerId = status.line1;

        bos.seconds = status.line2;

        bos.targetPrinterId = status.line4;

        bos.bulkId = (short)( (status.line3 >> 16 ) & 0xFFFF) ;

        bos.inNumber = (short)(status.line3 & 0xFFFF);

        return bos;
    }
}
