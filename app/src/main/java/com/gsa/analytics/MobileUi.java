package com.gsa.analytics;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;

/** Shared visual primitives for the phone UI; report arithmetic lives in Reports. */
final class MobileUi {
    static final int INK=0xff20282d, MUTED=0xff626e77, TEAL=0xff087f73;
    static final int LINE=0xffe4e9ec, PALE=0xfff3f6f7;
    static final int[] AGE_COLORS={0xff198069,0xff12848a,0xff397caf,0xff8d772a,0xffb26a2e,0xffba5945,0xffb14755,0xff993e50};
    final Context context;
    MobileUi(Context context) { this.context=context; }
    int dp(float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout v=new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v=new LinearLayout(context); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    TextView text(String value,int size,boolean bold) {
        TextView t=new TextView(context); t.setText(value); t.setTextSize(size); t.setTextColor(INK);
        t.setLetterSpacing(0); t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        return t;
    }
    TextView label(String value) { TextView t=text(value,12,false); t.setTextColor(MUTED); return t; }
    View line() { View v=new View(context); v.setBackgroundColor(LINE); v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1))); return v; }
    void gap(LinearLayout parent,int height) { View v=new View(context); parent.addView(v,new LinearLayout.LayoutParams(1,dp(height))); }
    GradientDrawable background(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    ImageView image(int resource,int color) {
        ImageView image=new ImageView(context); image.setImageResource(resource); image.setColorFilter(color); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return image;
    }
    ImageButton icon(int resource,String description,Runnable action) {
        ImageButton b=new ImageButton(context); b.setImageResource(resource); b.setImageTintList(ColorStateList.valueOf(INK)); b.setScaleType(ImageView.ScaleType.CENTER_INSIDE); b.setPadding(dp(12),dp(12),dp(12),dp(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18087f73),background(Color.TRANSPARENT,8),null));
        b.setContentDescription(description); b.setTooltipText(description); b.setOnClickListener(v->action.run()); b.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48))); return b;
    }
    MaterialButton button(String label,int icon,boolean primary,Runnable action) {
        MaterialButton b=new MaterialButton(context,null,primary?com.google.android.material.R.attr.materialButtonStyle:com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setLetterSpacing(0); b.setCornerRadius(dp(8));
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setInsetTop(0); b.setInsetBottom(0); b.setPadding(dp(12),dp(10),dp(12),dp(10));
        b.setMaxLines(2); b.setIconSize(dp(18)); b.setIconPadding(dp(8)); b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        if(icon!=0) b.setIconResource(icon);
        if(!primary) { b.setStrokeColor(ColorStateList.valueOf(LINE)); b.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE)); b.setTextColor(INK); b.setIconTint(ColorStateList.valueOf(INK)); }
        b.setOnClickListener(v->action.run()); return b;
    }
    void fitMoney(TextView text,int maximum) { text.setMaxLines(1); TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(text,12,maximum,1,TypedValue.COMPLEX_UNIT_SP); }
    boolean narrow() { return context.getResources().getConfiguration().screenWidthDp<360 || context.getResources().getConfiguration().fontScale>1.2f; }
}
