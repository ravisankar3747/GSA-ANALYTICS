package com.gsa.analytics;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** V7 report rules, deliberately independent of Android. */
public final class Reports {
    private Reports() {}
    public static final String[] TITLES = {"BALANCE BY SLAB", "BILLS BY SLAB", "BILLS COUNT BY SLAB", "OLD DUE BILLING", "OLD DUE VS NEW BILLS"};
    public static final String[] SLABS = {"S1: 0 - 15 days", "S2: 16 - 30 days", "S3: 31 - 45 days", "S4: 46 - 60 days", "S5: 61 - 90 days", "S6: 91 - 120 days", "S7: 121 - 150 days", "S8: 150+ days"};
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    public static final class Invoice {
        public final String customer, group, reference;
        public final LocalDate date;
        public final double total, balance;
        public Invoice(String customer, String group, String reference, LocalDate date, double total, double balance) {
            this.customer=customer; this.group=group; this.reference=reference; this.date=date; this.total=total; this.balance=balance;
        }
    }
    public static final class Row {
        public final String[] cells;
        public final List<Invoice> details;
        public Row(List<Invoice> details, Object... values) {
            this.details = details;
            cells = Arrays.stream(values).map(String::valueOf).toArray(String[]::new);
        }
    }
    public static final class Table {
        public final String title;
        public final String[] headers;
        public final List<Row> rows = new ArrayList<>();
        public Row total;
        public boolean partyDetails;
        public Table(String title, String... headers) { this.title=title; this.headers=headers; }
        public void add(List<Invoice> details, Object... cells) { rows.add(new Row(details, cells)); }
        public void total(Object... cells) { total=new Row(null, cells); }
        public List<Row> all() { List<Row> result=new ArrayList<>(rows); if(total!=null) result.add(total); return result; }
        public void sort(int column, boolean reverse) {
            Comparator<Row> cmp=(a,b)->compare(a.cells[column], b.cells[column]);
            rows.sort(reverse ? cmp.reversed() : cmp);
        }
    }
    public static final class Options {
        public LocalDate asOn=LocalDate.now();
        public String query="";
        public int slab=-1, customerSort=0, gap=60;
        public double threshold=500;
    }
    public static double number(Object value) {
        try { double d=Double.parseDouble(String.valueOf(value).replace(",", "").replace("\u20b9", "").replace("Rs.", "").trim()); return Double.isFinite(d)?d:0; }
        catch(Exception ignored) { return 0; }
    }
    public static LocalDate date(String text) {
        for(String pattern : new String[]{"dd/MM/uuuu", "dd-MM-uuuu", "uuuu-MM-dd"}) {
            try { return LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)); }
            catch(Exception ignored) { }
        }
        return null;
    }
    public static String money(double d) { return String.format(Locale.US, "\u20b9 %,.2f", d); }
    public static int slab(long age) { int[] ends={15,30,45,60,90,120,150}; for(int i=0;i<ends.length;i++) if(age<=ends[i]) return i; return 7; }
    public static long age(Invoice i, LocalDate asOn) { return Math.max(0, ChronoUnit.DAYS.between(i.date, asOn)); }
    public static LocalDate reportDate(String filename, String text) {
        Matcher m=Pattern.compile("(?:to_|Report[_ ]?)(\\d{2}-\\d{2}-\\d{4})", Pattern.CASE_INSENSITIVE).matcher(filename);
        if(m.find()) return date(m.group(1));
        m=Pattern.compile("Sale Aging Report\\s*(\\d{2}/\\d{2}/\\d{4})", Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find()?date(m.group(1)):null;
    }
    private static int compare(String a,String b) {
        LocalDate da=date(a), db=date(b);
        if(da!=null && db!=null) return da.compareTo(db);
        String aa=a.replace("\u20b9", "").replace(",", "").trim(), bb=b.replace("\u20b9", "").replace(",", "").trim();
        Double na=null, nb=null;
        try { na=Double.valueOf(aa); } catch(NumberFormatException ignored) { }
        try { nb=Double.valueOf(bb); } catch(NumberFormatException ignored) { }
        if(na!=null && nb!=null) return Double.compare(na,nb);
        if(na!=null || da!=null) return -1;
        if(nb!=null || db!=null) return 1;
        return aa.compareToIgnoreCase(bb);
    }
    private static double sum(List<Invoice> rows, boolean balance) { return rows.stream().mapToDouble(i->balance?i.balance:i.total).sum(); }
    private static double max(List<Invoice> rows) { return rows.stream().mapToDouble(i->i.balance).max().orElse(0); }
    private static Map<String,List<Invoice>> group(List<Invoice> rows) {
        Map<String,List<Invoice>> result=new LinkedHashMap<>();
        for(Invoice i:rows) result.computeIfAbsent(i.customer,k->new ArrayList<>()).add(i);
        return result;
    }
    private static boolean matches(Invoice i, Options o) {
        long age=age(i,o.asOn);
        String text=i.customer+" "+i.group+" "+i.reference+" "+i.date+" "+i.total+" "+i.balance+" "+age+" "+SLABS[slab(age)].replace(":", "");
        return text.toLowerCase(Locale.ROOT).contains(o.query.trim().toLowerCase(Locale.ROOT));
    }
    public static Table build(int report, List<Invoice> invoices, Options o) {
        List<Invoice> rows=invoices.stream().filter(i->i.date!=null && i.balance>0).collect(Collectors.toList());
        String q=o.query.trim().toLowerCase(Locale.ROOT);
        if(report==0) {
            Table t=new Table(TITLES[0],"No.","Slab","Outstanding balance","% of total due"); double total=sum(rows,true);
            for(int s=0;s<8;s++) { final int which=s; if(!SLABS[s].toLowerCase(Locale.ROOT).contains(q)) continue;
                double value=rows.stream().filter(i->slab(age(i,o.asOn))==which).mapToDouble(i->i.balance).sum();
                t.add(null,s+1,SLABS[s],money(value),String.format(Locale.US,"%.2f%%",total==0?0:value/total*100));
            }
            t.total("","TOTAL OUTSTANDING",money(total),"100.00%"); return t;
        }
        if(report==1 || report==2) {
            List<Invoice> visible=rows.stream().filter(i->(o.slab<0 || slab(age(i,o.asOn))==o.slab) && matches(i,o)).collect(Collectors.toList());
            if(report==1) {
                Table t=new Table(TITLES[1],"No.","Slab","Days","Customer","Invoice no.","Billing date","Invoice value","Balance");
                visible.sort(Comparator.<Invoice>comparingInt(i->-slab(age(i,o.asOn))).thenComparingDouble(i->-i.balance).thenComparing(i->i.customer.toLowerCase(Locale.ROOT)));
                for(Invoice i:visible) t.add(null,t.rows.size()+1,SLABS[slab(age(i,o.asOn))],age(i,o.asOn),i.customer,i.reference,i.date.format(DATE),money(i.total),money(i.balance));
                t.total("","TOTAL","","","","",money(sum(visible,false)),money(sum(visible,true))); return t;
            }
            Table t=new Table(TITLES[2],"No.","Customer","Party bills","Slab","Bills in slab","Total invoice value","Total balance","Highest balance","Invoices");
            Map<String,List<Invoice>> groups=group(visible); List<String> names=new ArrayList<>(groups.keySet());
            names.sort(Comparator.<String>comparingDouble(n->o.customerSort==2?-groups.get(n).size():-sum(groups.get(n),o.customerSort==0)).thenComparing(n->n.toLowerCase(Locale.ROOT)));
            for(String name:names) for(int s=0;s<8;s++) { final int which=s;
                List<Invoice> values=groups.get(name).stream().filter(i->slab(age(i,o.asOn))==which).collect(Collectors.toList());
                if(!values.isEmpty()) t.add(values,t.rows.size()+1,name,groups.get(name).size(),SLABS[s],values.size(),money(sum(values,false)),money(sum(values,true)),money(max(values)),"View bills");
            }
            t.total("","TOTAL",visible.size(),"","",money(sum(visible,false)),money(sum(visible,true)),money(max(visible)),""); return t;
        }
        Map<String,List<Invoice>> groups=group(rows);
        for(List<Invoice> bills:groups.values()) bills.sort(Comparator.comparing(i->i.date));
        if(report==3) {
            Table t=new Table(TITLES[3],"No.","Customer","Old invoice","Old bill date","Age at new bill","Old balance","New invoice","New bill date","New value");
            List<Invoice[]> pairs=new ArrayList<>();
            for(List<Invoice> bills:groups.values()) for(Invoice later:bills) for(Invoice old:bills) {
                long gap=ChronoUnit.DAYS.between(old.date,later.date);
                if(gap>Math.max(o.gap,0) && old.balance>o.threshold && (matches(old,o)||matches(later,o)||String.valueOf(gap).contains(q))) pairs.add(new Invoice[]{old,later});
            }
            pairs.sort(Comparator.<Invoice[]>comparingDouble(p->-p[0].balance).thenComparing(p->p[0].customer.toLowerCase(Locale.ROOT)));
            double oldTotal=0,newTotal=0;
            for(Invoice[] p:pairs) { Invoice old=p[0], later=p[1]; oldTotal+=old.balance; newTotal+=later.total;
                t.add(null,t.rows.size()+1,old.customer,old.reference,old.date.format(DATE),ChronoUnit.DAYS.between(old.date,later.date),money(old.balance),later.reference,later.date.format(DATE),money(later.total));
            }
            t.total("","TOTAL","","","",money(oldTotal),"","",money(newTotal)); return t;
        }
        Table t=new Table(TITLES[4],"No.","Party name","Party total balance","Old balance","New balance","Invoices"); t.partyDetails=true;
        List<List<Invoice>> parties=new ArrayList<>();
        for(List<Invoice> bills:groups.values()) {
            Invoice oldest=bills.get(0);
            if(oldest.balance>o.threshold && oldest.customer.toLowerCase(Locale.ROOT).contains(q) && bills.stream().skip(1).anyMatch(i->ChronoUnit.DAYS.between(oldest.date,i.date)>Math.max(0,o.gap))) parties.add(bills);
        }
        parties.sort(Comparator.<List<Invoice>>comparingDouble(b->-b.get(0).balance).thenComparing(b->b.get(0).customer.toLowerCase(Locale.ROOT)));
        double all=0,old=0,recent=0;
        for(List<Invoice> bills:parties) { double total=sum(bills,true), oldest=bills.get(0).balance; all+=total; old+=oldest; recent+=total-oldest;
            t.add(bills,t.rows.size()+1,bills.get(0).customer,money(total),money(oldest),money(total-oldest),"View bills");
        }
        t.total("","TOTAL",money(all),money(old),money(recent),""); return t;
    }
    public static Table details(List<Invoice> invoices, boolean party) {
        List<Invoice> bills=new ArrayList<>(invoices); bills.sort(Comparator.comparing(i->i.date));
        Table t=party?new Table(bills.get(0).customer,"No.","Type","Bill number","Bill date","Bill value","Balance"):
            new Table(bills.get(0).customer,"No.","Bill date","Bill number","Balance");
        for(Invoice i:bills) {
            if(party) t.add(null,t.rows.size()+1,t.rows.isEmpty()?"OLDEST OLD BILL":"NEW BILL",i.reference,i.date.format(DATE),money(i.total),money(i.balance));
            else t.add(null,t.rows.size()+1,i.date.format(DATE),i.reference,money(i.balance));
        }
        if(party) t.total("","TOTAL","","",money(sum(bills,false)),money(sum(bills,true)));
        else t.total("","TOTAL","",money(sum(bills,true)));
        return t;
    }
}
