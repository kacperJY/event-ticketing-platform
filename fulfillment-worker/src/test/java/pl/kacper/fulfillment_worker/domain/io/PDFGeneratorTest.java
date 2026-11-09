package pl.kacper.fulfillment_worker.domain.io;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import pl.kacper.exception.PDFException;
import pl.kacper.fulfillment_worker.domain.dto.CompletedOrderPdfPayload;
import pl.kacper.fulfillment_worker.io.PDFGenerator;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PDFGeneratorTest {

    private final PDFGenerator pdfGenerator = new PDFGenerator();

    @TempDir(cleanup = CleanupMode.ALWAYS)
    private Path tempDirectory;

    @Test
    void shouldCreateSuccessfullyPdfTicketBaseOnPayload() {
        UUID orderId = UUID.randomUUID();
        Instant date = Instant.now();

        UUID ticket1 = UUID.randomUUID();
        UUID ticket2 = UUID.randomUUID();

        CompletedOrderPdfPayload completedOrderPdfPayload = new CompletedOrderPdfPayload(
                orderId,
                date,
                List.of(
                        new CompletedOrderPdfPayload.Item(
                                ticket1,
                                "Dawid Podsiadło - Małomiasteczkowa Trasa",
                                "Polska",
                                "Warszawa",
                                "Księcia Józefa Poniatowskiego",
                                "1",
                                "03-901",
                                date,
                                "PGE Narodowy",
                                "S1"
                        ),
                        new CompletedOrderPdfPayload.Item(
                                ticket2,
                                "Meteora Tour - Linkin Park Tribute",
                                "Polska",
                                "Kraków",
                                "Marii Konopnickiej",
                                "28",
                                "30-307",
                                date,
                                "ICE Kraków Congress Centre",
                                "S2"
                        )
                )
        );

        try {
            Path finalPath = tempDirectory.resolve(completedOrderPdfPayload.orderId() + "::" + completedOrderPdfPayload.paidAt() + "-" + 0);
            pdfGenerator.generateTicketPdf(completedOrderPdfPayload, finalPath);
            try (
                    PdfReader pdfReader = new PdfReader(finalPath.toAbsolutePath().toString());
                    PdfDocument pdfDocument = new PdfDocument(pdfReader);
            ) {


                int numberOfPages = pdfDocument.getNumberOfPages();
                Assertions.assertThat(numberOfPages).isEqualTo(completedOrderPdfPayload.itemList().size());

                for (int i = 1; i <= numberOfPages; i++) {
                    PdfPage page = pdfDocument.getPage(i);

                    String currentText = PdfTextExtractor.getTextFromPage(page);
                    String ticketIdText = completedOrderPdfPayload.itemList().get(i - 1).ticketId().toString();
                    String orderIdText = completedOrderPdfPayload.orderId().toString();
                    Assertions.assertThat(currentText).contains(ticketIdText);
                    Assertions.assertThat(currentText).contains(orderIdText);
                }
            }
        } catch (IOException | PDFException e) {
            Assertions.fail(e.getMessage(), e);
        }
    }
}
