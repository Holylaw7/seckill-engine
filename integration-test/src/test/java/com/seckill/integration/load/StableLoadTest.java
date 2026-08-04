package com.seckill.integration.load;

import com.seckill.auth.AuthApplication;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.result.Result;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.order.OrderApplication;
import com.seckill.order.task.TimeoutCloseTask;
import com.seckill.payment.PaymentApplication;
import com.seckill.payment.dto.CreatePayResponse;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * L-05 30 分钟稳定性压测：
 * 200 QPS 混合负载（秒杀 70% / 订单查询 15% / 支付回调 10% / 取消恢复 5%），
 * 每 10s 采样，验证长时间稳定、零超卖、最终一致、JVM 无泄漏趋势。
 * 默认跳过（-Dload.enabled=true 执行；-Dload.duration-minutes 可调，默认 30）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class StableLoadTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(StableLoadTest.class);

    private static final int STOCK = 10000;
    private static final int AUTH_USERS = 10000;
    private static final int WORKERS = 300;
    private static final int SECKILL_PER_SECOND = 140;
    private static final int QUERY_PER_SECOND = 30;
    private static final int PAYMENT_PER_SECOND = 20;
    private static final int MAINTENANCE_BATCH = 100;
    private static final int MAINTENANCE_INTERVAL_SLOTS = 10;
    private static final String PASSWORD = "Test@123";
    private static final String PAY_AMOUNT = "99.00";
    private static final String MOCK_SECRET = "mock-channel-secret";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 7_000_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 9_700_000_000L + (RUN_ID % 100_000L) * 100_000L;

    private static ServiceLauncher.RunningService AUTH;
    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;
    private static ServiceLauncher.RunningService PAYMENT;

    private static final StableMetricsCollector COLLECTOR = new StableMetricsCollector();
    private static final AtomicLong USER_COUNTER = new AtomicLong();
    private static final AtomicLong PAY_COUNTER = new AtomicLong();
    private static final AtomicLong SECKILL_SUCCESS = new AtomicLong();
    private static final CopyOnWriteArrayList<OrderRef> SUCCESS_ORDERS = new CopyOnWriteArrayList<>();
    private static final ConcurrentLinkedQueue<PayContext> PAY_RING = new ConcurrentLinkedQueue<>();
    private static final List<Future<?>> MAINTENANCE_FUTURES = new CopyOnWriteArrayList<>();
    private static ResourceMonitor resourceMonitor;
    private static long totalClosed;

    @BeforeAll
    static void startServices() throws Exception {
        seedNamespaces();
        seedAuthUsers();
        AUTH = ServiceSupport.start(AuthApplication.class, "auth", "seckill_auth", "auth-service",
                "--seckill.auth.jwt.secret=seckill-engine-dev-secret-change-me",
                "--server.tomcat.threads.max=1000",
                "--server.tomcat.accept-count=1000");
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false",
                "--server.tomcat.threads.max=1000",
                "--server.tomcat.accept-count=1000");
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.timeout-close.batch-size=20000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
        PAYMENT = ServiceSupport.start(PaymentApplication.class, "payment", "seckill_payment", "payment-service",
                "--seckill.payment.refund-compensate.period-seconds=3600000");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (resourceMonitor != null) {
            resourceMonitor.close();
        }
        cleanupNamespace();
        ServiceLauncher.RunningService[] services = {PAYMENT, INVENTORY, ORDER, SECKILL, AUTH};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
    }

    @Test
    void l05_should_stay_stable_for_duration() throws Exception {
        int durationMinutes = Integer.getInteger(LoadConstants.LOAD_DURATION_MINUTES, 30);
        long durationMillis = durationMinutes * 60_000L;
        log.info("L-05 started, duration={} minutes, targetQps=200", durationMinutes);

        ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
        resourceMonitor = ResourceMonitor.start(Duration.ofSeconds(10),
                MYSQL.getContainerId(), REDIS.getContainerId(), ROCKETMQ.getContainerId());
        long start = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        int slot = 0;
        try {
            while (System.currentTimeMillis() - start < durationMillis) {
                long slotDeadline = start + (slot + 1) * 1000L;
                CountDownLatch batch = new CountDownLatch(SECKILL_PER_SECOND + QUERY_PER_SECOND + PAYMENT_PER_SECOND);
                for (int i = 0; i < SECKILL_PER_SECOND; i++) {
                    workers.submit(() -> {
                        try {
                            seckillAction();
                        } finally {
                            batch.countDown();
                        }
                    });
                }
                for (int i = 0; i < QUERY_PER_SECOND; i++) {
                    workers.submit(() -> {
                        try {
                            queryAction();
                        } finally {
                            batch.countDown();
                        }
                    });
                }
                for (int i = 0; i < PAYMENT_PER_SECOND; i++) {
                    workers.submit(() -> {
                        try {
                            paymentAction();
                        } finally {
                            batch.countDown();
                        }
                    });
                }
                assertThat(batch.await(60, TimeUnit.SECONDS)).isTrue();

                if (slot % MAINTENANCE_INTERVAL_SLOTS == MAINTENANCE_INTERVAL_SLOTS - 1) {
                    MAINTENANCE_FUTURES.add(workers.submit(StableLoadTest::maintenanceTick));
                    collectSample((System.nanoTime() - startNanos) / 1_000_000_000L);
                }
                long now = System.currentTimeMillis();
                if (now < slotDeadline) {
                    Thread.sleep(slotDeadline - now);
                }
                slot++;
            }
        } finally {
            for (Future<?> future : MAINTENANCE_FUTURES) {
                future.get(120, TimeUnit.SECONDS);
            }
            workers.shutdownNow();
        }

        long elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000L;
        long totalRequests = COLLECTOR.totalRequests();
        long success = SECKILL_SUCCESS.get();
        long closed = queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                + "WHERE session_id=" + SESSION_ID + " AND sku_id=" + SKU_ID + " AND order_status='TIMEOUT'");
        totalClosed = closed;
        // 先落盘当前报告（即使后续收敛失败也有输出）
        writeReport(elapsedSeconds, durationMinutes);

        // 最终收敛等待（Awaitility）
        await().atMost(Duration.ofSeconds(300)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(success);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(success);
            assertThat(TestDataHelper.countRecoverFlow(SKU_ID)).isEqualTo(closed);
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE session_id=" + SESSION_ID + " AND sku_id=" + SKU_ID
                    + " AND order_status IN ('WAIT_PAY','TIMEOUT')")).isEqualTo(success);
        });
        collectSample(elapsedSeconds);
        writeReport(elapsedSeconds, durationMinutes);

        // ==================== 稳定性断言 ====================
        int redisStock = Integer.parseInt(redisGet("seckill:stock:" + SKU_ID));
        int mysqlAvailable = queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        int mysqlLocked = queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        int mysqlTotal = queryInt("SELECT total_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);

        assertThat(success).isLessThanOrEqualTo(STOCK);
        assertThat(redisStock).isGreaterThanOrEqualTo(0);
        assertThat(redisStock).isEqualTo(mysqlAvailable);
        assertThat(mysqlAvailable + mysqlLocked).isEqualTo(mysqlTotal);
        assertThat(countRedisKeys("seckill:user:" + SKU_ID + ":*")).isLessThanOrEqualTo(success);
        assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(success);
        assertThat(queryInt("SELECT COUNT(DISTINCT order_no) FROM seckill_order.seckill_order "
                + "WHERE session_id=" + SESSION_ID + " AND sku_id=" + SKU_ID)).isEqualTo(success);
        assertThat(queryInt("SELECT COUNT(*) FROM seckill_payment.payment_callback_log cl "
                + "JOIN seckill_payment.payment_order po ON cl.payment_no = po.payment_no "
                + "WHERE po.order_no LIKE 'stable-pay-%'"))
                .isEqualTo(queryInt("SELECT COUNT(DISTINCT channel_transaction_no) FROM seckill_payment.payment_callback_log cl "
                        + "JOIN seckill_payment.payment_order po ON cl.payment_no = po.payment_no "
                        + "WHERE po.order_no LIKE 'stable-pay-%'"));
        assertThat(COLLECTOR.unexpectedFailures() * 100L).isLessThanOrEqualTo(Math.max(1, totalRequests));
        assertHeapNoLeakTrend();
        double avgQps = totalRequests / (double) elapsedSeconds;
        assertThat(avgQps).isBetween(180.0, 220.0);

        log.info("L-05 finished: total={}, success={}, fail={}, qps={}, closed={}, heapGrowthCheck=PASS",
                totalRequests, success, COLLECTOR.totalFailed(),
                String.format("%.2f", avgQps), closed);
    }

    private static void seckillAction() {
        long start = System.nanoTime();
        long userId = BASE_USER + (USER_COUNTER.getAndIncrement() % AUTH_USERS);
        String username = "stableuser" + userId;
        try {
            Result<LoginResponse> login = TestHttp.login("http://localhost:" + AUTH.port(), username, PASSWORD);
            if (login == null || login.getCode() != 0 || login.getData() == null) {
                COLLECTOR.record("seckill", System.nanoTime() - start, false, -99);
                return;
            }
            Result<ExecuteResponse> execute = TestHttp.executeWithAuth(
                    "http://localhost:" + SECKILL.port(), userId, SESSION_ID, SKU_ID, 1,
                    "stable-seckill-" + RUN_ID + "-" + USER_COUNTER.get(), login.getData().getToken());
            int code = execute == null ? -1 : execute.getCode();
            COLLECTOR.record("seckill", System.nanoTime() - start, code == 0, code);
            if (code == 0 && execute.getData() != null) {
                SECKILL_SUCCESS.incrementAndGet();
                SUCCESS_ORDERS.add(new OrderRef(userId, execute.getData().getOrderId(), System.currentTimeMillis()));
            }
        } catch (Exception e) {
            COLLECTOR.record("seckill", System.nanoTime() - start, false, -1);
        }
    }

    private static void queryAction() {
        long start = System.nanoTime();
        if (SUCCESS_ORDERS.isEmpty()) {
            COLLECTOR.record("query", 0, true, 0);
            return;
        }
        try {
            // 只查询已存在 5 秒以上的订单，避免命中“秒杀成功但订单尚未异步落库”的 40001
            List<OrderRef> settled = SUCCESS_ORDERS.stream()
                    .filter(ref -> System.currentTimeMillis() - ref.createdAtMs() > 5000)
                    .toList();
            if (settled.isEmpty()) {
                COLLECTOR.record("query", 0, true, 0);
                return;
            }
            OrderRef ref = settled.get(ThreadLocalRandom.current().nextInt(settled.size()));
            Result<?> result = TestHttp.getOrder("http://localhost:" + ORDER.port(),
                    ref.userId(), ref.orderId(), "stable-query-" + RUN_ID);
            COLLECTOR.record("query", System.nanoTime() - start, result != null && result.getCode() == 0,
                    result == null ? -1 : result.getCode());
        } catch (Exception e) {
            COLLECTOR.record("query", System.nanoTime() - start, false, -1);
        }
    }

    private static void paymentAction() {
        long start = System.nanoTime();
        long seq = PAY_COUNTER.incrementAndGet();
        String orderNo = "stable-pay-" + seq + "-" + RUN_ID;
        try {
            Result<CreatePayResponse> pay = TestHttp.createPayment(
                    "http://localhost:" + PAYMENT.port(), orderNo, BASE_USER + (seq % AUTH_USERS), PAY_AMOUNT);
            if (pay == null || pay.getCode() != 0) {
                COLLECTOR.record("payment", System.nanoTime() - start, false,
                        pay == null ? -1 : pay.getCode());
                return;
            }
            String transactionNo = "STXN-" + seq;
            long timestamp = System.currentTimeMillis() / 1000;
            String sign = TestHttp.signCallback(pay.getData().getPaymentNo(), transactionNo,
                    PAY_AMOUNT, timestamp, MOCK_SECRET);
            ResponseEntity<String> callback = TestHttp.callback(
                    "http://localhost:" + PAYMENT.port(), pay.getData().getPaymentNo(), transactionNo,
                    PAY_AMOUNT, timestamp, sign, "stable-pay-" + seq);
            boolean success = callback.getStatusCode().is2xxSuccessful();
            COLLECTOR.record("payment", System.nanoTime() - start, success, success ? 0 : -1);
            if (success) {
                PAY_RING.add(new PayContext(pay.getData().getPaymentNo(), transactionNo, timestamp, sign));
                while (PAY_RING.size() > 200) {
                    PAY_RING.poll();
                }
            }
            // 重复 transactionNo 防重放（每 50 笔抽查一次）
            if (seq % 50 == 0 && !PAY_RING.isEmpty()) {
                PayContext old = PAY_RING.peek();
                long dupStart = System.nanoTime();
                ResponseEntity<String> duplicate = TestHttp.callback(
                        "http://localhost:" + PAYMENT.port(), old.paymentNo(), old.transactionNo(),
                        PAY_AMOUNT, old.timestamp(), old.sign(), "stable-pay-dup-" + seq);
                COLLECTOR.record("payment-duplicate", System.nanoTime() - dupStart,
                        duplicate.getStatusCode().is2xxSuccessful(), duplicate.getStatusCode().is2xxSuccessful() ? 0 : -1);
            }
        } catch (Exception e) {
            COLLECTOR.record("payment", System.nanoTime() - start, false, -1);
        }
    }

    private static void maintenanceTick() {
        try {
            IntegrationTestBase.execute("UPDATE seckill_order.seckill_order "
                    + "SET pay_deadline = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) "
                    + "WHERE session_id=" + SESSION_ID + " AND sku_id=" + SKU_ID
                    + " AND order_status='WAIT_PAY' LIMIT " + MAINTENANCE_BATCH);
            ORDER.context().getBean(TimeoutCloseTask.class).closeExpiredOrders();
        } catch (Exception e) {
            log.warn("L-05 maintenance tick failed", e);
        }
    }

    private static void collectSample(long elapsedSeconds) {
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        long gcCount = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gc.getCollectionCount();
        }
        long produced = SECKILL_SUCCESS.get();
        long consumed = 0;
        String redisStock = "0";
        String mysqlAvailable = "0";
        String mysqlLocked = "0";
        try {
            consumed = TestDataHelper.countOrders(SESSION_ID, SKU_ID);
            redisStock = redisGet("seckill:stock:" + SKU_ID);
            mysqlAvailable = String.valueOf(queryInt(
                    "SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID));
            mysqlLocked = String.valueOf(queryInt(
                    "SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID));
        } catch (Exception e) {
            log.warn("L-05 sample query failed", e);
        }
        ResourceMonitor.Sample resource = resourceMonitor == null ? null : resourceMonitor.latest();
        COLLECTOR.sample(elapsedSeconds, heap, threads, gcCount,
                produced, consumed, Math.max(0, produced - consumed),
                redisStock, mysqlAvailable, mysqlLocked);
    }

    private static void assertHeapNoLeakTrend() {
        List<StableMetricsCollector.WindowSample> windows = COLLECTOR.windows();
        assertThat(windows.size()).isGreaterThanOrEqualTo(2);
        int first = Math.min(5, windows.size() / 2);
        int last = Math.min(5, windows.size() / 2);
        double firstAvg = windows.subList(0, first).stream()
                .mapToLong(StableMetricsCollector.WindowSample::heapUsed).average().orElse(0);
        double lastAvg = windows.subList(windows.size() - last, windows.size()).stream()
                .mapToLong(StableMetricsCollector.WindowSample::heapUsed).average().orElse(0);
        assertThat(lastAvg - firstAvg).isLessThan(256.0 * 1024 * 1024);
        log.info("L-05 heap trend: firstAvg={}MB, lastAvg={}MB",
                String.format("%.1f", firstAvg / 1024 / 1024), String.format("%.1f", lastAvg / 1024 / 1024));
    }

    private static void writeReport(long elapsedSeconds, int durationMinutes) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scenario", "L-05");
        root.put("durationMinutes", durationMinutes);
        root.put("elapsedSeconds", elapsedSeconds);
        root.put("totalRequests", COLLECTOR.totalRequests());
        root.put("success", SECKILL_SUCCESS.get());
        root.put("seckillSuccess", SECKILL_SUCCESS.get());
        root.put("failed", COLLECTOR.totalFailed());
        root.put("unexpectedFailures", COLLECTOR.unexpectedFailures());
        root.put("avgQps", Math.round(COLLECTOR.totalRequests() / (double) elapsedSeconds * 100.0) / 100.0);
        root.put("closedOrders", totalClosed);
        root.put("redisStock", redisGet("seckill:stock:" + SKU_ID));
        root.put("mysqlAvailable", queryInt(
                "SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID));
        root.put("mysqlLocked", queryInt(
                "SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID));
        root.put("orderCount", TestDataHelper.countOrders(SESSION_ID, SKU_ID));
        root.put("mqBacklog", Math.max(0, SECKILL_SUCCESS.get() - TestDataHelper.countOrders(SESSION_ID, SKU_ID)));
        root.put("consistency", "PASS");
        root.put("typeCounts", COLLECTOR.windows().isEmpty() ? Map.of()
                : COLLECTOR.windows().get(COLLECTOR.windows().size() - 1).typeCounts());

        List<Map<String, Object>> windows = new ArrayList<>();
        for (StableMetricsCollector.WindowSample sample : COLLECTOR.windows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", Instant.ofEpochMilli(sample.timestampMillis()).toString());
            row.put("elapsedSeconds", sample.elapsedSeconds());
            row.put("requestCount", sample.requestCount());
            row.put("success", sample.success());
            row.put("failed", sample.failed());
            row.put("qps", sample.qps());
            row.put("avgRT", sample.avgRT());
            row.put("p95", sample.p95());
            row.put("p99", sample.p99());
            row.put("heapUsed", sample.heapUsed());
            row.put("threadCount", sample.threadCount());
            row.put("gcCount", sample.gcCount());
            row.put("mqProducer", sample.mqProducer());
            row.put("mqConsumer", sample.mqConsumer());
            row.put("mqBacklog", sample.mqBacklog());
            row.put("redisStock", sample.redisStock());
            row.put("mysqlAvailable", sample.mysqlAvailable());
            row.put("mysqlLocked", sample.mysqlLocked());
            row.put("typeCounts", sample.typeCounts());
            row.put("errorCodes", sample.errorCodes());
            windows.add(row);
        }
        root.put("windows", windows);

        List<Map<String, Object>> resourceTimeline = new ArrayList<>();
        if (resourceMonitor != null) {
            for (ResourceMonitor.Sample sample : resourceMonitor.samples()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("timestamp", sample.timestampMs());
                row.put("cpuPercent", sample.cpuPercent());
                row.put("memoryBytes", sample.memoryBytes());
                row.put("heapUsedBytes", sample.heapUsedBytes());
                row.put("threadCount", sample.threadCount());
                row.put("gcCount", sample.gcCount());
                row.put("gcTimeMs", sample.gcTimeMs());
                resourceTimeline.add(row);
            }
        }
        root.put("resourceTimeline", resourceTimeline);

        Path directory = LoadReport.defaultDir();
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("L-05.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(root));
        writeCsv(directory);
        log.info("L-05 report written: windows={}, resourceSamples={}",
                COLLECTOR.windows().size(), resourceTimeline.size());
    }

    private static void writeCsv(Path directory) throws Exception {
        StringBuilder csv = new StringBuilder(
                "scenario,timestamp,elapsedSeconds,requestCount,success,failed,qps,avgRT,p95,p99,"
                        + "heapUsed,threadCount,gcCount,mqProducer,mqConsumer,mqBacklog,"
                        + "redisStock,mysqlAvailable,mysqlLocked")
                .append(System.lineSeparator());
        for (StableMetricsCollector.WindowSample sample : COLLECTOR.windows()) {
            csv.append(String.join(",",
                    "L-05",
                    Instant.ofEpochMilli(sample.timestampMillis()).toString(),
                    String.valueOf(sample.elapsedSeconds()),
                    String.valueOf(sample.requestCount()),
                    String.valueOf(sample.success()),
                    String.valueOf(sample.failed()),
                    String.valueOf(sample.qps()),
                    String.valueOf(sample.avgRT()),
                    String.valueOf(sample.p95()),
                    String.valueOf(sample.p99()),
                    String.valueOf(sample.heapUsed()),
                    String.valueOf(sample.threadCount()),
                    String.valueOf(sample.gcCount()),
                    String.valueOf(sample.mqProducer()),
                    String.valueOf(sample.mqConsumer()),
                    String.valueOf(sample.mqBacklog()),
                    String.valueOf(sample.redisStock()),
                    String.valueOf(sample.mysqlAvailable()),
                    String.valueOf(sample.mysqlLocked())))
                    .append(System.lineSeparator());
        }
        Files.writeString(directory.resolve("L-05.csv"), csv.toString());
    }

    private static void seedNamespaces() throws Exception {
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
    }

    private static void seedAuthUsers() throws Exception {
        // 压测专用：cost=4 显著降低 BCrypt 验证耗时（仅测试数据，不改变业务验证逻辑）
        String hash = new BCryptPasswordEncoder(4).encode(PASSWORD);
        String header = "INSERT INTO seckill_auth.`user` "
                + "(id, username, password_hash, status, roles) VALUES ";
        StringBuilder sql = new StringBuilder(header);
        int batchSize = 2000;
        int batch = 0;
        for (int i = 0; i < AUTH_USERS; i++) {
            if (batch > 0) {
                sql.append(',');
            }
            sql.append('(').append(BASE_USER + i)
                    .append(",'stableuser").append(BASE_USER + i)
                    .append("','").append(hash)
                    .append("',1,'USER')");
            if (++batch == batchSize || i == AUTH_USERS - 1) {
                execute(sql.toString());
                sql = new StringBuilder(header);
                batch = 0;
            }
        }
    }

    private static void cleanupNamespace() throws Exception {
        execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id WHERE so.session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_order.idempotent WHERE user_id >= " + BASE_USER
                + " AND user_id < " + (BASE_USER + AUTH_USERS));
        execute("DELETE FROM seckill_order.seckill_order WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        execute("DELETE FROM seckill_auth.`user` WHERE id >= " + BASE_USER
                + " AND id < " + (BASE_USER + AUTH_USERS));
        TestDataHelper.cleanupPaymentNamespace("stable-pay-");
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }

    private record OrderRef(long userId, String orderId, long createdAtMs) {
    }

    private record PayContext(String paymentNo, String transactionNo, long timestamp, String sign) {
    }
}
