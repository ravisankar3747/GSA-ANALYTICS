"""Compare Android's pure-Java report engine to the supplied, unedited V7 source."""
import ast
import collections
import datetime
import pathlib
import random
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
source = ast.parse((ROOT / "reference/windows_v7.py").read_text(encoding="utf-8-sig"))
names = {"clean_number", "parse_date", "as_money", "slab_for"}
methods = {"records", "refresh_reports", "_populate_control", "_populate_customers", "_populate_balances", "_populate_party_billing", "_clear"}
nodes = []
for node in source.body:
    if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id in {"SLABS", "SLAB_ORDER"} for t in node.targets):
        nodes.append(node)
    if isinstance(node, ast.FunctionDef) and node.name in names:
        nodes.append(node)
    if isinstance(node, ast.ClassDef) and node.name == "App":
        node.bases = []
        node.body = [n for n in node.body if isinstance(n, ast.FunctionDef) and n.name in methods]
        nodes.append(node)
scope = dict(date=datetime.date, datetime=datetime.datetime, defaultdict=collections.defaultdict)
exec(compile(ast.fix_missing_locations(ast.Module(body=[ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)] + nodes, type_ignores=[])), "v7_reference", "exec"), scope)

class Var:
    def __init__(self, value): self.value = value
    def get(self): return self.value

class Tree:
    def __init__(self): self.rows = []
    def insert(self, *args, **kwargs): self.rows.append([str(x) for x in kwargs["values"]])
    def get_children(self): return []
    def delete(self, *args): self.rows = []

rng = random.Random(70001)
cases = []
for case in range(40):
    rows = []
    for n in range(24):
        rows.append(dict(customer=f"Party {rng.randrange(4)}", group="General", reference=f"SALE-FY/25-26/{n}", billing_date=datetime.date(2026, 1, 1)+datetime.timedelta(days=rng.randrange(200)), total=float(rng.randrange(1, 30)*100), balance=float(rng.choice([0, 500, 501, 600, 1200]))))
    cutoff = datetime.date(2026, 7, 1)
    threshold = rng.choice([0, 500, 600])
    gap = rng.choice([0, 15, 60, 61])
    slab = rng.choice([-1, 0, 4, 7])
    sort = rng.randrange(3)
    query = rng.choice(["", "Party 1", "SALE-FY/25-26/1", "S1", "nonexistent"])
    app = scope["App"]()
    app.invoices = rows
    for name, value in dict(as_on="01/07/2026", minimum_due=str(threshold), old_due_days=str(gap), party_min_due=str(threshold), party_gap_days=str(gap), sort_by=["Highest balance", "Bill value", "Number of bills"][sort], slab_filter="All slabs" if slab<0 else f"S{slab+1}: x", slab_filter_bills="All slabs" if slab<0 else f"S{slab+1}: x", search_slab=query, search_control=query, search_customer=query, search_balance=query, search_party=query).items():
        setattr(app, name, Var(value))
    tree_names = ["balance_tree", "slab_tree", "customer_tree", "control_tree", "party_tree"]
    for name in tree_names: setattr(app, name, Tree())
    app.refresh_reports()
    cases.append((rows, cutoff, threshold, gap, slab, sort, query, [getattr(app, name).rows for name in tree_names]))

def java_string(value):
    import json
    return json.dumps(str(value), ensure_ascii=True)

with tempfile.TemporaryDirectory() as directory:
    path = pathlib.Path(directory)
    statements = []
    for index, (rows, cutoff, threshold, gap, slab, sort, query, expected) in enumerate(cases):
        body = ["List<Reports.Invoice> rows=new ArrayList<>();"]
        for row in rows:
            body.append('rows.add(new Reports.Invoice(%s,"General",%s,LocalDate.parse(%s),%s,%s));' % (java_string(row["customer"]), java_string(row["reference"]), java_string(row["billing_date"]), row["total"], row["balance"]))
        body.append('Reports.Options o=new Reports.Options(); o.asOn=LocalDate.parse("2026-07-01"); o.threshold=%s; o.gap=%s; o.slab=%s; o.customerSort=%s; o.query=%s;' % (threshold, gap, slab, sort, java_string(query)))
        for report, values in enumerate(expected):
            encoded = "\n".join("\t".join(row) for row in values)
            body.append('check(%s, Reports.build(%s,rows,o),%s);' % (java_string(f"case {index}, report {report}"), report, java_string(encoded)))
        statements.append("static void case%s() { %s }" % (index,"\n".join(body)))
    runner = '''import com.gsa.analytics.Reports; import java.util.*; import java.time.*;
public class Parity {
static void check(String name,Reports.Table table,String expected) {
String actual=String.join("\\n",table.all().stream().map(r->String.join("\\t",r.cells)).toArray(String[]::new));
if(!actual.equals(expected)) throw new AssertionError(name+"\\nEXPECTED\\n"+expected+"\\nACTUAL\\n"+actual);
}
''' + "\n".join(statements) + "\npublic static void main(String[] args) {" + "".join(f"case{i}();" for i in range(len(cases))) + 'System.out.println("PASS: 200 V7 differential report cases");}}'
    (path / "Parity.java").write_text(runner, encoding="utf-8")
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, str(ROOT / "app/src/main/java/com/gsa/analytics/Reports.java"), str(path / "Parity.java")], check=True)
    subprocess.run(["java", "-cp", directory, "Parity"], check=True)
