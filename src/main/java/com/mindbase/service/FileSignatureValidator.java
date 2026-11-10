package com.mindbase.service;

import com.mindbase.exception.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 上传文件签名校验器（task-0018）。
 *
 * <p>此前上传只按扩展名放行（{@link FileTypeValidationService}），改个扩展名就能把任意
 * 二进制送进 Tika/OCR 解析链路。本类对首分片读到的文件头（magic number）做比对：
 * 签名与扩展名不符时抛 {@link CustomException}（HTTP 400），消息只含期望类型与扩展名，
 * 绝不携带文件内容。
 *
 * <p>规则：
 * <ul>
 *   <li>pdf：{@code %PDF-}</li>
 *   <li>docx/xlsx/pptx/odt/ods/odp/epub：{@code PK\x03\x04}（ZIP local file header）</li>
 *   <li>doc/xls/ppt：OLE Compound File {@code D0 CF 11 E0 A1 B1 1A E1}</li>
 *   <li>rtf：{@code {\rtf}</li>
 *   <li>txt/md/csv/json/html/htm/xml：不做二进制签名，但拒绝包含 NUL 字节（疑似伪装的二进制）</li>
 *   <li>其余扩展名（如 pages/numbers/keynote）：无签名规则，不做二进制判定</li>
 * </ul>
 *
 * <p>头部不足 {@value #MIN_SIGNATURE_HEAD_BYTES} 字节时无法可靠比对签名（OLE 签名恰为
 * 8 字节），按扩展名放行并记 warn；完全读不到字节时同样按扩展名放行并记 warn，
 * 绝不让上传路径抛 500。
 */
public final class FileSignatureValidator {

    private static final Logger logger = LoggerFactory.getLogger(FileSignatureValidator.class);

    /** 判定二进制签名所需的最小头部字节数（OLE 签名恰好 8 字节）。 */
    private static final int MIN_SIGNATURE_HEAD_BYTES = 8;

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_LOCAL_FILE_HEADER = {0x50, 0x4B, 0x03, 0x04}; // PK\x03\x04
    private static final byte[] OLE_COMPOUND_FILE = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] RTF_SIGNATURE = "{\\rtf".getBytes(StandardCharsets.US_ASCII);

    private static final Set<String> ZIP_BASED_EXTENSIONS =
            Set.of("docx", "xlsx", "pptx", "odt", "ods", "odp", "epub");
    private static final Set<String> OLE_BASED_EXTENSIONS = Set.of("doc", "xls", "ppt");
    private static final Set<String> TEXT_EXTENSIONS =
            Set.of("txt", "md", "csv", "json", "html", "htm", "xml");

    /** 与 FileTypeValidationService 的类型描述保持一致，用于拼装面向用户的拒绝消息。 */
    private static final Map<String, String> EXPECTED_TYPE_DESCRIPTIONS = Map.ofEntries(
            Map.entry("pdf", "PDF文档"),
            Map.entry("doc", "Word文档"), Map.entry("docx", "Word文档"),
            Map.entry("xls", "Excel表格"), Map.entry("xlsx", "Excel表格"),
            Map.entry("ppt", "PowerPoint演示文稿"), Map.entry("pptx", "PowerPoint演示文稿"),
            Map.entry("odt", "OpenDocument文本"), Map.entry("ods", "OpenDocument表格"),
            Map.entry("odp", "OpenDocument演示文稿"),
            Map.entry("epub", "EPUB电子书"),
            Map.entry("rtf", "富文本文档"),
            Map.entry("txt", "文本文件"), Map.entry("md", "Markdown文档"),
            Map.entry("csv", "CSV文件"), Map.entry("json", "JSON文件"),
            Map.entry("html", "HTML文档"), Map.entry("htm", "HTML文档"),
            Map.entry("xml", "XML文档"));

    private FileSignatureValidator() {
    }

    /**
     * 校验文件头签名是否与扩展名匹配。
     *
     * <p>调用时机：仅首个分片（chunkIndex==0）且扩展名校验通过之后；后续分片不重复校验。
     *
     * @param fileName 上传文件名（用于提取扩展名；文件内容不进入任何日志或异常消息）
     * @param head     文件头字节（调用方用 {@code file.getInputStream().readNBytes(8)} 读取，
     *                 可为 null 表示读不到）
     * @throws CustomException 签名与扩展名不匹配或文本内容包含 NUL 字节时，
     *                         HTTP 400，消息含期望类型与扩展名、不含文件内容
     */
    public static void validate(String fileName, byte[] head) {
        String extension = extractExtension(fileName);
        if (extension == null) {
            // 扩展名缺失或非法由 FileTypeValidationService 把关并已在此前拦截，这里无事可校验
            return;
        }
        if (head == null || head.length == 0) {
            logger.warn("文件头字节不可读，按扩展名放行: extension={}", extension);
            return;
        }
        if (TEXT_EXTENSIONS.contains(extension)) {
            rejectTextWithNul(extension, head);
            return;
        }
        if (head.length < MIN_SIGNATURE_HEAD_BYTES) {
            logger.warn("文件头不足 {} 字节，跳过签名校验并按扩展名放行: extension={}, headLen={}",
                    MIN_SIGNATURE_HEAD_BYTES, extension, head.length);
            return;
        }
        byte[] expectedSignature = expectedSignatureOf(extension);
        if (expectedSignature == null) {
            // 该扩展名无签名规则（如 pages/numbers/keynote），不做二进制判定
            return;
        }
        if (!startsWith(head, expectedSignature)) {
            throw new CustomException(String.format(
                    "文件签名校验失败：扩展名 .%s 期望 %s（%s）文件签名，实际内容不匹配，请确认文件真实类型",
                    extension, EXPECTED_TYPE_DESCRIPTIONS.get(extension), describeSignature(extension)),
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 文本类扩展名不做二进制签名，但拒绝 NUL 字节（对读到的头部全部生效，不受 8 字节下限约束）。
     */
    private static void rejectTextWithNul(String extension, byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                throw new CustomException(String.format(
                        "文件签名校验失败：扩展名 .%s 为文本类型（%s），内容不应包含 NUL 字节，疑似伪装的二进制文件",
                        extension, EXPECTED_TYPE_DESCRIPTIONS.get(extension)),
                        HttpStatus.BAD_REQUEST);
            }
        }
    }

    private static byte[] expectedSignatureOf(String extension) {
        if ("pdf".equals(extension)) {
            return PDF_SIGNATURE;
        }
        if (ZIP_BASED_EXTENSIONS.contains(extension)) {
            return ZIP_LOCAL_FILE_HEADER;
        }
        if (OLE_BASED_EXTENSIONS.contains(extension)) {
            return OLE_COMPOUND_FILE;
        }
        if ("rtf".equals(extension)) {
            return RTF_SIGNATURE;
        }
        return null;
    }

    private static String describeSignature(String extension) {
        if ("pdf".equals(extension)) {
            return "%PDF-";
        }
        if (ZIP_BASED_EXTENSIONS.contains(extension)) {
            return "PK\\x03\\x04";
        }
        if (OLE_BASED_EXTENSIONS.contains(extension)) {
            return "D0 CF 11 E0 A1 B1 1A E1";
        }
        if ("rtf".equals(extension)) {
            return "{\\rtf";
        }
        return "";
    }

    private static boolean startsWith(byte[] head, byte[] signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (head[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String extractExtension(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
