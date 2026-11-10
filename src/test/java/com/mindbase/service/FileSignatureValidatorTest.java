package com.mindbase.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mindbase.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0018：上传文件签名校验测试。
 *
 * <p>覆盖 7 组：合法 pdf / 非法 pdf / 合法 docx（含非法 docx）/
 * 合法 doc-OLE（含非法 doc）/ 合法 rtf（含非法 rtf）/ 短文件（含读不到字节）/ txt 含 NUL。
 * 全部为纯内存 fixture，不依赖任何外部服务。
 */
class FileSignatureValidatorTest {

    private static final byte[] PDF_HEAD = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_HEAD = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00}; // PK\x03\x04 + …
    private static final byte[] OLE_HEAD = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] RTF_HEAD = "{\\rtf1\\ansi".getBytes(StandardCharsets.US_ASCII);

    private ListAppender<ILoggingEvent> warnAppender;
    private Logger validatorLogger;

    @BeforeEach
    void attachWarnAppender() {
        validatorLogger = (Logger) LoggerFactory.getLogger(FileSignatureValidator.class);
        warnAppender = new ListAppender<>();
        warnAppender.start();
        validatorLogger.addAppender(warnAppender);
    }

    @AfterEach
    void detachWarnAppender() {
        validatorLogger.detachAppender(warnAppender);
        warnAppender.stop();
    }

    private boolean hasWarnContaining(String fragment) {
        return warnAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && String.valueOf(event.getFormattedMessage()).contains(fragment));
    }

    // 组1：合法 pdf 放行（合法 PDF 上传路径不被误杀）
    @Test
    void group1_legalPdfHeadPasses() {
        assertDoesNotThrow(() -> FileSignatureValidator.validate("report.pdf", PDF_HEAD));
        assertDoesNotThrow(() -> FileSignatureValidator.validate("REPORT.PDF", PDF_HEAD));
    }

    // 组2：非法 pdf（改扩展名的伪装文件）被 400 拒绝，消息含期望类型与扩展名、不含文件内容
    @Test
    void group2_disguisedPdfIsRejectedWithBadRequest() {
        byte[] pngHead = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        CustomException e = assertThrows(CustomException.class,
                () -> FileSignatureValidator.validate("report.pdf", pngHead));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains(".pdf"), "消息应含扩展名: " + e.getMessage());
        assertTrue(e.getMessage().contains("PDF文档"), "消息应含期望类型: " + e.getMessage());

        // 用可读 ASCII 假头部直接验证：异常消息绝不携带文件内容
        byte[] asciiFakeHead = "this is definitely not a pdf".getBytes(StandardCharsets.US_ASCII);
        CustomException e2 = assertThrows(CustomException.class,
                () -> FileSignatureValidator.validate("report.pdf", asciiFakeHead));
        assertEquals(HttpStatus.BAD_REQUEST, e2.getStatus());
        assertFalse(e2.getMessage().contains("this is definitely not a pdf"),
                "消息不得包含文件内容: " + e2.getMessage());
    }

    // 组3：合法 docx（ZIP 头）放行；伪装 docx 被 400 拒绝
    @Test
    void group3_legalDocxPassesAndDisguisedDocxRejected() {
        assertDoesNotThrow(() -> FileSignatureValidator.validate("doc.docx", ZIP_HEAD));

        CustomException e = assertThrows(CustomException.class,
                () -> FileSignatureValidator.validate("doc.docx", OLE_HEAD));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains(".docx"));
        assertTrue(e.getMessage().contains("Word文档"));

        // 其余 ZIP 系扩展名同规则
        for (String name : new String[]{"t.xlsx", "t.pptx", "t.odt", "t.ods", "t.odp", "t.epub"}) {
            assertDoesNotThrow(() -> FileSignatureValidator.validate(name, ZIP_HEAD), name);
        }
    }

    // 组4：合法 doc（OLE Compound File 8 字节签名）放行；伪装 doc 被 400 拒绝
    @Test
    void group4_legalDocOleHeadPassesAndDisguisedDocRejected() {
        assertDoesNotThrow(() -> FileSignatureValidator.validate("old.doc", OLE_HEAD));

        CustomException e = assertThrows(CustomException.class,
                () -> FileSignatureValidator.validate("old.doc", PDF_HEAD));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains(".doc"));
        assertTrue(e.getMessage().contains("Word文档"));

        // 其余 OLE 系扩展名同规则
        assertDoesNotThrow(() -> FileSignatureValidator.validate("t.xls", OLE_HEAD));
        assertDoesNotThrow(() -> FileSignatureValidator.validate("t.ppt", OLE_HEAD));
    }

    // 组5：合法 rtf 放行；伪装 rtf 被 400 拒绝
    @Test
    void group5_legalRtfPassesAndDisguisedRtfRejected() {
        assertDoesNotThrow(() -> FileSignatureValidator.validate("note.rtf", RTF_HEAD));

        CustomException e = assertThrows(CustomException.class,
                () -> FileSignatureValidator.validate("note.rtf", PDF_HEAD));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains(".rtf"));
        assertTrue(e.getMessage().contains("富文本文档"));
    }

    // 组6：短文件（头部不足 8 字节 / 空 / 读不到）跳过签名校验按扩展名放行，并记 warn，不抛异常
    @Test
    void group6_shortOrUnreadableHeadPassesWithWarn() {
        // 不足 8 字节：无法可靠比对签名，按扩展名放行
        byte[] shortPdfHead = "%PDF".getBytes(StandardCharsets.US_ASCII); // 4 字节
        assertDoesNotThrow(() -> FileSignatureValidator.validate("short.pdf", shortPdfHead));
        assertDoesNotThrow(() -> FileSignatureValidator.validate("short.docx", new byte[]{0x50, 0x4B}));

        // 完全读不到字节：按扩展名放行并记 warn
        assertDoesNotThrow(() -> FileSignatureValidator.validate("unreadable.pdf", null));
        assertDoesNotThrow(() -> FileSignatureValidator.validate("unreadable.pdf", new byte[0]));

        assertTrue(hasWarnContaining("按扩展名放行"),
                "读不到字节应记 warn，实际: " + warnAppender.list);
    }

    // 组7：txt 含 NUL 字节被 400 拒绝；干净文本放行
    @Test
    void group7_txtWithNulByteIsRejected() {
        byte[] nulHead = "hello world\0".getBytes(StandardCharsets.US_ASCII);
        CustomException e = assertThrows(CustomException.class,
                () -> FileSignatureValidator.validate("notes.txt", nulHead));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains(".txt"));
        assertTrue(e.getMessage().contains("文本文件"));
        // 消息不携带文件内容
        assertFalse(e.getMessage().contains("hello world"));

        // 干净文本放行
        assertDoesNotThrow(() -> FileSignatureValidator.validate(
                "notes.txt", "hello world".getBytes(StandardCharsets.US_ASCII)));

        // 其余文本类扩展名同样拒绝 NUL
        for (String name : new String[]{"t.md", "t.csv", "t.json", "t.html", "t.htm", "t.xml"}) {
            CustomException te = assertThrows(CustomException.class,
                    () -> FileSignatureValidator.validate(name, nulHead), name);
            assertEquals(HttpStatus.BAD_REQUEST, te.getStatus(), name);
        }
    }

    // 补充：无签名规则的受支持扩展名（pages/numbers/keynote）不做二进制判定
    @Test
    void extensionsWithoutSignatureRulePassThrough() {
        assertDoesNotThrow(() -> FileSignatureValidator.validate("t.pages", ZIP_HEAD));
        assertDoesNotThrow(() -> FileSignatureValidator.validate("t.keynote", PDF_HEAD));
    }

    // 补充：扩展名缺失/非法时无事可校验（由 FileTypeValidationService 把关）
    @Test
    void missingExtensionIsNotValidatedHere() {
        assertDoesNotThrow(() -> FileSignatureValidator.validate("noext", "whatever!".getBytes(StandardCharsets.US_ASCII)));
        assertDoesNotThrow(() -> FileSignatureValidator.validate(null, OLE_HEAD));
    }
}
