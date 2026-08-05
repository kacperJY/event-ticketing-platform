package pl.kacper.sales_api.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Component
@Profile("test")
public class TransactionTestUtil {

    private final static Logger LOGGER = LoggerFactory.getLogger(TransactionTestUtil.class);

    @Transactional
    public void beginAndHoldTransaction(Runnable runnable, CountDownLatch acquireLock, CountDownLatch releaseLock) {

        runnable.run();

        acquireLock.countDown();

        try {
            boolean release = releaseLock.await(5, TimeUnit.SECONDS);
            if (!release)
                throw new IllegalStateException(
                    "Test did not release the transaction lock"
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Lock-holding thread was interrupted",
                    e
            );
        }
    }
}
