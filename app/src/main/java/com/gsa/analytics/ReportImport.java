package com.gsa.analytics;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import jxl.*;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

public final class ReportImport {
    private ReportImport() {}
    public static final class Result {
        public final List<Reports.Invoice> invoices;
        public final LocalDate reportDate;
        Result(List<Reports.Invoice> invoices, LocalDate reportDate) { this.invoices=invoices; this.reportDate=reportDate; }
    }
    public static Result read(File file, String name) throws Exception {
        byte[] signature=new byte[5];
        try(InputStream in=new FileInputStream(file)) { if(in.read(signature)<5) throw new IOException("The selected report is empty or incomplete."); }
        if(new String(signature,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
            try(PDDocument document=PDDocument.load(file)) { return pdfText(new PDFTextStripper().getText(document),name); }
        }
        if((signature[0]&255)!=0xD0 || (signature[1]&255)!=0xCF) throw new IOException("Choose a Vyapar .xls or text-based PDF report. XLSX and scanned PDFs are not supported.");
        return excel(file,name);
    }
    public static Result excel(File file, String name) throws Exception {
        WorkbookSettings settings=new WorkbookSettings(); settings.setSuppressWarnings(true);
        Workbook book=Workbook.getWorkbook(file,settings);
        try {
            Sheet sheet=book.getSheet("Outstanding Sale Invoices");
            if(sheet==null) throw new IOException("Excel must include 'Outstanding Sale Invoices'.");
            List<Reports.Invoice> invoices=new ArrayList<>(); String customer="", group="";
            StringBuilder top=new StringBuilder();
            for(int row=0;row<sheet.getRows();row++) {
                String[] v=new String[7];
                for(int col=0;col<7;col++) {
                    if(col>=sheet.getColumns()) { v[col]=""; continue; }
                    Cell cell=sheet.getCell(col,row);
                    v[col]=cell instanceof NumberCell ? Double.toString(((NumberCell)cell).getValue()) : cell.getContents();
                }
                if(row<5) top.append(v[0]).append(' ');
                String first=v[0].trim(); LocalDate date=Reports.date(v[1]);
                if(!first.isEmpty() && date!=null && !v[4].trim().isEmpty()) invoices.add(new Reports.Invoice(customer.isEmpty()?"Unidentified customer":customer,group,first,date,Reports.number(v[4]),Reports.number(v[5])));
                else if(first.equalsIgnoreCase("general")) group=first;
                else if(!first.isEmpty() && !first.equalsIgnoreCase("reference no") && !first.equalsIgnoreCase("totals") && !first.toLowerCase(Locale.ROOT).contains("days")) customer=first;
            }
            if(invoices.isEmpty()) throw new IOException("No invoice records found. Choose the Vyapar Outstanding Sale Invoices report with text billing dates.");
            return new Result(invoices,Reports.reportDate(name,top.toString()));
        } finally { book.close(); }
    }
    public static Result pdfText(String raw, String name) throws IOException {
        raw=raw.replaceAll("(\\d{2}/\\d{2}/)\\s*\\n\\s*(\\d{4})", "$1$2");
        Pattern pattern=Pattern.compile("((?:SALE-FY|S-Y)/\\d{2}-\\d{2}/\\d+)\\s+(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2}/\\d{2}/\\d{4})\\s+\\d+\\s+([\\d,.]+)\\s+([\\d,.]+)");
        Matcher found=pattern.matcher(raw); String customer="Unidentified customer"; int cursor=0;
        List<Reports.Invoice> invoices=new ArrayList<>();
        while(found.find()) {
            String chunk=raw.substring(cursor,found.start()); cursor=found.end();
            Matcher candidates=Pattern.compile("General\\s+(.+?)\\s+Ref No",Pattern.DOTALL).matcher(chunk);
            String candidate=null; while(candidates.find()) candidate=candidates.group(1);
            if(candidate!=null) {
                customer=candidate.replaceAll("\\s+", " ").trim();
                String cleaned=customer.replaceAll("\\s+(?:-?[\\d,.]+\\s+){5}-?[\\d,.]+$", "").trim();
                if(!cleaned.isEmpty()) customer=cleaned;
            }
            invoices.add(new Reports.Invoice(customer,"General",found.group(1),Reports.date(found.group(2)),Reports.number(found.group(4)),Reports.number(found.group(5))));
        }
        if(invoices.isEmpty()) throw new IOException("No invoice records found in PDF. Use the Excel report for exact import. Scanned PDFs require OCR and are not supported.");
        return new Result(invoices,Reports.reportDate(name,raw));
    }
}
