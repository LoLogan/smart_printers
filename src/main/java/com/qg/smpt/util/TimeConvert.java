package com.qg.smpt.util;

import java.time.LocalTime;

/**
 * Created by logan on 2017/12/13.
 */
public class TimeConvert {
    public static int getTimeCurrent() {
        String time = LocalTime.now().toString().replace(":","").replace(".","");
        return Integer.parseInt(time);
    }
}
