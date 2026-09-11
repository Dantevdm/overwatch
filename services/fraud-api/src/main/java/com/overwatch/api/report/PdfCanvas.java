package com.overwatch.api.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

import java.io.Closeable;
import java.io.IOException;

/**
 * A page being drawn on, with the origin at the top-left.
 *
 * <p>PDF measures upward from the bottom of the page and every layout decision a
 * person makes measures downward from the top. Flipping it once here means the
 * rest of the report reads in the direction it is laid out, instead of every
 * coordinate being written as a subtraction from the page height.
 *
 * <p>Only the standard 14 fonts are used, so nothing has to be embedded and the
 * document stays a few tens of kilobytes. The cost is WinAnsi: characters outside
 * it cannot be shown at all, and PDFBox throws rather than dropping them, so
 * {@link #safe} narrows the text instead of letting a merchant name with an
 * unusual character fail the whole export.
 */
final class PdfCanvas implements Closeable {

    static final PDFont REGULAR = new PDType1Font(FontName.HELVETICA);
    static final PDFont BOLD = new PDType1Font(FontName.HELVETICA_BOLD);

    static final float MARGIN = 48;
    static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;

    private final PDPageContentStream stream;

    PdfCanvas(PDDocument document) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        this.stream = new PDPageContentStream(document, page);
    }

    /** Flip a top-down y into PDF's bottom-up space. */
    private static float flip(float y) {
        return PAGE_HEIGHT - y;
    }

    void text(String value, float x, float y, PDFont font, float size, int rgb) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        colour(rgb, false);
        stream.newLineAtOffset(x, flip(y));
        stream.showText(safe(value));
        stream.endText();
    }

    /** Right-aligned at {@code x}. Numbers in a column line up on their units. */
    void textRight(String value, float x, float y, PDFont font, float size, int rgb) throws IOException {
        text(value, x - width(value, font, size), y, font, size, rgb);
    }

    static float width(String value, PDFont font, float size) {
        try {
            return font.getStringWidth(safe(value)) / 1000 * size;
        } catch (IOException e) {
            // Width of a standard-14 font is a table lookup that cannot fail in
            // practice; estimating is better than failing an export over it.
            return safe(value).length() * size * 0.5f;
        }
    }

    void rect(float x, float y, float w, float h, int rgb) throws IOException {
        colour(rgb, false);
        stream.addRect(x, flip(y) - h, w, h);
        stream.fill();
    }

    void line(float x1, float y1, float x2, float y2, int rgb, float thickness) throws IOException {
        colour(rgb, true);
        stream.setLineWidth(thickness);
        stream.moveTo(x1, flip(y1));
        stream.lineTo(x2, flip(y2));
        stream.stroke();
    }

    /** A polyline, drawn as one path so joins are mitred rather than stacked. */
    void path(float[] xs, float[] ys, int rgb, float thickness) throws IOException {
        if (xs.length < 2) {
            return;
        }
        colour(rgb, true);
        stream.setLineWidth(thickness);
        stream.moveTo(xs[0], flip(ys[0]));
        for (int i = 1; i < xs.length; i++) {
            stream.lineTo(xs[i], flip(ys[i]));
        }
        stream.stroke();
    }

    private void colour(int rgb, boolean stroking) throws IOException {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        if (stroking) {
            stream.setStrokingColor(r, g, b);
        } else {
            stream.setNonStrokingColor(r, g, b);
        }
    }

    /**
     * Replace anything the standard fonts cannot encode.
     *
     * <p>Typographic punctuation is mapped to its ASCII equivalent rather than
     * dropped, because an em dash becoming a hyphen still reads and an em dash
     * becoming nothing does not.
     */
    static String safe(String value) {
        String mapped = value
                .replace('—', '-').replace('–', '-')
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace(' ', ' ');
        StringBuilder out = new StringBuilder(mapped.length());
        for (char c : mapped.toCharArray()) {
            out.append(c >= 0x20 && c <= 0xFF ? c : '?');
        }
        return out.toString();
    }

    /** Truncate to fit a column, with an ellipsis so the reader knows it was cut. */
    static String ellipsise(String value, float available, PDFont font, float size) {
        if (width(value, font, size) <= available) {
            return value;
        }
        String text = value;
        while (text.length() > 1 && width(text + "...", font, size) > available) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
