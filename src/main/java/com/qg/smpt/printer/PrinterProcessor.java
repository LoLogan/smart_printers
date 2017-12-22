package com.qg.smpt.printer;

import com.qg.smpt.printer.model.*;
import com.qg.smpt.receive.ReceOrderServlet;
import com.qg.smpt.share.ShareMem;
import com.qg.smpt.util.*;
import com.qg.smpt.web.model.BulkOrder;
import com.qg.smpt.web.model.Order;
import com.qg.smpt.web.model.Printer;
import com.qg.smpt.web.model.User;
import com.qg.smpt.web.repository.CompactMapper;
import com.qg.smpt.web.repository.OrderMapper;
import com.qg.smpt.web.repository.PrinterMapper;
import com.qg.smpt.web.repository.UserMapper;
import com.sun.glass.ui.Size;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.sql.Time;
import java.util.*;
import java.util.concurrent.CountDownLatch;


import static com.qg.smpt.share.ShareMem.compactPrinter;
import static com.qg.smpt.share.ShareMem.priSentQueueMap;


/**
 * 打印机的线程调度：将缓存队列中数据组装并发送到给打印机，将缓存队列转发到已发队列
 */
public class PrinterProcessor implements Runnable, Lifecycle{
    private final static Logger LOGGER_COMPACT = Logger.getLogger("compact");
    private final Logger LOGGER = Logger.getLogger(PrinterProcessor.class);

    private Thread thread = null;

    private String threadName = null;

    private int id;                 // 当前线程id

    private long waitTime = 20000;  // 普通订单通道睡眠时间

    // TODO 关于字节数组分配过下,而导致需要两次调用的问题
    private ByteBuffer byteBuffer;

    private boolean available;     // 唤醒线程池中的线程的标志-普通订单发送标志

    private boolean sendAvailable; // 唤醒因发送条件不满足而进入睡眠状态的线程

    private boolean expedite;      // 唤醒线程池中的线程的标志-加急订单发送标志

    private boolean started;

    private boolean stopped;

    private SocketChannel socketChannel;

    private final static SqlSessionFactory sqlSessionFactory;

    static {
        String resource = "mybatis/mybatis-config.xml";
        Reader reader = null;
        try {
            reader = Resources.getResourceAsReader(resource);
        } catch (IOException e) {
            System.out.println(e.getMessage());

        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
    }

    public PrinterProcessor(int id, PrinterConnector printerConnector) {
        this.id = id;
        this.threadName = "PrinterProcessor[" + printerConnector.getPort() + "][" + id + "}";
        this.available = false;
        this.sendAvailable = false;
        this.expedite = false;
        this.started = false;
        this.stopped = false;


    }


    /* 生命周期管理 */
    public void start() throws LifecycleException{
        if (started) {
            throw new LifecycleException("printerProcessor already started");
        }

        started = true;

        threadStart();
    }
    public void stop() {

    }
    /**
     * 开启后台线程
     */
    private void threadStart() {
        LOGGER.log(Level.DEBUG, "printerProcessor starting");
        thread = new Thread(this, threadName);
        thread.setDaemon(true);
        thread.start();
    }
    private void threadStop() {

    }

    /* 线程启动 睡眠 唤醒模块*/
    public void run() {
        // 数据转发
        while (!stopped) {
            SocketChannel socketChannel = await();
            if (socketChannel == null) {
                continue;
            }

            parseData(socketChannel, this.byteBuffer);
        }
    }
    /**
     * 线程池中的线程进入睡眠状态
     * @return SocketChannel
     */
    private synchronized SocketChannel await() {

        while (!available) {
            try {
                wait();
            } catch (InterruptedException e) {

            }
        }

        LOGGER.log(Level.DEBUG, "printerProcessor thread is notified");

        SocketChannel socketChannel = this.socketChannel;

        available = false;

        notifyAll();

        return socketChannel;
    }
    /**
     * 唤醒线程池中睡眠的线程
     * @param sc
     */
    public synchronized void assign(SocketChannel sc, ByteBuffer byteBuffer) {

        // 根据不同的请求数据进行不同的处理
        // 1. 第一次请求：建立用户与打印机关系；
        // 2. 内存发送
        // 3. 订单状态 （批次，单次）
        // 4. 打印机状态

        // 5. 接收数据后： 发送数据（条件判断-根据打印机id，即线程要拥有。来获取每个打印机所拥有的数据）
        // 内存容量，缓存容量，时间间隔

        while (available) {
            try {
                wait();
            } catch (InterruptedException e) {

            }
        }

        available = true;

        this.socketChannel = sc;

        this.byteBuffer = byteBuffer;

        notifyAll();
    }
    /**
     * 唤醒等待数据的打印机线程
     */
    public synchronized void notifyOK() {
        try {
            while (sendAvailable) {
                wait();
            }
        } catch (final InterruptedException e) {
            LOGGER.log(Level.ERROR, "线程 printerProcessor [{0}] 被中断", this.id, e);
        }

        sendAvailable = true;

        LOGGER.log(Level.INFO, "线程 printerProcessor [{0}] 设置可发送标志", this.id);

        notifyAll();
    }

    public synchronized void notifyExpedite() {
        try {
            while (expedite) {
                wait();
            }
        } catch (final InterruptedException e) {
            LOGGER.log(Level.ERROR, "线程 printerProcessor [{0}] 被中断", this.id, e);
        }

        expedite = true;

        LOGGER.log(Level.INFO, "线程 printerProcessor [{0}] 设置可发送标志", this.id);

        notifyAll();
    }

    /* 数据解析模块 */
    /**
     * 解析数据
     */
    private void parseData(SocketChannel socketChannel, ByteBuffer byteBuffer){
        // Debug 模式


        LOGGER.log(Level.DEBUG, "printerProcessor [{0}] parse data", this.id);

        // 将byteBuffer 中的字节数组进行提取
        byte[] bytes = byteBuffer.array();


        if (bytes[0] == (byte)0xCF && bytes[1] == (byte)0xFC) {
//            DebugUtil.printBytes(bytes);
            switch (bytes[2]) {
                case BConstants.connectStatus :
                    LOGGER.log(Level.DEBUG, "打印机 发送连接数据，初始化打印机对象和打印机对象所拥有的缓存批次订单队列，异常订单队列，打印机处理线程 thread [{0}]", this.getId());
                    parseConnectStatus(bytes, socketChannel);
                    break;
                case BConstants.okStatus:
                    LOGGER_COMPACT.log(Level.DEBUG, "打印机 发送过来可以请求数据 thread [{0}] ", this.getId());
                    parseOkStatus(bytes, socketChannel);
                    break;
                case BConstants.orderStatus:
//                    DebugUtil.printBytes(bytes);
                    LOGGER.log(Level.DEBUG, "打印机 接收订单状态数据 thread [{0}] ", this.getId());
                    parseOrderStatus(bytes, socketChannel);
                    break;
                case BConstants.bulkStatus:
                    LOGGER.log(Level.DEBUG, "打印机 接收批次状态数据 thread [{0}] ", this.getId());
                    parseBulkStatus(bytes);
                    break;
                case BConstants.printStatus:
                    parsePrinterStatus(bytes);
                    LOGGER.log(Level.DEBUG, "打印机 接收打印机状态数据 thread [{1}] ", this.getId());
                    break;
                default:
                    LOGGER.log(Level.WARN, "打印机  发送状态数据 thread [{1}] 错误", this.getId());
                    return ;
            }
        } else if (bytes[0] == (byte)0xBF && bytes[1] == (byte)0xFB){
            DebugUtil.printBytes(bytes);
            switch (bytes[2]) {
                case BConstants.bid :
                    LOGGER_COMPACT.log(Level.DEBUG, "[投标]主控板进行投标，服务器对标书进行筛选，处理线程 thread [{0}]", this.getId());
                    parseBid(bytes, socketChannel);
                    break;
                case BConstants.sign :
                    LOGGER_COMPACT.log(Level.DEBUG, "[签约]收到主控板的签约，处理线程 thread [{0}]", this.getId());
                    sign(bytes, socketChannel);
                    break;
                case BConstants.removeSign :
                    LOGGER_COMPACT.log(Level.DEBUG, "[确认解约]收到主控板的确认解约，处理线程 thread [{0}]", this.getId());
                    removeSign(bytes);
            }

        } else {
            LOGGER.log(Level.INFO, "收到无效数据");
        }

    }

    /***
     * 确认解约
     * @param bytes
     */
    private void removeSign(byte[] bytes){
        CompactModel compactModel = CompactModel.bytesToCompact(bytes);
        LOGGER_COMPACT.log(Level.DEBUG, "[确认解约]与主控板 [{0}]确认解约", compactModel.getId());
        synchronized (ShareMem.compactOfPrinter) {
            List<Printer> printers = ShareMem.compactOfPrinter.get(compactModel.getCompactNumber());
            Printer p = ShareMem.printerIdMap.get(compactModel.getId());
            printers.remove(p);
            synchronized (p){
                p.notifyAll();
            }
        }
    }

    /***
     * 解析打印机传来的标书，计算信任度
     * @param bytes
     * @param socketChannel
     */
    private void parseBid(byte[] bytes, SocketChannel socketChannel){

        CompactModel compactModel = CompactModel.bytesToCompact(bytes);
        //获得打印机对象
        Printer printer = ShareMem.printerIdMap.get(compactModel.getId());

        //存储打印速度（一台主控板下的打印机数量）
        printer.setSpeed(compactModel.getSpeed());

        //计算信任度
        Double credibility =(BConstants.initialCre + BConstants.alpha*printer.getPrintSuccessNum()-BConstants.beta*printer.getPrintErrorNum());

        //存储信任度
        printer.setCre(credibility);

        //存储缓冲区大小
        printer.setBufferSize(compactModel.getBufferSize());

        //计算存储代价 并存储打印代价
        double price = credibility * compactModel.getSpeed();
        printer.setPrice(price);

        //将进行投标的主控板进行存储
        synchronized (ShareMem.compactPrinter) {
            List<Printer> printers = ShareMem.compactPrinter.get(compactModel.getCompactNumber());
            printers.add(printer);
        }

        LOGGER_COMPACT.log(Level.DEBUG, "[投标]收到主控板[{0}]的标书，打印速度为[{1}]，健康状态[{3}]，计算信任度[{2}]", compactModel.getId(),compactModel.getSpeed(),credibility,compactModel.getHealth());


        SqlSessionFactory sqlSessionFactory = SqlSessionFactoryBuild.getSqlSessionFactory();
        SqlSession sqlSession = sqlSessionFactory.openSession();
        CompactMapper compactMapper = sqlSession.getMapper(CompactMapper.class);

        try {
            compactMapper.updatePrinter(compactModel.getId(),credibility,compactModel.getSpeed(),price);
        }finally {
            sqlSession.commit();
            sqlSession.close();
        }
    }

    /***
     * 主控板确认与服务端的签约，服务端进行订单的下发
     * @param bytes
     * @param socketChannel
     */
    private synchronized void sign(byte[] bytes, SocketChannel socketChannel){

        CompactModel compactModel = CompactModel.bytesToCompact(bytes);
        LOGGER_COMPACT.log(Level.DEBUG, "[签约确认]收到主控板[{0}]的签约确认，准备向其发送订单数据", compactModel.getId());
        Printer printer = ShareMem.printerIdMap.get(compactModel.getId());
        printer.setCanAccept(true);
        //如果都主控板都解约了，则不需要继续进行订单的下发了 //
        while(ShareMem.priSocketMap.get(ShareMem.printerIdMap.get(compactModel.getId()))!=null && ShareMem.compactOfPrinter.get(compactModel.getCompactNumber()).contains(ShareMem.printerIdMap.get(compactModel.getId()))) {
            synchronized (printer){
                BulkOrder bOrders = new BulkOrder(new ArrayList<BOrder>());
                List<Order> orders = ShareMem.compactBulkMap.get((short) compactModel.getCompactNumber());
                if (orders.size()==0 || !printer.isCanAccept()) continue;
//                if (orders.size() != 0) {
                synchronized (orders) {
                    printer.increaseBulkId();
                    bOrders.setId(printer.getCurrentBulk());
                    List<Order> orderList = new ArrayList<Order>();
                    for (Order order : orders) {
                        BOrder bOrder = order.orderToBOrder((short) printer.getCurrentBulk(), (short) bOrders.getbOrders().size());
                        if (bOrders.getDataSize() + bOrder.size > printer.getBufferSize()/2) break;

                        bOrders.getbOrders().add(bOrder);
                        bOrders.getOrders().add(order);
                        //更新所有订单的字节数（不包括批次报文）
                        bOrders.setDataSize(bOrders.getDataSize() + bOrder.size);
                        //设置所属批次
                        bOrder.bulkId = (short) bOrders.getId();
                        //设置批次内的序号
                        bOrder.inNumber = (short) bOrders.getOrders().size();
                        //为订单设置打印机
                        order.setMpu(printer.getId());
                        orderList.add(order);
                    }
                    orders.removeAll(orderList);
                }
                LOGGER_COMPACT.log(Level.DEBUG, "为打印机 [{0}] 分配任务, 合同网订单缓冲队列 [{1}]，" +
                                "批次号为 [{2}], 最后批次订单容量 [{3}] byte", printer.getId(),
                        orders.size(), bOrders.getId(), bOrders.getDataSize());


                //存入已发送队列
                synchronized (ShareMem.priSentQueueMap.get(printer)) {
                    List<BulkOrder> bulkOrderList = ShareMem.priSentQueueMap.get(printer);
                    if (bulkOrderList == null) {
                        bulkOrderList = new ArrayList<BulkOrder>();
                        ShareMem.priSentQueueMap.put(printer, bulkOrderList);
                    }
                    bulkOrderList.add(bOrders);
                }
                if (bOrders.getDataSize()==0)continue;

                //引用以前的批次报文，但是只用里边的data属性，data即是这个批次的订单报文数据
                BBulkOrder bBulkOrder = BulkOrder.convertBBulkOrder(bOrders, false);
                byte[] bBulkOrderBytes = BBulkOrder.bBulkOrderToBytes(bBulkOrder);

                try {
                    socketChannel.write(ByteBuffer.wrap(bBulkOrderBytes));
                    LOGGER_COMPACT.log(Level.DEBUG, "[确认签约]发放任务时成功");
                } catch (final IOException e) {
                    LOGGER_COMPACT.log(Level.ERROR, "[确认签约]发放任务时发生错误");
                }
//                }
                LOGGER_COMPACT.log(Level.DEBUG, "[发放任务]主控板[{0}]不可接受新数据，进入睡眠",printer.getId());
                printer.setCanAccept(false);
                try {
                    printer.wait();
                    LOGGER_COMPACT.log(Level.DEBUG, "[发放任务]唤醒，继续发送订单");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        }
    }


    /**
     * 处理打印机的连接请求, 若打印机对象未建立，则建立并初始化打印机对象
     * 1. 检测打印机id - 打印机关系是否建立, 若已建立，则直接返回
     * 2. （未建立条件下）, 从数据库中获取打印机对应的 userid
     * 3. 根据userid 获取共享内存中的User对象
     * 4. 若User对象为空，则查询数据库, 获取List<Printer>集合; 否则直接从User对象中获取Printer集合
     * 5. 从Printer集合中查找对应的Printer对象
     * 6. 将Printer对象放入共享内存中,并设置为已连接状态
     * @param bytes
     */
    private void parseConnectStatus(byte[] bytes, SocketChannel socketChannel) {
        BRequest bRequest = BRequest.bytesToRequest(bytes);

        LOGGER.log(Level.DEBUG, "打印机id [{0}], 发送时间戳 [{1}], 校验和 [{2}], 标志位 [{3}] ",
                bRequest.printerId, bRequest.seconds, bRequest.checkSum, bRequest.flag);

        int printerId = bRequest.printerId;

        // 建立用户-printer 关系
        Printer printer = ShareMem.printerIdMap.get(printerId);



        if (printer != null){
            ShareMem.priSocketMap.put(printer, socketChannel);
            LOGGER.log(Level.WARN, "共享对象中已存在打印机[{0}]与打印机对象", printerId);
            LOGGER.log(Level.DEBUG, "重新建立打印机[{0}] 与 socketChannel对象关联", printerId);
            return ;
        }

        // id - printer 建立在商家注册时 进行建立
        Integer userId = null;
        User user = null;
        SqlSession sqlSession = sqlSessionFactory.openSession();
        try {
            PrinterMapper printerMapper = sqlSession.getMapper(PrinterMapper.class);
            /* Step 2 从数据库中获取用户id */
            userId = printerMapper.selectUserIdByPrinter(printerId);
            if (userId == null) {
                LOGGER.log(Level.ERROR, "打印机信息并未注册[{0}]", printerId);
                return ;
            }
            /* Step 3 根据userId 获取 user 对象 */
            user = ShareMem.userIdMap.get(userId);
            synchronized (ShareMem.userIdMap) {
                UserMapper userMapper = sqlSession.getMapper(UserMapper.class);
                user = userMapper.selectUserPrinter(userId);
                if (user == null) {
                    LOGGER.log(Level.WARN, "无商家信息 [{0}]", userId);
                    return;
                }
                ShareMem.userIdMap.put(userId, user);

            }



        } finally {
            sqlSession.commit();
            sqlSession.close();
        }

        /* Step 4 */
        List<Printer> printers = user.getPrinters();
        if (printers == null || printers.size() < 1) {
            LOGGER.log(Level.ERROR, "打印机信息并未注册 商家：[{0}]", userId);
            return ;
        }
        int position  = 0;
        for (position = 0; position < printers.size(); position++) {
            Printer p = printers.get(position);
            if (p.getId() == printerId) {
                printer = p;
                break;
            }
        }
        if (position >= printers.size()) {
            LOGGER.log(Level.WARN, "商家无该打印机信息 [{0}]", printerId);
            return ;
        }

        printer.setUserId(userId);
        printer.setConnected(true);
        printer.setCurrentBulk(0);
        // TODO 如果有两个线程同时向 HashMap中添加相同printerId， 是否会出现重复问题

        synchronized (ShareMem.printerIdMap) {
            ShareMem.printerIdMap.put(printerId, printer);
        }

        ShareMem.priSocketMap.put(printer, socketChannel);
        LOGGER.log(Level.DEBUG, "建立打印机[{0}] 与 socketChannel对象关联", printerId);

        LOGGER.log(Level.DEBUG, "将打印机[{0}]并为建立打印对象；打印机状态:[{1}];用户:[{2}]", printerId,
                printer.getPrinterStatus(), printer.getUserId());


        // TODO printer 锁是否有用
        /* 锁住 printer 打印机对象，避免出现创建多次队列现象*/
        synchronized (printer) {
            if (ShareMem.priExceQueueMap.get(printer) == null) {
                // 异常队列
                LOGGER.log(Level.INFO, "初始化打印机[{0}] 异常队列", printerId);
                ShareMem.priExceQueueMap.put(printer, new ArrayList<BulkOrder>());
            }
            if (ShareMem.priBufferMapList.get(printer) == null) {
                LOGGER.log(Level.INFO, "初始化打印机[{0}] 缓存队列", printerId);
                ShareMem.priBufferMapList.put(printer, new ArrayList<BulkOrder>());
            }
            if (priSentQueueMap.get(printer) == null) {
                LOGGER.log(Level.INFO, "初始化打印机[{0}] 已发队列", printerId);
                ShareMem.priSentQueueMap.put(printer, new ArrayList<BulkOrder>());
            }
        }

        /* 检测userOrderBufferMap 中是否存有订单数据 */
        List<Order> orders = ShareMem.userOrderBufferMap.get(userId);
        if (orders == null || orders.size() == 0) {
            return ;
        }

        LOGGER.log(Level.DEBUG, "在打印机建立连接前商家已经接收到订单");

        synchronized (ShareMem.userOrderBufferMap.get(userId)) {
            new ReceOrderServlet().sendUserOrderBuffer(printer, userId);
        }
    }

    /**
     * 解析打印机阈值请求, 并向打印机发送数据
     * @param bytes
     * @param socketChannel
     */
    private synchronized void parseOkStatus(byte[] bytes, SocketChannel socketChannel) {
        // 解析OK请求
        BRequest request = BRequest.bytesToRequest(bytes);

        // 获取打印机主控板id,获取打印机
        int printerId = request.printerId;

        LOGGER_COMPACT.log(Level.DEBUG, "------------------------------阈值请求----------------------------------------------" );
        LOGGER_COMPACT.log(Level.DEBUG, "解析请求打印机请求id:[{0}], flag:[{1}]," +
                        "seconds:[{2}];checksum [{3}]; 当前线程 [{4}]" , request.printerId, request.flag, request.seconds,
                request.checkSum, this.id);

        Printer p = ShareMem.printerIdMap.get(printerId);
        if (p == null) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (p == null) {
                LOGGER_COMPACT.log(Level.ERROR, "共享内存中并未找到打印机id[{0}]对应printer对象 当前线程 [{1}]", printerId, this.id);
                return;
            }
        }

        synchronized (p){
            p.setCanAccept(true);
            p.notifyAll();
        }

//        if (!p.isConnected()) {
//            //LOGGER.log();
//        }
//
//        ShareMem.priPriProcessMap.put(p, this);
//        LOGGER.log(Level.INFO, "建立打印机对象:[{0}] 与 PrinterConnector 线程之间连接 当前线程 [{1}]", p.getId(), this.id);
//
//        p.setCanAccept(true);
//        LOGGER.log(Level.INFO, "printer id {0}, accept: {1}, connect {2}",
//                p.getId(), p.isCanAccept(), p.isConnected());
//        // TODO Debug
//        if (ShareMem.priSocketMap.get(p) != null) {
//            LOGGER.log(Level.DEBUG, "判断打印机[{0}] 绑定 socketChannel 对象 [{1}] 当前线程 [{2}]", p.getId(), socketChannel == ShareMem.priSocketMap.get(p), this.id);
//        }
//
//        long requestTime = System.currentTimeMillis();
//        LOGGER.log(Level.INFO, "打印机 [{0}] 在 [{1}] 时间发来订单请求", p.getId(), TimeUtil.timeToString(requestTime));
//        LOGGER.log(Level.DEBUG, "当前时间: [{0}] 当前线程 [{1}]", requestTime, this.id);
//        try {
//            while (!sendAvailable) {
//                if (ShareMem.priBufferMapList.get(p).size() > 1)
//                    break;
//                LOGGER.log(Level.DEBUG, "打印机 [{0}] 的 线程printerConnector[{1}]并不满足发送条件，进入睡眠 ", printerId, this.id);
//                wait(waitTime + 1 / 10 * waitTime);
//                LOGGER.log(Level.DEBUG, "线程唤醒时间 [{0}}", System.currentTimeMillis());
//                if (System.currentTimeMillis() - requestTime > 10000) {
//                    LOGGER.log(Level.INFO, "打印机 [{0}] 的 线程printerConnector[{1}]自动睡醒", printerId, this.id);
//                    if (ShareMem.priBufferMapList.get(p).size() > 0) {
//                        LOGGER.log(Level.INFO, "打印机 [{0}] 的 线程printerConnector[{1}] 发送缓冲区存有批次订单 [{2}] 条, 该批次中有订单数据 [{2}] 条, 容量 [{3}] 字节准备发送",
//                                printerId, this.id, ShareMem.priBufferMapList.get(p).size(), ShareMem.priBufferMapList.get(p).get(0).getOrders().size(), ShareMem.priBufferMapList.get(p).get(0).getDataSize());
//                        break;
//                    }
//                }
//            }
//        } catch (final InterruptedException e) {
//            LOGGER.log(Level.ERROR, "打印机 [{0}] 的 线程printerConnector[{1}] 睡眠被打断", printerId, this.id, e);
//        }
//
//        LOGGER.log(Level.DEBUG, "打印机 [{0}] 解除绑定线程 printerConnector[{1}], 取消可发生状态, printer对象设置为不可接收数据状态", printerId, this);
//        ShareMem.priPriProcessMap.remove(p);
//        sendAvailable = false;
//        p.setCanAccept(false);
//
//        notifyAll();
//
//        BulkOrder bOrders = null;
//
//        synchronized (ShareMem.priBufferMapList.get(p)) {
//            LOGGER.log(Level.DEBUG, "打印机 [{0}] printerConnector[{1}] 锁定打印机缓存队列 [{2}]," +
//                    "从缓存队列中弹出批次订单数据", printerId, this.id, ShareMem.priBufferMapList.get(p));
//            bOrders = ShareMem.priBufferMapList.get(p).get(0);
//
//            ShareMem.priBufferMapList.get(p).remove(0);
//        }
//        if (bOrders == null) {
//            LOGGER.log(Level.WARN, "打印机 [{0}] printerConnector [{1}} 缓存队列无批次订单数据");
//        }
//
//        LOGGER.log(Level.DEBUG, "打印机 [{0}] 绑定线程 printerConnector[{1}] 锁定打印机已发队列 [{2}]，" +
//                "并将批次订单数据加入已发队列中", printerId, this.id, ShareMem.priSentQueueMap.get(p));
//        List<BulkOrder> bulkOrderList = ShareMem.priSentQueueMap.get(p);
//
//        if (bulkOrderList == null) {
//            bulkOrderList = new ArrayList<BulkOrder>();
//            ShareMem.priSentQueueMap.put(p, bulkOrderList);
//        }
//
//
//        bulkOrderList.add(bOrders);
//
//
//        LOGGER.log(Level.DEBUG, "打印机 [{0}] 线程 printerConnector[{1}] 开始转换批次订单数据",
//                printerId, this.id);
//        BBulkOrder bBulkOrder = BulkOrder.convertBBulkOrder(bOrders, false);
//        byte[] bBulkOrderBytes = BBulkOrder.bBulkOrderToBytes(bBulkOrder);
//
//
//        if (bBulkOrderBytes.length % 4 != 0) {
//            LOGGER.log(Level.ERROR, "打印机 [{0}] 线程 printerConnector[{1}] 字节并未对齐");
//        }
//
//
//        // TODO Debug 模式
//        LOGGER.log(Level.DEBUG, "========== 订单数据字节流 start ============ 当前线程线程[{0}]", this.id);
//
//        DebugUtil.printBytes(bBulkOrderBytes);
//
//        LOGGER.log(Level.DEBUG, "========== 订单数据字节流 end ============ 当前线程[{0}]", this.id);
//        ByteBuffer byteBuffer = ByteBuffer.wrap(bBulkOrderBytes);
//
//        try {
//            socketChannel.write(byteBuffer);
//        } catch (final IOException e) {
//            LOGGER.log(Level.ERROR, "打印机 [{0}] 打印机线程 printerProcessor [{1}] 发送订单数据异常", p.getId(), this.id);
//            LOGGER.log(Level.ERROR, "打印机 [{0}] 打印机线程 printerProcessor [{1}] 撤销批次发送[{2}]", p.getId(), this.id, bOrders.getId());
//            ShareMem.priBufferMapList.get(p).add(0,bOrders);
//            bulkOrderList.remove(bOrders);
//        }
//
//        long sendtime = System.currentTimeMillis();
//        LOGGER.log(Level.INFO, "打印机 [{0}] 在 [{1}] 时间发来订单请求,正式发送订单给打印机的时间是[{2}],总共等待时间为 [{3}] ms"
//                , p.getId(), TimeUtil.timeToString(requestTime), TimeUtil.timeToString(sendtime), sendtime - requestTime);
//        String orderSent = String.valueOf(BConstants.orderSent);
//        for (Order o : bOrders.getOrders()) {
//            o.setSendTime(sendtime);
//            o.setOrderStatus(orderSent);
//        }
//
//        // 修改打印机已发送、未发送数量
//        int num = bOrders.getOrders().size();
//        synchronized (p) {
//            p.setUnsendedOrdersNum(p.getUnsendedOrdersNum() - num);
//            p.setSendedOrdersNum(p.getSendedOrdersNum() + num);
//        }
//
//        LOGGER.log(Level.INFO, "打印机 [{0}] 对应 打印机线程 printerProcessor [{1}] 完成订单发送请求; 时间 [{2}]",
//                printerId, this.id, System.currentTimeMillis());
    }

    /**
     * 解析打印机发送的订单状态
     * 状态码: 0x0 打印成功 从已发队列中的订单状态标志为成功
     *        0x1 | 0x4 打印出错 获取已发队列批次订单的单个订单数据，组装新的批次订单（加急标志），放入异常队列。改：并不删除
     *        0x2 | 0x3 打印状态(进入打印队列;开始打印) 从已发队列中的订单状态标志为成功
     *        0x5 因异常而打印成功
     * @param bytes
     * @param socketChannel
     */
    private void parseOrderStatus(byte[] bytes, SocketChannel socketChannel) {

        // 所有订单报文长度都是28字节
        BOrderStatus bOrderStatus;

        // 如果是订单转移按照24字节解析
        if (bytes[3] == BConstants.orderMigrate) {
            // todo : 此处输出是为了方便嵌入式查看，测试完后删除
            System.out.println("===========进行订单转移报文解析===========");
            bOrderStatus = BOrderStatus.bytesToOrderStatusWithRemoving(bytes);
            // 将报文打印出来
            System.out.println(DataUtil.byteArrayToHexStr(bytes));
            System.out.println("转移报文信息 : " + bOrderStatus.toString());
            // 报文转化信息可以在debug信息中查看
            System.out.println("===========订单转移报文解析完成===========");
        } else {
            // 否则就按照状态报文20字节解析
            bOrderStatus = BOrderStatus.bytesToOrderStatus(bytes);
        }


        byte flag = (byte)(bOrderStatus.flag  & 0xFF);

        LOGGER.log(Level.DEBUG, "打印机id [{0}], 订单标志 : [{1}] , 订单发送时间戳 : [{2}], " +
                        "所属批次[{3}], 批次内序号 [{4}], 校验和 [{5}] 当前线程 [{6}]", bOrderStatus.printerId, flag,
                bOrderStatus.seconds, bOrderStatus.bulkId, bOrderStatus.inNumber, bOrderStatus.checkSum, this.id);

        Printer printer = ShareMem.printerIdMap.get(bOrderStatus.printerId);

        /* 获取批次订单队列 flag 0x5 : 获取异常批次订单队列; others : 获取已发送批次订单队列 */
        List<BulkOrder> bulkOrderList = null;
        if (flag == BConstants.orderMigrate || flag == BConstants.orderInQueue || flag == BConstants.orderFail || flag == BConstants.orderTyping
                || flag == BConstants.orderDataW || flag == BConstants.orderSucc) {
            // 获取已发队列数据
            bulkOrderList = ShareMem.priSentQueueMap.get(printer);
        } else {
            bulkOrderList = ShareMem.priExceQueueMap.get(printer);
        }
        /* 获取批次订单中的订单内容 */
        BulkOrder bulkOrderF = null; // 订单所在的批次订单
        Order order = null;          // 处理的订单
        BOrder bOrder = null;        // 处理的订单
        int position;              // 记录批次订单在缓存队列中的位置
        for (position = 0; position < bulkOrderList.size(); position++) {
            bulkOrderF = bulkOrderList.get(position);
//            LOGGER.log(Level.DEBUG,"寻找的批次订单号[{0}], 打印机发送的批次订单号 [{1}]", bulkOrderF.getId(), bOrderStatus.printerId);
            if (bulkOrderF.getId() == bOrderStatus.bulkId) {
                // 找到批次
                LOGGER.log(Level.DEBUG, "已找到打印机 [{0}]  对应批次订单号 [{1}] 当前线程 [{2}]", bOrderStatus.printerId, bOrderStatus.bulkId, this.id);
                // 记录该批次数量大小
                int size = bulkOrderF.getbOrders().size();
                if (size < bOrderStatus.inNumber) {
                    LOGGER.log(Level.ERROR, "批次 [{2}] 批次内序号 [{0}] 超出批次订单范围 [{1}] 当前线程 [{3}]", bOrderStatus.inNumber, size, bOrderStatus.bulkId, this.id);
                    return;
                }
                // 获得对应的那份订单
                order = bulkOrderF.getOrders().get(bOrderStatus.inNumber);
                bOrder = bulkOrderF.getbOrders().get(bOrderStatus.inNumber);

                order.setOrderStatus(String.valueOf(bOrderStatus.flag & 0xFF));  // 设置订单状态 //

                long time = System.currentTimeMillis();
                switch (flag) {
                    case BConstants.orderInQueue :
                        order.setEnterQueueTime(time);
                        break;

                    case BConstants.orderTyping :
                        order.setStartPrintTime(time);
                        break;

                    case BConstants.orderFail :
                        order.setPrintResultTime(time);
                        synchronized (printer) {
                            // 失败订单加一
                            printer.increaseErrorNum(1);
                        }
                        break;

                    case BConstants.orderSucc :
                        synchronized (printer) {
                            // 成功订单加一
                            printer.increaseSuccessNum(1);
                        }
                        order.setPrintResultTime(time);
                        break;

                    case BConstants.orderExcepInQueue:
                        order.setExecEnterQueueTime(time);
                        break;

                    case BConstants.orderExcepTyping:
                        order.setExecStartPrintTime(time);
                        break;

                    case BConstants.orderMigrate:
                        // todo 订单转移导致打印机的失败订单数+1，这个具体数值可能需要重新确定
                        synchronized (printer) {
                            printer.increaseErrorNum(1);
                            // 重新计算信任度
                            printer.setCre((BConstants.initialCre + BConstants.alpha * printer.getPrintSuccessNum()
                                    - BConstants.beta * printer.getPrintErrorNum()));
                        }
                        break;

                    case BConstants.orderExcep:
                    case BConstants.orderExcepDataW:
                    case BConstants.orderExcepFail:
                        order.setExecPrintResultTime(time);
                        break;
                }

//                LOGGER.log(Level.DEBUG, "订单内容 [{0}] 当前线程 [{1}]", order.toString(), this.id);

                break;
            }
        }
        if (position == bulkOrderList.size()) {
            LOGGER.log(Level.WARN, "打印机[{0}] 批次订单号[{1}] 订单内序号[{2}] 批次并不存在 ;当前线程 [{3}]", bOrderStatus.printerId,
                    bOrderStatus.bulkId, bOrderStatus.inNumber, this.id);
            return ;
        }

        LOGGER.log(Level.DEBUG, "内存中的订单id: [{0}], 当前线程 [{1}]", order.getId(), this.id);
        LOGGER.log(Level.DEBUG, "====确认 orderId: {0}, userId: {1}", order.getId(), order.getUserId());


        /* 失败 重发数据*/
        if (flag == BConstants.orderMigrate) {
            // 订单打印失败，从此订单开始批次开始转移
            // 获得批次
            bulkOrderF = bulkOrderList.get(position);
            // 移除批次
            bulkOrderList.remove(position);
            int size = bulkOrderF.getOrders().size();
            // 拿到需要重新打印的订单
            List<Order> orderList = new ArrayList<>();
            List<BOrder> bOrderList = new ArrayList<>();
            // 批次订单数据的总大小
            int totalSize = 0;

            /* 组装批次订单 */
            BulkOrder bulkOrder = new BulkOrder(new ArrayList<>());

            // 加急处理设置
            bulkOrder.setBulkType((short) 1);
            bulkOrder.setDataSize(totalSize);
            printer.increaseBulkId();
            bulkOrder.setId(printer.getCurrentBulk());
            bulkOrder.setUserId(bulkOrderF.getUserId());

            // 获取需要重新打印的订单
            long time = System.currentTimeMillis();
            for (int i=bOrderStatus.inNumber, num=0; i<size; i++, num++) {
                Order migrateOrder = bulkOrderF.getOrders().get(i);
                BOrder bMigrateOrder = bulkOrderF.getbOrders().get(i);

                totalSize += bMigrateOrder.size;
                migrateOrder.setExecEnterQueueTime(time);
                migrateOrder.setExecSendTime(time);
                migrateOrder.setOrderStatus(Integer.valueOf(flag).toString());
                // 判断该次要求重发的订单是否是故意封装错误的订单，如果是重新封装成正确的订单
                if(migrateOrder.getIndexError() >= 0 && migrateOrder.getIndexError() < 3) {
                    migrateOrder.setIndexError(4);
                }

                orderList.add(migrateOrder);
                bOrderList.add(migrateOrder.orderToBOrder( (short) (bulkOrder.getId()), (short) num ) );
            }
            LOGGER.log(Level.INFO, "打印机 [{0}] 打印订单 (订单批次号 [{1}], 批次内序号 [{2}]) 开始进行批次转移, 当前线程 [{3}], 当前时间为 [{4}]," +
                            " 离发送订单相差的时间为 [{5}]",
                    bOrderStatus.printerId, bOrderStatus.bulkId, bOrderStatus.inNumber, this.id,
                    TimeUtil.timeToString(time), time - bulkOrderF.getSendtime());

            // 将订单封装到批次中
            bulkOrder.getbOrders().addAll(bOrderList);
            bulkOrder.getOrders().addAll(orderList);
            bulkOrder.setDataSize(totalSize);

            /* 转化发送批次订单数据 */
            byte[] bBulkOrderByters = BBulkOrder.bBulkOrderToBytes(BulkOrder.convertBBulkOrder(bulkOrder, false));
            // bBulkOrderByters[15] = (byte)0x1;
            LOGGER.log(Level.DEBUG, "打印机 [{0}] 重新发送批次转移订单 当前线程 [{1}]", bOrderStatus.printerId, this.id);
//            DebugUtil.printBytes(bBulkOrderByters);

            SqlSession sqlSession = sqlSessionFactory.openSession();
            try {
                // 根据打印机id获得用户id
                Integer userId = -1;
                PrinterMapper printerMapper = sqlSession.getMapper(PrinterMapper.class);
                userId = printerMapper.selectUserIdByPrinter(bOrderStatus.printerId);
                if (userId == null) {
                    LOGGER.log(Level.INFO, "打印机 [{0}] 发送错误主控板Id[{1}] 当前线程 [{2}]", bOrderStatus.printerId, position,this.id);
                    userId = -1;
                    return;
                }
                // 将批次订单转移到信任度最高的anget
                Compact compact = new Compact();
                // 获得最高信任度打印机 id
                int printerId = compact.getMaxCreForBulkPrinter(userId);
                Printer agent = ShareMem.printerIdMap.get(printerId);
                LOGGER.log(Level.DEBUG, "打印机id === "+printerId);
                SocketChannel printerChannel = ShareMem.priSocketMap.get(agent);

                bulkOrderList.add(bulkOrder);

                // todo 考虑到在于打印机单方面测试时，用户可能没有登录，暂时注释这部分，同时这部分3次连续出错的策略有点傻

//                User user = ShareMem.userIdMap.get(userId);
//                if (user == null) {
//                    LOGGER.log(Level.WARN, "用户 {0} 不存在！", userId);
//                    return;
//                }

                if (printerChannel != null && printerChannel != socketChannel) {
                    // 重置打印机出错次数
//                    user.resetErrorNum();
                    // 直接发送到信任度最高的打印机
                    printerChannel.write(ByteBuffer.wrap(bBulkOrderByters));
                } else {
//                    user.increaseErrorNum();
//                    if (user.getErrorNum() == 3) {
//                        return;
//                    }
                    // 如果找不到打印机或者只有一台打印机的情况，直接按照原路返回
                    socketChannel.write(ByteBuffer.wrap(bBulkOrderByters));
                }

            } catch (IOException e) {
                LOGGER.log(Level.ERROR, "打印机 [{0}] 重新发送异常单 异常 当前线程 [{1}]", bOrderStatus.printerId,
                        this.id, e);
            } finally {
                sqlSession.commit();
                sqlSession.close();
            }

            long resendTime = System.currentTimeMillis();
            LOGGER.log(Level.INFO, "打印机 [{0}] 转移发送批次 [{1}] 中的异常订单 [{2}] ，当前时间为 [{3}] 距离接受到异常报告时" +
                            "所过去的时间为 [{4}] ms", bOrderStatus.printerId, bulkOrderF.getId(),bOrderStatus.inNumber,
                    TimeUtil.timeToString(resendTime), resendTime - time);

        } else if ( flag == BConstants.orderFail || flag == BConstants.orderDataW) {
            // 订单打印失败或者订单解析错误，将该订单再次打印
            bulkOrderF.increaseReceNum();

            // TODO 如何获取打印机发来的异常订单被更新后的数据
            long time = System.currentTimeMillis();
            order.setExecSendTime(time);
            LOGGER.log(Level.INFO, "打印机 [{0}] 打印订单 (订单批次号 [{1}], 批次内序号 [{2}]) 失败, 当前线程 [{3}], 当前时间为 [{4}]," +
                            " 离发送订单相差的时间为 [{5}]",
                    bOrderStatus.printerId, bOrderStatus.bulkId, bOrderStatus.inNumber, this.id,
                    TimeUtil.timeToString(time), time - bulkOrderF.getSendtime());

            /* 组装批次订单 */
            BulkOrder bulkOrder = new BulkOrder(new ArrayList<BOrder>());
            // 判断该次要求重发的订单是否是故意封装错误的订单，如果是重新封装成正确的订单
            if(order.getIndexError() >= 0 && order.getIndexError() < 3) {
                order.setIndexError(4);
            }

            bulkOrder.getOrders().add(order);
            bulkOrder.setBulkType((short) 1);
            bulkOrder.setDataSize(bOrder.size);
            printer.increaseBulkId();
            bulkOrder.setId(printer.getCurrentBulk());
            bulkOrder.getbOrders().add(order.orderToBOrder( (short) (bulkOrder.getId()), (short) 1 ) );  // 根据bulkid 和 批次内序号 : 1
            bulkOrder.setUserId(bulkOrderF.getUserId());

            order.setOrderStatus(Integer.valueOf(flag).toString());

            LOGGER.log(Level.DEBUG, "打印机 [{0}] 当前线程 [{1}] 组装异常单 并放入异常队列",
                    bOrderStatus.printerId, this.id);

            ShareMem.priExceQueueMap.get(printer).add(bulkOrder); // 放入异常队列

            /*  转化发送批次订单数据 */
            byte[] bBulkOrderByters = BBulkOrder.bBulkOrderToBytes(BulkOrder.convertBBulkOrder(bulkOrder, true));
            //bBulkOrderByters[15] = (byte)0x1;
            LOGGER.log(Level.DEBUG, "打印机 [{0}] 重新发送批次异常单 当前线程 [{1}]", bOrderStatus.printerId, this.id);
//            DebugUtil.printBytes(bBulkOrderByters);

            try {
                socketChannel.write(ByteBuffer.wrap(bBulkOrderByters));
            } catch (IOException e) {
                LOGGER.log(Level.ERROR, "打印机 [{0}] 重新发送异常单 异常 当前线程 [{1}]", bOrderStatus.printerId,
                        this.id, e);
            }


            long resendTime = System.currentTimeMillis();
            LOGGER.log(Level.INFO, "打印机 [{0}] 重新发送批次 [{1}] 中的异常订单 [{2}] ，当前时间为 [{3}] 距离接受到异常报告时" +
                            "所过去的时间为 [{4}] ms", bOrderStatus.printerId, bulkOrderF.getId(),bOrderStatus.inNumber,
                    TimeUtil.timeToString(resendTime), resendTime - time);

        } else if ( flag == BConstants.orderSucc ) {
            bulkOrderF.increaseReceNum();

            LOGGER.log(Level.DEBUG, "订单处理成功 当前线程 [{1}]", this.id);
            order.setOrderStatus(Integer.valueOf(BConstants.orderSucc).toString());

            if (bulkOrderF.getReceNum() < bulkOrderF.getOrders().size()) {
                return ;
            }

            LOGGER.log(Level.DEBUG, "打印机[{0}] 批次订单处理完毕 [{1}] 当前线程 [{2}]", printer.getId(), bOrderStatus.bulkId, this.id);

            // 将订单保存到数据库
            SqlSession sqlSession = sqlSessionFactory.openSession();
            OrderMapper orderMapper = sqlSession.getMapper(OrderMapper.class);
            try {
                Order o = null;
                String succ = Integer.valueOf(BConstants.orderSucc).toString();
                for (int i = 0; i < bulkOrderF.getbOrders().size(); i++) {
                    o = bulkOrderF.getOrders().get(i);
                    if (o.getOrderStatus().equals(succ)) {
                        orderMapper.insert(o);
                        orderMapper.insertUserOrder(o);
                    }
                }
            } finally {
                sqlSession.commit();
                sqlSession.close();
            }

        } else if ( flag == BConstants.orderInQueue ){
            LOGGER.log(Level.DEBUG, "订单进入打印队列 当前线程 [{1}]", this.id);
            order.setOrderStatus(Integer.valueOf(BConstants.orderInQueue).toString());
        } else if ( flag == BConstants.orderTyping) {
            LOGGER.log(Level.DEBUG, "订单正在打印成功 当前线程 [{1}]", this.id);
            order.setOrderStatus(Integer.valueOf(BConstants.orderTyping).toString());
        } else if ( flag == BConstants.orderExcep ) {
            LOGGER.log(Level.DEBUG, "打印机 [{0}] 异常队列批次 [{1}] 处理成功 当前线程 [{2}]", printer.getId(), bOrderStatus.bulkId, this.id);
            order.setOrderStatus(Integer.valueOf(BConstants.orderExcep).toString());
            /* 向数据库中插入处理成功的订单数据 */
            SqlSession sqlSession = sqlSessionFactory.openSession();
            OrderMapper orderMapper = sqlSession.getMapper(OrderMapper.class);
            try {
                LOGGER.log(Level.DEBUG, "====再次确认 orderId: {0}, userId: {1}", order.getId(), order.getUserId());
                orderMapper.insertUserOrder(order);
                orderMapper.insert(order);
            } finally {
                sqlSession.commit();
                sqlSession.close();
            }

            LOGGER.log(Level.DEBUG, "打印机 [{0}] 的异常队列 移除成功处理批次订单 (id [{1}], position [{2}]) 当前线程 [{3}]", bOrderStatus.printerId,
                    bOrderStatus.bulkId, position, this.id);
            bulkOrderList.remove(position);
        } else {
            LOGGER.log(Level.WARN, "打印机 [{0}] 无g该状态 当前线程 [{1}]", bOrderStatus.printerId, this.id);
        }
    }

    /**
     * 解析打印机发送的批次订单状态 暂时废弃, 当批次中的单个订单全部反馈完状态后，插入数据库中
     * @param bytes
     */
    private void parseBulkStatus(byte[] bytes) {
        BBulkStatus bBulkStatus = BBulkStatus.bytesToBulkStatus(bytes);

        LOGGER.log(Level.DEBUG, "打印机 [{0}] 处理批次订单 [{1}], 发送时间戳 [{2}]， 校验和 [{3}] printerProcessor 线程 [{4}]", bBulkStatus.printerId,bBulkStatus.bulkId,
                bBulkStatus.seconds, bBulkStatus.checkSum, this.id);

        if ( (byte)(bBulkStatus.flag  & 0xFF) == (byte) BConstants.bulkSucc) {
            // 批次订单成功
            // 将已发队列中数据装填到数据库中，并清除已发队列
            LOGGER.log(Level.DEBUG, "打印机 [{0}] 处理批次订单成功 [{1}]  printerProcessor 线程 [{2}]", bBulkStatus.printerId,
                    bBulkStatus.bulkId, this.id);
            // TODO 模拟打印机id
            int printerId = 1;
            Printer printer = ShareMem.printerIdMap.get(printerId);
            List<BulkOrder> bulkOrders = ShareMem.priSentQueueMap.get(printer);
            if (bulkOrders == null || bulkOrders.size() < 1) {
                LOGGER.log(Level.WARN, "打印机 [{0}] 的发送队列无数据 printerProcessor 线程 [{1}]", printerId, this.id);
                return ;
            }
            BulkOrder bulkOrder = null;
            synchronized (ShareMem.priSentQueueMap.get(printer)) {
                for (int i = 0; i < bulkOrders.size(); i++) {
                    bulkOrder = bulkOrders.get(i);
                    if (bBulkStatus.bulkId == bBulkStatus.bulkId) {
                        LOGGER.log(Level.DEBUG, "打印机 [{0}] 找到批次订单 [{1}] printerProcessor 线程 [{2}]", printerId, bBulkStatus.bulkId,
                                this.id);
                        bulkOrders.remove(i);
                        break;
                    }
                }
            }

            LOGGER.log(Level.DEBUG, "打印机 [{0}] 处理成功批次订单， 向数据库中写入数据 printerProcessor 线程 [{1}]", printerId, this.id);

            SqlSession sqlSession = sqlSessionFactory.openSession();
            OrderMapper orderMapper = sqlSession.getMapper(OrderMapper.class);
//            try {
//                Order o = null;
//                for (int i = 0; i < bulkOrder.getbOrders().size(); i++) {
//                    o = bulkOrder.getOrders().get(i);
//                    orderMapper.insert(o);
//                    orderMapper.insertUserOrder(o.getUserId(), o.getId());
//                }
//            } finally {
//                sqlSession.commit();
//                sqlSession.close();
//            }

        } else if ( (byte)(bBulkStatus.flag  & 0xFF) == (byte) BConstants.bulkInBuffer) {
            // 批次进入缓冲区 超时重新传送
            LOGGER.log(Level.DEBUG, "暂未处理批次订进入缓冲区 当前线程[{0}]", this.id);
        } else if ( (byte)(bBulkStatus.flag & 0xFF) == (byte) BConstants.bulkFail) {
            LOGGER.log(Level.DEBUG, "暂未处理批次订错误 当前线程[{0}]", this.id);
        }
    }

    /**
     * 记录打印机状态到内存中
     * @param bytes
     */
    private void parsePrinterStatus(byte[] bytes) {
        BPrinterStatus bPrinterStatus = BPrinterStatus.bytesToPrinterStatus(bytes);

        LOGGER.log(Level.DEBUG, "打印机[{0}] 请求flag [{1}] 时间戳 [{2}] 打印单元序号 [{3}] 检验和 [{4}] 当前线程 [{5}]",
                bPrinterStatus.printerId, bPrinterStatus.flag, bPrinterStatus.seconds, bPrinterStatus.number, bPrinterStatus.checkSum, this.id);

        Printer printer = ShareMem.printerIdMap.get(bPrinterStatus.printerId);
        if (printer == null) {
            LOGGER.log(Level.WARN, "打印机[{0}]未找到内中中对应打印机对象", bPrinterStatus.printerId);
            return ;
        }
        LOGGER.log(Level.DEBUG, ((bPrinterStatus.flag ) + "" ));
        LOGGER.log(Level.DEBUG, ((bPrinterStatus.flag ) & 0xFF) + "" ) ;
        printer.setPrinterStatus( ( (bPrinterStatus.flag ) & 0xFF ) + "");
    }

    /* getter 模块 */
    public int getId() {
        return id;
    }
}
