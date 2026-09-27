package com.gsa.analytics;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/** Presents the existing report cells without recalculating their financial values. */
final class ReportListAdapter extends BaseAdapter {
    private final MobileUi ui;
    private final Reports.Table table;
    private final int report;
    ReportListAdapter(MobileUi ui,Reports.Table table,int report) { this.ui=ui; this.table=table; this.report=report; }
    public int getCount() { return table.rows.size(); }
    public Reports.Row getItem(int position) { return table.rows.get(position); }
    public long getItemId(int position) { return position; }
    public View getView(int position,View convert,ViewGroup parent) {
        Item view=convert instanceof Item?(Item)convert:new Item();
        Reports.Row row=getItem(position); String[] c=row.cells;
        view.title.setText(report==0?c[1].substring(c[1].indexOf(':')+1).trim():report==1?c[3]:c[1]);
        view.amount.setText(c[new int[]{2,7,6,5,2}[report]]);
        view.share.setVisibility(report==0?View.VISIBLE:View.GONE);
        view.bar.setVisibility(report==0?View.VISIBLE:View.GONE);
        view.arrow.setVisibility(report==0?View.GONE:View.VISIBLE);
        view.badge.setVisibility(report==1||report==2?View.VISIBLE:View.GONE);
        view.subtitle.setVisibility(report==0?View.GONE:View.VISIBLE);
        view.meta.setVisibility(View.VISIBLE);
        if(report==0) {
            int slab=Character.digit(c[1].charAt(1),10)-1; int color=MobileUi.AGE_COLORS[Math.max(0,Math.min(7,slab))];
            view.meta.setText(c[1].substring(0,2)); view.meta.setTextColor(color); view.share.setText(c[3]);
            view.bar.setIndicatorColor(color); view.bar.setProgress((int)Math.round(Reports.number(c[3].replace("%",""))*100));
        } else {
            view.meta.setTextColor(MobileUi.MUTED);
            if(report==1) {
                view.subtitle.setText(c[4]+"  |  "+c[5]); view.meta.setText(c[2]+" days  |  Bill value "+c[6]); view.badge.setText(c[1]);
            } else if(report==2) {
                view.subtitle.setText(c[4]+(c[4].equals("1")?" bill":" bills")+"  |  Bill value "+c[5]);
                view.meta.setText("Highest balance "+c[7]); view.badge.setText(c[3]);
            } else if(report==3) {
                view.subtitle.setText(c[2]+"  \u2192  "+c[6]); view.meta.setText(c[4]+" days apart  |  New value "+c[8]);
            } else {
                view.subtitle.setText("Old "+c[3]+"  |  New "+c[4]); view.meta.setText(row.details.size()+" outstanding bills");
            }
        }
        view.setContentDescription(String.join(", ",row.cells)+", Open details");
        return view;
    }
    private final class Item extends LinearLayout {
        final TextView title,amount,subtitle,meta,badge,share;
        final ImageView arrow;
        final LinearProgressIndicator bar;
        Item() {
            super(ui.context); setOrientation(VERTICAL); setBackgroundColor(Color.WHITE); setPadding(ui.dp(18),ui.dp(14),ui.dp(18),ui.dp(14));
            LinearLayout top=ui.row(); if(ui.narrow()) top.setOrientation(VERTICAL);
            title=ui.text("",15,true); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(title,new LinearLayout.LayoutParams(ui.narrow()?-1:0,-2,ui.narrow()?0:1));
            LinearLayout money=ui.row(); amount=ui.text("",18,true); ui.fitMoney(amount,18);
            money.addView(amount,new LinearLayout.LayoutParams(ui.dp(ui.narrow()?240:144),ui.dp(27)));
            amount.setGravity(ui.narrow()?Gravity.START|Gravity.CENTER_VERTICAL:Gravity.END|Gravity.CENTER_VERTICAL);
            arrow=ui.image(R.drawable.ic_chevron_right,MobileUi.MUTED); LinearLayout.LayoutParams arrowParams=new LinearLayout.LayoutParams(ui.dp(16),ui.dp(16)); arrowParams.setMarginStart(ui.dp(6)); money.addView(arrow,arrowParams);
            top.addView(money); addView(top);
            subtitle=ui.label(""); subtitle.setTextSize(13); subtitle.setMaxLines(2); subtitle.setEllipsize(TextUtils.TruncateAt.END); subtitle.setPadding(0,ui.dp(6),0,0); addView(subtitle);
            LinearLayout footer=ui.row(); footer.setPadding(0,ui.dp(6),0,0); meta=ui.label(""); footer.addView(meta,new LinearLayout.LayoutParams(0,-2,1));
            share=ui.label(""); footer.addView(share); addView(footer);
            badge=ui.text("",11,true); badge.setTextColor(MobileUi.MUTED); badge.setPadding(ui.dp(6),ui.dp(3),ui.dp(6),ui.dp(3)); badge.setBackground(ui.background(MobileUi.PALE,4));
            LinearLayout.LayoutParams badgeParams=new LinearLayout.LayoutParams(-2,-2); badgeParams.topMargin=ui.dp(8); addView(badge,badgeParams);
            bar=new LinearProgressIndicator(ui.context); bar.setMax(10000); bar.setTrackColor(MobileUi.PALE); bar.setTrackThickness(ui.dp(3)); bar.setTrackCornerRadius(ui.dp(1));
            LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(-1,ui.dp(3)); barParams.topMargin=ui.dp(9); addView(bar,barParams);
        }
    }
}
