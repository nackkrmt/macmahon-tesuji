============================================================
  MacMahon-TESUJI Launcher
  Technical Documentation & User Guide
============================================================

  Version:    macmahon-tesuji.jar (wraps MacMahon 3.10)
  Runtime:    Java 25+ (Amazon Corretto บน Windows, Temurin บน macOS)
  Platform:   Windows, macOS
  License:    Internal use - TESUJI Go Competition System

============================================================
  1. OVERVIEW
============================================================

  MacMahon-TESUJI Launcher เป็น wrapper ที่ครอบโปรแกรม MacMahon 3.10
  (tournament pairing สำหรับหมากล้อม) เพิ่มความสามารถ:

    1) Sync Results - ดึงผลการแข่งขันจาก TESUJI server แล้ว auto-fill
       ลง MacMahon โดยไม่ต้องพิมพ์เอง
    2) Force Pairing - แก้ชื่อนักกีฬาใน MacMahon ให้ตรงกับ TESUJI
       พร้อมกำหนดเลขโต๊ะให้ตรงกัน (ใช้ได้ Round 1 เท่านั้น)
    3) Export Pairings - ส่งคู่จัดจาก MacMahon ไปยัง TESUJI server
       พร้อมระบบเตือน 3 ครั้งเมื่อจะเขียนทับข้อมูลเดิม
    4) Export Wall List - ส่งตาราง Wall List ไปยัง TESUJI server
       ยืนยันครั้งเดียว (เพราะต้องส่งทุกรอบ) แต่จะเตือนถ้าเขียนทับของเดิม
    5) Multi-Division Tab Bar - เปิดหลายไฟล์ tournament (.xml) พร้อมกัน
       ใน 1 window สลับได้ด้วย tab, auto-save เมื่อเปลี่ยน tab
    6) Thai Font Support - บังคับ font ภาษาไทยให้แสดงผลถูกต้อง
       ใน JTable, JMenu, JLabel ทุกจุด
    7) Java 25 Auto-Detection - เมื่อ double-click .jar ถ้า Java เก่า
       จะค้นหา Java 25 ในเครื่องและ relaunch อัตโนมัติ

============================================================
  2. ไฟล์ในชุดโปรแกรม
============================================================

  macmahon-tesuji.jar   ตัวโปรแกรม (รวม MacMahon 3.10 ไว้ข้างใน)
  launcher.properties   ไฟล์ตั้งค่า (URL + token ของ TESUJI server)
  README.txt            ไฟล์นี้
  MacMahon.bat          ตัวเปิดโปรแกรมสำรองสำหรับ Windows (ปกติไม่ต้องใช้)
  MacMahon.command       ตัวเปิดโปรแกรมสำรองสำหรับ macOS (ปกติไม่ต้องใช้)

  *** ไฟล์ .xml tournament วางใน folder "xml" ***
  วาง .xml ไว้ใน subfolder ชื่อ "xml" ข้าง ๆ macmahon-tesuji.jar
  ระบบจะ scan หา .xml ทั้งหมดแล้วสร้าง tab ให้อัตโนมัติ
  (ถ้าไม่มี folder "xml" จะ fallback ไปหา .xml ข้าง ๆ jar เหมือนเดิม)

  *** ไม่ต้องใช้ .bat / .command แล้ว ***
  Double-click macmahon-tesuji.jar ได้เลย (Windows/macOS)
  ระบบจะหา Java 25 เองอัตโนมัติ (ดู section 8)
  ถ้า double-click แล้วไม่มีอะไรเกิดขึ้น (พบได้บาง setup บน macOS ที่ยัง
  ไม่ได้ผูก .jar กับ Java) ให้ใช้ MacMahon.command แทน

============================================================
  3. SOURCE CODE STRUCTURE
============================================================

  src/launcher/
    MacMahonLauncher.java   Main entry point, tab bar, font, menu,
                            export to TESUJI, Java 25 auto-detection
    SyncDialog.java         Sync dialog UI + verify/auto-fill/force pairing
    TesujiClient.java       HTTP client for TESUJI REST API

  3 classes ทั้งหมดอยู่ใน package "launcher"
  ไม่มี external dependency - ใช้แค่ Java standard library
  (java.net.HttpURLConnection, javax.swing.*, java.lang.reflect.*)

============================================================
  4. TECHNICAL ARCHITECTURE
============================================================

  4.1 JAR Loading Strategy
  -------------------------
  Launcher ไม่ได้ compile ร่วมกับ MacMahon - มัน load MacMahon JAR
  ที่ runtime ผ่าน URLClassLoader:

    1) ค้นหา macmahon-*.jar ใน folder เดียวกัน
       (filter: ชื่อขึ้นต้น "macmahon-", ไม่มี "launcher"/"tesuji")
    2) ถ้าไม่เจอ: extract embedded JAR จาก /embedded/macmahon.jar
       ที่ถูก bundle ไว้ใน macmahon-tesuji.jar ตอน build
    3) สร้าง URLClassLoader โดยมี parent = launcher's classloader
    4) เรียก MacMahonApplication.main() ผ่าน reflection

  Flow:
    MacMahonLauncher.main()
      -> ensureJava25()                // ตรวจ/หา Java 25
      -> loadOrCreateProperties()      // อ่าน launcher.properties
      -> scanTournamentFiles()         // หา .xml ใน folder
      -> findMacMahonJar()             // หา JAR (external > embedded)
      -> new URLClassLoader(jarUrl)    // load MacMahon classes
      -> MacMahonApplication.main()    // เปิด MacMahon ปกติ
      -> Timer(500ms) polling          // รอ JFrame แสดง
        -> findAppInstance(jf)         // หา MacMahonApplication instance
        -> applyThaiFontToAll(jf)      // ใส่ font ไทย
        -> injectMenu(jf)             // เพิ่มเมนู TESUJI
        -> injectTabBar(jf, xmlFiles)  // เพิ่ม tab bar (ถ้ามี .xml)

  4.2 Obtaining MacMahonApplication Instance
  -------------------------------------------
  MacMahon ไม่ expose app instance เป็น public static field
  Launcher ต้อง traverse Swing component tree:

    JMenuBar -> JMenu -> JMenuItem -> ActionListener (inner class)
      -> this$0 (MacMahonMainWindow)
        -> m_application (MacMahonApplication)

    Code path: findAppInstance()
      1. วน loop ทุก JMenuItem ใน JMenuBar
      2. ดึง ActionListener ของแต่ละ item
      3. ActionListener เป็น inner class ของ MacMahonMainWindow
         มี field "this$0" ชี้ไปหา outer instance
      4. จาก MacMahonMainWindow เข้าถึง field "m_application"
         ซึ่งคือ MacMahonApplication instance ที่เราต้องการ

    Fallback: ถ้าไม่เจอ ลอง scan static fields ของ class
    MacMahonApplication เผื่อมี singleton pattern

  4.3 Content Pane Wrapping (Tab Bar)
  ------------------------------------
  MacMahon ใช้ GridBagLayout สำหรับ content pane ของ JFrame
  มี constraints สำคัญ: fill=BOTH, weightx=1.0, weighty=1.0

  *** ห้ามแก้ไข content pane โดยตรง ***
  ถ้าย้าย component ออกจาก GridBagLayout แล้วใส่กลับ
  GridBagConstraints จะหาย ทำให้ตารางแสดงเล็กมาก

  วิธีที่ถูกต้อง - wrap content pane ทั้งก้อน:

    Container originalContentPane = frame.getContentPane();
    JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.add(tabBarPanel, BorderLayout.NORTH);     // tab bar
    wrapper.add(originalContentPane, BorderLayout.CENTER); // MacMahon UI
    frame.setContentPane(wrapper);

  MacMahon เก็บ reference ไว้ใน field "jContentPane" ของ
  MacMahonMainWindow โดยไม่เรียก frame.getContentPane()
  ดังนั้นการเปลี่ยน content pane ของ JFrame ไม่กระทบ MacMahon

  Tab bar ใช้ JScrollPane ที่ซ่อน scrollbar โดยรองรับ scroll
  ด้วย mouse wheel แทน (สำหรับกรณีที่มี tab จำนวนมาก)

  4.4 Tournament Switching (Tab)
  --------------------------------
  เมื่อกดเปลี่ยน tab ระบบทำ:

    switchToTab(index, xmlFiles, frame):
      1. Auto-save: เรียก tournamentSave() เพื่อ save ไฟล์ปัจจุบัน
      2. Open: เรียก tournamentOpenInternal(File) ผ่าน reflection
         - method นี้ return Tournament object (ไม่ใช่ boolean)
         - มัน parse XML, สร้าง Tournament, set global message
         - แต่ไม่ set m_tournament field เอง!
      3. Set: ถ้าเปิดสำเร็จ set m_tournament = tournament เอง
         *** เปิดใหม่ก่อน ถ้าสำเร็จค่อย overwrite ***
         *** ถ้าเปิดไม่ได้ อยู่ tab เดิม ไม่มีอะไรหาย ***
      4. UI Setup: เรียก tournamentOpened() ผ่าน reflection
         - enable/disable menu items
         - set WalllistTableModel + PairingsTableModel
         - build window title
         - select WallList tab
      5. Restyle tab buttons (active = น้ำเงิน, inactive = เทา)

  *** สำคัญ: ต้อง set m_tournament ก่อนเรียก tournamentOpened() ***
  เพราะ tournamentOpenInternal() return Tournament แต่ไม่ set field
  ถ้าไม่ set เอง จะได้ NullPointerException

  *** Auto-save ก่อนสลับ tab จะตรวจผลลัพธ์ด้วย ***
  tournamentSave() คืนค่า 0 = สำเร็จ, อื่น ๆ (หรือ exception) = ไม่สำเร็จ
  (เช่น user กด Cancel ใน Save As dialog) ถ้าไม่สำเร็จ ระบบจะถามยืนยัน
  ก่อนสลับ tab ต่อ เพื่อไม่ให้งานที่ยังไม่ได้ save หายไปเงียบ ๆ

  4.5 Thai Font System
  ---------------------
  MacMahon ใช้ font "Dialog" ซึ่งแสดงภาษาไทยเป็นกล่อง/คำถาม
  Launcher บังคับ font ไทยทั้งระบบผ่าน 3 ชั้น:

  ชั้นที่ 1: UIManager defaults (ก่อน MacMahon สร้าง UI)
    - เปลี่ยน FontUIResource ทุกตัวใน UIManager.getDefaults()
    - set specific keys: Label.font, Table.font, MenuItem.font ฯลฯ

  ชั้นที่ 2: AWTEventListener (จับ component ใหม่ทุกตัว)
    - ติดตั้ง listener บน CONTAINER_EVENT_MASK
    - เมื่อ component ใดถูก add เข้า container -> เปลี่ยน font ทันที
    - ถ้าเป็น JTable -> wrap renderers ด้วย ThaiCellRenderer

  ชั้นที่ 3: Font maintenance timer (ทุก 5 วินาที)
    - วน loop ทุก visible JFrame
    - applyThaiFontToAll() แบบ recursive ลงลึกทุก component
    - จับ table ที่ MacMahon สร้างใหม่ภายหลัง (เช่น เปิด round)

  ThaiCellRenderer:
    - เป็น TableCellRenderer wrapper ที่ delegate ไปหา renderer เดิม
    - หลัง delegate แล้ว override font ของ component ที่ return มา
    - ทำแบบ recursive (setFontDeep) เผื่อ compound renderer
      ที่ใช้ JPanel มี JLabel หลายตัวข้างใน

  Font priority list:
    TH Sarabun New > TH SarabunPSK > Sarabun > Tahoma
    > Leelawadee UI > Segoe UI > Cordia New > Microsoft Sans Serif

  4.6 MacMahon Class Hierarchy (ที่ Launcher ใช้)
  -------------------------------------------------
  de.cgerlach.macmahon.gui.MacMahonApplication
    - m_tournament: Tournament
    - m_mainWindow: MacMahonMainWindow
    - tournamentOpenInternal(File): Tournament  [private]
    - tournamentOpened(): void                  [private]
    - tournamentSave(): int                     [public]
    - tournamentClose(): void                   [public]
    - getTournament(): Tournament
    - fireWalllistTableDataChanged(): void
    - firePairingsTableDataChanged(): void
    - setGlobalMessage(String): void
    - buildMainWindowTitle(): void

  de.cgerlach.macmahon.gui.MacMahonMainWindow
    - jFrame: JFrame
    - jContentPane: JPanel                (GridBagLayout!)
    - m_application: MacMahonApplication
    - getJFrame(): JFrame
    - getJTabbedPane(): JTabbedPane       (Walllist + Pairings)
    - getJTableWalllist(): JTable         (WalllistTable)
    - getJTablePairings(): JTable         (PairingsTable)

  de.cgerlach.macmahon.model.Tournament
    - getCurrentRound(): TournamentRound
    - getCurrentRoundNumber(): int
    - getRound(int): TournamentRound
    - getName(): String
    - getWalllist(): Walllist
    - buildParticipantScores(): void

  de.cgerlach.macmahon.model.TournamentRound
    - getPairings(): List<Pairing>

  de.cgerlach.macmahon.model.Pairing
    - getResult(): ResultDescriptor
    - setResult(ResultDescriptor): void
    - getBoardNumber(): int
    - setBoardNumber(int, boolean): void
    - getBlack(): Participant
    - getWhite(): Participant
    - isPairingWithBye(): boolean
    - static fields: NO_RESULT, BLACK_WINS, WHITE_WINS,
                     JIGO, BOTH_LOSE, BOTH_WIN

  de.cgerlach.macmahon.model.Participant
    - getName(): String
    - getScoreAfterRound(int): int           (raw score หลังจบรอบ n)
    - getScoreDisplayString(int): String      (format score เช่น "1½")

  de.cgerlach.macmahon.model.IndividualParticipant extends Participant
    - getGoPlayer(): GoPlayer

  de.cgerlach.macmahon.model.GoPlayer extends Person

  de.cgerlach.macmahon.model.Person
    - setFirstName(String): void
    - setSurname(String): void
    - getName(): String
    - isAsianName(): boolean

============================================================
  5. SYNC DIALOG - TECHNICAL DETAIL
============================================================

  5.1 Reflection Cache (initReflection)
  --------------------------------------
  SyncDialog เก็บ Method/Field references ไว้ใน instance fields
  เพื่อไม่ต้อง lookup ซ้ำทุกครั้ง:

    pairingClass          = Class<Pairing>
    getResultMethod       = Pairing.getResult()
    setResultMethod       = Pairing.setResult(ResultDescriptor)
    getBoardNumberMethod  = Pairing.getBoardNumber()
    setBoardNumberMethod  = Pairing.setBoardNumber(int, boolean)
    getBlackMethod        = Pairing.getBlack()
    getWhiteMethod        = Pairing.getWhite()
    getNameMethod         = Participant.getName()
    getShortNameMethod    = ResultDescriptor.getShortName()
    getGoPlayerMethod     = IndividualParticipant.getGoPlayer()
    setFirstNameMethod    = Person.setFirstName(String)
    setSurnameMethod      = Person.setSurname(String)
    isAsianNameMethod     = Person.isAsianName()

    Result constants:
    NO_RESULT, BLACK_WINS, WHITE_WINS, JIGO, BOTH_LOSE, BOTH_WIN

  5.2 Division Auto-Select
  -------------------------
  ชื่อไฟล์ .xml ใน MacMahon ต้องขึ้นต้นด้วยเลข Division:

    "01 - 1-2 Kyu.xml"   -> regex ^(\d+) -> "01" -> Division "01"
    "02 - 3-4 Kyu.xml"   -> regex ^(\d+) -> "02" -> Division "02"
    "03 - 5-6 Kyu.xml"   -> regex ^(\d+) -> "03" -> Division "03"

  เก็บ ID เป็น string ตามที่เป็น (รักษา leading zeros เช่น "01")

  เปรียบเทียบ 2 แบบ:
    - Exact: "01" == "01"
    - Normalized: strip leading zeros -> "1" == "1"

  5.3 Round Auto-Select
  ----------------------
  อ่าน Tournament.getCurrentRoundNumber() จาก MacMahon
  แล้ว select ใน roundCombo ให้ตรงกัน

  5.4 Verify Flow
  -----------------
  doVerify() ทำงานบน SwingWorker (background thread):

    1. GET /api/divisions/:id/matches?round=:round จาก TESUJI
    2. อ่าน pairings จาก MacMahon ผ่าน reflection:
       getTournament() -> getCurrentRound()/getRound(n-1)
       -> getPairings() -> Map<boardNumber, Pairing>
    3. จับคู่ TESUJI match กับ MacMahon pairing:
       *** ลำดับความสำคัญ: จับคู่ด้วยชื่อก่อน, fallback เลขโต๊ะ ***
       a) ลองจับคู่ด้วยชื่อ Black+White (namesMatch)
       b) ถ้าจับคู่ด้วยชื่อไม่ได้ -> ลองจับด้วย board number
       c) ตรวจว่า board number ตรงกับ TESUJI table หรือไม่
    4. กำหนด status:
       - "Match"          ผลตรงกัน (สีเขียว)
       - "Ready to fill"  TESUJI มีผล, MacMahon ว่าง (สีฟ้า)
       - "Name mismatch!" พร้อม fill แต่ชื่อไม่ตรง (สีส้ม)
       - "Mismatch"       ผลไม่ตรง (สีแดง)
       - "Pending"        ยังไม่มีผลทั้งสองฝั่ง (สีเหลือง)
    5. Update JTable + summary counts

  5.5 Auto-Fill Flow
  -------------------
  doAutoFill():

    1. นับคู่ "Ready to fill" + "Name mismatch!"
    2. Popup ยืนยัน: จำนวนคู่ที่จะ fill, skip, mismatch
    3. ถ้ามี name mismatch -> ถามแยกอีกครั้ง (Yes/No)
    4. วน loop comparisonData:
       - แปลง TESUJI result -> MacMahon ResultDescriptor
         "1-0" -> BLACK_WINS, "0-1" -> WHITE_WINS, etc.
       - เรียก Pairing.setResult(resultDesc) ผ่าน reflection
    5. refreshMacMahonUI():
       - fireWalllistTableDataChanged()
       - firePairingsTableDataChanged()
       - Tournament.buildParticipantScores()
    6. Re-verify อัตโนมัติ

  5.6 Name Matching (Fuzzy)
  --------------------------
  namesMatch(tesujiName, macName):

    1. Normalize: trim, lowercase, remove ",", ".", collapse spaces
    2. Exact match: "สมชาย ใจดี" == "สมชาย ใจดี"
    3. Partial match: a.contains(b) || b.contains(a)
    4. Reversed order: "First Last" vs "Last First"
       -> split by space, compare first+last reversed

  5.7 Result Normalization
  -------------------------
  normalizeResult(result):

    Input           -> Normalized
    "B+", "B"       -> "1-0"
    "W+", "W"       -> "0-1"
    "1-0"           -> "1-0"
    "0-1"           -> "0-1"
    "Jigo", "Draw"  -> "1/2-1/2"
    "0-0"           -> "0-0"
    "1-1"           -> "1-1"

  5.8 Force Pairing (Name + Board Number Fix)
  ---------------------------------------------
  doFixNames(): ใช้ได้เฉพาะ Round 1 เท่านั้น

  จับคู่ด้วยชื่อ (name-based matching):
    Verify จะจับคู่ด้วยชื่อก่อน ไม่ใช่เลขโต๊ะ
    ทำให้ระบบรู้ว่า pairing ไหนตรงกับ TESUJI match ไหน
    แม้ว่าเลขโต๊ะจะต่างกัน

  Force Pairing ทำ 2 อย่าง:
    1. แก้เลขโต๊ะ - สำหรับ pairing ทุกคู่ที่จับคู่ได้
       ถ้าเลขโต๊ะใน MacMahon ไม่ตรงกับ TESUJI table
       -> เรียก setBoardNumber(tesujiTable, true)

    2. แก้ชื่อ - สำหรับ pairing ที่ชื่อไม่ตรง
       renameParticipant():
         Participant -> IndividualParticipant.getGoPlayer()
         -> Person.setFirstName("")
         -> Person.setSurname("ชื่อเต็มจาก TESUJI")
       ใส่ชื่อทั้งหมดลง surname field (firstName = "")
       เพราะชื่อไทยไม่ต้องแยก first/last

  Popup แสดง:
    - จำนวน board ที่จะแก้เลขโต๊ะ
    - จำนวนชื่อที่จะแก้
    - refreshMacMahonUI() + re-verify หลังเสร็จ

  *** ปุ่ม Force Pairing enabled เมื่อ: ***
    - Round ที่เลือก = 1 (isRound1)
    - nameWarn > 0 (มีชื่อไม่ตรงอย่างน้อย 1 คู่)
    - ต้อง Verify ก่อนเสมอ

============================================================
  6. TESUJI API CLIENT
============================================================

  TesujiClient.java - HTTP client ไม่มี dependency

  6.1 Endpoints Used
  -------------------
  GET /api/divisions
    Response: { "success": true,
                "divisions": [{"id":"01","name":"1-2 Kyu"}, ...] }

  GET /api/divisions/:id/matches?round=:round
    Response: { "rounds": ["1","2",...], "currentRound": "3",
                "matches": [{"table":"1","black":"...","white":"...",
                             "result":"1-0","submittedBy":"..."}, ...] }

  POST /api/divisions
    Body: { "id": "01", "name": "1-2 Kyu" }
    สร้าง division ใหม่ (ensureDivision จะตรวจก่อนว่ามีหรือยัง)

  POST /api/divisions/:id/matches
    Body: { "round": "1",
            "matches": [{"table":"1","black":"...","white":"...",
                         "blackScore":"1½","whiteScore":"2"}, ...] }
    Upload คู่จัดสำหรับ round
    *** blackScore/whiteScore = McMahon score ตอนเข้ารอบนั้น ***
    (getScoreDisplayString(getScoreAfterRound(round-1)) เก็บเป็น String
     เพราะอาจมีครึ่งแต้ม เช่น "1½" จาก jigo; null = ไม่ทราบ)

  POST /api/divisions/:id/standings
    Body: { "standings": { "headers": [...], "rows": [[...], ...] } }
    Upload ตาราง wall list / standings

  DELETE /api/divisions/:id/rounds/:round
    ลบข้อมูลรอบนั้นทั้งหมด (ก่อน re-upload)

  6.2 Authentication
  -------------------
  Header: x-admin-token: <token from launcher.properties>
  Token จำเป็นสำหรับ write operations (POST, PUT, DELETE)

  6.3 JSON Parser
  ----------------
  ไม่ใช้ library ภายนอก - parse JSON เองด้วย string manipulation:
    extractJsonString(json, key)     -> String value
    extractJsonValue(json, key)      -> Raw value (string/number/bool)
    extractJsonArray(json, key)      -> Array content (string)
    splitJsonArray(content)          -> List<String> objects
    splitJsonArrayStrings(content)   -> List<String> string values

  รองรับ: string, number, boolean, null, nested objects, arrays
  ไม่รองรับ: complex nested structures ที่ลึกกว่า 1 ระดับ

  6.4 Export Flow
  ----------------
  Export Pairings:
    1. อ่าน Tournament -> getCurrentRoundNumber()
    2. อ่าน Pairings ผ่าน reflection (skip bye pairings)
       พร้อมดึง McMahon score ของแต่ละฝ่าย (blackScore/whiteScore)
       ตอนเข้ารอบนั้น -> ส่งไป TESUJI ด้วย
    3. ตรวจข้อมูลเดิม: getMatches(divId, round)
       ถ้ามีอยู่แล้ว -> เตือน 3 ครั้ง (overwrite warning)
    4. ensureDivision(id, name) - สร้าง division ถ้ายังไม่มี
    5. deleteRound(divId, round) - ลบข้อมูลเดิม
    6. exportPairings(divId, round, matches) - upload ใหม่

  Export Wall List:
    1. อ่าน JTable wall list ผ่าน reflection (headers + rows)
    2. ตรวจ division เดิม: getDivisions()
       ถ้ามีอยู่แล้ว -> ยืนยันครั้งเดียว + ข้อความเตือนว่าเขียนทับ
       (ไม่เตือน 3 ครั้งเหมือน Pairings เพราะ Wall List ต้องส่งทุกรอบ)
    3. ensureDivision(id, name) - สร้าง division ถ้ายังไม่มี
    4. exportStandings(divId, headers, rows) - upload ใหม่

  Division ID/Name จากชื่อไฟล์:
    "01 - 1-2 Kyu.xml" -> id="01", name="1-2 Kyu"
    regex: ^(\d+)\s*-\s*(.+)$

============================================================
  7. BUILD PROCESS
============================================================

  7.1 Requirements
  -----------------
  - JDK 25 (JDK ตัวเต็ม ไม่ใช่แค่ JRE — ต้องมี javac + jar)
  - Windows: PowerShell + build.ps1
  - macOS/Linux: bash + build.sh

  build.ps1 และ build.sh หา JDK ให้อัตโนมัติ (เช็ค JAVA_HOME ก่อน แล้ว
  scan ตำแหน่งติดตั้งทั่วไปของแต่ละ OS — บน macOS ใช้ java_home) ไม่ต้อง
  แก้ path ในสคริปต์เอง

  7.2 Build Steps (build.ps1 / build.sh)
  ----------------------------------------
  [1/3] Compile: javac -encoding UTF-8 --release 8 -d build src/**/*.java
  [2/3] Embed:   copy lib/macmahon-3.10.jar -> build/embedded/macmahon.jar
  [3/3] Package: jar cfm macmahon-tesuji.jar MANIFEST.MF -C build .

  รัน: .\build.ps1 (Windows) หรือ ./build.sh (macOS/Linux — ต้อง
  chmod +x build.sh ครั้งแรก) ขั้นตอนเหมือนกันทุกประการ ต่างแค่ภาษา
  สคริปต์

  MANIFEST.MF (generated inline, ASCII encoding):
    Manifest-Version: 1.0
    Main-Class: launcher.MacMahonLauncher

  *** --release 8 ***
  Compile ด้วย Java 8 class file format เพื่อให้ Java ทุก version
  สามารถ launch .jar ได้ (แล้ว ensureJava25() จะ relaunch ด้วย
  Java 25 เอง)

  Output: macmahon-tesuji.jar (~600KB, includes embedded MacMahon)

  7.3 Distribution
  -----------------
  ไฟล์ที่ต้อง deploy:
    - macmahon-tesuji.jar     (ตัวโปรแกรม)
    - launcher.properties     (ตั้งค่า TESUJI)
    - *.xml                   (ไฟล์ tournament)

============================================================
  8. JAVA 25 AUTO-DETECTION
============================================================

  เมื่อ double-click macmahon-tesuji.jar:

  1. เช็ค java.version ปัจจุบัน
  2. ถ้า >= 25 -> ทำงานปกติ
  3. ถ้า < 25 -> ค้นหา Java 25 ในเครื่อง ตาม OS (findJava25Path()
     แยกเป็น findJava25Mac() / findJava25Windows() / findJava25Generic()):

     Windows (findJava25Windows):
       - C:\Program Files\Amazon Corretto\jdk25*
       - C:\Program Files\Java\jdk-25*
       - C:\Program Files\Eclipse Adoptium\jdk-25*
       - C:\Program Files\BellSoft\*25*
       - C:\Program Files\Microsoft\*25*
       ใช้ bin\javaw.exe (ไม่มี console) หรือ bin\java.exe

     macOS (findJava25Mac):
       - เรียก `/usr/libexec/java_home -v "25+"` ก่อน — เจอ JDK/JRE
         เวอร์ชัน 25 ขึ้นไปของ vendor ไหนก็ได้ (Temurin, Corretto, Zulu)
         ที่ติดตั้งถูกวิธี (.pkg หรือ brew cask)
       - Fallback: scan /Library/Java/JavaVirtualMachines/* หา bundle
         ที่ชื่อ match jdk-25/corretto-25/temurin-25/zulu...25
       - ใช้ Contents/Home/bin/java (ไม่มี javaw บน mac)

  4. ถ้าเจอ -> relaunch ด้วย ProcessBuilder (java/javaw path ที่เจอ +
     -Dfile.encoding=UTF-8 -jar macmahon-tesuji.jar)
  5. ถ้าไม่เจอ -> แสดง dialog แนะนำติดตั้ง (Windows: Corretto .msi,
     macOS: `brew install --cask temurin` หรือ adoptium.net)

  *** ไม่ต้องใช้ .bat / .command อีกต่อไป ***
  เพราะ .jar compile ด้วย --release 8 จึงรันได้กับ Java ทุก version
  แล้วค่อย relaunch ด้วย Java 25 เอง (ทั้ง Windows และ macOS)

============================================================
  9. USER GUIDE - ขั้นตอนการใช้งาน
============================================================

  9.1 ติดตั้ง (ครั้งแรก)
  -------------------------
  Windows:
  1. ติดตั้ง Java 25 (Amazon Corretto):
     https://corretto.aws/downloads/latest/amazon-corretto-25-x64-windows-jdk.msi
  2. Copy folder ทั้งหมดไปที่ไหนก็ได้ (Desktop, C:\MacMahon)
  3. Double-click macmahon-tesuji.jar
  4. ถ้ายังไม่มี Java 25 ระบบจะแนะนำให้ download

  macOS:
  1. ติดตั้ง Java 25 (Temurin) ผ่าน Homebrew:
     brew install --cask temurin
     (หรือดาวน์โหลด .pkg จาก https://adoptium.net/temurin/releases/?version=25)
  2. Copy folder ทั้งหมดไปที่ไหนก็ได้ (Desktop, ~/MacMahon)
  3. Double-click macmahon-tesuji.jar — ถ้า Finder ไม่รู้จักไฟล์ .jar
     ให้ double-click MacMahon.command แทน (เปิด Terminal รันให้อัตโนมัติ)
  4. ถ้ายังไม่มี Java 25 ระบบจะแนะนำให้ install ผ่าน brew

  9.2 ตั้งค่า launcher.properties
  --------------------------------
  เปิดด้วย Notepad:

    tesuji.url=https://tesuji-reg.vercel.app
    tesuji.token=your_secret_token

  - tesuji.url  = URL ของ TESUJI server
  - tesuji.token = Admin token (จำเป็นสำหรับ export/write)

  9.3 เตรียม Tournament
  -----------------------
  ชื่อไฟล์ .xml ต้องขึ้นต้นด้วยเลข Division:

    "01 - 1-2 Kyu.xml"   -> Division 01, ชื่อ "1-2 Kyu"
    "02 - 3-4 Kyu.xml"   -> Division 02, ชื่อ "3-4 Kyu"
    "03 - 5-6 Kyu.xml"   -> Division 03, ชื่อ "5-6 Kyu"

  วาง .xml files ใน folder "xml" (ข้าง ๆ macmahon-tesuji.jar)
  ระบบจะสร้าง tab bar ให้อัตโนมัติ

  9.4 Multi-Division Tab Bar
  ----------------------------
  - วาง .xml หลายไฟล์ใน folder -> จะมี tab bar ด้านบน
  - กด tab เพื่อสลับ tournament
  - *** Auto-save เมื่อเปลี่ยน tab ***
  - Tab active = สีน้ำเงิน, inactive = สีเทา
  - Scroll ด้วย mouse wheel เมื่อมี tab มาก
  - ไฟล์แรก (sorted by name) จะเปิดอัตโนมัติ

  9.5 Export Pairings to TESUJI
  ------------------------------
  1. เปิด tournament ที่ต้องการ
  2. ทำ Make Pairing ใน MacMahon ตามปกติ
  3. เมนู TESUJI > Export Pairings to TESUJI...
  4. ตรวจ Division/Round -> ยืนยัน
  5. *** ถ้ามีข้อมูลอยู่แล้ว จะเตือน 3 ครั้ง ***
  6. ระบบจะ:
     - สร้าง Division อัตโนมัติ (ถ้ายังไม่มี)
     - ลบข้อมูลรอบเดิม
     - Upload คู่จัดใหม่

  9.6 Export Wall List to TESUJI
  --------------------------------
  1. เปิด tournament ที่ต้องการ
  2. เมนู TESUJI > Export Wall List to TESUJI...
  3. ตรวจ Division -> ยืนยัน (ครั้งเดียว)
  4. *** ถ้ามีข้อมูลอยู่แล้ว จะมีข้อความเตือนว่าเขียนทับ (แต่ยืนยันครั้งเดียว) ***
  5. ระบบจะ:
     - สร้าง Division อัตโนมัติ (ถ้ายังไม่มี)
     - Upload ตาราง Wall List ทับของเดิม

  9.7 Force Pairing (Round 1)
  ----------------------------
  1. เปิด TESUJI > Sync from TESUJI...
  2. ตรวจ Division/Round -> กด Verify
  3. ดูชื่อที่ไม่ตรง (highlight สีชมพู)
  4. กด Force Pairing (ได้เฉพาะ Round 1)
  5. ระบบจะ:
     - แก้เลขโต๊ะให้ตรงกับ TESUJI (ทุกคู่ที่จับคู่ได้)
     - แก้ชื่อให้ตรงกับ TESUJI (เฉพาะคู่ที่ชื่อไม่ตรง)
  6. ตรวจผลลัพธ์ -> Save Tournament (File > Save)

  9.8 Sync ผลการแข่งขัน (ทุก Round)
  ------------------------------------
  1. TESUJI > Sync from TESUJI...
  2. Division + Round จะ auto-select
  3. กด Verify -> ดูตารางเปรียบเทียบ
  4. กด Auto-fill -> ยืนยัน
  5. Save Tournament

  สี Status:
    เขียว  "Match"          ผลตรงกันแล้ว
    ฟ้า    "Ready to fill"  พร้อมเติม
    ส้ม    "Name mismatch!" พร้อมเติมแต่ชื่อไม่ตรง
    แดง    "Mismatch"       ผลไม่ตรง (ต้องตรวจสอบ!)
    เหลือง "Pending"        ยังไม่มีผล

============================================================
  10. TROUBLESHOOTING
============================================================

  "Java 25 not found"
  -> ติดตั้ง Amazon Corretto 25:
     https://corretto.aws/downloads/latest/amazon-corretto-25-x64-windows-jdk.msi

  "Connection failed"
  -> ตรวจ tesuji.url ใน launcher.properties
  -> ตรวจ internet connection

  Division auto-select ไม่ตรง
  -> ชื่อไฟล์ .xml ต้องขึ้นต้นด้วยเลข Division ("01 - ...")

  "ไม่พบ MacMahon Application instance"
  -> ต้องเปิด Tournament + Make Pairing ใน MacMahon ก่อน

  ชื่อไทยแสดงเป็นกล่อง
  -> ระบบจะตั้ง font อัตโนมัติ (TH Sarabun New/Tahoma)
  -> ถ้ายังไม่ได้ ให้ติดตั้ง font TH Sarabun New

  ปุ่ม Force Pairing กดไม่ได้
  -> ใช้ได้เฉพาะ Round 1 เท่านั้น
  -> ต้อง Verify ก่อน + มีชื่อไม่ตรงอย่างน้อย 1 คู่

  Export Pairings เตือนซ้ำ 3 ครั้ง
  -> ปกติ: เมื่อ Round นั้นมีข้อมูลอยู่แล้วใน TESUJI
     ระบบจะเตือน 3 ครั้งเพื่อกันเขียนทับข้อมูลผลแข่งที่ผิดพลาด
  -> ถ้า Round นั้นยังไม่เคยมี จะถามยืนยันแค่ 1 ครั้ง
  -> Export Wall List ยืนยันครั้งเดียวเสมอ (เพราะต้องส่งทุกรอบ)
     แต่จะมีข้อความเตือนถ้ากำลังเขียนทับของเดิม

  JAR build ไม่ได้ (file locked)
  -> ปิด MacMahon ก่อน build (JAR ถูก lock ขณะเปิดอยู่)

  NullPointerException: m_tournament is null
  -> ต้อง set m_tournament ก่อนเรียก tournamentOpened()
  -> ดู section 4.4 Tournament Switching

============================================================
  11. KNOWN LIMITATIONS
============================================================

  - Force Pairing ใช้ได้ Round 1 เท่านั้น
  - JSON parser เป็น basic - รองรับ TESUJI API format เท่านั้น
  - Font maintenance timer ทำงานทุก 5 วินาที แต่จะ skip component ที่ font
    ถูกต้องอยู่แล้ว (ไม่ churn ทั้ง tree ทุกครั้ง overhead จึงต่ำมาก)
  - ต้องใช้ Java 25+ (MacMahon 3.10 requirement)
  - รองรับ Windows และ macOS (ทดสอบจริงบน macOS Intel แล้ว — ดู section 8)
    Linux ยังไม่ได้ทดสอบ มี fallback แบบ best-effort ให้ (findJava25Generic)

============================================================
  12. TESUJI MENU SUMMARY
============================================================

  เมนู "TESUJI" ใน menu bar มี 3 รายการ:

    1. Sync from TESUJI...
       เปิด Sync Dialog: Verify -> Auto-fill -> Force Pairing
       (ดึงข้อมูลจาก TESUJI มาใส่ MacMahon)

    2. Export Pairings to TESUJI...
       ส่งคู่จัดรอบปัจจุบันจาก MacMahon ไป TESUJI
       (MacMahon -> TESUJI)

    3. Export Wall List to TESUJI...
       ส่งตาราง Wall List จาก MacMahon ไป TESUJI
       (MacMahon -> TESUJI)

  ทิศทาง:
    Sync   = TESUJI -> MacMahon (ดึงผลเข้า)
    Export = MacMahon -> TESUJI (ส่งข้อมูลออก)

============================================================
