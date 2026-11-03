package pl.kacper.fulfillment_worker.io;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.kacper.exception.PDFException;
import pl.kacper.exception.ResourcesNotFoundException;
import pl.kacper.fulfillment_worker.domain.dto.CompletedOrderPdfPayload;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

@Component
public class PDFGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PDFGenerator.class);

    private static final String FONT_REGULAR;
    private static final String FONT_BOLD;
    private static final String FONT_MONO;

    private static final ZoneId TIME_ZONE = ZoneId.of("Europe/Warsaw");

    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DeviceRgb DARK = new DeviceRgb(23, 43, 56);
    private static final DeviceRgb MUTED = new DeviceRgb(88, 108, 119);
    private static final DeviceRgb ACCENT = new DeviceRgb(0, 127, 121);
    private static final DeviceRgb PALE = new DeviceRgb(234, 245, 243);
    private static final DeviceRgb RULE = new DeviceRgb(214, 224, 228);

    static {
        URL regular = PDFGenerator.class.getClassLoader().getResource("fonts/DejaVuSans.ttf");
        URL bold = PDFGenerator.class.getClassLoader().getResource("fonts/DejaVuSans-Bold.ttf");
        URL mono = PDFGenerator.class.getClassLoader().getResource("fonts/DejaVuSansMono.ttf");

        if (regular == null || bold == null || mono == null)
            throw new ResourcesNotFoundException("""
                    Missing font resources:
                    FONT_REGULAR=%s
                    FONT_BOLD=%s
                    FONT_MONO=%s
                    """.formatted(
                    regular == null ? "null" : regular.toString(),
                    bold == null ? "null" : bold.toString(),
                    mono == null ? "null" : mono.toString()
            ));
        else {
            FONT_REGULAR = regular.toString();
            FONT_BOLD = bold.toString();
            FONT_MONO = mono.toString();
        }
    }

    public PDFGenerator() {
    }

    public Path generateTicketPdf(CompletedOrderPdfPayload completedOrderPdfPayload, Path destinationPath) {
        Path finalPath = buildPath(completedOrderPdfPayload, destinationPath);
        try (
                PdfDocument pdfDocument = initializeDocument(finalPath.toAbsolutePath().toString());
        ) {
            int counter = 1;
            Fonts fonts = createFonts();
            for (CompletedOrderPdfPayload.Item item : completedOrderPdfPayload.itemList()) {
                TicketData ticketData = new TicketData(
                        item.eventCategory(),
                        item.eventName(),
                        LocalDate.ofInstant(item.eventDate(), TIME_ZONE).format(dateFormatter),
                        LocalTime.ofInstant(item.eventDate(), TIME_ZONE).format(timeFormatter),
                        item.seatNumber(),
                        buildStreet(item.street(), item.no()),
                        buildLocation(item.postalCode(), item.city(), item.country()),
                        item.ticketId().toString(),
                        completedOrderPdfPayload.orderId().toString()
                );
                PdfPage pdfPage = pdfDocument.addNewPage(PageSize.A5);
                drawTicket(
                        new PdfCanvas(pdfPage),
                        pdfPage.getPageSize().getWidth(),
                        pdfPage.getPageSize().getHeight(),
                        ticketData,
                        counter++,
                        completedOrderPdfPayload.itemList().size(),
                        fonts
                );
            }
        }
        return finalPath;
    }

    private String buildStreet(String street, String no) {
        return "ul. %s %s".formatted(street, no);
    }

    private String buildLocation(String postalCode, String city, String country) {
        return "%s, %s, %s".formatted(postalCode, city, country);
    }

    private Fonts createFonts() {
        try {
            return new Fonts(
                    PdfFontFactory.createFont(FONT_REGULAR, PdfEncodings.IDENTITY_H,
                            PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED),
                    PdfFontFactory.createFont(FONT_BOLD, PdfEncodings.IDENTITY_H,
                            PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED),
                    PdfFontFactory.createFont(FONT_MONO, PdfEncodings.IDENTITY_H,
                            PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED)
            );
        } catch (IOException e) {
            LOGGER.error("Create fonts failure: {}", e.getMessage(), e);
            throw new PDFException(e);
        }
    }

    private PdfDocument initializeDocument(String filename) {
        try {
            PdfWriter pdfWriter = new PdfWriter(new FileOutputStream(filename, false));
            return new PdfDocument(pdfWriter);

        } catch (IOException e) {
            LOGGER.error("Cannot create output stream to file={}. Probably no permissions", filename);
            throw new PDFException("Cannot write to file", e);
        }
    }

    private Path buildPath(CompletedOrderPdfPayload completedOrderPdfPayload, Path destinationPath) {
        String filename = "tickets-" + completedOrderPdfPayload.orderId() + "::" + completedOrderPdfPayload.paidAt().toString() + ".pdf";
        Path finalPath = destinationPath.resolve(filename);
        return finalPath;
    }

    private static void drawTicket(PdfCanvas c, float width, float height,
                                   TicketData t, int number, int total, Fonts fonts) {
        c.setFillColor(ACCENT).rectangle(0, height - 7, width, 7).fill();
        text(c, fonts.bold(), 11, DARK, 28, height - 38, "TICKETING");
        String counter = "BILET %02d / %02d".formatted(number, total);
        rightText(c, fonts.regular(), 8, MUTED, width - 28, height - 38, counter);
        rule(c, 28, height - 54, width - 28, height - 54, false);

        text(c, fonts.bold(), 8, ACCENT, 28, height - 82, t.category());
        // The event title is the main visual anchor; split it across two lines.
        drawTitle(c, fonts.bold(), width - 56, height, t.eventName());

        c.setFillColor(PALE).roundRectangle(28, height - 252, width - 56, 67, 7).fill();
        label(c, fonts, 41, height - 206, "DATA");
        text(c, fonts.bold(), 13, DARK, 41, height - 233, t.date());
        label(c, fonts, 179, height - 206, "GODZINA");
        text(c, fonts.bold(), 13, DARK, 179, height - 233, t.time());
        label(c, fonts, 295, height - 206, "MIEJSCE");
        text(c, fonts.bold(), 18, ACCENT, 295, height - 233, t.seatNumber());

        label(c, fonts, 28, height - 283, "LOKALIZACJA");
        text(c, fonts.bold(), fittingSize(fonts.bold(), t.street(), width - 56, 10.5f, 8f),
                DARK, 28, height - 305, t.street());
        text(c, fonts.regular(), 10, DARK, 28, height - 324, t.cityAndCountry());
        text(c, fonts.regular(), 7.5f, MUTED, 28, height - 343,
                "Godzina wydarzenia podana w czasie lokalnym.");

        rule(c, 28, 221, width - 28, 221, true);
        label(c, fonts, 28, 190, "IDENTYFIKATOR BILETU");
        text(c, fonts.mono(), 9, DARK, 28, 169, t.ticketId());
        label(c, fonts, 28, 135, "NUMER ZAMÓWIENIA");
        text(c, fonts.mono(), 9, DARK, 28, 114, t.orderId());

//        rule(c, 28, 78, width - 28, 78, false);
//        text(c, fonts.bold(), 8, ACCENT, 28, 55, "WZÓR - DANE PRZYKŁADOWE");
//        text(c, fonts.regular(), 8, MUTED, 28, 38,
//                "Jeden bilet uprawnia do zajęcia jednego miejsca.");
    }

    private static void drawTitle(PdfCanvas c, PdfFont font, float maxWidth,
                                  float height, String eventName) {
        for (float size = 30; size >= 18; size--) {
            String[] lines = splitTitle(eventName, font, size, maxWidth);
            if (lines == null) continue;
            text(c, font, size, DARK, 28, height - 120, lines[0]);
            if (!lines[1].isEmpty()) {
                text(c, font, size, DARK, 28, height - 157, lines[1]);
            }
            return;
        }
        throw new IllegalArgumentException("Event name does not fit on two title lines: " + eventName);
    }

    private static String[] splitTitle(String title, PdfFont font, float size, float maxWidth) {
        String[] words = title.trim().split("\\s+");
        if (words.length == 1) {
            return font.getWidth(title, size) <= maxWidth ? new String[]{title, ""} : null;
        }

        String[] best = null;
        float bestWidth = Float.MAX_VALUE;
        for (int split = 1; split < words.length; split++) {
            String first = String.join(" ", Arrays.copyOfRange(words, 0, split));
            String second = String.join(" ", Arrays.copyOfRange(words, split, words.length));
            float widest = Math.max(font.getWidth(first, size), font.getWidth(second, size));
            if (widest <= maxWidth && widest < bestWidth) {
                best = new String[]{first, second};
                bestWidth = widest;
            }
        }
        return best;
    }

    private static float fittingSize(PdfFont font, String value, float maxWidth,
                                     float preferredSize, float minimumSize) {
        for (float size = preferredSize; size >= minimumSize; size -= 0.5f) {
            if (font.getWidth(value, size) <= maxWidth) return size;
        }
        throw new IllegalArgumentException("Text does not fit: " + value);
    }

    private static void label(PdfCanvas c, Fonts fonts, float x, float y, String text) {
        text(c, fonts.bold(), 7.5f, MUTED, x, y, text);
    }

    private static void text(PdfCanvas c, PdfFont font, float size, DeviceRgb color,
                             float x, float y, String value) {
        c.beginText().setFontAndSize(font, size).setFillColor(color)
                .moveText(x, y).showText(value).endText();
    }

    private static void rightText(PdfCanvas c, PdfFont font, float size, DeviceRgb color,
                                  float rightX, float y, String value) {
        text(c, font, size, color, rightX - font.getWidth(value, size), y, value);
    }

    private static void rule(PdfCanvas c, float x1, float y1, float x2, float y2,
                             boolean dashed) {
        c.saveState().setStrokeColor(RULE).setLineWidth(0.7f);
        if (dashed) c.setLineDash(3, 4);
        c.moveTo(x1, y1).lineTo(x2, y2).stroke().restoreState();
    }

    private record TicketData(String category, String eventName, String date,
                              String time, String seatNumber, String street,
                              String cityAndCountry, String ticketId, String orderId) {
    }

    private record Fonts(PdfFont regular, PdfFont bold, PdfFont mono) {
    }
}
