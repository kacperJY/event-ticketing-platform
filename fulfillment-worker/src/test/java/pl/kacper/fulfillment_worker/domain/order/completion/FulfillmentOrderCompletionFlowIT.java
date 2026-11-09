package pl.kacper.fulfillment_worker.domain.order.completion;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.kacper.exception.PDFException;
import pl.kacper.fulfillment_worker.BaseIT;
import pl.kacper.fulfillment_worker.domain.dto.CompletedOrderPdfPayload;
import pl.kacper.fulfillment_worker.domain.order.DeliveryStatus;
import pl.kacper.fulfillment_worker.domain.order.FulfillmentOrderEntity;
import pl.kacper.fulfillment_worker.domain.order.FulfillmentOrderRepository;
import pl.kacper.fulfillment_worker.domain.ticket.TicketEntity;
import pl.kacper.fulfillment_worker.io.PDFGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentOrderCompletionFlowIT extends BaseIT {

    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FulfillmentOrderRepository repository;
    @Autowired private FulfillmentOrderCompletionService completionService;

    @MockitoSpyBean private FulfillmentOrderCompletionTransactionService transactionService;
    @MockitoSpyBean private PDFGenerator pdfGenerator;

    @Test
    void happyPathGeneratesAndSendsTwoTicketsThenMarksOrderSent() throws Exception {
        UUID orderId = seedOrder(2);
        AtomicInteger attachedPages = new AtomicInteger();
        stubMimeMessage();
        org.mockito.Mockito.doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            assertThat(message.getAllRecipients()).extracting(Object::toString)
                    .containsExactly("buyer@example.com");
            MimeMultipart parts = (MimeMultipart) message.getContent();
            boolean foundPdf = false;
            for (int i = 0; i < parts.getCount(); i++) {
                var part = parts.getBodyPart(i);
                if (part.getFileName() != null && part.getFileName().endsWith(".pdf")) {
                    foundPdf = true;
                    try (PdfDocument pdf = new PdfDocument(new PdfReader(part.getInputStream()))) {
                        attachedPages.set(pdf.getNumberOfPages());
                    }
                }
            }
            assertThat(foundPdf).isTrue();
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        completionService.scanAndCompleteFulfillmentOrder();

        FulfillmentOrderEntity order = load(orderId);
        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(order.getAttemptCount()).isEqualTo(1);
        assertThat(order.getProcessingStartedAt()).isNotNull();
        assertThat(order.getDeliveredAt()).isNotNull();
        assertThat(order.getNextAttemptAt()).isNull();
        assertThat(order.getLastError()).isNull();
        assertThat(attachedPages.get()).isEqualTo(2);
        verify(mailSender).send(any(MimeMessage.class));
        assertNoPdfLeft(orderId);
    }

    @Test
    void tx1ErrorReleasesEverySemaphorePermit() {
        doThrow(new IllegalStateException("TX1 unavailable"))
                .when(transactionService).findAndPrepareOrdersCandidates(anyInt(), anyInt());
        Semaphore semaphore = (Semaphore) ReflectionTestUtils.getField(completionService, "backpreasureSemaphore");
        assertThat(semaphore).isNotNull();
        int initialPermits = semaphore.availablePermits();

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(completionService::scanAndCompleteFulfillmentOrder)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("TX1 unavailable");
            assertThat(semaphore.availablePermits()).isEqualTo(initialPermits);
        }
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void tx2SuccessErrorSchedulesRetryAndRemovesPdf() throws IOException {
        UUID orderId = seedOrder(1);
        stubMimeMessage();
        doThrow(new CannotAcquireLockException("TX2 lock unavailable"))
                .when(transactionService).updateCompletedOrder(eq(orderId), eq(1));

        completionService.scanAndCompleteFulfillmentOrder();

        assertPendingRetry(load(orderId), "TX2 lock unavailable");
        verify(mailSender).send(any(MimeMessage.class));
        assertNoPdfLeft(orderId);
    }

    @Test
    void smtpErrorSchedulesRetryAndRemovesPdf() throws IOException {
        UUID orderId = seedOrder(1);
        stubMimeMessage();
        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender).send(any(MimeMessage.class));

        completionService.scanAndCompleteFulfillmentOrder();

        assertPendingRetry(load(orderId), "SMTP unavailable");
        assertNoPdfLeft(orderId);
    }

    @Test
    void pdfErrorSchedulesRetryWithoutSendingEmail() throws IOException {
        UUID orderId = seedOrder(1);
        doThrow(new PDFException("PDF unavailable", new IOException("disk error")))
                .when(pdfGenerator).generateTicketPdf(any(CompletedOrderPdfPayload.class), any(Path.class));

        completionService.scanAndCompleteFulfillmentOrder();

        assertPendingRetry(load(orderId), "PDF unavailable");
        verify(mailSender, never()).send(any(MimeMessage.class));
        assertNoPdfLeft(orderId);
    }

    @Test
    void failedTx2FailureLeavesProcessingOrderForTx1OrphanRecovery() throws IOException {
        UUID orderId = seedOrder(1);
        doThrow(new PDFException("PDF unavailable", new IOException("disk error")))
                .when(pdfGenerator).generateTicketPdf(any(CompletedOrderPdfPayload.class), any(Path.class));
        doThrow(new IllegalStateException("TX2-FAILURE unavailable"))
                .when(transactionService).checkAvailableRetriesAndUpdateOrder(
                        eq(orderId), eq(1), anyInt(), any(String.class), any(Instant.class));

        completionService.scanAndCompleteFulfillmentOrder();

        FulfillmentOrderEntity afterFailedTx2 = load(orderId);
        assertThat(afterFailedTx2.getDeliveryStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(afterFailedTx2.getAttemptCount()).isEqualTo(1);
        assertThat(afterFailedTx2.getProcessingStartedAt()).isNotNull();
        assertThat(afterFailedTx2.getNextAttemptAt()).isNull();
        assertThat(afterFailedTx2.getLastError()).isNull();
        assertNoPdfLeft(orderId);

        // Simulate the orphan timeout without waiting 90 seconds in the test.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.find(FulfillmentOrderEntity.class, orderId)
                        .setProcessingStartedAt(Instant.now().minusSeconds(120)));

        var recoveredTasks = transactionService.findAndPrepareOrdersCandidates(1, 5);

        assertThat(recoveredTasks).singleElement().satisfies(task -> {
            assertThat(task.completedOrderPdfPayload().orderId()).isEqualTo(orderId);
            assertThat(task.attemptCount()).isEqualTo(2);
        });
        FulfillmentOrderEntity afterRecovery = load(orderId);
        assertThat(afterRecovery.getDeliveryStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(afterRecovery.getAttemptCount()).isEqualTo(2);
        assertThat(afterRecovery.getProcessingStartedAt()).isAfter(Instant.now().minusSeconds(90));
        assertThat(afterRecovery.getNextAttemptAt()).isNull();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    private UUID seedOrder(int ticketCount) {
        UUID orderId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Instant now = Instant.now();
            FulfillmentOrderEntity order = new FulfillmentOrderEntity(
                    orderId, "buyer@example.com", now.minusSeconds(60), now.minusSeconds(120));
            entityManager.persist(order);
            for (int i = 0; i < ticketCount; i++) {
                entityManager.persist(new TicketEntity(
                        order, (long) (i + 1), now, "Concert", "Poland", "Warsaw",
                        "Main Street", "12", "00-001", now.plusSeconds(86_400),
                        "MUSIC", "A-" + (i + 1)));
            }
        });
        return orderId;
    }

    private FulfillmentOrderEntity load(UUID orderId) {
        return repository.findById(orderId).orElseThrow();
    }

    private void stubMimeMessage() {
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private void assertPendingRetry(FulfillmentOrderEntity order, String error) {
        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(order.getAttemptCount()).isEqualTo(1);
        assertThat(order.getProcessingStartedAt()).isNotNull();
        assertThat(order.getNextAttemptAt()).isAfter(order.getProcessingStartedAt());
        assertThat(order.getLastError()).contains(error);
        assertThat(order.getDeliveredAt()).isNull();
    }

    private void assertNoPdfLeft(UUID orderId) throws IOException {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "ticketing-completion-it");
        try (Stream<Path> paths = Files.list(directory)) {
            assertThat(paths.filter(path -> path.getFileName().toString().contains(orderId.toString())))
                    .isEmpty();
        }
    }
}
