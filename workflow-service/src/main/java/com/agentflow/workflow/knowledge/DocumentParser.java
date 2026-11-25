package com.agentflow.workflow.knowledge;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

/**
 * 文档正文提取：PDF / Word(docx) / PPT(pptx) / Excel(xlsx) / 纯文本。
 *
 * <p>只做文本提取，不做 OCR——扫描件 / 图片型 PDF 没有文本层，提取结果为空或近乎为空，
 * 由调用方给出明确提示，不静默吞掉。解析失败（文件损坏 / 加密 / 不支持的类型）一律
 * 抛业务异常，不把「解析不了」伪装成「文档是空的」。
 */
@Component
public class DocumentParser {

    public record ParsedDocument(String text, String source) {
    }

    public ParsedDocument parse(String filename, byte[] bytes) {
        String ext = extensionOf(filename);
        String text = switch (ext) {
            case "pdf" -> extractPdf(bytes);
            case "docx" -> extractDocx(bytes);
            case "pptx" -> extractPptx(bytes);
            case "xlsx" -> extractXlsx(bytes);
            case "txt", "md" -> new String(bytes, StandardCharsets.UTF_8);
            default -> throw invalid("不支持的文件类型 ." + ext + "，支持 pdf/docx/pptx/xlsx/txt/md");
        };
        return new ParsedDocument(text, ext.toUpperCase(Locale.ROOT));
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException e) {
            throw invalid("PDF 解析失败：" + e.getMessage());
        }
    }

    private static String extractDocx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
                XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException e) {
            throw invalid("Word 解析失败：" + e.getMessage());
        }
    }

    private static String extractPptx(byte[] bytes) {
        try (XMLSlideShow slides = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder out = new StringBuilder();
            slides.getSlides().forEach(slide -> slide.getShapes().forEach(shape -> {
                if (shape instanceof XSLFTextShape textShape) {
                    out.append(textShape.getText()).append('\n');
                }
            }));
            return out.toString();
        } catch (IOException e) {
            throw invalid("PPT 解析失败：" + e.getMessage());
        }
    }

    private static String extractXlsx(byte[] bytes) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder out = new StringBuilder();
            for (Sheet sheet : workbook) {
                out.append("【").append(sheet.getSheetName()).append("】\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell);
                        if (!value.isBlank()) {
                            out.append(value).append('\t');
                        }
                    }
                    out.append('\n');
                }
            }
            return out.toString();
        } catch (IOException e) {
            throw invalid("Excel 解析失败：" + e.getMessage());
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.COM_VALIDATION_001, message);
    }
}
