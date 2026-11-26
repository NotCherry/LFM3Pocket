package com.notcherry.lfm3pocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renders PDF pages on-device using Android's built-in {@link PdfRenderer} into bounded JPEG
 * cache files so scanned/image-only PDFs (or user-selected vision PDFs) can be fed directly
 * into the multimodal projector without external native dependencies.
 */
final class PdfPageRenderer {
    static final int MAX_VISION_PAGES = 4;
    private static final int DEFAULT_MAX_SIDE = 1024;
    private static final int MIN_RENDER_SIDE = 256;
    private static final int MAX_RENDER_SIDE = 1536;

    static final class RenderedPage {
        final File file;
        final int pageNumber;
        final int totalPages;
        final int width;
        final int height;
        final String label;
        final String caption;

        RenderedPage(File file, int pageNumber, int totalPages, int width, int height, String displayName) {
            this.file = file;
            this.pageNumber = pageNumber;
            this.totalPages = totalPages;
            this.width = width;
            this.height = height;
            String base = displayName == null || displayName.trim().isEmpty() ? "document.pdf" : displayName.trim();
            this.label = totalPages > 1 ? base + " · p." + pageNumber : base;
            this.caption = "Page " + pageNumber + "/" + totalPages + " · " + width + "×" + height;
        }
    }

    static final class Result {
        final List<RenderedPage> pages;
        final int totalPages;
        final boolean truncated;
        final String error;

        private Result(List<RenderedPage> pages, int totalPages, boolean truncated, String error) {
            this.pages = pages == null ? Collections.emptyList() : Collections.unmodifiableList(pages);
            this.totalPages = totalPages;
            this.truncated = truncated;
            this.error = error;
        }

        static Result ok(List<RenderedPage> pages, int totalPages, boolean truncated) {
            return new Result(pages, totalPages, truncated, null);
        }

        static Result error(String error) {
            return new Result(Collections.emptyList(), 0, false, error);
        }

        boolean isOk() { return error == null && !pages.isEmpty(); }
    }

    private PdfPageRenderer() {}

    /** Computes bounded pixel dimensions preserving aspect ratio for a PDF page in points. */
    static ImageGeometry.Size computeRenderSize(int pageWidthPt, int pageHeightPt, int maxSidePx) {
        int safeW = Math.max(1, pageWidthPt);
        int safeH = Math.max(1, pageHeightPt);
        int bound = Math.max(MIN_RENDER_SIDE, Math.min(MAX_RENDER_SIDE, maxSidePx > 0 ? maxSidePx : DEFAULT_MAX_SIDE));
        if (safeW >= safeH) {
            int outW = bound;
            int outH = Math.max(1, Math.round((float) safeH * bound / safeW));
            return new ImageGeometry.Size(outW, outH);
        } else {
            int outH = bound;
            int outW = Math.max(1, Math.round((float) safeW * bound / safeH));
            return new ImageGeometry.Size(outW, outH);
        }
    }

    static Result renderPages(Context context, File pdfFile, String displayName,
                              ImageSettings settings, int maxPages, AtomicBoolean cancelled) {
        if (context == null || pdfFile == null || !pdfFile.isFile()) {
            return Result.error("Could not read this PDF file.");
        }
        int limit = Math.max(1, Math.min(MAX_VISION_PAGES, maxPages > 0 ? maxPages : MAX_VISION_PAGES));
        int targetMaxSide = (settings != null && settings.enabled)
                ? Math.min(MAX_RENDER_SIDE, Math.max(512, settings.maxSidePixels()))
                : DEFAULT_MAX_SIDE;
        int jpegQuality = settings != null ? Math.max(60, Math.min(95, settings.jpegQuality)) : 85;
        ArrayList<RenderedPage> rendered = new ArrayList<>();
        long stamp = System.currentTimeMillis();
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            int total = renderer.getPageCount();
            if (total <= 0) {
                return Result.error("This PDF has no readable pages.");
            }
            int count = Math.min(total, limit);
            for (int i = 0; i < count; i++) {
                if (cancelled != null && cancelled.get()) {
                    cleanupFiles(rendered);
                    return Result.error("PDF import cancelled.");
                }
                Bitmap bitmap = null;
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    ImageGeometry.Size size = computeRenderSize(page.getWidth(), page.getHeight(), targetMaxSide);
                    bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888);
                    // PDF pages have transparent backgrounds by default; fill white so black text stays readable.
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    File out = new File(context.getCacheDir(),
                            "pdf-page-" + stamp + "-" + (i + 1) + ".jpg");
                    writeJpeg(bitmap, out, jpegQuality);
                    rendered.add(new RenderedPage(out, i + 1, total, size.width, size.height, displayName));
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                }
            }
            return Result.ok(rendered, total, total > count);
        } catch (SecurityException e) {
            cleanupFiles(rendered);
            return Result.error("This PDF is password-protected and cannot be rendered.");
        } catch (IOException | IllegalArgumentException e) {
            cleanupFiles(rendered);
            return Result.error("Could not render this PDF as images.");
        } catch (OutOfMemoryError e) {
            cleanupFiles(rendered);
            return Result.error("This PDF is too large to render on this phone.");
        }
    }

    private static void writeJpeg(Bitmap bitmap, File target, int quality) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)) {
                throw new IOException("Could not encode rendered PDF page.");
            }
            fos.getFD().sync();
        } catch (IOException e) {
            target.delete();
            throw e;
        }
    }

    private static void cleanupFiles(List<RenderedPage> pages) {
        for (RenderedPage page : pages) {
            if (page != null && page.file != null && page.file.exists()) {
                page.file.delete();
            }
        }
    }
}
