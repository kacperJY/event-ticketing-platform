package pl.kacper.sales_api.utils;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Component
@Profile("test")
public class TransactionTestUtil {

    private final TransactionTemplate transactionTemplate;

    public TransactionTestUtil(PlatformTransactionManager platformTransactionManager) {
        this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
    }

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

    public void beginAndTimeHoldTransactionRollbackAfterTime(Runnable runnable, CountDownLatch acquireLock, CountDownLatch releaseLock, long time, TimeUnit timeUnit) {
        transactionTemplate.execute((TransactionCallback<Void>) status ->
        {
            runnable.run();

            acquireLock.countDown();

            try {
                releaseLock.await(time, timeUnit);
                status.setRollbackOnly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Lock-holding thread was interrupted",
                        e
                );
            }

            return null;
        });
    }
}
