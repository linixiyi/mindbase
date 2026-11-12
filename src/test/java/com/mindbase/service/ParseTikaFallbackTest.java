package com.mindbase.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * engine=tika（PDFBox 文本层回退引擎，Decisions D10）的单元测试。
 * 不依赖 Spring Context、无外部服务；PDF 由 PDFBox 在测试内构造。
 */
class ParseTikaFallbackTest {

    private ParseService parseService;

    @BeforeEach
    void setUp() {
        parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "pdfParsingEngine", "tika");
        ReflectionTestUtils.setField(parseService, "chunkSize", 1000);
        ReflectionTestUtils.setField(parseService, "overlapSize", 0);
        ReflectionTestUtils.setField(parseService, "minChunkSize", 1);
        ReflectionTestUtils.setField(parseService, "bufferSize", 8192);
        ReflectionTestUtils.setField(parseService, "aliyunOcrEnabled", false);
        ReflectionTestUtils.setField(parseService, "aliyunOcrCallbackToken", "");
        ReflectionTestUtils.setField(parseService, "serverPort", 8081);
        ReflectionTestUtils.setField(parseService, "serverContextPath", "");
    }

    private static byte[] twoPagePdf(String pageOneText, String pageTwoText) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page1 = new PDPage();
            document.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(document, page1)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(pageOneText);
                cs.endText();
            }
            PDPage page2 = new PDPage();
            document.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(document, page2)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(pageTwoText);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> invokePrivatePages(String methodName, byte[] pdf) throws Exception {
        Method method = ParseService.class.getDeclaredMethod(methodName, InputStream.class);
        method.setAccessible(true);
        return (List<Object>) method.invoke(parseServiceInstance(), new ByteArrayInputStream(pdf));
    }

    private static ParseService parseServiceInstance() {
        ParseService service = new ParseService();
        ReflectionTestUtils.setField(service, "pdfParsingEngine", "tika");
        ReflectionTestUtils.setField(service, "chunkSize", 1000);
        ReflectionTestUtils.setField(service, "overlapSize", 0);
        ReflectionTestUtils.setField(service, "minChunkSize", 1);
        ReflectionTestUtils.setField(service, "bufferSize", 8192);
        ReflectionTestUtils.setField(service, "aliyunOcrEnabled", false);
        ReflectionTestUtils.setField(service, "aliyunOcrCallbackToken", "");
        ReflectionTestUtils.setField(service, "serverPort", 8081);
        ReflectionTestUtils.setField(service, "serverContextPath", "");
        return service;
    }

    private static int pageNumberOf(Object page) throws Exception {
        return (Integer) page.getClass().getDeclaredMethod("pageNumber").invoke(page);
    }

    private static String textOf(Object page) throws Exception {
        return (String) page.getClass().getDeclaredMethod("text").invoke(page);
    }

    @Test
    void tikaBranchExtractsPerPageText() throws Exception {
        byte[] pdf = twoPagePdf("Alpha retrieval evidence A1", "Beta chunk evidence B2");
        List<Object> pages = invokePrivatePages("parsePdfWithPdfBox", pdf);
        assertEquals(2, pages.size());
        assertEquals(1, pageNumberOf(pages.get(0)));
        assertEquals(2, pageNumberOf(pages.get(1)));
        assertTrue(textOf(pages.get(0)).toLowerCase().contains("alpha"), textOf(pages.get(0)));
        assertTrue(textOf(pages.get(1)).toLowerCase().contains("beta"), textOf(pages.get(1)));
    }

    @Test
    void emptyTextLayerYieldsBlankPageEntries() throws Exception {
        // 无文本层的空白页：text 归一为空串，由调用方跳过——与 07 OCR blocked 既有口径一致
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            pdf = out.toByteArray();
        }
        List<Object> pages = invokePrivatePages("parsePdfWithPdfBox", pdf);
        assertEquals(1, pages.size());
        assertTrue(textOf(pages.get(0)).isBlank());
    }

    @Test
    void dispatcherRoutesByEngineAndKeepsLiteParseSemantics() throws Exception {
        byte[] pdf = twoPagePdf("route check R1", "route check R2");
        List<Object> pages = invokePrivatePages("parsePdfPages", pdf);
        assertEquals(2, pages.size());

        // engine=未知值仍走 liteparse 路径并保持原有报错语义
        ParseService bogus = parseServiceInstance();
        ReflectionTestUtils.setField(bogus, "pdfParsingEngine", "bogus-engine");
        Method lite = ParseService.class.getDeclaredMethod("parsePdfWithLiteParse", InputStream.class);
        lite.setAccessible(true);
        Exception thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> lite.invoke(bogus, new ByteArrayInputStream(pdf)));
        assertTrue(thrown.getCause().getMessage().contains("不支持的 PDF 解析引擎"), thrown.getCause().getMessage());
    }
}
