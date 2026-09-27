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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import com.google.android.material.bottomsheet.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class MainActivity extends AppCompatActivity {
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
    private LinearLayout root, bottomBar, summary, searchRow, filterBar;
    private TextView source, status, totalAmount, totalLabel, totalMeta, activeFilters;
    private MaterialButton dateButton, importButton, exportButton, filtersButton;
    private EditText search;
    private MobileUi ui;
    private ListView resultList;
    private BottomSheetDialog openSheet;
    private boolean calculating;
    private ImageButton clearSearch;
    private Spinner reports, slabPicker, sortPicker;
    private ProgressBar progress;
    private SharedPreferences prefs;
    private static final String[] NAMES={"Balance by slab","Bills by slab","Bills count by slab","Old due billing","Old due vs new bills"};
    private static final String[] TOTAL_LABELS={"Outstanding","Outstanding","Outstanding","Old pair balance","Party balance"};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); WindowCompat.setDecorFitsSystemWindows(getWindow(),false); ui=new MobileUi(this); PDFBoxResourceLoader.init(getApplicationContext()); prefs=getSharedPreferences("settings",MODE_PRIVATE);
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
        root=vertical(); root.setFocusableInTouchMode(true); root.setBackgroundColor(Color.WHITE); setContentView(root);
        LinearLayout toolbar=horizontal(); toolbar.setPadding(dp(18),dp(6),dp(6),dp(6));
        LinearLayout brand=vertical(); brand.addView(ui.text("GSA Analytics",20,true)); source=ui.label(filename); source.setSingleLine(); source.setEllipsize(TextUtils.TruncateAt.MIDDLE); ui.gap(brand,4); brand.addView(source);
        toolbar.addView(brand,new LinearLayout.LayoutParams(0,-2,1)); toolbar.addView(ui.icon(R.drawable.ic_ellipsis_vertical,"More options",this::showMore)); root.addView(toolbar);
        reports=spinner(NAMES); reports.setContentDescription("Report"); reports.setSelection(report); reports.setPadding(dp(12),0,dp(12),0); LinearLayout reportNav=horizontal(); reportNav.addView(reports,new LinearLayout.LayoutParams(0,dp(52),1)); if(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE) reportNav.addView(ui.icon(R.drawable.ic_ellipsis_vertical,"More options",this::showMore)); root.addView(reportNav);
        root.addView(ui.line()); progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
        resultList=new ListView(this); resultList.setContentDescription("Report results"); resultList.setDivider(new android.graphics.drawable.ColorDrawable(MobileUi.LINE)); resultList.setDividerHeight(dp(1)); resultList.setClipToPadding(false);
        LinearLayout header=vertical();
        summary=vertical(); summary.setPadding(dp(18),dp(12),dp(18),dp(12)); summary.setBackgroundColor(MobileUi.PALE);
        LinearLayout amountRow=horizontal(); LinearLayout totals=vertical(); LinearLayout labelRow=horizontal(); totalLabel=ui.label(""); labelRow.addView(totalLabel,new LinearLayout.LayoutParams(0,-2,1)); labelRow.addView(ui.image(R.drawable.ic_chevron_right,MobileUi.MUTED),new LinearLayout.LayoutParams(dp(16),dp(16))); totals.addView(labelRow);
        ui.gap(totals,5); totalAmount=ui.text("",26,true); ui.fitMoney(totalAmount,26); totals.addView(totalAmount,new LinearLayout.LayoutParams(-1,dp(34))); totalMeta=ui.label(""); totalMeta.setVisibility(View.GONE);
        totals.setContentDescription("Report totals"); totals.setMinimumHeight(dp(48)); totals.setOnClickListener(v->{ if(table!=null) showRow(table.total,table); }); amountRow.addView(totals,new LinearLayout.LayoutParams(0,-2,1));
        dateButton=ui.button("",R.drawable.ic_calendar_days,false,this::pickDate); dateButton.setTextSize(12); dateButton.setPadding(dp(8),dp(8),dp(8),dp(8)); dateButton.setContentDescription("Change ageing date"); LinearLayout.LayoutParams dateParams=new LinearLayout.LayoutParams(dp(ui.narrow()?132:142),-2); dateParams.setMarginStart(dp(16)); amountRow.addView(dateButton,dateParams); summary.addView(amountRow); header.addView(summary);
        if(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE) toolbar.setVisibility(View.GONE);
        searchRow=horizontal(); searchRow.setPadding(dp(16),dp(14),dp(16),dp(6));
        LinearLayout searchBox=horizontal(); searchBox.setBackground(ui.background(MobileUi.PALE,8)); ImageView glass=ui.image(R.drawable.ic_search,MobileUi.MUTED); LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(20),dp(20)); gp.setMargins(dp(12),0,dp(8),0); searchBox.addView(glass,gp);
        search=new EditText(this); search.setSingleLine(true); search.setHint("Search report"); search.setContentDescription("Search report"); search.setTextSize(14); search.setBackgroundColor(Color.TRANSPARENT); search.setPadding(0,dp(10),0,dp(10)); search.setMinHeight(dp(48)); search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(search,new LinearLayout.LayoutParams(0,-2,1)); clearSearch=ui.icon(R.drawable.ic_x,"Clear search",()->search.setText("")); searchBox.addView(clearSearch); clearSearch.setVisibility(View.GONE);
        searchRow.addView(searchBox,new LinearLayout.LayoutParams(0,-2,1)); filtersButton=ui.button("Filters",R.drawable.ic_sliders_horizontal,false,this::showFilters); LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-2,-2); fp.setMargins(dp(8),0,0,0); searchRow.addView(filtersButton,fp); header.addView(searchRow);
        activeFilters=ui.label(""); activeFilters.setPadding(dp(18),dp(4),dp(18),dp(8)); header.addView(activeFilters);
        filterBar=horizontal(); filterBar.setPadding(dp(18),dp(2),dp(6),dp(8)); status=ui.label(""); filterBar.addView(status,new LinearLayout.LayoutParams(0,-2,1)); filterBar.addView(ui.button("Table",R.drawable.ic_table_2,false,this::showTable)); filterBar.addView(ui.icon(R.drawable.ic_arrow_down_up,"Sort report",this::showSort)); header.addView(filterBar);
        resultList.addHeaderView(header,null,false); root.addView(resultList,new LinearLayout.LayoutParams(-1,0,1));
        resultList.setOnItemClickListener((p,v,pos,id)->{ int i=pos-resultList.getHeaderViewsCount(); if(!calculating && table!=null && i>=0 && i<table.rows.size()) { hideKeyboard(); showRow(table.rows.get(i),table); } });
        resultList.setOnScrollListener(new AbsListView.OnScrollListener() { public void onScrollStateChanged(AbsListView v,int state) { if(state==SCROLL_STATE_TOUCH_SCROLL) hideKeyboard(); } public void onScroll(AbsListView v,int first,int visible,int total) {} });
        root.addView(ui.line()); bottomBar=horizontal(); bottomBar.setPadding(dp(16),dp(10),dp(16),dp(10));
        importButton=ui.button("Import report",R.drawable.ic_file_up,true,this::chooseImport); exportButton=ui.button("Export PDF",R.drawable.ic_download,false,this::chooseExport); bottomBar.addView(importButton,new LinearLayout.LayoutParams(0,-2,1)); LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(0,-2,1); ep.setMargins(dp(10),0,0,0); bottomBar.addView(exportButton,ep); root.addView(bottomBar);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{ androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()); androidx.core.graphics.Insets ime=insets.getInsets(WindowInsetsCompat.Type.ime()); v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom)); bottomBar.setVisibility(insets.isVisible(WindowInsetsCompat.Type.ime())?View.GONE:View.VISIBLE); return insets; });
        reports.setOnItemSelectedListener(selected(position->{ if(position!=report) { report=position; sortColumn=-1; configureReport(); refresh(); resultList.setSelection(0); } }));
        search.setOnEditorActionListener((v,action,event)->{ hideKeyboard(); return true; });
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int start,int count,int after) {} public void onTextChanged(CharSequence s,int start,int before,int count) {
            clearSearch.setVisibility(s.length()==0?View.GONE:View.VISIBLE); if(!restoring) { queries[report]=s.toString(); handler.removeCallbacks(searchRefresh); handler.postDelayed(searchRefresh,200); }
        } public void afterTextChanged(Editable e) {} });
    }
    private void hideKeyboard() {
        root.requestFocus(); android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE); if(keyboard!=null) keyboard.hideSoftInputFromWindow(search.getWindowToken(),0);
    }
    private void showMore() {
        PopupMenu menu=new PopupMenu(this,reports); menu.getMenu().add("Report file"); menu.getMenu().add("Clear imported report");
        menu.setOnMenuItemClickListener(item->{ if(item.getTitle().equals("Report file")) new MaterialAlertDialogBuilder(this).setTitle("Report file").setMessage(filename+"\n"+invoices.size()+" invoice records").setPositiveButton("Close",null).show(); else confirmClear(); return true; }); menu.show();
    }
    private interface Selection { void accept(int value); }
    private AdapterView.OnItemSelectedListener selected(Selection listener) { return new AdapterView.OnItemSelectedListener() {
        public void onItemSelected(AdapterView<?> p,View v,int position,long id) { listener.accept(position); }
        public void onNothingSelected(AdapterView<?> p) {}
    }; }
    private final Runnable searchRefresh=()-> { sortColumn=-1; refresh(); };
    private void configureReport() {
        restoring=true; search.setText(queries[report]); filtersButton.setVisibility(report==0?View.GONE:View.VISIBLE); restoring=false; hideKeyboard();
    }
    private void pickDate() { hideKeyboard(); new DatePickerDialog(this,(v,y,m,d)-> { asOn=LocalDate.of(y,m+1,d); refresh(); },asOn.getYear(),asOn.getMonthValue()-1,asOn.getDayOfMonth()).show(); }
    private LinearLayout sheet(BottomSheetDialog dialog,String title) {
        LinearLayout content=vertical(); content.setBackgroundColor(Color.WHITE); LinearLayout heading=horizontal(); heading.setPadding(dp(18),dp(8),dp(6),dp(8)); heading.addView(ui.text(title,20,true),new LinearLayout.LayoutParams(0,-2,1)); heading.addView(ui.icon(R.drawable.ic_x,"Close",dialog::dismiss)); content.addView(heading); content.addView(ui.line()); return content;
    }
    private void present(BottomSheetDialog dialog,LinearLayout content) {
        present(dialog,content,true);
    }
    private void present(BottomSheetDialog dialog,LinearLayout content,boolean tall) {
        if(openSheet!=null) openSheet.dismiss(); openSheet=dialog; dialog.setContentView(content);
        dialog.setOnShowListener(d->{ FrameLayout frame=dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet); if(frame!=null) { int height=(int)(getResources().getDisplayMetrics().heightPixels*.88); if(!tall) height=Math.min(height,dp(420*getResources().getConfiguration().fontScale)); frame.getLayoutParams().height=height; BottomSheetBehavior<FrameLayout> behavior=BottomSheetBehavior.from(frame); behavior.setMaxHeight(height); behavior.setSkipCollapsed(true); behavior.setState(BottomSheetBehavior.STATE_EXPANDED); } });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN); dialog.show();
    }
    private void showFilters() {
        hideKeyboard(); final int current=report; if(current==0) return;
        BottomSheetDialog dialog=new BottomSheetDialog(this); LinearLayout content=sheet(dialog,"Filters"); ScrollView scroll=new ScrollView(this); LinearLayout fields=vertical(); fields.setPadding(dp(18),dp(20),dp(18),dp(20)); scroll.addView(fields); content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        String[] choices=new String[9]; choices[0]="All slabs"; System.arraycopy(Reports.SLABS,0,choices,1,8); Spinner slab=spinner(choices); slab.setContentDescription("Slab filter"); slab.setSelection(slabs[current]+1);
        Spinner ranking=spinner(new String[]{"Highest balance","Bill value","Number of bills"}); ranking.setContentDescription("Sort customers by"); ranking.setSelection(customerSort);
        EditText amount=new EditText(this), days=new EditText(this); amount.setInputType(8194); days.setInputType(2); amount.setContentDescription("Minimum old balance"); days.setContentDescription("Minimum days");
        if(current<=2) { fields.addView(ui.label("Ageing slab")); fields.addView(slab); if(current==2) { ui.gap(fields,20); fields.addView(ui.label("Rank customers by")); fields.addView(ranking); } }
        else { amount.setText(String.valueOf(thresholds[current-3])); days.setText(String.valueOf(gaps[current-3])); fields.addView(ui.label("Minimum old balance")); fields.addView(amount); ui.gap(fields,20); fields.addView(ui.label(current==3?"Old bill age at new bill (days)":"Minimum gap between bills (days)")); fields.addView(days); }
        LinearLayout actions=horizontal(); actions.setPadding(dp(18),dp(12),dp(18),dp(16));
        actions.addView(ui.button("Reset",0,false,()->{ slab.setSelection(0); ranking.setSelection(0); amount.setText("500"); days.setText("60"); }),new LinearLayout.LayoutParams(0,-2,1));
        MaterialButton apply=ui.button("Apply",0,true,()->{
            if(current>=3) { try { double value=Double.parseDouble(amount.getText().toString()); int gap=Integer.parseInt(days.getText().toString()); if(!Double.isFinite(value)||value<0||gap<0) throw new NumberFormatException(); thresholds[current-3]=value; gaps[current-3]=gap; } catch(NumberFormatException e) { days.setError("Enter a non-negative amount and whole number of days"); return; } }
            else { slabs[current]=slab.getSelectedItemPosition()-1; if(current==2) customerSort=ranking.getSelectedItemPosition(); }
            sortColumn=-1; dialog.dismiss(); refresh();
        }); LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,-2,1); ap.setMargins(dp(12),0,0,0); actions.addView(apply,ap); content.addView(ui.line()); content.addView(actions); present(dialog,content,false);
    }
    private void showSort() {
        if(table==null) return; hideKeyboard(); BottomSheetDialog dialog=new BottomSheetDialog(this); LinearLayout content=sheet(dialog,"Sort report");
        ScrollView scroll=new ScrollView(this); LinearLayout fields=vertical(); fields.setPadding(dp(18),dp(20),dp(18),dp(20)); scroll.addView(fields); content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        String[] choices=new String[table.headers.length+1]; choices[0]="Default report order"; System.arraycopy(table.headers,0,choices,1,table.headers.length);
        fields.addView(ui.label("Sort by")); Spinner column=spinner(choices); column.setSelection(sortColumn+1); fields.addView(column); ui.gap(fields,16);
        RadioGroup order=new RadioGroup(this); RadioButton ascending=new RadioButton(this),descending=new RadioButton(this); ascending.setId(View.generateViewId()); descending.setId(View.generateViewId()); ascending.setText("Ascending"); descending.setText("Descending"); order.addView(ascending); order.addView(descending); order.check(reverse?descending.getId():ascending.getId()); fields.addView(order);
        MaterialButton apply=ui.button("Apply",0,true,()->{ sortColumn=column.getSelectedItemPosition()-1; reverse=descending.isChecked(); dialog.dismiss(); refresh(); }); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(dp(18),dp(12),dp(18),dp(16)); content.addView(apply,p); present(dialog,content,false);
    }
    private void refresh() {
        if(busy) return; final int token=++generation;
        Reports.Options o=new Reports.Options(); o.asOn=asOn; o.query=queries[report]; o.slab=slabs[report]; o.customerSort=customerSort;
        if(report>=3) { o.threshold=thresholds[report-3]; o.gap=gaps[report-3]; }
        int selected=report, column=sortColumn; boolean descending=reverse; List<Reports.Invoice> input=invoices;
        calculating=true; exportButton.setEnabled(false); progress.setVisibility(View.VISIBLE);
        WORK.execute(()-> { try { Reports.Table result=Reports.build(selected,input,o); if(column>=0) result.sort(column,descending);
            runOnUiThread(()-> { if(isDestroyed()||token!=generation) return; calculating=false; table=result; progress.setVisibility(View.INVISIBLE); exportButton.setEnabled(!invoices.isEmpty()); render(); saveSettings();
            });
        } catch(Exception e) { runOnUiThread(()-> { if(isDestroyed()||token!=generation) return; calculating=false; progress.setVisibility(View.INVISIBLE); error("Report failed",e); }); } });
    }
    private void render() {
        boolean loaded=!invoices.isEmpty(); summary.setVisibility(loaded?View.VISIBLE:View.GONE); searchRow.setVisibility(loaded?View.VISIBLE:View.GONE); filterBar.setVisibility(loaded?View.VISIBLE:View.GONE);
        int amountColumn=new int[]{2,7,6,5,2}[report]; totalLabel.setText(TOTAL_LABELS[report]); totalAmount.setText(table.total.cells[amountColumn]); dateButton.setText("As of\n"+asOn.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")));
        String active=report>=3?"Old balance > "+Reports.money(thresholds[report-3])+"  ·  Gap > "+gaps[report-3]+" days":slabs[report]>=0?Reports.SLABS[slabs[report]]:"";
        if(report==2 && customerSort!=0) active+=(active.isEmpty()?"":"  ·  ")+"Ranked by "+(customerSort==1?"bill value":"bill count");
        activeFilters.setText(active); activeFilters.setVisibility(loaded&&!active.isEmpty()?View.VISIBLE:View.GONE); status.setText(table.rows.size()+" results");
        if(loaded && !table.rows.isEmpty()) resultList.setAdapter(new ReportListAdapter(ui,table,report));
        else resultList.setAdapter(new BaseAdapter() {
            public int getCount(){return 1;} public Object getItem(int p){return null;} public long getItemId(int p){return p;} public boolean isEnabled(int p){return false;}
            public View getView(int p,View old,ViewGroup parent) {
                LinearLayout empty=vertical(); empty.setPadding(dp(24),dp(40),dp(24),dp(40)); empty.setGravity(Gravity.CENTER);
                empty.addView(ui.image(loaded?R.drawable.ic_search:R.drawable.ic_file_spreadsheet,MobileUi.TEAL),new LinearLayout.LayoutParams(dp(40),dp(40))); ui.gap(empty,18);
                TextView title=ui.text(loaded?"No matching results":"No report imported",20,true); title.setGravity(Gravity.CENTER); empty.addView(title); ui.gap(empty,12);
                if(loaded) empty.addView(ui.button("Reset filters",0,false,()->{ queries[report]=""; slabs[report]=-1; if(report>=3){thresholds[report-3]=500;gaps[report-3]=60;} configureReport(); refresh(); }));
                else { TextView hint=ui.label("Outstanding report · XLS or PDF"); hint.setGravity(Gravity.CENTER); empty.addView(hint); }
                return empty;
            }
        });
    }
    private void showRow(Reports.Row row,Reports.Table owner) {
        BottomSheetDialog dialog=new BottomSheetDialog(this); LinearLayout content=sheet(dialog,row==owner.total?"Report totals":"Report details"); ListView details=new ListView(this); details.setDivider(new android.graphics.drawable.ColorDrawable(MobileUi.LINE)); details.setDividerHeight(dp(1));
        LinearLayout fields=vertical(); fields.setPadding(dp(18),dp(20),dp(18),dp(8)); addFields(fields,owner.headers,row.cells);
        if(row.details!=null) { ui.gap(fields,12); fields.addView(ui.text("Invoices",18,true)); ui.gap(fields,12); }
        details.addHeaderView(fields,null,false); Reports.Table invoicesTable=row.details==null?null:Reports.details(row.details,owner.partyDetails); List<Reports.Row> entries=invoicesTable==null?Collections.emptyList():invoicesTable.all();
        details.setAdapter(new BaseAdapter() {
            public int getCount(){return entries.size();} public Object getItem(int p){return entries.get(p);} public long getItemId(int p){return p;} public boolean isEnabled(int p){return false;}
            public View getView(int p,View old,ViewGroup parent){ LinearLayout item=vertical(); item.setPadding(dp(18),dp(16),dp(18),dp(4)); if(entries.get(p)==invoicesTable.total) item.setBackgroundColor(MobileUi.PALE); addFields(item,invoicesTable.headers,entries.get(p).cells); return item; }
        });
        content.addView(details,new LinearLayout.LayoutParams(-1,0,1)); present(dialog,content);
    }
    private void addFields(LinearLayout target,String[] labels,String[] values) {
        for(int i=0;i<labels.length;i++) if(!labels[i].equals("No.")&&!labels[i].equals("No")&&!labels[i].equals("Invoices")&&!values[i].isEmpty()) {
            LinearLayout field=horizontal(); field.setMinimumHeight(dp(44)); field.setPadding(0,dp(8),0,dp(8)); TextView label=ui.label(labels[i]); label.setPadding(0,0,dp(12),0); field.addView(label,new LinearLayout.LayoutParams(0,-2,1)); TextView value=ui.text(values[i],14,true); value.setGravity(Gravity.END); value.setTextIsSelectable(true); field.addView(value,new LinearLayout.LayoutParams(0,-2,1.3f)); target.addView(field); target.addView(ui.line());
        }
    }
    private void showTable() {
        if(table==null || calculating) return; hideKeyboard(); BottomSheetDialog dialog=new BottomSheetDialog(this); LinearLayout content=sheet(dialog,"Table view"); content.addView(tableView(table,true),new LinearLayout.LayoutParams(-1,0,1)); dialog.setOnDismissListener(d->{ if(!isDestroyed()) render(); }); present(dialog,content);
    }
    private View tableView(Reports.Table data,boolean sortable) {
        HorizontalScrollView scroll=new HorizontalScrollView(this); LinearLayout content=vertical();
        int[] widths=new int[data.headers.length]; int total=0;
        for(int i=0;i<widths.length;i++) { String h=data.headers[i]; widths[i]=dp(i==0?40:h.equals("Customer")||h.equals("Party name")?168:h.equals("Slab")?145:120); total+=widths[i]; }
        int available=getResources().getDisplayMetrics().widthPixels-dp(24);
        if(total<available) { widths[1]+=available-total; total=available; }
        LinearLayout header=horizontal(); header.setBackgroundColor(0xffe2eeeb);
        for(int c=0;c<widths.length;c++) { final int col=c; TextView cell=text(data.headers[c]+(sortable && sortColumn==c?(reverse?" \u2193":" \u2191"):""),13,true); cell.setPadding(dp(8),dp(8),dp(8),dp(8)); cell.setGravity(Gravity.CENTER_VERTICAL); header.addView(cell,new LinearLayout.LayoutParams(widths[c],dp(60)));
            if(sortable) { cell.setTooltipText("Sort by "+data.headers[c]); cell.setOnClickListener(v->{ reverse=sortColumn==col&&!reverse; sortColumn=col; table.sort(col,reverse); if(openSheet!=null) openSheet.dismiss(); showTable(); }); }
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
        list.setOnItemClickListener((parent,view,position,id)->showRow(display.get(position),data));
        content.addView(list,new LinearLayout.LayoutParams(-1,0,1)); scroll.addView(content,new HorizontalScrollView.LayoutParams(total,-1)); return scroll;
    }
    private void showDetails(Reports.Table details) {
        LinearLayout panel=vertical(); panel.addView(tableView(details,false),new LinearLayout.LayoutParams(-1,dp(360)));
        new AlertDialog.Builder(this).setTitle(details.title).setView(panel).setPositiveButton("Close",null).show();
    }
    private void chooseImport() { if(busy) return; hideKeyboard(); Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent,IMPORT); }
    private void chooseExport() {
        if(table==null||busy||calculating) return; hideKeyboard(); pendingExport=table; exportDate=asOn;
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
    private void setBusy(boolean value,String message) { busy=value; importButton.setEnabled(!value); exportButton.setEnabled(!value&&!invoices.isEmpty()); reports.setEnabled(!value); dateButton.setEnabled(!value); search.setEnabled(!value); filtersButton.setEnabled(!value); progress.setVisibility(value?View.VISIBLE:View.INVISIBLE); status.setText(message); }
    private void error(String title,Exception error) { new AlertDialog.Builder(this).setTitle(title).setMessage(error.getMessage()==null?error.toString():error.getMessage()).setPositiveButton("OK",null).show(); }
    private void saveSettings() {
        SharedPreferences.Editor e=prefs.edit().putString("asOn",asOn.toString()).putInt("report",report).putInt("customerSort",customerSort);
        for(int i=0;i<5;i++) e.putString("q"+i,queries[i]).putInt("s"+i,slabs[i]);
        for(int i=0;i<2;i++) e.putString("threshold"+i,String.valueOf(thresholds[i])).putInt("gap"+i,gaps[i]); e.apply();
    }
    @Override protected void onStop() { super.onStop(); saveSettings(); }
    @Override protected void onDestroy() { generation++; handler.removeCallbacksAndMessages(null); if(openSheet!=null) openSheet.dismiss(); super.onDestroy(); }
}
