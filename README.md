# GSA-ANALYTICS for Android

Native Android port of the supplied Windows V7 `sale_ageing_pro.py`. Android 8.0 (API 26) or newer is required. Import a Vyapar `.xls` or text-based sale-ageing PDF using the import icon. Select a report, adjust the ageing date, search, filter, sort by a column heading, or export the displayed table to PDF. Tap a customer or party row to see its invoices.

## Download the APK

Open [Android APK builds](https://github.com/ravisankar3747/GSA-ANALYTICS/actions/workflows/android.yml), select the latest successful run, and download **GSA-ANALYTICS-V7-Android-APK** under Artifacts. Extract the ZIP and install `GSA-ANALYTICS-V7-Android.apk` on your Android device. GitHub requires sign-in for artifact downloads. Builds run on every push to `main` and can also be started with **Run workflow**. Artifacts are retained for 90 days.

This is an installable **debug-signed APK**, suitable for testing and direct installation, not a Play Store production release. Each clean CI runner may generate a different debug key; uninstalling an older build may be necessary before installing a later one, which deletes its private saved report. Original imported files are unaffected. A production release needs a stable private signing key stored in GitHub Actions secrets; no signing secrets or credentials are committed here.

## V7 functionality

| Windows report | Android functionality |
| --- | --- |
| Balance by slab | Eight slabs, outstanding balance, percentage of total, search, totals |
| Bills by slab | Billing-date ageing, invoice values/balances, slab filter, search, totals |
| Bills count by slab | Customer/slab groups, bill counts, invoice values, balances, maximum balance, three customer ranking modes, invoice details |
| Old due billing | All qualifying old/new invoice pairs; configurable minimum balance and age gap; pair totals |
| Old due vs new bills | Oldest outstanding invoice as old balance, all other outstanding invoices as new balance; configurable qualifying gap; party details |

All five reports have sortable headings and landscape PDF export of the current filtered and sorted rows. Large tables scroll horizontally; rows are recycled vertically. Import, calculations and PDF export run off the UI thread. Report data and settings survive reopening the app. The delete icon clears the saved imported records.

## Exact rule notes

- Only invoices with a billing date and positive current balance participate. Due dates are ignored. Future billing dates have zero age.
- Slabs: 0-15, 16-30, 31-45, 46-60, 61-90, 91-120, 121-150, and 151+ days. V7 labels the final slab `150+ days`; that label is preserved.
- Defaults: old balance **greater than** 500; billing gap **greater than** 60 days. Equality does not qualify.
- Old due billing uses the current outstanding balance, not reconstructed historical balances. The same old balance can appear in multiple pairs and is counted repeatedly in that report's total, matching V7.
- Old due vs new bills considers only the oldest positive-balance invoice as old. At least one later invoice must exceed the gap. Once qualified, every remaining positive-balance invoice contributes to new balance, including same-date invoices.
- Customer counts/rankings are calculated after search/slab filtering. Balance-by-slab percentages and the final total retain the full outstanding denominator even when search hides slabs. The total percentage remains 100.00% on an empty report, matching V7.
- Money calculations retain V7's floating-point arithmetic and two-decimal display.

## Import limits

The XLS workbook must contain `Outstanding Sale Invoices`. Columns match V7: reference 0, billing date 1, invoice value 4, balance 5. The reference parser expects text billing dates. XLSX is not supported. PDF import supports the V7 `SALE-FY/YY-YY/number` and `S-Y/YY-YY/number` invoice layouts with extractable text; scanned PDFs/OCR and other layouts are not supported. Prefer XLS for accuracy. Android PDFBox and Windows pypdf may extract unusual PDF layouts differently, so compare totals with the source report. Mobile imports are limited to 50 MB.

The report date comes first from `to_DD-MM-YYYY` or `Report_DD-MM-YYYY` in the filename, then `Sale Aging Report DD/MM/YYYY` in content. Otherwise the existing ageing date remains selected.

## Build and test

Use JDK 17, Android SDK 35 and Gradle 8.11.1:

```sh
gradle wrapper --gradle-version 8.11.1 --distribution-type bin
./gradlew testDebugUnitTest lintDebug assembleDebug
python tools/v7_parity.py
```

The workflow bootstraps the standard Gradle wrapper with pinned Gradle. It compares 200 generated report cases with the original Python V7 logic, runs focused Java tests and Android lint, and publishes the APK. A dependent emulator job tests report rendering/restoration and multi-page PDF output on Android 15, uploading screenshots and device test reports.

The unedited Windows reference is in `reference/windows_v7.py`, SHA-256 `baf3fc6b121444d989cf2673b218336f91bbe482ecd4b58578602dd1a17614b9`. Desktop UI and executables are not packaged into the APK.

## Privacy and authentication

There is no account, password, API key, token, server, analytics SDK or network permission. Imports and exports use Android's document picker. Imported invoice data is stored in the app's private directory and Android backup is disabled. Temporary source copies are deleted after import. Choosing a cloud document provider may cause that provider, outside this app, to transfer the selected file. The GitHub workflow uses the short-lived GitHub-provided token with read-only repository permissions.

## Dependencies

- Android Gradle Plugin 8.9.2, Gradle 8.11.1 and JDK 17: [compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes).
- JExcelAPI 2.6.12 (LGPL): [XLS parser](https://jexcelapi.sourceforge.net/).
- PDFBox-Android 2.0.27.0 (Apache-2.0): [PDF parser](https://github.com/TomRoush/PdfBox-Android).
- Android framework `PdfDocument`: PDF output.

The source project and dependencies remain subject to their respective owners' rights and licenses.
