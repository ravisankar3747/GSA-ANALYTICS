package com.gsa.analytics;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private List<Reports.Invoice> fixture() {
        return Arrays.asList(new Reports.Invoice("Alpha Distributors","General","SALE-FY/25-26/1001",LocalDate.of(2026,1,1),2000,1500),new Reports.Invoice("Alpha Distributors","General","SALE-FY/25-26/1002",LocalDate.of(2026,6,20),1000,700),new Reports.Invoice("Beta Trading","General","S-Y/25-26/22",LocalDate.of(2026,5,25),800,650));
    }
    @Test public void reportsPersistRotateAndRenderOnDevice() throws Exception {
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().putString("asOn","2026-07-01").commit();
        SessionStore.save(context,new SessionStore.Session("V7 test report.xls","2026-07-01",fixture()));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            awaitRows(scenario);
            for(int report=0;report<5;report++) {
                final int position=report;
                scenario.onActivity(a->((Spinner)find(a.getWindow().getDecorView(),"Report")).setSelection(position));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync(); awaitRows(scenario);
                scenario.onActivity(a->assertEquals(new int[]{9,4,4,2,2}[position],list(a.getWindow().getDecorView()).getCount()));
                screenshot("report-"+report+".png");
            }
            scenario.recreate(); awaitRows(scenario);
            scenario.onActivity(a->assertEquals(4,((Spinner)find(a.getWindow().getDecorView(),"Report")).getSelectedItemPosition()));
            screenshot("restored-report.png");
        }
    }
    @Test public void importsTextPdfOnAndroid() throws Exception {
        PDFBoxResourceLoader.init(context);
        File file=new File(context.getCacheDir(),"Sale_to_30-06-2026.pdf");
        android.graphics.pdf.PdfDocument pdf=new android.graphics.pdf.PdfDocument();
        try {
            android.graphics.pdf.PdfDocument.Page page=pdf.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(842,595,1).create());
            android.graphics.Paint paint=new android.graphics.Paint(); paint.setTextSize(12);
            page.getCanvas().drawText("General Alpha Ref No",20,30,paint);
            page.getCanvas().drawText("SALE-FY/25-26/1 01/01/2026 15/01/2026 166 1000.00 600.00",20,50,paint);
            pdf.finishPage(page);
            try(OutputStream output=new FileOutputStream(file)) { pdf.writeTo(output); }
        } finally { pdf.close(); }
        ReportImport.Result result=ReportImport.read(file,file.getName());
        assertEquals(1,result.invoices.size()); assertEquals("Alpha",result.invoices.get(0).customer); assertEquals(600,result.invoices.get(0).balance,0);
        assertEquals(LocalDate.of(2026,6,30),result.reportDate); file.delete();
    }
    private ListView list(View view) {
        if(view instanceof ListView) return (ListView)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) { ListView found=list(((ViewGroup)view).getChildAt(i)); if(found!=null) return found; }
        return null;
    }
    @Test public void pdfExportWrapsAndPaginates() throws Exception {
        PDFBoxResourceLoader.init(context); Reports.Options options=new Reports.Options(); options.asOn=LocalDate.of(2026,7,1);
        List<Reports.Invoice> invoices=new ArrayList<>();
        for(int n=0;n<100;n++) invoices.add(new Reports.Invoice("Long customer name with several words for wrapping "+n,"General","SALE-FY/25-26/"+n,LocalDate.of(2026,1,1),1000,600));
        File file=new File(context.getExternalFilesDir(null),"export-test.pdf");
        try(OutputStream output=new FileOutputStream(file)) { PdfExport.write(output,Reports.build(1,invoices,options),options.asOn); }
        try(PDDocument document=PDDocument.load(file)) {
            assertTrue(document.getNumberOfPages()>1); String text=new PDFTextStripper().getText(document);
            assertTrue(text.contains("SALE-FY/25-26/99")); assertTrue(text.contains("TOTAL")); assertTrue(text.contains("60,000.00"));
        }
        Reports.Table detail=Reports.details(fixture().subList(0,2),true);
        assertEquals("OLDEST OLD BILL",detail.rows.get(0).cells[1]);
    }
    private View find(View view,String label) {
        if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) { View found=find(((ViewGroup)view).getChildAt(i),label); if(found!=null) return found; }
        return null;
    }
    private boolean containsRows(View view) {
        if(view instanceof ListView) return ((ListView)view).getCount()>1;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) if(containsRows(((ViewGroup)view).getChildAt(i))) return true;
        return false;
    }
    private void awaitRows(ActivityScenario<MainActivity> scenario) throws Exception {
        AtomicBoolean found=new AtomicBoolean();
        for(int attempt=0;attempt<100;attempt++) { scenario.onActivity(a->found.set(containsRows(a.getWindow().getDecorView()))); if(found.get()) { Thread.sleep(400); return; } Thread.sleep(100); }
        fail("Report table did not appear");
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),name))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); } finally { bitmap.recycle(); }
    }
}

