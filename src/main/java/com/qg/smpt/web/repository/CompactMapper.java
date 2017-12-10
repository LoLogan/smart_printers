package com.qg.smpt.web.repository;

import com.qg.smpt.printer.model.CompactModel;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/**
 * Created by logan on 2017/11/5.
 */

@Repository
public interface CompactMapper {

    /**
     * 查询当前合同网最大编号
     * @return
     */
    int selectMaxCompact();

    /***
     * 插入合同记录
     * @param compact
     * @return
     */
    int addCompact(@Param("compact") CompactModel compact);


    /***
     * 更新打印机信任度，速度，打印代价
     * @param id
     * @param credibility
     * @param speed
     * @param price
     * @return
     */
    int updatePrinter(@Param("id") Object id, @Param("credibility") Object credibility, @Param("speed") Object speed, @Param("price") Object price);

    /***
     * 根据id获取信任度
     * @param id
     * @return
     */
    double getCreById(@Param("id") int id);

    /***
     * 根据id获取打印代价
     * @param id
     * @return
     */
    double getPriById(@Param("id") int id);

    /***
     * 根据订单份数来获取需要的打印机台数
     * @param orderNumber
     * @return
     */
    int getPrinterCapacityByOrderNumber(@Param("orderNumber") int orderNumber);
}
