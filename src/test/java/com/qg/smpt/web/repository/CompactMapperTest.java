package com.qg.smpt.web.repository;

import com.qg.smpt.printer.model.CompactModel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.annotation.Resource;

import static org.junit.Assert.*;

/**
 * Created by logan on 2017/11/5.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {"classpath:spring/spring-*.xml"})
public class CompactMapperTest {
    @Resource
    private  CompactMapper compactMapper;

    @Test
    public void selectMaxCompact() throws Exception {
        System.out.println(compactMapper.selectMaxCompact());
    }

    @Test
    public void addCompact() throws Exception {
        CompactModel compactModel = new CompactModel();
        compactModel.setCompactNumber((short) (compactMapper.selectMaxCompact()+1));
//        compactModel.setSeq((short) 1);
//        compactModel.setOrderNumber((short) 100);
        compactModel.setId(5);
        System.out.println(compactMapper.addCompact(compactModel));
    }

    @Test
    public void updatePrinter() throws Exception {
        compactMapper.updatePrinter(2,2.5,3,4.3);
    }

    @Test
    public void getPriById() throws Exception {
        System.out.println(compactMapper.getCreById(2));
        System.out.println(compactMapper.getPriById(2));
    }
}