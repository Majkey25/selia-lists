# Security policy

Report vulnerabilities through [GitHub private vulnerability reporting](https://github.com/Majkey25/selia-lists/security/advisories/new). Do not publish exploit details in a public issue before a fix is available.

SheetSet is offline and requests no Android permissions. The main trust boundary is imported PDF data. The importer caps files at 250 MiB, checks the PDF signature, and opens the private copy with Android `PdfRenderer` before adding it to the catalog.

Security fixes are supported on the latest release only while the project remains in alpha.
