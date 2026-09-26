package com.gsa.analytics;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.*;
import java.io.*;
import jxl.*;
import jxl.write.*;

public class ReportsTest {
    private Reports.Invoice bill(String ref,int days,double balance) { return new Reports.Invoice("Alpha","General",ref,LocalDate.of(2026,1,1).plusDays(days),1000,balance); }
    @Test public void slabEdgesAndFutureBills() {
        int[] ages={0,15,16,30,31,45,46,60,61,90,91,120,121,150,151};
        int[] expected={0,0,1,1,2,2,3,3,4,4,5,5,6,6,7};
        for(int i=0;i<ages.length;i++) assertEquals(expected[i],Reports.slab(ages[i]));
        assertEquals(0,Reports.age(bill("future",20,10),LocalDate.of(2026,1,1)));
        assertNull(Reports.date("31/02/2026")); assertEquals(LocalDate.of(2026,2,1),Reports.date("01/02/2026"));
    }
    @Test public void strictThresholdsAndRepeatedPairTotals() {
        Reports.Options o=new Reports.Options(); o.asOn=LocalDate.of(2026,6,1);
        List<Reports.Invoice> bills=Arrays.asList(bill("old",0,501),bill("exact-gap",60,20),bill("new",61,30),bill("new2",62,40),bill("paid",63,0));
        Reports.Table t=Reports.build(3,bills,o); assertEquals(2,t.rows.size()); assertEquals(Reports.money(1002),t.total.cells[5]);
        o.threshold=501; assertEquals(0,Reports.build(3,bills,o).rows.size());
    }
    @Test public void onlyOldestBalanceAndAllLaterBillsCountForParty() {
        Reports.Options o=new Reports.Options();
        Reports.Table t=Reports.build(4,Arrays.asList(bill("old",0,600),bill("same-day",0,200),bill("young",10,300),bill("qualifying",61,400)),o);
        assertEquals(1,t.rows.size()); assertEquals(Reports.money(600),t.total.cells[3]); assertEquals(Reports.money(900),t.total.cells[4]);
        assertEquals(4,t.rows.get(0).details.size());
        assertEquals(0,Reports.build(4,Arrays.asList(bill("small-oldest",0,10),bill("big",1,900),bill("new",70,20)),o).rows.size());
    }
    @Test public void balanceSearchKeepsWholeReportDenominator() {
        Reports.Options o=new Reports.Options(); o.asOn=LocalDate.of(2026,1,20); o.query="S1";
        Reports.Table t=Reports.build(0,Arrays.asList(bill("old",0,600),bill("new",15,400)),o);
        assertEquals(1,t.rows.size()); assertEquals("40.00%",t.rows.get(0).cells[3]); assertEquals(Reports.money(1000),t.total.cells[2]);
    }
    @Test public void filteredCustomerCountsAndTotals() {
        Reports.Options o=new Reports.Options(); o.asOn=LocalDate.of(2026,1,20); o.slab=0;
        Reports.Table t=Reports.build(2,Arrays.asList(bill("old",0,600),bill("new",15,400),bill("paid",16,0)),o);
        assertEquals("1",t.rows.get(0).cells[2]); assertEquals(Reports.money(400),t.total.cells[6]);
        t.sort(6,true); assertEquals("TOTAL",t.total.cells[1]);
    }
    @Test public void pdfParsingAndReportDates() throws Exception {
        String raw="Sale Aging Report 30/06/2026\nGeneral Alpha & Sons 0 0 0 0 0 0 Ref No\nSALE-FY/25-26/1 01/01/\n2026 15/01/2026 166 1,000.00 600.00\nGeneral Beta Ref No\nS-Y/25-26/2 02/01/2026 16/01/2026 165 400 200";
        ReportImport.Result result=ReportImport.pdfText(raw,"report.pdf");
        assertEquals(2,result.invoices.size()); assertEquals("Alpha & Sons",result.invoices.get(0).customer); assertEquals("Beta",result.invoices.get(1).customer);
        assertEquals(LocalDate.of(2026,6,30),result.reportDate);
        assertEquals(LocalDate.of(2026,7,31),Reports.reportDate("Sale_to_31-07-2026.pdf",raw));
    }
    @Test public void structuredXlsImport() throws Exception {
        File file=File.createTempFile("gsa-test-",".xls");
        try {
            WritableWorkbook book=Workbook.createWorkbook(file); WritableSheet sheet=book.createSheet("Outstanding Sale Invoices",0);
            sheet.addCell(new Label(0,0,"Sale Aging Report 30/06/2026")); sheet.addCell(new Label(0,1,"General")); sheet.addCell(new Label(0,2,"Alpha"));
            sheet.addCell(new Label(0,3,"SALE-FY/25-26/1")); sheet.addCell(new Label(1,3,"01/01/2026")); sheet.addCell(new jxl.write.Number(4,3,1000)); sheet.addCell(new jxl.write.Number(5,3,600)); book.write(); book.close();
            ReportImport.Result result=ReportImport.excel(file,"report.xls"); assertEquals(1,result.invoices.size()); assertEquals("Alpha",result.invoices.get(0).customer); assertEquals(600,result.invoices.get(0).balance,0);
        } finally { file.delete(); }
    }
}
