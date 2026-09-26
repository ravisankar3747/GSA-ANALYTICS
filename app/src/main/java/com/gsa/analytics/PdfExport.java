package com.gsa.analytics;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import java.io.*;
import java.time.LocalDate;
import java.util.*;

/** Landscape A4 export with wrapped cells and repeated headings on every page. */
public final class PdfExport {
    private PdfExport() {}
    public static void write(OutputStream output, Reports.Table table, LocalDate asOn) throws IOException {
        PdfDocument pdf=new PdfDocument();
        PdfDocument.Page page=null;
        try {
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
            float width=774f/table.headers.length; int pageNumber=1;
            page=start(pdf,table,asOn,pageNumber,paint,width); float y=116;
            for(Reports.Row row:table.all()) {
                paint.setTextSize(8); paint.setTypeface(Typeface.create("sans-serif",row==table.total?Typeface.BOLD:Typeface.NORMAL));
                List<List<String>> lines=new ArrayList<>(); int count=1;
                for(String cell:row.cells) { List<String> wrapped=wrap(cell,paint,width-8); lines.add(wrapped); count=Math.max(count,wrapped.size()); }
                int consumed=0;
                while(consumed<count) {
                    int fits=(int)((555-y-10)/11);
                    if(fits<1) { pdf.finishPage(page); page=start(pdf,table,asOn,++pageNumber,paint,width); y=116; fits=(int)((555-y-10)/11); }
                    int take=Math.min(count-consumed,fits); float height=take*11+10;
                    for(int c=0;c<row.cells.length;c++) {
                        float x=34+c*width;
                        paint.setColor(row==table.total?Color.rgb(221,240,236):Color.WHITE); paint.setStyle(Paint.Style.FILL); page.getCanvas().drawRect(x,y,x+width,y+height,paint);
                        paint.setColor(Color.LTGRAY); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(.4f); page.getCanvas().drawRect(x,y,x+width,y+height,paint); paint.setStyle(Paint.Style.FILL);
                        paint.setColor(Color.rgb(30,40,38)); paint.setTextSize(8); paint.setTypeface(Typeface.create("sans-serif",row==table.total?Typeface.BOLD:Typeface.NORMAL));
                        for(int line=0;line<take && consumed+line<lines.get(c).size();line++) page.getCanvas().drawText(lines.get(c).get(consumed+line),x+4,y+12+line*11,paint);
                    }
                    consumed+=take; y+=height;
                }
            }
            pdf.finishPage(page); page=null; pdf.writeTo(output);
        } finally { if(page!=null) pdf.finishPage(page); pdf.close(); }
    }
    private static PdfDocument.Page start(PdfDocument pdf,Reports.Table table,LocalDate date,int number,Paint paint,float width) {
        PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(842,595,number).create()); Canvas canvas=page.getCanvas();
        paint.setColor(Color.rgb(18,50,44)); paint.setTextSize(18); paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD)); canvas.drawText("GSA-ANALYTICS",34,35,paint);
        paint.setTextSize(11); canvas.drawText(table.title,34,55,paint);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL)); paint.setTextSize(9); canvas.drawText("Ageing from billing date only. As on: "+date.format(Reports.DATE),34,72,paint);
        canvas.drawText("Page "+number,748,578,paint);
        paint.setColor(Color.rgb(8,100,90)); canvas.drawRect(34,84,808,116,paint);
        paint.setColor(Color.WHITE); paint.setTextSize(8);
        for(int i=0;i<table.headers.length;i++) { List<String> lines=wrap(table.headers[i],paint,width-8); for(int l=0;l<lines.size();l++) canvas.drawText(lines.get(l),38+i*width,96+l*10,paint); }
        return page;
    }
    private static List<String> wrap(String text,Paint paint,float width) {
        List<String> result=new ArrayList<>();
        for(String paragraph:text.split("\\n",-1)) {
            String rest=paragraph;
            while(!rest.isEmpty()) { int n=paint.breakText(rest,true,width,null); n=Math.max(1,n);
                if(n<rest.length()) { int space=rest.lastIndexOf(' ',n); if(space>0) n=space; }
                result.add(rest.substring(0,n)); rest=rest.substring(n).trim();
            }
        }
        if(result.isEmpty()) result.add(""); return result;
    }
}

