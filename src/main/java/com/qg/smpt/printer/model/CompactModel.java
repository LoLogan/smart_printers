package com.qg.smpt.printer.model;

import com.qg.smpt.util.BytesConvert;
import com.qg.smpt.web.model.Printer;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * 合同网报文协议
 * Created by logan on 2017/11/4.
 */
public class CompactModel {
    private short start = BConstants.compactStart;    //起始符

    private byte type;                               //报文类型

    private byte urg;                                //紧急标识符,好像暂时遗弃了

    private int seconds;                             //毫秒数,时间戳

    private short compactNumber;                     //合同号

    private short padding0;                           //保留

    private int id;                              //主控板id

    private short speed;                            //打印速度（即一台主控板下有几台打印机）

    private short bufferSize;                       //缓冲区的大小

    private float health;                           //健康状态

    private short checkSum;                         //校验和

    private short end = BConstants.compactEnd;      //结束符



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

        compactModel.setPadding0(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 10, 12)));


        compactModel.setId(BytesConvert.bytesToInt(Arrays.copyOfRange(bytes, 12, 16)));

        compactModel.setSpeed(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 16, 18)));



        compactModel.setBufferSize(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 18, 20)));

        compactModel.setHealth(BytesConvert.bytesToFloat(Arrays.copyOfRange(bytes, 20, 24)));

        compactModel.setCheckSum(BytesConvert.bytesToShort(Arrays.copyOfRange(bytes, 24, 26)));


        return compactModel;
    }


    /***
     * 将合同网对象转换为字节数组
     * @param compactModel
     * @return
     */
    public static byte[] compactToBytes(CompactModel compactModel) {
        byte[] bytes = new byte[28];

        int position = 0;

        position = BytesConvert.fillShort(compactModel.start, bytes, position);


        position = BytesConvert.fillByte(ByteBuffer.allocate(1).put(compactModel.type).array(), bytes, position);

        position = BytesConvert.fillByte(ByteBuffer.allocate(1).put(compactModel.urg).array(), bytes, position);


        position = BytesConvert.fillInt(compactModel.seconds, bytes, position);

        position = BytesConvert.fillShort(compactModel.compactNumber, bytes, position);

        position = BytesConvert.fillShort(compactModel.padding0, bytes, position);

        position = BytesConvert.fillInt(compactModel.id, bytes, position);

        position = BytesConvert.fillShort(compactModel.speed, bytes, position);

        position = BytesConvert.fillShort(compactModel.getBufferSize(), bytes, position);

        position = BytesConvert.fillFloat(compactModel.health, bytes, position);

        position = BytesConvert.fillShort(compactModel.checkSum, bytes, position);

        position = BytesConvert.fillShort(compactModel.end, bytes, position);

        return bytes;
    }

    @Override
    public String toString() {
        return "CompactModel{" +
                "start=" + start +
                ", type=" + type +
                ", urg=" + urg +
                ", seconds=" + seconds +
                ", compactNumber=" + compactNumber +
                ", padding0=" + padding0 +
                ", id=" + id +
                ", speed=" + speed +
                ", bufferSize=" + bufferSize +
                ", health=" + health +
                ", checkSum=" + checkSum +
                ", end=" + end +
                '}';
    }

    public CompactModel() {
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

    public short getPadding0() {
        return padding0;
    }

    public void setPadding0(short padding0) {
        this.padding0 = padding0;
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

    public short getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(short bufferSize) {
        this.bufferSize = bufferSize;
    }

    public float getHealth() {
        return health;
    }

    public void setHealth(float health) {
        this.health = health;
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
