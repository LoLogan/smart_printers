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
}
