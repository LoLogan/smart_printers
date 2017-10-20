package com.qg.smpt.printers;

import com.qg.smpt.printer.model.AbstactStatus;
import com.qg.smpt.printer.model.BBulkOrder;
import com.qg.smpt.printer.model.BConstants;
import com.qg.smpt.util.BytesConvert;
import com.qg.smpt.util.DebugUtil;
import com.qg.smpt.web.model.BulkOrder;

import java.io.*;
import java.net.Socket;

/**
 *  模拟打印机客户端发送数据
 */
public class TestClient implements Runnable{

    private int printerId;

    public TestClient(int printerId) {
        this.printerId = printerId;
    }

    public static void main(String[] args)  {

        Thread thread = new Thread(new TestClient(1));

        thread.start();

        System.out.println(System.currentTimeMillis());

    }

    private static byte[] buildAbstractStatus(short flag, int printerId) {
        AbstactStatus abstactStatus = new AbstactStatus();

        abstactStatus.flag =  flag;

        abstactStatus.line1 = printerId;

        abstactStatus.line2 = 0x0;

        abstactStatus.line3 = 0x1 << 16 | 0x0;

        abstactStatus.checkSum = 0x0;

        byte[] bytes = AbstactStatus.abstratcStatusToBytes(abstactStatus);


        DebugUtil.printBytes(bytes);

        return bytes;
    }


    @Override
    public void run() {
        try {
            Socket socket = new Socket("localhost", 8086);

            OutputStream outputStream = socket.getOutputStream();

            InputStream inputStream = socket.getInputStream();


            while (true) {

                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                byte[] bytes = null;

                String s = br.readLine();
                if (s.equals("connection")) {
                    // 建立请求连接
                    bytes = buildAbstractStatus((short) ((BConstants.connectStatus << 8) & 0xFFFF), this.printerId);
                    outputStream.write(bytes);
                    outputStream.flush();
                } else if (s.equals("ok")) {
                    // 阈值反馈
                    bytes = buildAbstractStatus((short) ((BConstants.okStatus << 8) & 0xFFFF), this.printerId);
                    outputStream.write(bytes);
                    outputStream.flush();
                } else if (s.equals("bulk")) {
                    // 批次订单接收
                    bytes = buildAbstractStatus((short) (((BConstants.bulkStatus << 8) & 0xFFFF) | 0x0), this.printerId);
                    outputStream.write(bytes);
                    outputStream.flush();
                } else if (s.equals("order")) {
                    String s1 = br.readLine();
                    short flag = (BConstants.orderStatus << 8) & 0xFFFF;
                    if (s1.equals("succ")) {
                        flag |= 0x0;
                    } else if (s1.equals("fail")) {
                        flag |= 0x1;
                    } else if (s1.equals("in")) {
                        flag |= 0x2;
                    } else if (s1.equals("start")) {
                        flag |= 0x3;
                    } else if (s1.equals("excp")) {
                        flag |= 0x4;
                    } else if (s1.equals("excpsucc")) {
                        flag |= 0x5;
                    } else {
                        System.out.println("输入错误");
                    }
                    // 单个订单接收
                    bytes = buildAbstractStatus(flag, this.printerId);
                    System.out.print("请输入订单批次号与订单号内序号");
                    String bulkId = br.readLine();
                    String inNumber = br.readLine();

                    bytes[14] = Byte.valueOf(bulkId);
                    bytes[15] = Byte.valueOf(inNumber);
                    outputStream.write(bytes);
                    outputStream.flush();
                } else if (s.equals("printer")) {
                    // 打印机状态
                } else {
                    // 处理发送数据
                    byte[] byte4 = new byte[4];
                    int i = 0;
                    byte[] t = new byte[1024];
                    inputStream.read(t);
//
//                while (inputStream.read(byte4) != -1) {
//                    StringBuffer stringBuffer = new StringBuffer();
//                    for (int j = 0; j < 4; j++)
//                        stringBuffer.append(Integer.toHexString(byte4[j] & 0xFF) + " | ");
//                    System.out.println("第" + i + "字节 ： " + stringBuffer.toString());
//                    i++;
//                }

                    System.out.println("接收数据完成");
                    i = 0;
                    //DebugUtil.printBytes(byte4);
                }


            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
