package com.qg.smpt.printer.model;

import com.qg.smpt.util.BytesConvert;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 合同网报文协议
 * Created by logan on 2017/11/4.
 */
public class CompactModel {
    private short start = BConstants.startAndEnd;    //起始符

    private byte type;                               //报文类型

    private byte urg;                                //紧急标识符

    private int seconds;                             //毫秒数,时间戳

    private short compactNumber;                     //合同号

    private short seq;                              //合同子序号

    private short orderNumber;                      //订单个数

    private short bulklength;                       //批次长度

    private int id;                              //主控板id

    private short speed;                            //打印速度（即一台主控板下有几台打印机）

    private short health;                           //健康状态

    private int padding;                          //保留（填充）

    private short checkSum;                         //校验和

    private short end = BConstants.startAndEnd;      //结束符

    public static void main(String[] args){

    }

    /***
     * 将合同网字节数组转换成合同网对象
     * @param bytes
     * @return
     */
    public static CompactModel bytesToCompact(byte[] bytes) {

        CompactModel compactModel = new CompactModel();

        compactModel.setType(bytes[2]);
        compactModel.setUrg(bytes[3]);
        compactModel.setSeconds(BytesConvert.bytesToInt(Arrays.copyOfRange(bytes, 4, 8)));

        compactModel.setCompactNumber(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 8, 10)));
        compactModel.setSeq(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 10, 12)));
        compactModel.setOrderNumber(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 12, 14)));
        compactModel.setBulklength(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 14, 16)));
        compactModel.setId(BytesConvert.bytesToInt(Arrays.copyOfRange(bytes, 16, 20)));
        compactModel.setSpeed(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 20, 22)));
        compactModel.setHealth(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 22, 24)));
        compactModel.setPadding(BytesConvert.bytesToInt(Arrays.copyOfRange(bytes, 24, 28)));
        compactModel.setCheckSum(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 28, 30)));


        return compactModel;
    }


    /***
     * 将合同网对象转换为字节数组
     * @param compactModel
     * @return
     */
    public static byte[] compactToBytes(CompactModel compactModel) {
        byte[] bytes = new byte[32];

        int position = 0;

        position = BytesConvert.fillShort(compactModel.start, bytes, position);


        position = BytesConvert.fillByte(ByteBuffer.allocate(1).put(compactModel.type).array(), bytes, position);

        position = BytesConvert.fillByte(ByteBuffer.allocate(1).put(compactModel.urg).array(), bytes, position);


        position = BytesConvert.fillInt(compactModel.seconds, bytes, position);

        position = BytesConvert.fillShort(compactModel.compactNumber, bytes, position);

        position = BytesConvert.fillShort(compactModel.seq, bytes, position);

        position = BytesConvert.fillShort(compactModel.orderNumber, bytes, position);

        position = BytesConvert.fillShort(compactModel.bulklength, bytes, position);

        position = BytesConvert.fillInt(compactModel.id, bytes, position);

        position = BytesConvert.fillShort(compactModel.speed, bytes, position);

        position = BytesConvert.fillShort(compactModel.health, bytes, position);

        position = BytesConvert.fillInt(compactModel.padding, bytes, position);

        position = BytesConvert.fillShort(compactModel.checkSum, bytes, position);

        position = BytesConvert.fillShort(compactModel.end, bytes, position);

        return bytes;
    }


    public CompactModel() {
    }

    public CompactModel(short start, byte type, byte urg, int seconds, short compactNumber, short seq, short orderNumber, short bulklength, int id, short speed, short health, int padding, short checkSum, short end) {
        this.start = start;
        this.type = type;
        this.urg = urg;
        this.seconds = seconds;
        this.compactNumber = compactNumber;
        this.seq = seq;
        this.orderNumber = orderNumber;
        this.bulklength = bulklength;
        this.id = id;
        this.speed = speed;
        this.health = health;
        this.padding = padding;
        this.checkSum = checkSum;
        this.end = end;
    }

    public short getStart() {
        return start;
    }

    public void setStart(short start) {
        this.start = start;
    }

    public byte getType() {
        return type;
    }

    public void setType(byte type) {
        this.type = type;
    }

    public byte getUrg() {
        return urg;
    }

    public void setUrg(byte urg) {
        this.urg = urg;
    }

    public int getSeconds() {
        return seconds;
    }

    public void setSeconds(int seconds) {
        this.seconds = seconds;
    }

    public short getCompactNumber() {
        return compactNumber;
    }

    public void setCompactNumber(short compactNumber) {
        this.compactNumber = compactNumber;
    }

    public short getSeq() {
        return seq;
    }

    public void setSeq(short seq) {
        this.seq = seq;
    }

    public short getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(short orderNumber) {
        this.orderNumber = orderNumber;
    }

    public short getBulklength() {
        return bulklength;
    }

    public void setBulklength(short bulklength) {
        this.bulklength = bulklength;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public short getSpeed() {
        return speed;
    }

    public void setSpeed(short speed) {
        this.speed = speed;
    }

    public short getHealth() {
        return health;
    }

    public void setHealth(short health) {
        this.health = health;
    }

    public int getPadding() {
        return padding;
    }

    public void setPadding(int padding) {
        this.padding = padding;
    }

    public short getCheckSum() {
        return checkSum;
    }

    public void setCheckSum(short checkSum) {
        this.checkSum = checkSum;
    }

    public short getEnd() {
        return end;
    }

    public void setEnd(short end) {
        this.end = end;
    }

}
