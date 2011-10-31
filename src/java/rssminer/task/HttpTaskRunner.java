package rssminer.task;

import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static me.shenfeng.http.HttpClientConstant.BAD_URL;
import static me.shenfeng.http.HttpClientConstant.CONN_RESET;
import static me.shenfeng.http.HttpClientConstant.IGNORED_URL;
import static me.shenfeng.http.HttpClientConstant.NULL_LOCATION;
import static me.shenfeng.http.HttpClientConstant.UNKOWN_ERROR;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.LOCATION;

import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import me.shenfeng.dns.DnsPrefecher;
import me.shenfeng.http.HttpClient;
import me.shenfeng.http.HttpResponseFuture;

import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rssminer.Links;

public class HttpTaskRunner {

    static Logger logger = LoggerFactory.getLogger(HttpTaskRunner.class);

    private final HttpClient mHttp;
    private final IHttpTaskProvder mProvider;
    private final String mName;
    private final Proxy mProxy; // proxy used if rested
    private final Queue<IHttpTask> mTaskQueue;
    private AtomicInteger mCounter = new AtomicInteger(0);
    private volatile long startTime;
    private volatile boolean mRunning;
    private Thread mWorkerThread;
    private Thread mConsummerThread;
    private final Semaphore mConcurrent;
    private final Links mLinkCheker;
    private final DnsPrefecher mDnsPrefecher;
    private final BlockingQueue<HttpResponseFuture> mDones;
    private final Map<Integer, Integer> mStat = new TreeMap<Integer, Integer>();

    private Runnable mWorker = new Runnable() {

        boolean fillQueueIfNeeded() {
            if (mTaskQueue.size() == 0) {
                List<IHttpTask> tasks = mProvider.getTasks();
                if (tasks == null || tasks.size() == 0)
                    return false;
                for (IHttpTask t : tasks) {
                    if (mDnsPrefecher != null)
                        mDnsPrefecher.prefetch(t.getUri());
                    mTaskQueue.offer(t);
                }
            }
            return true;
        }

        public void run() {
            try {
                while (mRunning) {
                    if (!fillQueueIfNeeded()) {
                        break;
                    }
                    mConcurrent.acquire();
                    IHttpTask t = mTaskQueue.poll(); // can't be null
                    final HttpResponseFuture future = mHttp.execGet(
                            t.getUri(), t.getHeaders(), t.getProxy());
                    future.setAttachment(t); // keep state
                    future.addListener(new Runnable() {
                        public void run() {
                            mConcurrent.release();
                            try {
                                mDones.put(future);
                            } catch (InterruptedException ignore) {
                            }
                        }
                    });
                }
            } catch (InterruptedException ignore) {
            }
            logger.info("{} producer is stopped", mName);
            mRunning = false;
        }
    };

    private Runnable mConsummer = new Runnable() {

        private void consumResponse(IHttpTask task, HttpResponse resp)
                throws Exception {
            if (resp == CONN_RESET && task.getProxy() != mProxy) {
                mTaskQueue.offer(new RetryHttpTask(task, mProxy, null));
            } else if (resp.getStatus().getCode() == 302) {
                handle302(task, resp);
            } else {
                task.doTask(resp);
            }
        }

        private void handle302(IHttpTask task, HttpResponse resp)
                throws Exception {
            try {
                String l = resp.getHeader(LOCATION);
                if (l == null) {
                    task.doTask(NULL_LOCATION);
                    return;
                }
                URI loc = new URI(l);
                if (mLinkCheker.keep(loc)) {
                    RetryHttpTask retry = new RetryHttpTask(task, null, loc);
                    if (retry.retryTimes() < 4) {
                        mTaskQueue.offer(retry);
                    } else {
                        task.doTask(UNKOWN_ERROR);
                        logger.error("redirect 4 times: " + l);
                    }
                } else {
                    task.doTask(IGNORED_URL);
                }
            } catch (URISyntaxException e) {
                // uri syntax error || no Location
                task.doTask(BAD_URL);
            }
        }

        public void run() {
            // finish job at hand
            while (!mDones.isEmpty() || mRunning) {
                try {
                    HttpResponseFuture future = mDones.take();
                    IHttpTask task = (IHttpTask) future.getAttachment();
                    try {
                        HttpResponse resp = future.get();
                        recordStat(task, resp);
                        logger.trace("{} {}", resp.getStatus(), task.getUri());
                        consumResponse(task, resp);
                    } catch (InterruptedException ignore) {
                    } catch (Exception e) {
                        logger.error(task.getUri().toString(), e);
                    } finally {
                        mCounter.incrementAndGet();
                    }
                } catch (InterruptedException ignore) {
                }
            }
            logger.info("{} consummer has stopped", mName);
            mRunning = false;
        }
    };

    public HttpTaskRunner(HttpTaskRunnerConf conf) {
        mProvider = conf.provider;
        mProxy = conf.proxy;
        // consumer and producer need access concurrently
        mTaskQueue = new ConcurrentLinkedQueue<IHttpTask>();
        // prevent too many concurrent HTTP request
        mConcurrent = new Semaphore(conf.queueSize);
        // pace producer and consumer
        mDones = new ArrayBlockingQueue<HttpResponseFuture>(conf.queueSize);
        mHttp = conf.client;
        mLinkCheker = conf.links;
        if (conf.dnsPrefetch) {
            mDnsPrefecher = DnsPrefecher.getInstance();
        } else {
            mDnsPrefecher = null;
        }
        mStat.put(1200, conf.queueSize);
        mName = conf.name;
    }

    // TODO mStat is not thread safe
    public Map<Integer, Integer> getStat() {
        mStat.put(1000, mCounter.get());
        double m = (double) (currentTimeMillis() - startTime) / 60000;
        mStat.put(1050, (int) (mCounter.get() / m));
        mStat.put(1250, mConcurrent.availablePermits());
        mStat.put(1300, (int) (startTime / 1000));
        return mStat;
    }

    public boolean isRunning() {
        return mRunning;
    }

    private void recordStat(IHttpTask task, HttpResponse resp) {
        int code = resp.getStatus().getCode();
        if (code == 200 && task.getProxy() == mProxy)
            code = 275;
        Integer c = mStat.get(code);
        if (c == null)
            c = 0;
        mStat.put(code, ++c);
    }

    public void start() {
        mRunning = true;
        mWorkerThread = new Thread(mWorker, mName + " Worker");
        mWorkerThread.start();

        mConsummerThread = new Thread(mConsummer, mName + " Consumer");
        mConsummerThread.start();

        startTime = currentTimeMillis();
        logger.info("starting {}", mName);
    }

    public void stop() {
        if (mRunning) {
            mRunning = false;
            mWorkerThread.interrupt();
            mConsummerThread.interrupt();
            logger.info(toString());
        }
    }

    public String toString() {
        return format("%s: %s", mName, getStat());
    }
}
