package pl.kacper.fulfillment_worker.domain.order.completion;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import pl.kacper.exception.NoSuchDbRecordException;
import pl.kacper.exception.OrderIllegalFlowException;
import pl.kacper.exception.PDFException;
import pl.kacper.fulfillment_worker.domain.dto.CompletedOrderPdfPayload;
import pl.kacper.fulfillment_worker.io.PDFGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class FulfillmentOrderCompletionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FulfillmentOrderCompletionService.class);
    private final Path pdfDestinationPath;

    private final int concurrentOrder;
    private final int fetchSize;
    private final int maxRetries;
    private final int delay;
    private final String emailFrom;

    private final Semaphore backpreasureSemaphore;

    private final FulfillmentOrderCompletionTransactionService fulfillmentOrderCompletionTransactionService;
    private final PDFGenerator pdfGenerator;
    private final JavaMailSender javaMailSender;

    @Autowired
    public FulfillmentOrderCompletionService(FulfillmentOrderCompletionTransactionService fulfillmentOrderCompletionTransactionService,
                                             PDFGenerator pdfGenerator,
                                             @Value("${order-completion.pdf-files-path}") String pdfDestinationPath,
                                             @Value("${order-completion.max-retries}") int maxRetries,
                                             @Value("${order-completion.fetch-size}") int fetchSize,
                                             @Value("${order-completion.delay}") int delay,
                                             @Value("${order-completion.concurrent}") int concurrentOrder,
                                             @Value("${mail-config.from}") String emailFrom,
                                             JavaMailSender javaMailSender) {
        this.fulfillmentOrderCompletionTransactionService = fulfillmentOrderCompletionTransactionService;
        this.pdfGenerator = pdfGenerator;
        this.pdfDestinationPath = Path.of(pdfDestinationPath);
        this.javaMailSender = javaMailSender;

        this.maxRetries = maxRetries;
        this.fetchSize = fetchSize;
        this.delay = delay;

        this.emailFrom = emailFrom;

        this.concurrentOrder = concurrentOrder;
        this.backpreasureSemaphore = new Semaphore(this.concurrentOrder, false);

        try {
            boolean exists = Files.exists(this.pdfDestinationPath);
            if (!exists) {
                Files.createDirectories(this.pdfDestinationPath);
                LOGGER.info("Destination path for PDF files has been created. Path={}", this.pdfDestinationPath);
            }
        } catch (IOException e) {
            LOGGER.error("Cannot create destination path for pdf files. Probably no permissions. Path: {}", this.pdfDestinationPath);
            throw new RuntimeException("Cannot initialize bean of %s with invalid state".formatted(this.getClass().getName()), e);
        }
    }

    public void scanAndCompleteFulfillmentOrder() {
        int permits = 0;
        for (; permits < fetchSize; permits++) {
            boolean acquired = backpreasureSemaphore.tryAcquire();
            if (!acquired) break;
        }

        if (permits == 0) return;

        int realPermitsLocks = permits; // All permits for safe release-way when exception on TX1
        try {
            // TX1
            List<ProcessingOrderTask> processingOrderTaskList = fulfillmentOrderCompletionTransactionService.findAndPrepareOrdersCandidates(permits, maxRetries);

            int permitsToRelease = permits - processingOrderTaskList.size(); // Calculate over permits
            backpreasureSemaphore.release(permitsToRelease); // Release over permits
            realPermitsLocks -= permitsToRelease; // Set real locked permits

            // LOGGING INFO
            UUID schedulerIterationID = UUID.randomUUID();
            LOGGER.info("SCHEDULER ITERATION ID: {} ----- ORDER NUMBER TO PROCESS IN BATCH ----- {}", schedulerIterationID, processingOrderTaskList.size());


            List<Runnable> taskList = new ArrayList<>();
            for (ProcessingOrderTask processingOrderTask : processingOrderTaskList) {
                LOGGER.info("SCHEDULER ITERATION ID: {} ----- PROCESSING ORDER: {}", schedulerIterationID, processingOrderTask.completedOrderPdfPayload().orderId());
                Runnable task = () -> {
                    Throwable exception = null;
                    final Path filePath;
                    final int expectedRetry = processingOrderTask.attemptCount();
                    UUID sourceOrderId = processingOrderTask.completedOrderPdfPayload().orderId();

                    try {

                        // PDF
                        filePath = buildPath(processingOrderTask.completedOrderPdfPayload(), pdfDestinationPath, expectedRetry);
                        pdfGenerator.generateTicketPdf(processingOrderTask.completedOrderPdfPayload(), filePath);

                        // SMTP
                        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
                        MimeMessageHelper mimeMessageHelper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.toString());
                        mimeMessageHelper.setTo(processingOrderTask.purchaserEmail());
                        mimeMessageHelper.setFrom(emailFrom);
                        mimeMessageHelper.setSubject(sourceOrderId + " - " + "confirmation - " + processingOrderTask.completedOrderPdfPayload().paidAt());
                        mimeMessageHelper.setText("""
                                Order confirmation - OrderId %s
                                There are your tickets below:
                                """.formatted(sourceOrderId));
                        FileSystemResource fileSystemResource = new FileSystemResource(filePath);
                        mimeMessageHelper.addAttachment(filePath.getFileName().toString(), fileSystemResource);

                        javaMailSender.send(mimeMessage);

                        // TX2 - SUCCESS
                        fulfillmentOrderCompletionTransactionService.updateCompletedOrder(sourceOrderId, expectedRetry);

                        // AFTER SUCCESS -  update
                        Files.deleteIfExists(filePath);

                    } catch (PDFException e) {
                        exception = e;
                        String message = buildCommonExceptionMessage(
                                sourceOrderId,
                                "Generating PDF order confirmation ended with failure.",
                                e.getMessage()
                        );
                        LOGGER.error(message, e);
                    } catch (MessagingException e) {
                        exception = e;
                        String message = buildCommonExceptionMessage(
                                sourceOrderId,
                                "Creating MIME message ended with failure.",
                                e.getMessage()
                        );
                        LOGGER.error(message, e);
                    } catch (MailException e) {
                        exception = e;
                        String message = buildCommonExceptionMessage(
                                sourceOrderId,
                                "Sending email message ended with failure.",
                                e.getMessage()
                        );
                        LOGGER.error(message, e);
                    } catch (CannotAcquireLockException e) {
                        exception = e;
                        String message = buildCommonExceptionMessage(
                                sourceOrderId,
                                "Cannot update completed order because order is processing by other transaction in the same time.",
                                e.getMessage()
                        );
                        LOGGER.error(message, e);
                    } catch (NoSuchDbRecordException | OrderIllegalFlowException | IllegalStateException e) {
                        exception = e;
                        String message = buildCommonExceptionMessage(
                                sourceOrderId,
                                "Cannot update completed order because order is processing by other transaction in the same time.",
                                "HandleType=SUCCESS-HANDLER :: " + e.getMessage()
                        );
                        LOGGER.error(message, e);
                    } catch (IOException e) {
                        Path path = buildPath(processingOrderTask.completedOrderPdfPayload, pdfDestinationPath, processingOrderTask.attemptCount);
                        String message = buildCommonExceptionMessage(
                                sourceOrderId,
                                "Fulfillment order has been completed and updated but cannot delete file -- Path=" + path,
                                e.getMessage()
                        );
                        LOGGER.error(message, e);
                    } finally {
                        if (exception != null) {
                            // TX2 - FAILURE
                            Instant nextAttemptAt = calculateDelay(expectedRetry);
                            try {
                                fulfillmentOrderCompletionTransactionService.checkAvailableRetriesAndUpdateOrder(
                                        sourceOrderId,
                                        expectedRetry,
                                        maxRetries,
                                        exception.getMessage(),
                                        nextAttemptAt
                                );
                            } catch (NoSuchDbRecordException | OrderIllegalFlowException | IllegalStateException e) {
                                String message = buildCommonExceptionMessage(
                                        sourceOrderId,
                                        "Cannot update completed order because order is processing by other transaction in the same time.",
                                        "HandleType=FAILURE-HANDLER :: " + e.getMessage()
                                );
                                LOGGER.error(message, e);
                            } finally {
                                Path path = buildPath(processingOrderTask.completedOrderPdfPayload, pdfDestinationPath, processingOrderTask.attemptCount);
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    String message = buildCommonExceptionMessage(
                                            sourceOrderId,
                                            "Failure retry updated but cannot delete file -- Path=" + path,
                                            e.getMessage()
                                    );
                                    LOGGER.error(message, e);
                                }
                            }
                        }
                    }
                };
                taskList.add(task);
            }
            List<Callable<Object>> callableList = taskList.stream()
                    .map(Executors::callable)
                    .toList();

            ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<Future<Object>> futures = executorService.invokeAll(callableList);
                for (int i = 0; i < processingOrderTaskList.size(); i++) {
                    try {
                        futures.get(i).get(); // Check execution
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        try {
                            ProcessingOrderTask processingOrderTask = processingOrderTaskList.get(i);
                            fulfillmentOrderCompletionTransactionService.checkAvailableRetriesAndUpdateOrder(
                                    processingOrderTask.completedOrderPdfPayload.orderId(),
                                    processingOrderTask.attemptCount,
                                    maxRetries,
                                    cause != null ? cause.getMessage() : "No information",
                                    calculateDelay(processingOrderTask.attemptCount)
                            );
                        } catch (NoSuchDbRecordException | OrderIllegalFlowException |
                                 IllegalStateException txException) {
                            String message = buildCommonExceptionMessage(
                                    processingOrderTaskList.get(i).completedOrderPdfPayload.orderId(),
                                    "Cannot update completed order because order is processing by other transaction in the same time.",
                                    "HandleType=FAILURE-HANDLER :: " + txException.getMessage()
                            );
                            LOGGER.error(message, txException);
                        } finally {

                            Path filePath = buildPath(processingOrderTaskList.get(i).completedOrderPdfPayload, pdfDestinationPath, processingOrderTaskList.get(i).attemptCount);
                            try {
                                Files.deleteIfExists(filePath);
                            } catch (IOException ioEx) {
                                String message = buildCommonExceptionMessage(
                                        processingOrderTaskList.get(i).completedOrderPdfPayload.orderId(),
                                        "Failure retry updated but cannot delete file -- Path=" + filePath,
                                        ioEx.getMessage()
                                );
                                LOGGER.error(message, ioEx);
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                LOGGER.error("ERROR: Main scheduler thread ThreadID={} has been interrupted", Thread.currentThread().getName());
                Thread.currentThread().interrupt();
            } finally {
                executorService.shutdown();
                executorService.close();
            }
        } finally {
            backpreasureSemaphore.release(realPermitsLocks);
        }
    }

    private Instant calculateDelay(int retryNumber) {
        return Instant.now().plus(Duration.ofSeconds(retryNumber * delay));
    }

    private String buildCommonExceptionMessage(UUID orderId, String shortErrorMessage, String details) {
        return """
                Short error message: %s
                OrderID: %s
                %s
                """.formatted(
                shortErrorMessage,
                orderId,
                details == null ? "" : "Details: " + details
        );
    }

    private Path buildPath(CompletedOrderPdfPayload completedOrderPdfPayload, Path destinationPath, int retryNumber) {
        String filename =
                "tickets-" +
                        completedOrderPdfPayload.orderId() +
                        "::" + completedOrderPdfPayload.paidAt().toString() +
                        "-" + retryNumber +
                        ".pdf";
        return destinationPath.resolve(filename);
    }

    record ProcessingOrderTask(
            int attemptCount,
            String purchaserEmail,
            CompletedOrderPdfPayload completedOrderPdfPayload
    ) {
    }

}
