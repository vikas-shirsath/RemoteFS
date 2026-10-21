
import sys
sys.stdout.reconfigure(encoding='utf-8')

from docx import Document
from docx.shared import Pt, RGBColor, Inches, Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_ALIGN_VERTICAL
from docx.oxml.ns import qn
from docx.oxml import OxmlElement
import copy

# ── helpers ──────────────────────────────────────────────────────────────────

def set_cell_bg(cell, hex_color):
    tc = cell._tc
    tcPr = tc.get_or_add_tcPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:val'), 'clear')
    shd.set(qn('w:color'), 'auto')
    shd.set(qn('w:fill'), hex_color)
    tcPr.append(shd)

def set_cell_borders(cell, top=None, bottom=None, left=None, right=None):
    tc = cell._tc
    tcPr = tc.get_or_add_tcPr()
    tcBorders = OxmlElement('w:tcBorders')
    for side, val in [('top', top), ('bottom', bottom), ('left', left), ('right', right)]:
        if val:
            el = OxmlElement(f'w:{side}')
            el.set(qn('w:val'), val.get('val', 'single'))
            el.set(qn('w:sz'), val.get('sz', '4'))
            el.set(qn('w:space'), '0')
            el.set(qn('w:color'), val.get('color', '000000'))
            tcBorders.append(el)
    tcPr.append(tcBorders)

def para_style(para, bold=False, italic=False, size=None, color=None,
               align=WD_ALIGN_PARAGRAPH.LEFT, space_before=0, space_after=0):
    para.alignment = align
    para.paragraph_format.space_before = Pt(space_before)
    para.paragraph_format.space_after = Pt(space_after)
    for run in para.runs:
        run.bold = bold
        run.italic = italic
        if size:
            run.font.size = Pt(size)
        if color:
            run.font.color.rgb = RGBColor(*bytes.fromhex(color))

def add_heading(doc, text, level=1, color='1F3864', size=None, bold=True,
                space_before=12, space_after=4, align=WD_ALIGN_PARAGRAPH.LEFT):
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.bold = bold
    run.font.color.rgb = RGBColor(*bytes.fromhex(color))
    sizes = {1: 14, 2: 12, 3: 11}
    run.font.size = Pt(size or sizes.get(level, 11))
    p.alignment = align
    p.paragraph_format.space_before = Pt(space_before)
    p.paragraph_format.space_after = Pt(space_after)
    return p

def add_body(doc, text, size=11, bold=False, italic=False, color=None,
             space_before=2, space_after=4, align=WD_ALIGN_PARAGRAPH.JUSTIFY):
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.font.size = Pt(size)
    run.bold = bold
    run.italic = italic
    if color:
        run.font.color.rgb = RGBColor(*bytes.fromhex(color))
    p.alignment = align
    p.paragraph_format.space_before = Pt(space_before)
    p.paragraph_format.space_after = Pt(space_after)
    return p

def add_bullet(doc, text, size=10.5):
    p = doc.add_paragraph(style='List Bullet')
    run = p.add_run(text)
    run.font.size = Pt(size)
    p.paragraph_format.space_before = Pt(1)
    p.paragraph_format.space_after = Pt(2)
    return p

def add_code_block(doc, code_text, size=8.5):
    """Add a shaded code block paragraph."""
    p = doc.add_paragraph()
    run = p.add_run(code_text)
    run.font.name = 'Courier New'
    run.font.size = Pt(size)
    p.paragraph_format.left_indent = Cm(0.5)
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.space_after = Pt(4)
    # Light grey background
    pPr = p._p.get_or_add_pPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:val'), 'clear')
    shd.set(qn('w:color'), 'auto')
    shd.set(qn('w:fill'), 'F0F0F0')
    pPr.append(shd)
    return p

def add_section_rule(doc):
    """Add a thin horizontal rule after a section."""
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(2)
    p.paragraph_format.space_after = Pt(2)
    pPr = p._p.get_or_add_pPr()
    pBdr = OxmlElement('w:pBdr')
    bottom = OxmlElement('w:bottom')
    bottom.set(qn('w:val'), 'single')
    bottom.set(qn('w:sz'), '4')
    bottom.set(qn('w:space'), '1')
    bottom.set(qn('w:color'), 'BFBFBF')
    pBdr.append(bottom)
    pPr.append(pBdr)

# ── document ──────────────────────────────────────────────────────────────────

doc = Document()

# Page margins
section = doc.sections[0]
section.top_margin = Cm(2)
section.bottom_margin = Cm(2)
section.left_margin = Cm(2.5)
section.right_margin = Cm(2.5)

# Default font
style = doc.styles['Normal']
style.font.name = 'Calibri'
style.font.size = Pt(11)

# ── COVER PAGE ───────────────────────────────────────────────────────────────

# College logo
try:
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run()
    run.add_picture(r'c:\study_material\RemotFS\image1.png', width=Inches(1.5))
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(6)
except Exception as e:
    print(f"Logo image skipped: {e}")

# College name
p = doc.add_paragraph()
run = p.add_run("Pimpri Chinchwad Education Trust's")
run.bold = True
run.font.size = Pt(13)
run.font.color.rgb = RGBColor(0x1F, 0x38, 0x64)
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(2)

p = doc.add_paragraph()
run = p.add_run("Pimpri Chinchwad College of Engineering")
run.bold = True
run.font.size = Pt(14)
run.font.color.rgb = RGBColor(0x1F, 0x38, 0x64)
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(2)

p = doc.add_paragraph()
run = p.add_run("Sector No. 26, Pradhikaran, Nigdi, Pune – 411 044")
run.font.size = Pt(10)
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(18)

add_section_rule(doc)

# Course title
p = doc.add_paragraph()
run = p.add_run("Distributed Computing Systems – Mini Project")
run.bold = True
run.font.size = Pt(13)
run.font.color.rgb = RGBColor(0x2E, 0x74, 0xB5)
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_before = Pt(12)
p.paragraph_format.space_after = Pt(18)

# Student details table
table = doc.add_table(rows=4, cols=2)
table.alignment = WD_TABLE_ALIGNMENT.CENTER
table.style = 'Table Grid'
widths = [Cm(5), Cm(10)]

rows_data = [
    ("Name", "Vikas Shirsath,  Kartik Ware,  Anuj Pathade"),
    ("PRN No.", "123B1F091,  123B1F104,  123B1F070"),
    ("Division", "B"),
    ("Title", "RemoteFS – Remote File Management System using Java RMI"),
]

for i, (label, value) in enumerate(rows_data):
    row = table.rows[i]
    row.cells[0].width = widths[0]
    row.cells[1].width = widths[1]

    c0, c1 = row.cells[0], row.cells[1]
    set_cell_bg(c0, 'DEEAF1')

    p0 = c0.paragraphs[0]
    run0 = p0.add_run(label)
    run0.bold = True
    run0.font.size = Pt(10.5)
    run0.font.color.rgb = RGBColor(0x1F, 0x38, 0x64)

    p1 = c1.paragraphs[0]
    run1 = p1.add_run(value)
    run1.font.size = Pt(10.5)
    if i == 3:
        run1.bold = True
        run1.italic = True

doc.add_paragraph().paragraph_format.space_after = Pt(18)

add_section_rule(doc)

doc.add_page_break()

# ── 1. ABSTRACT ──────────────────────────────────────────────────────────────

add_heading(doc, "1. Abstract", level=1)
add_body(doc,
    "RemoteFS is a distributed remote file management system built on Java Remote Method Invocation (RMI). "
    "It enables multiple concurrent clients on a LAN to perform file operations — read, write, append, rename, "
    "delete, mkdir, list, and stat — on a centralized server, as if the remote filesystem were local. "
    "The system is designed around three core engineering challenges inherent to distributed file systems: "
    "concurrent access safety, data integrity, and security.",
    space_after=4)
add_body(doc,
    "Concurrent safety is achieved through a per-file ReentrantReadWriteLock map (LockManager) with "
    "fair scheduling and a 5-second tryLock timeout, ensuring writers are never starved and no client "
    "waits indefinitely. Data integrity for overwrites uses optimistic concurrency control via per-file "
    "integer version numbers (VersionManager): the client must present the version it last observed, "
    "and any intervening write by another client causes an immediate conflict rejection rather than a "
    "silent lost update. Atomicity of file writes is guaranteed through a write-to-temp + ATOMIC_MOVE "
    "strategy, so readers always see either the complete old or complete new content. Deadlock during "
    "concurrent renames is prevented by always acquiring two path locks in sorted alphabetical order.",
    space_after=4)
add_body(doc,
    "Security is enforced through salted SHA-256 password hashing, UUID-based session tokens with "
    "30-minute inactivity expiry, and a PathValidator that normalises and bounds-checks every path "
    "to prevent directory traversal attacks. A JUnit 5 test suite of 25 automated tests (6 test classes) "
    "validates all scenarios including concurrent appends, atomic reads-during-writes, version conflicts, "
    "cross-rename deadlock avoidance, path traversal attempts, and authentication edge cases. "
    "All 25 tests pass.")

add_section_rule(doc)

# ── 2. INTRODUCTION ──────────────────────────────────────────────────────────

add_heading(doc, "2. Introduction", level=1)
add_body(doc,
    "Modern distributed applications must provide transparent access to shared resources across a network "
    "while preserving the correctness guarantees users expect from local storage. File systems are among "
    "the most fundamental shared resources in any computing environment. When multiple remote clients "
    "concurrently read and write the same files, a distributed file server must carefully mediate access "
    "to avoid data corruption, lost updates, and race conditions.")
add_body(doc,
    "RemoteFS was developed as a laboratory project for the Distributed Computing Systems course to "
    "demonstrate how classical distributed systems concepts — RMI, locking, versioning, atomic I/O, "
    "and security hardening — can be composed into a coherent, production-grade file server. The system "
    "is implemented entirely in Java using the standard library (Java 17), with no external dependencies "
    "beyond JUnit 5 for testing. It is structured as a Maven multi-module project (common, server, "
    "client, tests), buildable with the included Maven Wrapper without any pre-installed tooling.")
add_body(doc,
    "The client presents an interactive REPL shell offering ten commands (login, ls, cat, put, get, "
    "append, rm, mv, mkdir, stat) that map directly to RMI calls on the server. The server manages a "
    "local directory tree under server_data/ and mediates every operation through five cooperating "
    "components: PathValidator, LockManager, VersionManager, AuthManager, and AuditLogger.")

add_section_rule(doc)

# ── 3. OBJECTIVES ────────────────────────────────────────────────────────────

add_heading(doc, "3. Objectives", level=1)
objectives = [
    ("Transparent Remote Access via Java RMI",
     "Expose all file operations as remote methods over the RMI protocol so that clients interact with "
     "the server file system using the same interface they would use locally, with no client-side file "
     "I/O code."),
    ("Safe Concurrent Multi-Client Access",
     "Prevent data corruption when multiple clients read and write the same file simultaneously by "
     "implementing a per-file ReentrantReadWriteLock strategy with shared read locks and exclusive write locks."),
    ("Optimistic Version Control to Prevent Lost Updates",
     "Detect and reject stale writes using per-file integer version numbers so that a client writing "
     "with an outdated view of the file receives an explicit conflict error rather than silently "
     "overwriting another client's changes."),
    ("Atomic Write Operations",
     "Guarantee that readers never observe a file in a partially-written state by staging all writes to "
     "a temporary file and completing the update with a single atomic rename (Files.move with ATOMIC_MOVE)."),
    ("Deadlock-Free Rename",
     "Prevent circular-wait deadlocks when two clients rename files in opposing directions by always "
     "acquiring the two required write locks in sorted alphabetical order (resource ordering)."),
    ("Writer Starvation Prevention",
     "Ensure writers eventually gain access even under a continuous stream of readers by using "
     "fair=true ReentrantReadWriteLocks that serve waiting threads in FIFO arrival order."),
    ("Security: Authentication and Path Integrity",
     "Authenticate users with salted SHA-256 credentials and UUID session tokens, and reject "
     "any path that escapes the storage root (directory traversal, symlink escapes, absolute paths)."),
    ("Accountability via Audit Logging",
     "Record every client operation — including failures — to an append-only audit log so that "
     "administrators can trace all access to the file system."),
    ("Automated Correctness Verification",
     "Validate all concurrency guarantees and security properties with a JUnit 5 test suite that "
     "uses embedded RMI (no running server required) to exercise all 25 defined scenarios."),
]
for i, (title, desc) in enumerate(objectives, 1):
    p = doc.add_paragraph()
    run_num = p.add_run(f"{i}. ")
    run_num.bold = True
    run_num.font.size = Pt(10.5)
    run_title = p.add_run(f"{title}: ")
    run_title.bold = True
    run_title.font.size = Pt(10.5)
    run_title.font.color.rgb = RGBColor(0x2E, 0x74, 0xB5)
    run_desc = p.add_run(desc)
    run_desc.font.size = Pt(10.5)
    p.paragraph_format.space_before = Pt(3)
    p.paragraph_format.space_after = Pt(3)
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY

add_section_rule(doc)

# ── 4. THEORETICAL BACKGROUND ────────────────────────────────────────────────

add_heading(doc, "4. Theoretical Background", level=1)

add_heading(doc, "4.1  Java Remote Method Invocation (RMI)", level=2)
add_body(doc,
    "Java RMI allows a method defined in one JVM to be invoked by another JVM, transparently over "
    "a TCP network. The key elements are:")
add_bullet(doc, "Remote Interface: A Java interface that extends java.rmi.Remote. Every method must declare throws RemoteException.")
add_bullet(doc, "Remote Object: A class that extends UnicastRemoteObject and implements the remote interface. The super() constructor exports the object — opens a TCP server socket — and creates a serialisable stub.")
add_bullet(doc, "RMI Registry: A lightweight name service (port 1099) where the server binds its remote object under a name. Clients look up the stub by name and receive a local proxy.")
add_bullet(doc, "Stub: A serialisable proxy object that the client receives. Calling any method on the stub serialises the arguments, sends them over TCP to the server, the server deserialises and dispatches them, and the return value travels back the same way.")
add_bullet(doc, "All parameter and return types must implement java.io.Serializable so the RMI runtime can marshal them across the network boundary.")

add_heading(doc, "4.2  Distributed Concurrency Control", level=2)
add_body(doc,
    "When multiple clients concurrently access the same file, correctness requires two distinct mechanisms:")
add_bullet(doc, "Mutual Exclusion (Locking): Ensures that reads and writes do not interleave. A ReentrantReadWriteLock grants shared access to concurrent readers, but requires exclusive access for writers, preventing torn reads and writes.")
add_bullet(doc, "Optimistic Concurrency Control (Versioning): Prevents the lost-update anomaly without holding locks during the client think-time. Each file carries a monotonically increasing version number. A write is accepted only if the client presents the version it last observed; otherwise the server rejects it with a version-conflict error.")

add_heading(doc, "4.3  Atomic File I/O and the TOCTOU Problem", level=2)
add_body(doc,
    "Writing data directly to the target file is not atomic: a reader observing the file mid-write "
    "would see a mix of old and new bytes. RemoteFS eliminates this by writing all data to a temporary "
    "file first, then performing a single Files.move(ATOMIC_MOVE) — an OS-level directory-entry swap "
    "(rename(2) on Linux, MoveFileExW on Windows) — that readers never witness in a partial state.")
add_body(doc,
    "The TOCTOU (time-of-check to time-of-use) race is neutralised by performing the version check "
    "and increment inside the write lock. No other thread can modify the version between the check "
    "and the actual I/O.")

add_heading(doc, "4.4  Deadlock Avoidance by Resource Ordering", level=2)
add_body(doc,
    "The rename(from, to) operation must hold write locks on two paths simultaneously. "
    "If Thread 1 renames A→B and Thread 2 renames B→A, each thread may hold one lock while "
    "waiting for the other — a circular wait, i.e., deadlock. "
    "The classical solution (Coffman, 1971) is to impose a total order on resource acquisition: "
    "all threads must request locks in the same global order. RemoteFS uses alphabetical order "
    "of the normalised path strings. Both threads always try to lock the alphabetically-first "
    "path first; one wins and proceeds, the other waits — no cycle forms.")

add_heading(doc, "4.5  Security Fundamentals", level=2)
add_body(doc,
    "Three security principles are implemented:")
add_bullet(doc, "Salted Hashing: Passwords are never stored in plaintext. A random 128-bit salt is generated per user and stored alongside the SHA-256(salt || password) digest. This defeats rainbow-table attacks.")
add_bullet(doc, "Session Tokens: After successful login the server issues a random UUID token. Every subsequent RMI call presents this token; the server validates it and checks for 30-minute inactivity expiry. Tokens are held in a ConcurrentHashMap for thread-safe access.")
add_bullet(doc, "Path Validation: Every user-supplied path is resolved against the storage root and normalised. The result must start with the storage root path; otherwise it is rejected. Symlinks are additionally resolved with toRealPath() to catch symlink-based escapes.")

add_section_rule(doc)

# ── 5. SYSTEM ARCHITECTURE ───────────────────────────────────────────────────

add_heading(doc, "5. System Architecture", level=1)

add_heading(doc, "5.1  Module Structure", level=2)
add_body(doc,
    "RemoteFS is a Maven multi-module project comprising four modules:")

# Module table
tbl = doc.add_table(rows=5, cols=3)
tbl.style = 'Table Grid'
tbl.alignment = WD_TABLE_ALIGNMENT.LEFT
headers = ["Module", "Artifact / Package", "Key Classes"]
col_widths = [Cm(3.0), Cm(4.5), Cm(9.5)]
row0 = tbl.rows[0]
for j, h in enumerate(headers):
    row0.cells[j].width = col_widths[j]
    set_cell_bg(row0.cells[j], '1F3864')
    p = row0.cells[j].paragraphs[0]
    run = p.add_run(h)
    run.bold = True
    run.font.size = Pt(10)
    run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)

module_rows = [
    ("common", "remotefs.common", "FileService (Remote interface), FileInfo (Serializable DTO), FileOpException"),
    ("server", "remotefs.server", "ServerMain, FileServiceImpl, LockManager, VersionManager, AuthManager, PathValidator, AuditLogger"),
    ("client", "remotefs.client", "ClientMain (REPL loop + RMI lookup), ConsoleCommands (CLI command verbs)"),
    ("tests",  "remotefs.tests",  "ConcurrentAppendTest, AtomicReadWriteTest, VersionConflictTest, RenameDeadlockTest, PathTraversalTest, AuthSecurityTest"),
]
for i, (mod, pkg, classes) in enumerate(module_rows):
    row = tbl.rows[i + 1]
    for j, (text, bold) in enumerate([(mod, True), (pkg, False), (classes, False)]):
        row.cells[j].width = col_widths[j]
        p = row.cells[j].paragraphs[0]
        run = p.add_run(text)
        run.font.size = Pt(9.5)
        run.bold = bold
        run.font.name = 'Courier New' if bold else 'Calibri'
        if i % 2 == 1:
            set_cell_bg(row.cells[j], 'EBF3FB')

doc.add_paragraph()

add_heading(doc, "5.2  RMI Communication Flow", level=2)
add_body(doc,
    "Every client request follows the same pipeline inside FileServiceImpl:")
steps = [
    "Validate the session token (AuthManager.validateToken) — reject with FileOpException if missing or expired.",
    "Validate and resolve the path (PathValidator.resolve) — normalise, check startsWith(storageRoot), resolve symlinks.",
    "Derive the lock/version key (PathValidator.toKey) — a normalised relative path string.",
    "Acquire the appropriate lock (LockManager) — read lock for read(); write lock for write(), append(), delete(), rename().",
    "Perform the file I/O (java.nio.file.Files APIs).",
    "Release the lock in a finally block — guarantees release even if an exception occurs.",
    "Append to the audit log (AuditLogger) — records user, operation, path, and result.",
]
for i, step in enumerate(steps, 1):
    p = doc.add_paragraph()
    run = p.add_run(f"  {i}. {step}")
    run.font.size = Pt(10.5)
    p.paragraph_format.space_before = Pt(2)
    p.paragraph_format.space_after = Pt(2)

add_heading(doc, "5.3  Component Interaction Diagram", level=2)
add_code_block(doc,
"""  Client JVM                              Server JVM
  ┌─────────────┐   RMI Stub (TCP:1099)   ┌──────────────────────────────────────────┐
  │ ClientMain  │ ─────────────────────►  │ FileServiceImpl (UnicastRemoteObject)     │
  │ ConsoleCmd  │                          │                                          │
  └─────────────┘                          │  ┌─────────────┐  ┌──────────────────┐  │
                                           │  │ AuthManager │  │ PathValidator    │  │
                                           │  └─────────────┘  └──────────────────┘  │
                                           │  ┌─────────────┐  ┌──────────────────┐  │
                                           │  │ LockManager │  │ VersionManager   │  │
                                           │  └─────────────┘  └──────────────────┘  │
                                           │  ┌───────────────────────────────────┐  │
                                           │  │ AuditLogger → server_data/audit.log│  │
                                           │  └───────────────────────────────────┘  │
                                           └──────────────────────────────────────────┘
                                                         │
                                                  server_data/  (files on disk)""")

add_section_rule(doc)

# ── 6. FUNCTIONAL MODULES ────────────────────────────────────────────────────

add_heading(doc, "6. Functional Modules", level=1)

add_heading(doc, "6.1  FileService Remote Interface (common)", level=2)
add_body(doc,
    "FileService (remotefs.common.FileService) is the central RMI remote interface. "
    "It extends java.rmi.Remote and declares eight methods, each throwing RemoteException:")
add_code_block(doc,
"""public interface FileService extends Remote {
    String        login (String user, String password)              throws ...;
    List<FileInfo> list  (String token, String dir)                 throws ...;
    byte[]         read  (String token, String path)                throws ...;
    int            write (String token, String path,
                          byte[] data, int expectedVersion)         throws ...;
    void           append(String token, String path, byte[] data)   throws ...;
    void           delete(String token, String path)                throws ...;
    void           rename(String token, String from, String to)     throws ...;
    void           mkdir (String token, String path)                throws ...;
    FileInfo       stat  (String token, String path)                throws ...;
}""")
add_body(doc,
    "FileInfo is a Serializable DTO carrying: name, size (bytes), isDirectory, "
    "lastModifiedEpochMs, and version. FileOpException is a RemoteException subclass "
    "that overrides fillInStackTrace() to return an empty trace, preventing server "
    "internals from leaking to the client.")

add_heading(doc, "6.2  LockManager — Per-File Read-Write Locking", level=2)
add_body(doc,
    "LockManager maintains a ConcurrentHashMap<String, ReentrantReadWriteLock> keyed on "
    "the normalised relative path. Locks are created on demand with computeIfAbsent() "
    "(atomic — no race in creation). All locks are fair (new ReentrantReadWriteLock(true)) "
    "to prevent writer starvation. All acquisitions use tryLock(5, SECONDS) with a "
    "FileOpException(\"File is busy\") on timeout.")

# Lock type table
tbl2 = doc.add_table(rows=4, cols=3)
tbl2.style = 'Table Grid'
tbl2.alignment = WD_TABLE_ALIGNMENT.LEFT
h2 = ["Operation", "Lock Type", "Rationale"]
cw2 = [Cm(4.5), Cm(4.0), Cm(8.5)]
for j, h in enumerate(h2):
    tbl2.rows[0].cells[j].width = cw2[j]
    set_cell_bg(tbl2.rows[0].cells[j], '2E74B5')
    p = tbl2.rows[0].cells[j].paragraphs[0]
    run = p.add_run(h)
    run.bold = True
    run.font.size = Pt(10)
    run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)

lock_rows = [
    ("read()", "Shared read lock", "Concurrent readers; blocks only if a writer holds the write lock"),
    ("write(), append(), delete(), rename()", "Exclusive write lock", "One writer only; blocks all readers and other writers"),
    ("list(), mkdir(), stat()", "No lock", "Point-in-time metadata — same semantics as ls on a real OS"),
]
for i, (op, lt, rat) in enumerate(lock_rows):
    row = tbl2.rows[i + 1]
    for j, text in enumerate([op, lt, rat]):
        row.cells[j].width = cw2[j]
        p = row.cells[j].paragraphs[0]
        run = p.add_run(text)
        run.font.size = Pt(9.5)
        run.font.name = 'Courier New' if j == 0 else 'Calibri'
        if i % 2 == 1:
            set_cell_bg(row.cells[j], 'DEEAF1')

doc.add_paragraph()

add_heading(doc, "6.3  VersionManager — Optimistic Concurrency Control", level=2)
add_body(doc,
    "VersionManager maintains a ConcurrentHashMap<String, Integer> of file version numbers, "
    "persisted to server_data/.versions between server restarts. The write() operation calls "
    "checkAndIncrement(key, expectedVersion) inside the write lock:")
add_code_block(doc,
"""// Called inside the write lock — no TOCTOU race possible
public synchronized int checkAndIncrement(String key, int expectedVersion)
        throws FileOpException {
    int current = versions.getOrDefault(key, 0);
    if (current != expectedVersion) {
        throw new FileOpException(
            "Version conflict: expected " + expectedVersion +
            " but current version is " + current +
            ". Re-read the file and try again.");
    }
    int newVersion = current + 1;
    versions.put(key, newVersion);
    persistVersions();          // flush to .versions file
    return newVersion;
}""")

add_heading(doc, "6.4  AuthManager — Authentication and Session Management", level=2)
add_body(doc,
    "AuthManager loads user credentials from server_data/users.properties at startup. "
    "Each entry is stored as username=saltHex:sha256Hex. On login:")
add_bullet(doc, "Look up the stored salt and hash for the given username.")
add_bullet(doc, "Compute SHA-256(saltBytes || providedPassword.getBytes(UTF-8)).")
add_bullet(doc, "Compare with MessageDigest.isEqual() (constant-time — prevents timing attacks).")
add_bullet(doc, "On success: generate UUID.randomUUID().toString() as a session token and store it with the current timestamp.")
add_body(doc,
    "validateToken(token) checks existence in the ConcurrentHashMap<String, Session> "
    "and rejects or updates the last-access timestamp if found. Sessions expire after "
    "30 minutes of inactivity. A default users.properties is created on first run with "
    "three users: admin/admin123, alice/password1, bob/password2.")

add_heading(doc, "6.5  PathValidator — Directory Traversal Protection", level=2)
add_body(doc,
    "PathValidator.resolve(userPath) applies a four-step defence:")
add_bullet(doc, "Reject absolute paths and paths containing '..' immediately.")
add_bullet(doc, "Resolve the path against storageRoot using Path.resolve() and normalize() (collapses all . and .. elements).")
add_bullet(doc, "Assert normalised path startsWith(storageRoot) — catches ../  bypass attempts.")
add_bullet(doc, "If the path exists, call toRealPath() (resolves symlinks) and assert startsWith(storageRoot) again — catches symlink escapes.")

add_heading(doc, "6.6  AuditLogger — Accountability", level=2)
add_body(doc,
    "AuditLogger appends every operation to server_data/audit.log in ISO-8601 format:")
add_code_block(doc,
"""[2026-10-19T11:45:30.719] user=admin      op=LOGIN    path=-                    result=FAIL
[2026-10-19T11:45:30.733] user=admin      op=LOGIN    path=-                    result=OK
[2026-10-19T11:45:30.768] user=admin      op=WRITE    path=documents/pom.xml    result=OK (v1, 2640 bytes)
[2026-10-19T11:45:30.806] user=admin      op=RENAME   path=log.txt → renamed.txt result=OK""")
add_body(doc,
    "Audit failures are swallowed so that a logging error never disrupts a file operation.")

add_section_rule(doc)

# ── 7. IMPLEMENTATION DETAILS ────────────────────────────────────────────────

add_heading(doc, "7. Implementation Details", level=1)

add_heading(doc, "7.1  Atomic Write Flow", level=2)
add_body(doc,
    "The write() method inside FileServiceImpl follows this exact sequence:")
add_code_block(doc,
"""public int write(String token, String path, byte[] data, int expectedVersion)
        throws RemoteException, FileOpException {
    String user = authManager.validateToken(token);          // 1. auth
    Path target = pathValidator.resolve(path);               // 2. path safety
    String key  = pathValidator.toKey(target);               // 3. lock key

    int current = versionManager.getVersion(key);
    if (current != expectedVersion)                          // 4. early version check
        throw new FileOpException("Version conflict: ...");

    lockManager.acquireWriteLock(key);                       // 5. exclusive lock
    try {
        // Write to temp file first
        Path tmp = Files.createTempFile(target.getParent(), ".remotefs-", ".tmp");
        Files.write(tmp, data);                              // 6. temp write

        // Atomic rename: readers never see partial content
        Files.move(tmp, target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);            // 7. atomic swap

        int newVer = versionManager.checkAndIncrement(key, expectedVersion); // 8. version++
        auditLogger.log(user, "WRITE", path, "OK v" + newVer);
        return newVer;
    } finally {
        lockManager.releaseWriteLock(key);                   // 9. always release
    }
}""")

add_heading(doc, "7.2  Deadlock-Free Rename", level=2)
add_body(doc,
    "The rename() method acquires write locks on both source and destination paths "
    "in sorted alphabetical order:")
add_code_block(doc,
"""// Always acquire locks alphabetically — breaks circular-wait
lockManager.acquireWriteLocksOrdered(fromKey, toKey);
try {
    Files.move(fromPath, toPath, StandardCopyOption.REPLACE_EXISTING);
    versionManager.removeVersion(fromKey);
} finally {
    lockManager.releaseWriteLocks(fromKey, toKey);
}""")
add_body(doc,
    "acquireWriteLocksOrdered() sorts the two keys alphabetically, acquires the "
    "first, then the second — releasing the first immediately if the second fails, "
    "to prevent a half-locked state.")

add_heading(doc, "7.3  Client REPL Session", level=2)
add_body(doc,
    "ClientMain connects to the server via LocateRegistry.getRegistry(host, port).lookup(\"FileService\") "
    "and loops reading commands from stdin. ConsoleCommands maps each verb to its RMI call:")
add_code_block(doc,
"""login admin admin123          → svc.login("admin","admin123")  → stores token
mkdir docs                    → svc.mkdir(token, "docs")
put  C:\\myfile.txt docs/f.txt → svc.write(token, path, bytes, 0)
cat  docs/f.txt               → svc.read(token, path)  → UTF-8 print
stat docs/f.txt               → svc.stat(token, path)  → FileInfo
append docs/log.txt hello     → svc.append(token, path, bytes)
mv   docs/f.txt docs/new.txt  → svc.rename(token, from, to)
rm   docs/f.txt               → svc.delete(token, path)
ls   docs                     → svc.list(token, dir)
exit                          → disconnect""")

add_section_rule(doc)

# ── 8. TESTING & RESULTS ─────────────────────────────────────────────────────

add_heading(doc, "8. Verification and Test Results", level=1)

add_heading(doc, "8.1  Test Methodology", level=2)
add_body(doc,
    "All tests use an embedded RMI server (TestHelper spins up a full FileServiceImpl on a "
    "random port and tears it down after each class). No external running server is required. "
    "Tests are run with: .\\mvnw.cmd test -pl tests")

add_heading(doc, "8.2  Test Suite Summary", level=2)

tbl3 = doc.add_table(rows=8, cols=4)
tbl3.style = 'Table Grid'
tbl3.alignment = WD_TABLE_ALIGNMENT.LEFT
h3 = ["Test Class", "Tests", "Scenario", "Status"]
cw3 = [Cm(5.0), Cm(1.5), Cm(9.5), Cm(1.5)]
for j, h in enumerate(h3):
    tbl3.rows[0].cells[j].width = cw3[j]
    set_cell_bg(tbl3.rows[0].cells[j], '1F3864')
    p = tbl3.rows[0].cells[j].paragraphs[0]
    run = p.add_run(h)
    run.bold = True
    run.font.size = Pt(9.5)
    run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)

test_rows = [
    ("ConcurrentAppendTest", "1",  "10 threads append to same file simultaneously — all lines present, no corruption", "✓ Pass"),
    ("AtomicReadWriteTest",  "1",  "Readers during write see only complete old or complete new content, never a mix", "✓ Pass"),
    ("VersionConflictTest",  "1",  "Two writers with same expectedVersion — exactly one succeeds; other gets conflict error", "✓ Pass"),
    ("RenameDeadlockTest",   "2",  "Cross-rename (A↔B) completes without deadlock within 10 seconds", "✓ Pass"),
    ("PathTraversalTest",    "12", "../, absolute, and backslash paths all rejected; valid nested paths work correctly", "✓ Pass"),
    ("AuthSecurityTest",     "8",  "Invalid tokens, wrong passwords, expired sessions, user enumeration prevention", "✓ Pass"),
    ("Total",                "25", "All scenarios", "✓ 25/25"),
]
for i, (cls, cnt, scenario, status) in enumerate(test_rows):
    row = tbl3.rows[i + 1]
    for j, text in enumerate([cls, cnt, scenario, status]):
        row.cells[j].width = cw3[j]
        p = row.cells[j].paragraphs[0]
        run = p.add_run(text)
        run.font.size = Pt(9)
        if j == 0:
            run.font.name = 'Courier New'
        if i == 6:
            run.bold = True
            set_cell_bg(row.cells[j], 'E2EFDA')
        elif i % 2 == 1:
            set_cell_bg(row.cells[j], 'DEEAF1')
        if j == 3:
            run.font.color.rgb = RGBColor(0x37, 0x86, 0x10)

doc.add_paragraph()

add_heading(doc, "8.3  End-to-End Test Output (E2ETest)", level=2)
add_body(doc,
    "The E2ETest smoke test exercises all 17 operations against a live server. Sample output:")
add_code_block(doc,
"""=== RemoteFS End-to-End Test ===

✓ Connected to server
✓ [1]  login (wrong password rejected): Invalid username or password
✓ [2]  login admin/admin123 → token=3f7a1b2c...
✓ [3]  mkdir testdir
✓ [4]  write testdir/hello.txt → v1
✓ [5]  read testdir/hello.txt → correct content
✓ [6]  stat → name=hello.txt, size=17, v1
✓ [7]  write (overwrite v1 → v2)
✓ [8]  write version conflict detected: Version conflict: expected 1 but current is 2
✓ [9]  append → content has both original + appended
✓ [10] ls testdir → 1 item(s)
✓ [11] mv hello.txt → renamed.txt (v2)
✓ [12] old path correctly not found after rename
✓ [13] rm testdir/renamed.txt
✓ [14] rm testdir (empty dir)
✓ [15] path traversal blocked: Path escapes storage root
✓ [16] invalid token rejected: Invalid or expired session token
✓ [17] ls (root) → 0 item(s)

=== Results: 17 passed, 0 failed ===
ALL TESTS PASSED ✓""")

add_section_rule(doc)

# ── 9. ADVANTAGES AND LIMITATIONS ────────────────────────────────────────────

add_heading(doc, "9. Advantages and Limitations", level=1)

add_heading(doc, "9.1  Advantages", level=2)
adv_rows = [
    ("1", "Zero External Dependencies", "Built entirely on Java SE 17 standard library. No external frameworks, databases, or caches are required to build, run, or test."),
    ("2", "Starvation-Free Locking", "Fair ReentrantReadWriteLocks guarantee writers eventually get access under any read load."),
    ("3", "Atomic Writes", "The temp-file + ATOMIC_MOVE strategy ensures readers never observe partially-written content."),
    ("4", "Deadlock-Free Rename", "Resource-ordering (alphabetical lock acquisition) provably eliminates circular wait for the rename case."),
    ("5", "Lost-Update Prevention", "Optimistic versioning surfaces concurrent overwrites to the client as an explicit conflict rather than silently discarding one write."),
    ("6", "Defence-in-Depth Security", "Salted hashing + constant-time comparison + UUID tokens + path validation + stack-trace suppression together remove the most common attack vectors."),
    ("7", "Full Accountability", "Every operation — including failed login attempts — is recorded to the audit log with timestamp, user, and path."),
    ("8", "Cross-Platform LAN Deployment", "The RMI hostname flag (-Djava.rmi.server.hostname) allows the server to be reached from any machine on the LAN."),
]
tbl4 = doc.add_table(rows=len(adv_rows)+1, cols=3)
tbl4.style = 'Table Grid'
tbl4.alignment = WD_TABLE_ALIGNMENT.LEFT
cw4 = [Cm(0.8), Cm(4.5), Cm(12.0)]
for j, h in enumerate(["#", "Advantage", "Detail"]):
    tbl4.rows[0].cells[j].width = cw4[j]
    set_cell_bg(tbl4.rows[0].cells[j], '2E74B5')
    p = tbl4.rows[0].cells[j].paragraphs[0]
    run = p.add_run(h)
    run.bold = True
    run.font.size = Pt(9.5)
    run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
for i, (num, title, detail) in enumerate(adv_rows):
    row = tbl4.rows[i+1]
    for j, (text, bold) in enumerate([(num, False), (title, True), (detail, False)]):
        row.cells[j].width = cw4[j]
        p = row.cells[j].paragraphs[0]
        run = p.add_run(text)
        run.font.size = Pt(9.5)
        run.bold = bold
        if i % 2 == 1:
            set_cell_bg(row.cells[j], 'EBF3FB')

doc.add_paragraph()

add_heading(doc, "9.2  Limitations", level=2)
lim_rows = [
    ("1", "Single-Server Architecture", "All state is held in one JVM. Horizontal scaling requires a distributed lock manager (e.g., ZooKeeper) and a shared storage backend."),
    ("2", "No Persistent Sessions Across Restarts", "Session tokens are stored in-memory; all clients must re-login after a server restart."),
    ("3", "SHA-256 Password Hashing", "SHA-256 is fast and therefore amenable to brute-force attacks. A production system would use bcrypt, scrypt, or Argon2."),
    ("4", "RMI Random Second Port", "Java RMI uses a second random TCP port for method calls. Firewalls must allow this or the firewall should be disabled on the private LAN."),
    ("5", "No RBAC / ACLs", "All authenticated users have full read-write access. A production system would add per-user or per-directory access control lists."),
    ("6", "No TLS Encryption", "RMI traffic is unencrypted by default. Adding javax.net.ssl support or a TLS tunnel is required for use over untrusted networks."),
]
tbl5 = doc.add_table(rows=len(lim_rows)+1, cols=3)
tbl5.style = 'Table Grid'
tbl5.alignment = WD_TABLE_ALIGNMENT.LEFT
cw5 = [Cm(0.8), Cm(4.5), Cm(12.0)]
for j, h in enumerate(["#", "Limitation", "Impact / Mitigation"]):
    tbl5.rows[0].cells[j].width = cw5[j]
    set_cell_bg(tbl5.rows[0].cells[j], '1F3864')
    p = tbl5.rows[0].cells[j].paragraphs[0]
    run = p.add_run(h)
    run.bold = True
    run.font.size = Pt(9.5)
    run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
for i, (num, title, detail) in enumerate(lim_rows):
    row = tbl5.rows[i+1]
    for j, (text, bold) in enumerate([(num, False), (title, True), (detail, False)]):
        row.cells[j].width = cw5[j]
        p = row.cells[j].paragraphs[0]
        run = p.add_run(text)
        run.font.size = Pt(9.5)
        run.bold = bold
        if i % 2 == 1:
            set_cell_bg(row.cells[j], 'FFF2CC')

add_section_rule(doc)

# ── 10. CONCLUSION ────────────────────────────────────────────────────────────

add_heading(doc, "10. Conclusion", level=1)
add_body(doc,
    "RemoteFS demonstrates how a small, carefully composed set of classical distributed systems "
    "techniques can yield a robust, concurrent, and secure file server. Java RMI provides "
    "transparent network transparency, turning local method calls into network operations "
    "with no client-side I/O code. Per-file ReentrantReadWriteLocks with fair ordering "
    "and tryLock timeouts prevent both data corruption and thread starvation. Optimistic "
    "version control surfaces lost-update conflicts explicitly. The atomic temp-file rename "
    "strategy eliminates torn reads. Alphabetical lock ordering in rename() provably prevents "
    "deadlock. Salted SHA-256 hashing and UUID session tokens with expiry provide baseline "
    "authentication security. PathValidator's four-step resolution chain blocks all known "
    "directory-traversal patterns.")
add_body(doc,
    "The 25-test JUnit 5 suite — covering concurrent appends, atomic reads-during-writes, "
    "version conflicts, cross-rename deadlocks, twelve path-traversal patterns, and eight "
    "authentication scenarios — all pass, confirming that the theoretical guarantees hold "
    "under realistic concurrent workloads.")
add_body(doc,
    "The project reinforces that distributed correctness is not achieved by a single mechanism "
    "but by the careful interplay of locking, versioning, atomic I/O, ordered resource "
    "acquisition, and validation — each addressing a distinct failure mode that the others "
    "do not cover.")

add_section_rule(doc)

# ── 11. REFERENCES ────────────────────────────────────────────────────────────

add_heading(doc, "11. References", level=1)
refs = [
    'Tanenbaum, A. S., & van Steen, M. (2017). Distributed Systems: Principles and Paradigms (3rd ed.). CreateSpace Independent Publishing.',
    'Coulouris, G., Dollimore, J., Kindberg, T., & Blair, G. (2011). Distributed Systems: Concepts and Design (5th ed.). Addison-Wesley.',
    'Oracle Corporation. Java SE 17 — java.rmi Package Documentation. https://docs.oracle.com/en/java/docs/api/java.rmi/java/rmi/package-summary.html',
    'Oracle Corporation. java.nio.file.Files — Files.move() and StandardCopyOption.ATOMIC_MOVE. https://docs.oracle.com/en/java/docs/api/',
    'Oracle Corporation. java.util.concurrent.locks.ReentrantReadWriteLock — Class Documentation. https://docs.oracle.com/en/java/docs/api/',
    'Coffman, E. G., Elphick, M. J., & Shoshani, A. (1971). System Deadlocks. ACM Computing Surveys, 3(2), 67–78.',
    'Gray, J., & Reuter, A. (1992). Transaction Processing: Concepts and Techniques. Morgan Kaufmann. [Optimistic Concurrency Control]',
    'OWASP Foundation. Path Traversal Attack. https://owasp.org/www-community/attacks/Path_Traversal',
]
for i, ref in enumerate(refs, 1):
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Cm(0.7)
    p.paragraph_format.first_line_indent = Cm(-0.7)
    p.paragraph_format.space_before = Pt(2)
    p.paragraph_format.space_after = Pt(3)
    run = p.add_run(f"[{i}]  {ref}")
    run.font.size = Pt(10)

# ── SAVE ──────────────────────────────────────────────────────────────────────

output_path = r'c:\study_material\RemotFS\RemoteFS_DCS_MiniProject_Report.docx'
doc.save(output_path)
print(f"Report saved to: {output_path}")
