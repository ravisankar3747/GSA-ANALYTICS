package com.gsa.analytics;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SessionStore {
    static final class Session {
        final String name, date;
        final List<Reports.Invoice> invoices;
        Session(String name,String date,List<Reports.Invoice> invoices) { this.name=name; this.date=date; this.invoices=invoices; }
    }
    static void save(Context context,Session session) throws Exception {
        JSONArray rows=new JSONArray();
        for(Reports.Invoice i:session.invoices) rows.put(new JSONArray().put(i.customer).put(i.group).put(i.reference).put(i.date==null?"":i.date.toString()).put(i.total).put(i.balance));
        JSONObject json=new JSONObject().put("name",session.name).put("date",session.date).put("rows",rows);
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"session.json")); FileOutputStream out=null;
        try { out=file.startWrite(); out.write(json.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch(Exception e) { if(out!=null) file.failWrite(out); throw e; }
    }
    static Session load(Context context) throws Exception {
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"session.json"));
        if(!file.getBaseFile().exists()) return null;
        JSONObject json=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));
        JSONArray rows=json.getJSONArray("rows"); List<Reports.Invoice> invoices=new ArrayList<>();
        for(int n=0;n<rows.length();n++) { JSONArray r=rows.getJSONArray(n); invoices.add(new Reports.Invoice(r.getString(0),r.getString(1),r.getString(2),Reports.date(r.getString(3)),r.getDouble(4),r.getDouble(5))); }
        return new Session(json.getString("name"),json.getString("date"),invoices);
    }
    static void clear(Context context) { new AtomicFile(new File(context.getFilesDir(),"session.json")).delete(); }
}
