"""GSA-ANALYTICS - billing-date ageing and billing-control reports."""
from __future__ import annotations

import os
import re
import sys
import tkinter as tk
from collections import defaultdict
from datetime import date, datetime
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

if getattr(sys, "frozen", False):
    _bundle = getattr(sys, "_MEIPASS", "")
    os.environ.setdefault("TCL_LIBRARY", os.path.join(_bundle, "_tcl_data"))
    os.environ.setdefault("TK_LIBRARY", os.path.join(_bundle, "_tk_data"))

import xlrd
from pypdf import PdfReader
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle


SLABS = [
    ("S1", "0 - 15 days", 0, 15), ("S2", "16 - 30 days", 16, 30),
    ("S3", "31 - 45 days", 31, 45), ("S4", "46 - 60 days", 46, 60),
    ("S5", "61 - 90 days", 61, 90), ("S6", "91 - 120 days", 91, 120),
    ("S7", "121 - 150 days", 121, 150), ("S8", "150+ days", 151, None),
]
SLAB_ORDER = {key: position for position, (key, *_rest) in enumerate(SLABS)}
NAVY, BLUE, TEAL, PALE = "#172554", "#2563EB", "#0F766E", "#F1F5F9"


def clean_number(value: object) -> float:
    try:
        return float(str(value).replace(",", "").replace("₹", "").replace("Rs.", "").strip())
    except (TypeError, ValueError):
        return 0.0


def parse_date(value: object) -> date | None:
    if isinstance(value, datetime): return value.date()
    if isinstance(value, date): return value
    text = str(value).strip()
    for fmt in ("%d/%m/%Y", "%d-%m-%Y", "%Y-%m-%d"):
        try: return datetime.strptime(text, fmt).date()
        except ValueError: pass
    return None


def as_money(value: float) -> str: return f"₹ {value:,.2f}"


def slab_for(age: int) -> tuple[str, str]:
    age = max(age, 0)
    for key, name, low, high in SLABS:
        if age >= low and (high is None or age <= high): return key, name
    return "S8", "150+ days"


def find_report_date(path: str, text: str) -> date | None:
    hit = re.search(r"(?:to_|Report[_ ]?)(\d{2}-\d{2}-\d{4})", Path(path).name, re.I)
    if hit: return parse_date(hit.group(1))
    hit = re.search(r"Sale Aging Report\s*(\d{2}/\d{2}/\d{4})", text, re.I)
    return parse_date(hit.group(1)) if hit else None


def load_excel(path: str) -> tuple[list[dict], date | None]:
    book = xlrd.open_workbook(path)
    try: sheet = book.sheet_by_name("Outstanding Sale Invoices")
    except xlrd.biffh.XLRDError as exc: raise ValueError("Excel must include 'Outstanding Sale Invoices'.") from exc
    invoices, customer, group = [], "", ""
    top_text = " ".join(str(sheet.cell_value(row, 0)) for row in range(min(5, sheet.nrows)))
    for row in range(sheet.nrows):
        values = [sheet.cell_value(row, col) if col < sheet.ncols else "" for col in range(7)]
        first, bill_date = str(values[0]).strip(), parse_date(values[1])
        if first and bill_date and str(values[4]).strip():
            invoices.append({"customer": customer or "Unidentified customer", "group": group,
                "reference": first, "billing_date": bill_date, "total": clean_number(values[4]), "balance": clean_number(values[5])})
        elif first.lower() == "general": group = first
        elif first and first.lower() not in {"reference no", "totals"} and "days" not in first.lower():
            customer = first
    return invoices, find_report_date(path, top_text)


def load_pdf(path: str) -> tuple[list[dict], date | None]:
    raw = "\n".join(page.extract_text() or "" for page in PdfReader(path).pages)
    raw = re.sub(r"(\d{2}/\d{2}/)\s*\n\s*(\d{4})", r"\1\2", raw)
    matcher = re.compile(r"((?:SALE-FY|S-Y)/\d{2}-\d{2}/\d+)\s+(\d{2}/\d{2}/\d{4})\s+(\d{2}/\d{2}/\d{4})\s+\d+\s+([\d,.]+)\s+([\d,.]+)")
    customer, cursor, invoices = "Unidentified customer", 0, []
    for found in matcher.finditer(raw):
        chunk = raw[cursor:found.start()]; cursor = found.end()
        candidates = re.findall(r"General\s+(.+?)\s+Ref No", chunk, re.S)
        if candidates:
            customer = re.sub(r"\s+", " ", candidates[-1]).strip()
            customer = re.sub(r"\s+(?:-?[\d,.]+\s+){5}-?[\d,.]+$", "", customer).strip() or customer
        invoices.append({"customer": customer, "group": "General", "reference": found.group(1),
            "billing_date": parse_date(found.group(2)), "total": clean_number(found.group(4)), "balance": clean_number(found.group(5))})
    if not invoices: raise ValueError("No invoice records found in PDF. Please use the Excel report for exact import.")
    return invoices, find_report_date(path, raw)


class App(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("GSA-ANALYTICS")
        self.geometry("1240x760"); self.minsize(1080, 650)
        self.configure(bg=PALE)
        self.invoices: list[dict] = []; self.current_rows: list[list[str]] = []
        self.source = tk.StringVar(value="No report imported")
        self.as_on = tk.StringVar(value=date.today().strftime("%d/%m/%Y"))
        self.minimum_due = tk.StringVar(value="500")
        self.old_due_days = tk.StringVar(value="60")
        self.party_min_due = tk.StringVar(value="500")
        self.party_gap_days = tk.StringVar(value="60")
        self.sort_by = tk.StringVar(value="Highest balance")
        self.slab_filter = tk.StringVar(value="All slabs")
        self.slab_filter_bills = tk.StringVar(value="All slabs")
        self.search_slab = tk.StringVar(); self.search_control = tk.StringVar(); self.search_customer = tk.StringVar()
        self.customer_details: dict[str, list[dict]] = {}
        self.party_details: dict[str, tuple[dict, list[dict]]] = {}
        self._style(); self._build(); self.refresh_reports()

    def _style(self) -> None:
        style = ttk.Style(self); style.theme_use("clam")
        style.configure("Title.TLabel", background=NAVY, foreground="white", font=("Segoe UI", 21, "bold"))
        style.configure("Sub.TLabel", background=NAVY, foreground="#BFDBFE", font=("Segoe UI", 10))
        style.configure("Accent.TButton", font=("Segoe UI", 10, "bold"), padding=(13, 8), foreground="white", background=BLUE)
        style.map("Accent.TButton", background=[("active", "#1D4ED8")])
        style.configure("Treeview", font=("Segoe UI", 10), rowheight=30, background="white", fieldbackground="white", borderwidth=1, relief="solid", bordercolor="#94A3B8")
        style.configure("Treeview.Heading", font=("Segoe UI", 10, "bold"), background="#CBD5E1", foreground=NAVY, padding=8, borderwidth=1, relief="solid")

    def _build(self) -> None:
        header = ttk.Frame(self, padding=(22, 16), style="Header.TFrame"); header.pack(fill="x")
        header.configure(style="Header.TFrame"); ttk.Style().configure("Header.TFrame", background=NAVY)
        ttk.Label(header, text="GSA-ANALYTICS", style="Title.TLabel").pack(anchor="w")
        ttk.Label(header, text="Billing-date ageing • customer control • PDF reporting", style="Sub.TLabel").pack(anchor="w", pady=(2, 0))
        bar = ttk.Frame(self, padding=(20, 13)); bar.pack(fill="x")
        ttk.Button(bar, text="Import report", style="Accent.TButton", command=self.import_file).pack(side="left")
        ttk.Label(bar, text="  Ageing as on:").pack(side="left")
        as_on_entry = ttk.Entry(bar, textvariable=self.as_on, width=13); as_on_entry.pack(side="left", padx=5); as_on_entry.bind("<Return>", lambda _event: self.refresh_reports())
        ttk.Label(bar, textvariable=self.source, foreground="#475569").pack(side="right")
        self.notebook = ttk.Notebook(self); self.notebook.pack(fill="both", expand=True, padx=20, pady=(0, 20))
        self.slab_tab = ttk.Frame(self.notebook, padding=10); self.control_tab = ttk.Frame(self.notebook, padding=10); self.customer_tab = ttk.Frame(self.notebook, padding=10); self.balance_tab = ttk.Frame(self.notebook, padding=10); self.party_tab = ttk.Frame(self.notebook, padding=10)
        self.notebook.add(self.balance_tab, text="  1. BALANCE BY SLAB  "); self.notebook.add(self.slab_tab, text="  2. BILLS BY SLAB  "); self.notebook.add(self.customer_tab, text="  3. BILLS COUNT BY SLAB  "); self.notebook.add(self.control_tab, text="  4. OLD DUE BILLING  "); self.notebook.add(self.party_tab, text="  5. OLD DUE VS NEW BILLS  ")
        self.slab_tree = self._report_tab(self.slab_tab, self.search_slab, ("no", "slab", "age", "customer", "reference", "billing", "invoice", "balance"), ("No.", "Slab", "Days", "Customer", "Invoice no.", "Billing date", "Invoice value", "Balance"), self.slab_filter_bills)
        control_options = ttk.Frame(self.control_tab); control_options.pack(fill="x", pady=(0, 8))
        ttk.Label(control_options, text="Min old balance:").pack(side="left"); ttk.Entry(control_options, textvariable=self.minimum_due, width=9).pack(side="left", padx=5)
        ttk.Label(control_options, text="Old bill age at new bill (days):").pack(side="left", padx=(15, 0)); ttk.Entry(control_options, textvariable=self.old_due_days, width=7).pack(side="left", padx=5)
        ttk.Button(control_options, text="Refresh OLD DUE BILLING", style="Accent.TButton", command=self.refresh_reports).pack(side="left", padx=8)
        self.control_tree = self._report_tab(self.control_tab, self.search_control, ("no", "customer", "old_ref", "old_date", "old_age", "old_balance", "new_ref", "new_date", "new_value"), ("No.", "Customer", "Old invoice", "Old bill date", "Age at new bill", "Old balance", "New invoice", "New bill date", "New value"))
        controls = ttk.Frame(self.customer_tab); controls.pack(fill="x", pady=(0, 8)); ttk.Label(controls, text="Search:").pack(side="left")
        customer_search = ttk.Entry(controls, textvariable=self.search_customer, width=26); customer_search.pack(side="left", padx=(5, 14)); customer_search.bind("<KeyRelease>", lambda _event: self.refresh_reports())
        ttk.Label(controls, text="Slab:").pack(side="left")
        slab_selector = ttk.Combobox(controls, textvariable=self.slab_filter, values=("All slabs",) + tuple(f"{code}: {name}" for code, name, *_ in SLABS), state="readonly", width=18); slab_selector.pack(side="left", padx=(5, 14)); slab_selector.bind("<<ComboboxSelected>>", lambda _event: self.refresh_reports())
        ttk.Label(controls, text="Sort customers by:").pack(side="left")
        selector = ttk.Combobox(controls, textvariable=self.sort_by, values=("Highest balance", "Bill value", "Number of bills"), state="readonly", width=18); selector.pack(side="left", padx=8); selector.bind("<<ComboboxSelected>>", lambda _event: self.refresh_reports())
        ttk.Button(controls, text="Export PDF", style="Accent.TButton", command=lambda: self.export_pdf(self.customer_tab)).pack(side="right")
        self.customer_tree = self._tree(self.customer_tab, ("no", "customer", "party_bills", "slab", "bills", "invoice", "balance", "highest", "details"), ("No.", "Customer", "Party bills", "Slab", "Bills in slab", "Total invoice value", "Total balance", "Highest balance", "Invoices"))
        self.customer_tree.column("details", width=100, anchor="center")
        self.customer_tree.bind("<Double-1>", self.show_invoice_details)
        self.search_balance = tk.StringVar()
        self.balance_tree = self._report_tab(self.balance_tab, self.search_balance, ("no", "slab", "balance", "share"), ("No.", "Slab", "Outstanding balance", "% of total due"))
        self.search_party = tk.StringVar()
        party_options = ttk.Frame(self.party_tab); party_options.pack(fill="x", pady=(0, 8))
        ttk.Label(party_options, text="Min old balance:").pack(side="left"); ttk.Entry(party_options, textvariable=self.party_min_due, width=9).pack(side="left", padx=5)
        ttk.Label(party_options, text="Minimum gap between bills (days):").pack(side="left", padx=(15, 0)); ttk.Entry(party_options, textvariable=self.party_gap_days, width=7).pack(side="left", padx=5)
        ttk.Button(party_options, text="Refresh party report", style="Accent.TButton", command=self.refresh_reports).pack(side="left", padx=8)
        self.party_tree = self._report_tab(self.party_tab, self.search_party, ("no", "customer", "party_total", "old_balance", "new_balance", "details"), ("No.", "Party name", "Party total balance", "Old balance", "New balance", "Invoices"))
        self.party_tree.column("details", width=100, anchor="center")
        self.party_tree.bind("<Double-1>", self.show_party_details)

    def _report_tab(self, tab: ttk.Frame, search: tk.StringVar, columns: tuple, headings: tuple, slab_filter: tk.StringVar | None = None) -> ttk.Treeview:
        actions = ttk.Frame(tab); actions.pack(fill="x", pady=(0, 8)); ttk.Label(actions, text="Search:").pack(side="left")
        entry = ttk.Entry(actions, textvariable=search, width=30); entry.pack(side="left", padx=6); entry.bind("<KeyRelease>", lambda _event: self.refresh_reports())
        if slab_filter is not None:
            ttk.Label(actions, text="Slab:").pack(side="left", padx=(8, 0))
            selector = ttk.Combobox(actions, textvariable=slab_filter, values=("All slabs",) + tuple(f"{code}: {name}" for code, name, *_ in SLABS), state="readonly", width=17)
            selector.pack(side="left", padx=5); selector.bind("<<ComboboxSelected>>", lambda _event: self.refresh_reports())
        ttk.Label(actions, text="Search customer, invoice number, or any visible word. Click any heading to sort.", foreground="#475569").pack(side="left")
        tree = self._tree(tab, columns, headings); ttk.Button(actions, text="Export PDF", style="Accent.TButton", command=lambda: self.export_pdf(tab)).pack(side="right")
        return tree

    def _tree(self, parent: ttk.Frame, columns: tuple, headings: tuple) -> ttk.Treeview:
        area = ttk.Frame(parent); area.pack(fill="both", expand=True)
        tree = ttk.Treeview(area, columns=columns, show="headings")
        for col, title in zip(columns, headings):
            tree.heading(col, text=title, command=lambda current=col: self._sort_tree(tree, current))
            tree.column(col, width=120, anchor="e" if col in {"no", "age", "invoice", "balance", "bills", "highest", "old_age", "old_balance", "new_value"} else "w")
        if "customer" in columns:
            tree.column("customer", width=265)
        if "reference" in columns:
            tree.column("reference", width=160)
        tree.tag_configure("total", background="#DBEAFE", foreground=NAVY)
        tree.tag_configure("even", background="#F8FAFC")
        tree.tag_configure("odd", background="#FFFFFF")
        scroll = ttk.Scrollbar(area, orient="vertical", command=tree.yview); tree.configure(yscrollcommand=scroll.set); tree.pack(side="left", fill="both", expand=True); scroll.pack(side="right", fill="y")
        return tree

    def _sort_tree(self, tree: ttk.Treeview, column: str) -> None:
        reverse = getattr(tree, "_sort_column", None) == column and not getattr(tree, "_sort_reverse", False)
        items = [item for item in tree.get_children() if "total" not in tree.item(item, "tags")]
        def value(item: str):
            raw = str(tree.set(item, column)).replace("₹", "").replace(",", "").strip()
            parsed_date = parse_date(raw)
            if parsed_date: return (0, parsed_date)
            try: return (0, float(raw))
            except ValueError: return (1, raw.lower())
        for index, item in enumerate(sorted(items, key=value, reverse=reverse)):
            tree.move(item, "", index)
        tree._sort_column, tree._sort_reverse = column, reverse

    def import_file(self) -> None:
        path = filedialog.askopenfilename(title="Choose Vyapar sale-ageing report", filetypes=[("Vyapar reports", "*.xls *.pdf"), ("Excel", "*.xls"), ("PDF", "*.pdf")])
        if not path: return
        try:
            self.invoices, report_date = load_excel(path) if Path(path).suffix.lower() == ".xls" else load_pdf(path)
            if report_date: self.as_on.set(report_date.strftime("%d/%m/%Y"))
            self.source.set(f"{Path(path).name} • {len(self.invoices)} invoice records")
            self.refresh_reports()
        except Exception as exc: messagebox.showerror("Import failed", str(exc))

    def records(self) -> list[dict]:
        cutoff = parse_date(self.as_on.get())
        if not cutoff: raise ValueError("Enter 'Ageing as on' as DD/MM/YYYY.")
        result = []
        for item in self.invoices:
            if not item["billing_date"] or item["balance"] <= 0: continue
            age = max((cutoff - item["billing_date"]).days, 0); slab, slab_name = slab_for(age)
            result.append(dict(item, age=age, slab=slab, slab_name=slab_name))
        return result

    def refresh_reports(self) -> None:
        try: rows = self.records()
        except ValueError as exc:
            if self.invoices: messagebox.showerror("Date required", str(exc))
            return
        self._clear(self.slab_tree); self._clear(self.control_tree); self._clear(self.customer_tree); self._clear(self.balance_tree); self._clear(self.party_tree)
        query = self.search_slab.get().strip().lower()
        selected_slab = self.slab_filter_bills.get().split(":")[0] if self.slab_filter_bills.get() != "All slabs" else None
        visible = [item for item in rows if (not selected_slab or item["slab"] == selected_slab) and (not query or query in " ".join(str(value) for value in item.values()).lower())]
        for number, item in enumerate(sorted(visible, key=lambda r: (-SLAB_ORDER[r["slab"]], -r["balance"], r["customer"].lower())), 1):
            self.slab_tree.insert("", "end", tags=("even" if number % 2 == 0 else "odd",), values=(number, f"{item['slab']}: {item['slab_name']}", item["age"], item["customer"], item["reference"], item["billing_date"].strftime("%d/%m/%Y"), as_money(item["total"]), as_money(item["balance"])))
        self.slab_tree.insert("", "end", tags=("total",), values=("", "TOTAL", "", "", "", "", as_money(sum(item["total"] for item in visible)), as_money(sum(item["balance"] for item in visible))))
        self._populate_control(rows); self._populate_customers(rows); self._populate_balances(rows); self._populate_party_billing(rows)

    def _populate_control(self, rows: list[dict]) -> None:
        threshold = clean_number(self.minimum_due.get())
        gap_days = max(int(clean_number(self.old_due_days.get())), 0)
        by_customer: dict[str, list[dict]] = defaultdict(list)
        for row in rows: by_customer[row["customer"]].append(row)
        alerts = []
        for customer, bills in by_customer.items():
            bills.sort(key=lambda b: b["billing_date"])
            for later in bills:
                old = [earlier for earlier in bills if earlier["billing_date"] < later["billing_date"] and (later["billing_date"] - earlier["billing_date"]).days > gap_days and earlier["balance"] > threshold]
                for earlier in old:
                    alerts.append((customer, earlier, later, (later["billing_date"] - earlier["billing_date"]).days))
        query = self.search_control.get().strip().lower()
        visible = [row for row in alerts if not query or query in " ".join(str(value) for value in (row[0], *row[1].values(), *row[2].values(), row[3])).lower()]
        for number, (customer, old, new, age_at_new) in enumerate(sorted(visible, key=lambda row: (-row[1]["balance"], row[0].lower())), 1):
            self.control_tree.insert("", "end", tags=("even" if number % 2 == 0 else "odd",), values=(number, customer, old["reference"], old["billing_date"].strftime("%d/%m/%Y"), age_at_new, as_money(old["balance"]), new["reference"], new["billing_date"].strftime("%d/%m/%Y"), as_money(new["total"])))
        self.control_tree.insert("", "end", tags=("total",), values=("", "TOTAL", "", "", "", as_money(sum(row[1]["balance"] for row in visible)), "", "", as_money(sum(row[2]["total"] for row in visible))))

    def _populate_customers(self, rows: list[dict]) -> None:
        selected_slab = self.slab_filter.get().split(":")[0] if self.slab_filter.get() != "All slabs" else None
        query = self.search_customer.get().strip().lower()
        filtered_rows = [row for row in rows if (not selected_slab or row["slab"] == selected_slab) and (not query or query in " ".join(str(value) for value in row.values()).lower())]
        grouped: dict[tuple[str, str], list[dict]] = defaultdict(list)
        for row in filtered_rows: grouped[(row["customer"], row["slab"])].append(row)
        summaries: dict[str, list[dict]] = defaultdict(list)
        for (customer, _slab), values in grouped.items(): summaries[customer].extend(values)
        self.customer_details = {}
        if self.sort_by.get() == "Bill value": key = lambda customer: -sum(row["total"] for row in summaries[customer])
        elif self.sort_by.get() == "Number of bills": key = lambda customer: -len(summaries[customer])
        else: key = lambda customer: -sum(row["balance"] for row in summaries[customer])
        number = 0
        for customer in sorted(summaries, key=lambda name: (key(name), name.lower())):
            items = [(slab, values) for (name, slab), values in grouped.items() if name == customer]
            for slab, values in sorted(items, key=lambda pair: SLAB_ORDER[pair[0]]):
                number += 1
                iid = f"detail_{number}"
                self.customer_details[iid] = values
                self.customer_tree.insert("", "end", iid=iid, tags=("even" if number % 2 == 0 else "odd",), values=(number, customer, len(summaries[customer]), f"{slab}: {next(x[1] for x in SLABS if x[0] == slab)}", len(values), as_money(sum(x["total"] for x in values)), as_money(sum(x["balance"] for x in values)), as_money(max(x["balance"] for x in values)), "▾ View bills"))
        self.customer_tree.insert("", "end", tags=("total",), values=("", "TOTAL", len(filtered_rows), "", "", as_money(sum(row["total"] for row in filtered_rows)), as_money(sum(row["balance"] for row in filtered_rows)), as_money(max((row["balance"] for row in filtered_rows), default=0)), ""))

    def show_invoice_details(self, event: tk.Event) -> None:
        row_id = self.customer_tree.identify_row(event.y)
        if not row_id or row_id not in self.customer_details:
            return
        bills = sorted(self.customer_details[row_id], key=lambda bill: bill["billing_date"])
        values = self.customer_tree.item(row_id, "values")
        window = tk.Toplevel(self); window.title(f"Invoices - {values[1]} | {values[3]}"); window.geometry("620x360"); window.transient(self)
        ttk.Label(window, text=f"{values[1]}  •  {values[3]}", font=("Segoe UI", 12, "bold")).pack(anchor="w", padx=16, pady=(15, 8))
        table = self._tree(window, ("no", "date", "bill", "balance"), ("No.", "Bill date", "Bill number", "Balance"))
        for number, bill in enumerate(bills, 1):
            table.insert("", "end", tags=("even" if number % 2 == 0 else "odd",), values=(number, bill["billing_date"].strftime("%d/%m/%Y"), bill["reference"], as_money(bill["balance"])))
        table.insert("", "end", tags=("total",), values=("", "TOTAL", "", as_money(sum(bill["balance"] for bill in bills))))

    def _populate_balances(self, rows: list[dict]) -> None:
        total_due = sum(row["balance"] for row in rows)
        query = self.search_balance.get().strip().lower()
        for number, (code, name, _low, _high) in enumerate(SLABS, 1):
            label = f"{code}: {name}"
            if query and query not in label.lower():
                continue
            balance = sum(row["balance"] for row in rows if row["slab"] == code)
            self.balance_tree.insert("", "end", tags=("even" if number % 2 == 0 else "odd",), values=(number, label, as_money(balance), f"{(balance / total_due * 100) if total_due else 0:.2f}%"))
        self.balance_tree.insert("", "end", tags=("total",), values=("", "TOTAL OUTSTANDING", as_money(total_due), "100.00%"))

    def _populate_party_billing(self, rows: list[dict]) -> None:
        threshold = clean_number(self.party_min_due.get())
        gap_days = max(int(clean_number(self.party_gap_days.get())), 0)
        query = self.search_party.get().strip().lower()
        by_customer: dict[str, list[dict]] = defaultdict(list)
        for row in rows:
            by_customer[row["customer"]].append(row)
        results = []
        self.party_details = {}
        for customer, bills in by_customer.items():
            bills.sort(key=lambda bill: bill["billing_date"])
            old_bill, later_bills = bills[0], bills[1:]
            has_required_gap = any((new["billing_date"] - old_bill["billing_date"]).days > gap_days for new in later_bills)
            if old_bill["balance"] > threshold and later_bills and has_required_gap and (not query or query in customer.lower()):
                party_total = sum(bill["balance"] for bill in bills)
                old_balance = old_bill["balance"]
                new_balance = sum(bill["balance"] for bill in later_bills)
                results.append((customer, party_total, old_balance, new_balance, old_bill, later_bills))
        for number, (customer, party_total, old_balance, new_balance, old_bill, later_bills) in enumerate(sorted(results, key=lambda row: (-row[2], row[0].lower())), 1):
            iid = f"party_{number}"
            self.party_details[iid] = (old_bill, later_bills)
            self.party_tree.insert("", "end", iid=iid, tags=("even" if number % 2 == 0 else "odd",), values=(number, customer, as_money(party_total), as_money(old_balance), as_money(new_balance), "▾ View bills"))
        self.party_tree.insert("", "end", tags=("total",), values=("", "TOTAL", as_money(sum(row[1] for row in results)), as_money(sum(row[2] for row in results)), as_money(sum(row[3] for row in results)), ""))

    def show_party_details(self, event: tk.Event) -> None:
        row_id = self.party_tree.identify_row(event.y)
        if not row_id or row_id not in self.party_details:
            return
        old_bill, new_bills = self.party_details[row_id]
        customer = self.party_tree.item(row_id, "values")[1]
        window = tk.Toplevel(self); window.title(f"Party invoice details - {customer}"); window.geometry("760x390"); window.transient(self)
        ttk.Label(window, text=f"{customer}  •  Oldest bill is treated as Old Balance", font=("Segoe UI", 12, "bold")).pack(anchor="w", padx=16, pady=(15, 8))
        table = self._tree(window, ("no", "type", "bill", "date", "bill_value", "balance"), ("No.", "Type", "Bill number", "Bill date", "Bill value", "Balance"))
        table.insert("", "end", tags=("odd",), values=(1, "OLDEST OLD BILL", old_bill["reference"], old_bill["billing_date"].strftime("%d/%m/%Y"), as_money(old_bill["total"]), as_money(old_bill["balance"])))
        for number, bill in enumerate(new_bills, 2):
            table.insert("", "end", tags=("even" if number % 2 == 0 else "odd",), values=(number, "NEW BILL", bill["reference"], bill["billing_date"].strftime("%d/%m/%Y"), as_money(bill["total"]), as_money(bill["balance"])))
        table.insert("", "end", tags=("total",), values=("", "TOTAL", "", "", as_money(old_bill["total"] + sum(bill["total"] for bill in new_bills)), as_money(old_bill["balance"] + sum(bill["balance"] for bill in new_bills))))

    @staticmethod
    def _clear(tree: ttk.Treeview) -> None: tree.delete(*tree.get_children())

    def export_pdf(self, tab: ttk.Frame) -> None:
        tree = self.slab_tree if tab is self.slab_tab else self.control_tree if tab is self.control_tab else self.customer_tree if tab is self.customer_tab else self.balance_tree if tab is self.balance_tab else self.party_tree
        title = "BILLS BY SLAB" if tab is self.slab_tab else "OLD DUE BILLING" if tab is self.control_tab else "BILLS COUNT BY SLAB" if tab is self.customer_tab else "BALANCE BY SLAB" if tab is self.balance_tab else "OLD DUE VS NEW BILLS"
        path = filedialog.asksaveasfilename(title="Save PDF report", defaultextension=".pdf", initialfile=f"{title.replace(' ', '_')}.pdf", filetypes=[("PDF", "*.pdf")])
        if not path: return
        self._write_pdf(path, title, tree)

    def _write_pdf(self, path: str, title: str, tree: ttk.Treeview) -> None:
        headers = [tree.heading(col)["text"] for col in tree["columns"]]
        values = [list(tree.item(item)["values"]) for item in tree.get_children()]
        styles = getSampleStyleSheet(); story = [Paragraph("GSA-ANALYTICS", styles["Title"]), Paragraph(title, styles["Heading2"]), Paragraph(f"Ageing calculated from billing date only. As on: {self.as_on.get()}", styles["Normal"]), Spacer(1, 6*mm)]
        rows = [headers] + values if values else [headers, ["No matching records"] + [""] * (len(headers)-1)]
        table = Table(rows, repeatRows=1, colWidths=[(landscape(A4)[0]-24*mm)/len(headers)]*len(headers))
        table.setStyle(TableStyle([("BACKGROUND", (0,0), (-1,0), colors.HexColor("#172554")), ("TEXTCOLOR", (0,0), (-1,0), colors.white), ("FONTNAME", (0,0), (-1,0), "Helvetica-Bold"), ("FONTSIZE", (0,0), (-1,-1), 7), ("GRID", (0,0), (-1,-1), .25, colors.HexColor("#CBD5E1")), ("ROWBACKGROUNDS", (0,1), (-1,-1), [colors.white, colors.HexColor("#F8FAFC")]), ("VALIGN", (0,0), (-1,-1), "MIDDLE"), ("LEFTPADDING", (0,0), (-1,-1), 4), ("RIGHTPADDING", (0,0), (-1,-1), 4), ("TOPPADDING", (0,0), (-1,-1), 4), ("BOTTOMPADDING", (0,0), (-1,-1), 4)]))
        story.append(table); SimpleDocTemplate(path, pagesize=landscape(A4), leftMargin=12*mm, rightMargin=12*mm, topMargin=12*mm, bottomMargin=12*mm).build(story)
        messagebox.showinfo("PDF created", f"Saved report to:\n{path}")


if __name__ == "__main__": App().mainloop()
