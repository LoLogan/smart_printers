package com.qg.smpt.util;

/**
 * 数据转换工具类
 * @author FunriLy
 * Created by FunriLy on 2017/12/17.
 * From small beginnings comes great things.
 */
public class DataUtil {

    /**
     * 将给定的 byte 数组转换为十六进制 String
     * @param byteArray byte 数组
     * @return 十六进制 String
     */
    public static String byteArrayToHexStr(byte[] byteArray) {
        if (byteArray == null) {
            return null;
        }
        char[] hexArray = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[byteArray.length * 2];
        for (int j = 0; j < byteArray.length; j++) {
            int v = byteArray[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
