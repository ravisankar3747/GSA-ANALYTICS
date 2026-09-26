package com.gsa.analytics;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int IMPORT=10, EXPORT=11, TEAL=0xff087f73, INK=0xff20352f;
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private List<Reports.Invoice> invoices=new ArrayList<>();
    private Reports.Table table, pendingExport;
    private LocalDate asOn=LocalDate.now(), exportDate;
    private int report=0, sortColumn=-1, generation=0;
    private boolean reverse=false, restoring=false, busy=false;
    private String filename="No report imported";
    private final String[] queries={"","","","",""};
    private final int[] slabs={-1,-1,-1,-1,-1};
    private final double[] thresholds={500,500};
    private final int[] gaps={60,60};
    private int customerSort=0;
    private LinearLayout root, tableHost, filterBar;
    private TextView source, status;
    private Button dateButton;
    private EditText search;
    private ImageButton importButton, exportButton, settingsButton;
    private Spinner reports, slabPicker, sortPicker;
    private ProgressBar progress;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); PDFBoxResourceLoader.init(getApplicationContext()); prefs=getSharedPreferences("settings",MODE_PRIVATE);
        LocalDate stored=Reports.date(prefs.getString("asOn","")); if(stored!=null) asOn=stored;
        report=prefs.getInt("report",0); customerSort=prefs.getInt("customerSort",0);
        for(int i=0;i<5;i++) { queries[i]=prefs.getString("q"+i,""); slabs[i]=prefs.getInt("s"+i,-1); }
        for(int i=0;i<2;i++) { thresholds[i]=Reports.number(prefs.getString("threshold"+i,"500")); gaps[i]=prefs.getInt("gap"+i,60); }
        buildUi(); configureReport(); setBusy(true,"Loading report...");
        WORK.execute(()-> { try { SessionStore.Session session=SessionStore.load(getApplicationContext()); runOnUiThread(()-> {
            if(isDestroyed()) return;
            if(session!=null) { invoices=session.invoices; filename=session.name; if(!prefs.contains("asOn")) { LocalDate d=Reports.date(session.date); if(d!=null) asOn=d; } }
            dateButton.setText(asOn.format(Reports.DATE)); updateSource(); setBusy(false,""); refresh();
        }); } catch(Exception e) { runOnUiThread(()-> { if(isDestroyed()) return; setBusy(false,""); error("Saved report could not be restored",e); refresh(); }); } });
    }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout vertical() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String value,int size,boolean bold) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(INK); if(bold) t.setTypeface(null,Typeface.BOLD); return t; }
    private ImageButton icon(int drawable,String label,Runnable action) {
        ImageButton button=new ImageButton(this); button.setImageResource(drawable); button.setColorFilter(TEAL); button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(label); button.setTooltipText(label); button.setOnClickListener(v->action.run()); button.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48))); return button;
    }
    private Spinner spinner(String[] values) {
        Spinner s=new Spinner(this); ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(adapter); s.setMinimumHeight(dp(48)); return s;
    }
    private void buildUi() {
        root=vertical(); root.setBackgroundColor(Color.WHITE); root.setPadding(dp(12),0,dp(12),0);
        root.setOnApplyWindowInsetsListener((v,insets)-> { v.setPadding(dp(12)+insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),dp(12)+insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        setContentView(root);
        LinearLayout title=horizontal(); title.addView(text("GSA-ANALYTICS",20,true),new LinearLayout.LayoutParams(0,dp(56),1));
        importButton=icon(android.R.drawable.ic_menu_upload,"Import report",this::chooseImport); title.addView(importButton);
        exportButton=icon(android.R.drawable.ic_menu_save,"Export PDF",this::chooseExport); title.addView(exportButton); root.addView(title);
        source=text(filename,12,false); source.setMaxLines(2); source.setEllipsize(TextUtils.TruncateAt.END); root.addView(source);
        LinearLayout dateRow=horizontal(); dateRow.addView(text("Ageing as on",14,false)); dateButton=new Button(this); dateButton.setText(asOn.format(Reports.DATE)); dateButton.setOnClickListener(v->pickDate()); dateRow.addView(dateButton,new LinearLayout.LayoutParams(0,dp(48),1));
        dateRow.addView(icon(android.R.drawable.ic_menu_delete,"Clear imported report",this::confirmClear)); root.addView(dateRow);
        reports=spinner(Reports.TITLES); reports.setContentDescription("Report"); reports.setSelection(report); root.addView(reports);
        LinearLayout searchRow=horizontal(); search=new EditText(this); search.setSingleLine(true); search.setHint("Search"); search.setContentDescription("Search report"); search.setTextSize(16); searchRow.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
        settingsButton=icon(android.R.drawable.ic_menu_preferences,"Old balance and day thresholds",this::thresholdDialog); searchRow.addView(settingsButton); root.addView(searchRow);
        filterBar=horizontal(); String[] choices=new String[9]; choices[0]="All slabs"; System.arraycopy(Reports.SLABS,0,choices,1,8);
        slabPicker=spinner(choices); slabPicker.setContentDescription("Slab filter"); filterBar.addView(slabPicker,new LinearLayout.LayoutParams(0,dp(48),1));
        sortPicker=spinner(new String[]{"Highest balance","Bill value","Number of bills"}); sortPicker.setContentDescription("Sort customers by"); filterBar.addView(sortPicker,new LinearLayout.LayoutParams(0,dp(48),1)); root.addView(filterBar);
        status=text("",12,false); status.setPadding(0,dp(6),0,dp(6)); root.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        tableHost=vertical(); root.addView(tableHost,new LinearLayout.LayoutParams(-1,0,1));
        reports.setOnItemSelectedListener(selected(position->{ if(position!=report) { report=position; sortColumn=-1; configureReport(); refresh(); } }));
        slabPicker.setOnItemSelectedListener(selected(position->{ if(!restoring && slabs[report]!=position-1) { slabs[report]=position-1; sortColumn=-1; refresh(); } }));
        sortPicker.setOnItemSelectedListener(selected(position->{ if(!restoring && customerSort!=position) { customerSort=position; sortColumn=-1; refresh(); } }));
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int start,int count,int after) {} public void onTextChanged(CharSequence s,int start,int before,int count) {
            if(!restoring) { queries[report]=s.toString(); handler.removeCallbacks(searchRefresh); handler.postDelayed(searchRefresh,200); }
        } public void afterTextChanged(Editable e) {} });
    }
    private interface Selection { void accept(int value); }
    private AdapterView.OnItemSelectedListener selected(Selection listener) { return new AdapterView.OnItemSelectedListener() {
        public void onItemSelected(AdapterView<?> p,View v,int position,long id) { listener.accept(position); }
        public void onNothingSelected(AdapterView<?> p) {}
    }; }
    private final Runnable searchRefresh=()-> { sortColumn=-1; refresh(); };
    private void configureReport() {
        restoring=true; search.setText(queries[report]); slabPicker.setSelection(slabs[report]+1); sortPicker.setSelection(customerSort);
        filterBar.setVisibility(report==1 || report==2?View.VISIBLE:View.GONE); sortPicker.setVisibility(report==2?View.VISIBLE:View.GONE);
        settingsButton.setVisibility(report>=3?View.VISIBLE:View.GONE); restoring=false;
    }
    private void pickDate() { new DatePickerDialog(this,(v,y,m,d)-> { asOn=LocalDate.of(y,m+1,d); dateButton.setText(asOn.format(Reports.DATE)); refresh(); },asOn.getYear(),asOn.getMonthValue()-1,asOn.getDayOfMonth()).show(); }
    private void thresholdDialog() {
        int index=report-3; LinearLayout fields=vertical(); fields.setPadding(dp(20),dp(8),dp(20),0);
        fields.addView(text("Min old balance",14,false)); EditText amount=new EditText(this); amount.setInputType(8194); amount.setText(String.valueOf(thresholds[index])); fields.addView(amount);
        fields.addView(text(report==3?"Old bill age at new bill (days)":"Minimum gap between bills (days)",14,false)); EditText days=new EditText(this); days.setInputType(2); days.setText(String.valueOf(gaps[index])); fields.addView(days);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Report thresholds").setView(fields).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try { double value=Double.parseDouble(amount.getText().toString()); int gap=Integer.parseInt(days.getText().toString()); if(!Double.isFinite(value)||value<0||gap<0) throw new NumberFormatException();
                thresholds[index]=value; gaps[index]=gap; dialog.dismiss(); refresh();
            } catch(NumberFormatException e) { days.setError("Enter a non-negative amount and whole number of days"); }
        })); dialog.show();
    }
    private void refresh() {
        if(busy) return; final int token=++generation;
        Reports.Options o=new Reports.Options(); o.asOn=asOn; o.query=queries[report]; o.slab=slabs[report]; o.customerSort=customerSort;
        if(report>=3) { o.threshold=thresholds[report-3]; o.gap=gaps[report-3]; }
        int selected=report, column=sortColumn; boolean descending=reverse; List<Reports.Invoice> input=invoices;
        exportButton.setEnabled(false); progress.setVisibility(View.VISIBLE);
        WORK.execute(()-> { try { Reports.Table result=Reports.build(selected,input,o); if(column>=0) result.sort(column,descending);
            runOnUiThread(()-> { if(isDestroyed()||token!=generation) return; table=result; progress.setVisibility(View.GONE); exportButton.setEnabled(!invoices.isEmpty()); render();
                String suffix=selected>=3?" | Old balance > "+Reports.money(o.threshold)+" | Gap > "+o.gap+" days":"";
                status.setText(result.rows.size()+" rows"+suffix); saveSettings();
            });
        } catch(Exception e) { runOnUiThread(()-> { if(isDestroyed()||token!=generation) return; progress.setVisibility(View.GONE); error("Report failed",e); }); } });
    }
    private void render() { tableHost.removeAllViews(); if(invoices.isEmpty()) { TextView empty=text("No report imported",18,true); empty.setGravity(Gravity.CENTER); tableHost.addView(empty,new LinearLayout.LayoutParams(-1,-1)); } else tableHost.addView(tableView(table,true),new LinearLayout.LayoutParams(-1,-1)); }
    private View tableView(Reports.Table data,boolean sortable) {
        HorizontalScrollView scroll=new HorizontalScrollView(this); LinearLayout content=vertical();
        int[] widths=new int[data.headers.length]; int total=0;
        for(int i=0;i<widths.length;i++) { String h=data.headers[i]; widths[i]=dp(i==0?48:h.equals("Customer")||h.equals("Party name")?220:h.equals("Slab")?170:140); total+=widths[i]; }
        int available=getResources().getDisplayMetrics().widthPixels-dp(24);
        if(total<available) { widths[1]+=available-total; total=available; }
        LinearLayout header=horizontal(); header.setBackgroundColor(0xffe2eeeb);
        for(int c=0;c<widths.length;c++) { final int col=c; TextView cell=text(data.headers[c]+(sortable && sortColumn==c?(reverse?" \u2193":" \u2191"):""),13,true); cell.setPadding(dp(8),dp(8),dp(8),dp(8)); cell.setGravity(Gravity.CENTER_VERTICAL); header.addView(cell,new LinearLayout.LayoutParams(widths[c],dp(60)));
            if(sortable) { cell.setTooltipText("Sort by "+data.headers[c]); cell.setOnClickListener(v->{ reverse=sortColumn==col&&!reverse; sortColumn=col; table.sort(col,reverse); render(); }); }
        }
        content.addView(header);
        List<Reports.Row> display=data.all(); ListView list=new ListView(this); list.setDividerHeight(dp(1)); list.setAdapter(new BaseAdapter() {
            public int getCount() { return display.size(); } public Object getItem(int p) { return display.get(p); } public long getItemId(int p) { return p; }
            public View getView(int position,View convert,android.view.ViewGroup parent) {
                LinearLayout row=convert instanceof LinearLayout?(LinearLayout)convert:horizontal(); Reports.Row entry=display.get(position);
                if(row.getChildCount()==0) for(int w:widths) { TextView cell=text("",14,false); cell.setPadding(dp(8),dp(12),dp(8),dp(12)); cell.setMinHeight(dp(52)); cell.setGravity(Gravity.CENTER_VERTICAL); row.addView(cell,new LinearLayout.LayoutParams(w,-2)); }
                for(int c=0;c<widths.length;c++) { TextView cell=(TextView)row.getChildAt(c); cell.setText(entry.cells[c]); cell.setTypeface(null,entry==data.total?Typeface.BOLD:Typeface.NORMAL); cell.setTextColor(entry.details!=null && c==widths.length-1?TEAL:INK); }
                row.setBackgroundColor(entry==data.total?0xffdceee8:position%2==0?Color.WHITE:0xfff5f7f6); return row;
            }
        });
        list.setOnItemClickListener((parent,view,position,id)-> { Reports.Row r=display.get(position); if(r.details!=null) showDetails(Reports.details(r.details,data.partyDetails)); });
        content.addView(list,new LinearLayout.LayoutParams(-1,0,1)); scroll.addView(content,new HorizontalScrollView.LayoutParams(total,-1)); return scroll;
    }
    private void showDetails(Reports.Table details) {
        LinearLayout panel=vertical(); panel.addView(tableView(details,false),new LinearLayout.LayoutParams(-1,dp(360)));
        new AlertDialog.Builder(this).setTitle(details.title).setView(panel).setPositiveButton("Close",null).show();
    }
    private void chooseImport() { if(busy) return; Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent,IMPORT); }
    private void chooseExport() {
        if(table==null||busy) return; pendingExport=table; exportDate=asOn;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,table.title.replace(' ','_')+".pdf"); startActivityForResult(intent,EXPORT);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data); if(result!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if(request==IMPORT) importReport(uri);
        if(request==EXPORT) {
            Reports.Table snapshot=pendingExport; LocalDate date=exportDate;
            if(snapshot==null) { error("Export interrupted",new IOException("Please export again after rotating or reopening the app.")); return; }
            setBusy(true,"Exporting PDF..."); WORK.execute(()-> {
                try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")) { if(output==null) throw new IOException("Cannot open destination"); PdfExport.write(output,snapshot,date);
                    runOnUiThread(()-> { if(isDestroyed()) return; setBusy(false,"PDF saved"); Toast.makeText(this,"PDF saved",Toast.LENGTH_LONG).show(); refresh(); });
                } catch(Exception e) { runOnUiThread(()-> { if(isDestroyed()) return; setBusy(false,""); error("PDF export failed",e); refresh(); }); }
            });
        }
    }
    private void importReport(Uri uri) {
        setBusy(true,"Importing report..."); generation++;
        WORK.execute(()-> {
            File temp=null;
            try {
                String name="report";
                try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) { if(c!=null && c.moveToFirst()) name=c.getString(0); }
                temp=File.createTempFile("import-",".tmp",getCacheDir());
                try(InputStream input=getContentResolver().openInputStream(uri); OutputStream output=new FileOutputStream(temp)) {
                    if(input==null) throw new IOException("Cannot read selected report"); byte[] buffer=new byte[16384]; int count; long size=0;
                    while((count=input.read(buffer))!=-1) { size+=count; if(size>50L*1024*1024) throw new IOException("Report exceeds the 50 MB mobile import limit."); output.write(buffer,0,count); }
                }
                ReportImport.Result parsed=ReportImport.read(temp,name); LocalDate date=parsed.reportDate==null?asOn:parsed.reportDate;
                SessionStore.save(getApplicationContext(),new SessionStore.Session(name,date.toString(),parsed.invoices));
                prefs.edit().putString("asOn",date.toString()).apply(); String displayName=name;
                runOnUiThread(()-> { if(isDestroyed()) return; invoices=parsed.invoices; filename=displayName; asOn=date; dateButton.setText(asOn.format(Reports.DATE)); sortColumn=-1; updateSource(); setBusy(false,""); refresh(); });
            } catch(Exception e) { runOnUiThread(()-> { if(isDestroyed()) return; setBusy(false,""); error("Import failed",e); refresh(); }); }
            finally { if(temp!=null) temp.delete(); }
        });
    }
    private void confirmClear() { if(busy) return; new AlertDialog.Builder(this).setTitle("Clear imported report?").setMessage("Remove the app's saved copy and current report.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)-> {
        generation++; SessionStore.clear(this); invoices=new ArrayList<>(); filename="No report imported"; updateSource(); refresh();
    }).show(); }
    private void updateSource() { source.setText(filename+(invoices.isEmpty()?"":" | "+invoices.size()+" invoice records")); }
    private void setBusy(boolean value,String message) { busy=value; importButton.setEnabled(!value); exportButton.setEnabled(!value&&!invoices.isEmpty()); reports.setEnabled(!value); dateButton.setEnabled(!value); search.setEnabled(!value); settingsButton.setEnabled(!value); progress.setVisibility(value?View.VISIBLE:View.GONE); status.setText(message); }
    private void error(String title,Exception error) { new AlertDialog.Builder(this).setTitle(title).setMessage(error.getMessage()==null?error.toString():error.getMessage()).setPositiveButton("OK",null).show(); }
    private void saveSettings() {
        SharedPreferences.Editor e=prefs.edit().putString("asOn",asOn.toString()).putInt("report",report).putInt("customerSort",customerSort);
        for(int i=0;i<5;i++) e.putString("q"+i,queries[i]).putInt("s"+i,slabs[i]);
        for(int i=0;i<2;i++) e.putString("threshold"+i,String.valueOf(thresholds[i])).putInt("gap"+i,gaps[i]); e.apply();
    }
    @Override protected void onStop() { super.onStop(); saveSettings(); }
    @Override protected void onDestroy() { generation++; handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
