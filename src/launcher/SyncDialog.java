package launcher;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;

/**
 * Sync Dialog — verify and auto-fill results from TESUJI into MacMahon.
 */
public class SyncDialog extends JDialog {

    private final Object appInstance;
    private final ClassLoader cl;
    private final TesujiClient client;

    // UI components
    private JComboBox<TesujiClient.Division> divisionCombo;
    private JComboBox<String> roundCombo;
    private JButton verifyButton;
    private JButton autoFillButton;
    private JButton fixNamesButton;
    private JTable resultTable;
    private DefaultTableModel tableModel;
    private JLabel statusLabel;
    private JLabel summaryLabel;

    // Data
    private List<ComparisonRow> comparisonData = new ArrayList<ComparisonRow>();

    // Reflection cache
    private Class<?> pairingClass;
    private Object NO_RESULT, BLACK_WINS, WHITE_WINS, JIGO, BOTH_LOSE, BOTH_WIN;
    private Method getResultMethod, setResultMethod, getBoardNumberMethod, setBoardNumberMethod;
    private Method getBlackMethod, getWhiteMethod, getNameMethod;
    private Method getShortNameMethod;
    private Method getGoPlayerMethod, setFirstNameMethod, setSurnameMethod, isAsianNameMethod;

    public SyncDialog(JFrame parent, Object appInstance, ClassLoader cl,
                      String tesujiUrl, String tesujiToken) throws Exception {
        super(parent, "Sync Results from TESUJI", true);
        this.appInstance = appInstance;
        this.cl = cl;
        this.client = new TesujiClient(tesujiUrl, tesujiToken);

        initReflection();
        initUI();
        loadDivisions();

        setSize(1050, 600);
        setLocationRelativeTo(parent);
    }

    // ==================== Reflection Setup ====================

    private void initReflection() throws Exception {
        pairingClass = cl.loadClass("de.cgerlach.macmahon.model.Pairing");
        Class<?> resultDescClass = cl.loadClass("de.cgerlach.macmahon.model.Pairing$ResultDescriptor");
        Class<?> participantClass = cl.loadClass("de.cgerlach.macmahon.model.Participant");

        // Result constants
        NO_RESULT = pairingClass.getField("NO_RESULT").get(null);
        BLACK_WINS = pairingClass.getField("BLACK_WINS").get(null);
        WHITE_WINS = pairingClass.getField("WHITE_WINS").get(null);
        JIGO = pairingClass.getField("JIGO").get(null);
        BOTH_LOSE = pairingClass.getField("BOTH_LOSE").get(null);
        BOTH_WIN = pairingClass.getField("BOTH_WIN").get(null);

        // Methods
        getResultMethod = pairingClass.getMethod("getResult");
        setResultMethod = pairingClass.getMethod("setResult", resultDescClass);
        getBoardNumberMethod = pairingClass.getMethod("getBoardNumber");
        setBoardNumberMethod = pairingClass.getMethod("setBoardNumber", int.class, boolean.class);
        getBlackMethod = pairingClass.getMethod("getBlack");
        getWhiteMethod = pairingClass.getMethod("getWhite");
        getNameMethod = participantClass.getMethod("getName");
        getShortNameMethod = resultDescClass.getMethod("getShortName");

        // For Fix Names feature: IndividualParticipant → GoPlayer → Person
        Class<?> individualClass = cl.loadClass("de.cgerlach.macmahon.model.IndividualParticipant");
        Class<?> personClass = cl.loadClass("de.cgerlach.macmahon.model.Person");
        getGoPlayerMethod = individualClass.getMethod("getGoPlayer");
        setFirstNameMethod = personClass.getMethod("setFirstName", String.class);
        setSurnameMethod = personClass.getMethod("setSurname", String.class);
        isAsianNameMethod = personClass.getMethod("isAsianName");
    }

    // ==================== UI ====================

    private void initUI() {
        setLayout(new BorderLayout(8, 8));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Ensure Thai font for this dialog
        Font thaiFont = new Font("TH Sarabun New", Font.PLAIN, 12);

        // Top panel — Division + Round selection
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topPanel.add(new JLabel("Division:"));
        divisionCombo = new JComboBox<TesujiClient.Division>();
        divisionCombo.setPreferredSize(new Dimension(250, 28));
        topPanel.add(divisionCombo);

        topPanel.add(new JLabel("Round:"));
        roundCombo = new JComboBox<String>();
        roundCombo.setPreferredSize(new Dimension(80, 28));
        topPanel.add(roundCombo);

        verifyButton = new JButton("Verify");
        verifyButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doVerify(); }
        });
        topPanel.add(verifyButton);

        autoFillButton = new JButton("Auto-fill");
        autoFillButton.setEnabled(false);
        autoFillButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doAutoFill(); }
        });
        topPanel.add(autoFillButton);

        fixNamesButton = new JButton("Force Pairing");
        fixNamesButton.setEnabled(false);
        fixNamesButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doFixNames(); }
        });
        topPanel.add(fixNamesButton);

        add(topPanel, BorderLayout.NORTH);

        // Center — Result table
        String[] columns = {"Board", "TESUJI Black", "Mac Black", "TESUJI White", "Mac White", "TESUJI", "MacMahon", "Status"};
        tableModel = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int row, int col) { return false; }
        };
        resultTable = new JTable(tableModel);
        resultTable.setFont(thaiFont);
        resultTable.getTableHeader().setFont(thaiFont.deriveFont(Font.BOLD));
        resultTable.setRowHeight(26);
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(45);   // Board
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(130);  // TESUJI Black
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(130);  // Mac Black
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(130);  // TESUJI White
        resultTable.getColumnModel().getColumn(4).setPreferredWidth(130);  // Mac White
        resultTable.getColumnModel().getColumn(5).setPreferredWidth(65);   // TESUJI Result
        resultTable.getColumnModel().getColumn(6).setPreferredWidth(65);   // Mac Result
        resultTable.getColumnModel().getColumn(7).setPreferredWidth(120);  // Status

        // Color-coded name cells (highlight mismatch in red)
        DefaultTableCellRenderer nameRenderer = new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable t, Object val,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, val, sel, focus, row, col);
                c.setFont(thaiFont);
                // Compare TESUJI name (col 1,3) vs Mac name (col 2,4)
                int tesujiCol = (col == 2) ? 1 : (col == 4) ? 3 : -1;
                if (tesujiCol >= 0) {
                    String tesujiName = normalizeName(String.valueOf(t.getValueAt(row, tesujiCol)));
                    String macName = normalizeName(String.valueOf(val));
                    if (!macName.isEmpty() && !tesujiName.isEmpty() && !tesujiName.equals(macName)) {
                        c.setBackground(sel ? new Color(255, 180, 180) : new Color(255, 220, 220));
                    } else {
                        c.setBackground(sel ? t.getSelectionBackground() : Color.WHITE);
                    }
                } else {
                    c.setBackground(sel ? t.getSelectionBackground() : Color.WHITE);
                }
                return c;
            }
        };
        resultTable.getColumnModel().getColumn(1).setCellRenderer(nameRenderer);
        resultTable.getColumnModel().getColumn(2).setCellRenderer(nameRenderer);
        resultTable.getColumnModel().getColumn(3).setCellRenderer(nameRenderer);
        resultTable.getColumnModel().getColumn(4).setCellRenderer(nameRenderer);

        // Color-coded status cells
        resultTable.getColumnModel().getColumn(7).setCellRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable t, Object val,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, val, sel, focus, row, col);
                c.setFont(thaiFont);
                if (val != null) {
                    String s = val.toString();
                    if (s.contains("Name")) c.setBackground(new Color(255, 180, 100));
                    else if (s.equals("Match")) c.setBackground(new Color(200, 255, 200));
                    else if (s.contains("Mismatch")) c.setBackground(new Color(255, 200, 200));
                    else if (s.contains("Ready")) c.setBackground(new Color(200, 220, 255));
                    else if (s.contains("Pending")) c.setBackground(new Color(255, 255, 200));
                    else c.setBackground(Color.WHITE);
                    if (sel) c.setBackground(c.getBackground().darker());
                }
                return c;
            }
        });

        add(new JScrollPane(resultTable), BorderLayout.CENTER);

        // Bottom panel — Summary + Status
        JPanel bottomPanel = new JPanel(new BorderLayout());
        summaryLabel = new JLabel(" ");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD));
        bottomPanel.add(summaryLabel, BorderLayout.CENTER);
        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(Color.GRAY);
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);
        add(bottomPanel, BorderLayout.SOUTH);

        // Listen to division change to load rounds
        divisionCombo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { loadRounds(); }
        });
    }

    // ==================== Data Loading ====================

    private void loadDivisions() {
        statusLabel.setText("Connecting to TESUJI...");
        new SwingWorker<List<TesujiClient.Division>, Void>() {
            protected List<TesujiClient.Division> doInBackground() throws Exception {
                return client.getDivisions();
            }
            protected void done() {
                try {
                    List<TesujiClient.Division> divs = get();
                    divisionCombo.removeAllItems();
                    for (TesujiClient.Division d : divs) {
                        divisionCombo.addItem(d);
                    }
                    statusLabel.setText("Connected (" + divs.size() + " divisions)");
                    statusLabel.setForeground(new Color(0, 128, 0));

                    // Auto-select division matching MacMahon tournament name
                    try {
                        String macTournamentName = getMacMahonTournamentName();
                        System.out.println("[Sync] MacMahon tournament name: '" + macTournamentName + "'");
                        if (!macTournamentName.isEmpty()) {
                            TesujiClient.Division bestMatch = findBestDivisionMatch(macTournamentName, divs);
                            if (bestMatch != null) {
                                divisionCombo.setSelectedItem(bestMatch);
                                statusLabel.setText("Connected (" + divs.size() + " divisions) — auto: " + bestMatch.name);
                                System.out.println("[Sync] Auto-selected division: " + bestMatch.id + " = " + bestMatch.name);
                            } else {
                                statusLabel.setText("Connected (" + divs.size() + " divisions) — no match for: " + macTournamentName);
                                System.out.println("[Sync] No division match found");
                            }
                        } else {
                            statusLabel.setText("Connected (" + divs.size() + " divisions) — no tournament open");
                            System.out.println("[Sync] No tournament name found");
                        }
                    } catch (Exception ex) {
                        System.err.println("[Sync] Auto-select division failed: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                } catch (Exception e) {
                    statusLabel.setText("Connection failed: " + e.getMessage());
                    statusLabel.setForeground(Color.RED);
                }
            }
        }.execute();
    }

    private void loadRounds() {
        TesujiClient.Division div = (TesujiClient.Division) divisionCombo.getSelectedItem();
        if (div == null) return;

        new SwingWorker<TesujiClient.MatchData, Void>() {
            protected TesujiClient.MatchData doInBackground() throws Exception {
                return client.getMatches(div.id, "");
            }
            protected void done() {
                try {
                    TesujiClient.MatchData data = get();
                    roundCombo.removeAllItems();
                    if (data.rounds != null) {
                        for (String r : data.rounds) {
                            roundCombo.addItem(r);
                        }
                    }
                    // Select current round from MacMahon if possible
                    try {
                        int macRound = getMacMahonCurrentRound();
                        if (macRound > 0) {
                            roundCombo.setSelectedItem(String.valueOf(macRound));
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                } catch (Exception e) {
                    statusLabel.setText("Failed to load rounds: " + e.getMessage());
                }
            }
        }.execute();
    }

    // ==================== Verify ====================

    private void doVerify() {
        TesujiClient.Division div = (TesujiClient.Division) divisionCombo.getSelectedItem();
        String round = (String) roundCombo.getSelectedItem();
        if (div == null || round == null) {
            JOptionPane.showMessageDialog(this, "กรุณาเลือก Division และ Round");
            return;
        }

        verifyButton.setEnabled(false);
        statusLabel.setText("Verifying...");

        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                // Get TESUJI data
                TesujiClient.MatchData tesujiData = client.getMatches(div.id, round);

                // Get MacMahon data
                int roundNum = Integer.parseInt(round);
                if (roundNum <= 0) throw new Exception("กรุณา Make Pairing ใน MacMahon ก่อน (ยังไม่มี round)");
                Map<Integer, Object> macPairings = getMacMahonPairings(roundNum);

                // Compare — match by NAME first, then fallback to board number
                comparisonData.clear();
                if (tesujiData.matches != null) {
                    // Build name-based lookup from MacMahon pairings
                    java.util.Set<Object> usedPairings = new java.util.HashSet<Object>();

                    for (TesujiClient.MatchInfo tm : tesujiData.matches) {
                        int boardNum = 0;
                        try { boardNum = Integer.parseInt(tm.table); } catch (Exception e) { continue; }

                        ComparisonRow row = new ComparisonRow();
                        row.boardNumber = boardNum;
                        row.tesujiBlack = tm.black != null ? tm.black : "";
                        row.tesujiWhite = tm.white != null ? tm.white : "";
                        row.tesujiResult = tm.result != null ? tm.result : "?-?";

                        // 1) Try match by player name (find the right pairing regardless of board number)
                        row.macPairing = null;
                        for (Object pairing : macPairings.values()) {
                            if (usedPairings.contains(pairing)) continue;
                            try {
                                Object bp = getBlackMethod.invoke(pairing);
                                Object wp = getWhiteMethod.invoke(pairing);
                                String mb = bp != null ? (String) getNameMethod.invoke(bp) : "";
                                String mw = wp != null ? (String) getNameMethod.invoke(wp) : "";
                                if (namesMatch(row.tesujiBlack, mb) && namesMatch(row.tesujiWhite, mw)) {
                                    row.macPairing = pairing;
                                    usedPairings.add(pairing);
                                    break;
                                }
                            } catch (Exception ex) { /* skip */ }
                        }

                        // 2) Fallback: match by board number
                        if (row.macPairing == null) {
                            Object byBoard = macPairings.get(boardNum);
                            if (byBoard != null && !usedPairings.contains(byBoard)) {
                                row.macPairing = byBoard;
                                usedPairings.add(byBoard);
                            }
                        }

                        if (row.macPairing != null) {
                            try {
                                Object macResult = getResultMethod.invoke(row.macPairing);
                                row.macResult = (String) getShortNameMethod.invoke(macResult);
                            } catch (Exception e) {
                                row.macResult = "?";
                            }
                            try {
                                Object blackPlayer = getBlackMethod.invoke(row.macPairing);
                                Object whitePlayer = getWhiteMethod.invoke(row.macPairing);
                                row.macBlack = blackPlayer != null ? (String) getNameMethod.invoke(blackPlayer) : "";
                                row.macWhite = whitePlayer != null ? (String) getNameMethod.invoke(whitePlayer) : "";
                            } catch (Exception e) {
                                row.macBlack = "?";
                                row.macWhite = "?";
                            }
                            row.nameMatch = namesMatch(row.tesujiBlack, row.macBlack)
                                         && namesMatch(row.tesujiWhite, row.macWhite);
                            // Check board number match
                            try {
                                int macBoard = (Integer) getBoardNumberMethod.invoke(row.macPairing);
                                row.boardMatch = (macBoard == row.boardNumber);
                            } catch (Exception e) {
                                row.boardMatch = true;
                            }
                        } else {
                            row.macResult = "(no match)";
                            row.macBlack = "";
                            row.macWhite = "";
                            row.nameMatch = true;
                            row.boardMatch = true;
                        }

                        row.status = determineStatus(row);
                        comparisonData.add(row);
                    }
                }
                return null;
            }

            protected void done() {
                try {
                    get(); // Check for exceptions
                    updateTable();
                    verifyButton.setEnabled(true);
                } catch (Exception e) {
                    // Unwrap nested exceptions to find root cause
                    Throwable cause = e;
                    while (cause.getCause() != null && cause.getCause() != cause) {
                        cause = cause.getCause();
                    }
                    String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                    if (msg.contains("No tournament") || msg.contains("NullPointer")) {
                        msg = "กรุณาเปิด Tournament + Make Pairing ใน MacMahon ก่อน";
                    }
                    statusLabel.setText(msg);
                    statusLabel.setForeground(Color.RED);
                    cause.printStackTrace();
                    verifyButton.setEnabled(true);
                }
            }
        }.execute();
    }

    private void updateTable() {
        tableModel.setRowCount(0);
        int matched = 0, mismatched = 0, pending = 0, ready = 0, nameWarn = 0;

        for (ComparisonRow row : comparisonData) {
            tableModel.addRow(new Object[]{
                row.boardNumber,
                row.tesujiBlack,
                row.macBlack,
                row.tesujiWhite,
                row.macWhite,
                formatResult(row.tesujiResult),
                formatResult(row.macResult),
                row.status
            });

            if (row.status.contains("Name")) nameWarn++;
            else if (row.status.equals("Match")) matched++;
            else if (row.status.equals("Mismatch")) mismatched++;
            else if (row.status.equals("Pending")) pending++;
            else if (row.status.contains("Ready")) ready++;
        }

        summaryLabel.setText(String.format(
            "Match: %d | Mismatch: %d | Pending: %d | Ready: %d | Name warn: %d",
            matched, mismatched, pending, ready, nameWarn));

        autoFillButton.setEnabled(ready > 0 || nameWarn > 0);
        String selectedRound = (String) roundCombo.getSelectedItem();
        boolean isRound1 = "1".equals(selectedRound);
        fixNamesButton.setEnabled(nameWarn > 0 && isRound1);
        statusLabel.setText("Verified " + comparisonData.size() + " pairings");
        statusLabel.setForeground(new Color(0, 128, 0));
    }

    private String formatResult(String result) {
        if (result == null || result.isEmpty() || result.equals("?-?")) return "(empty)";
        if (result.equals("1-0")) return "B+ (1-0)";
        if (result.equals("0-1")) return "W+ (0-1)";
        // Also handle TESUJI format in case it comes differently
        if (result.equalsIgnoreCase("B+") || result.equalsIgnoreCase("B")) return "B+ (1-0)";
        if (result.equalsIgnoreCase("W+") || result.equalsIgnoreCase("W")) return "W+ (0-1)";
        return result;
    }

    private String determineStatus(ComparisonRow row) {
        boolean tesujiHasResult = row.tesujiResult != null && !row.tesujiResult.isEmpty()
            && !row.tesujiResult.equals("?-?");
        boolean macHasResult = row.macResult != null && !row.macResult.isEmpty()
            && !row.macResult.equals("?-?") && !row.macResult.equals("(no match)");

        String base;
        if (!tesujiHasResult && !macHasResult) base = "Pending";
        else if (!tesujiHasResult && macHasResult) base = "Match";
        else if (tesujiHasResult && !macHasResult) base = "Ready to fill";
        else {
            // Both have results — compare
            String normTesuji = normalizeResult(row.tesujiResult);
            String normMac = normalizeResult(row.macResult);
            base = normTesuji.equals(normMac) ? "Match" : "Mismatch";
        }

        // Add name warning if names don't match
        if (!row.nameMatch && row.macPairing != null) {
            if (base.equals("Ready to fill")) return "Name mismatch!";
            return base + " (Name!)";
        }
        return base;
    }

    /**
     * Check if two player names match (fuzzy).
     * Handles different formats: "First Last" vs "Last, First", whitespace, case.
     */
    private boolean namesMatch(String tesujiName, String macName) {
        if (tesujiName == null || macName == null) return true;
        String a = normalizeName(tesujiName);
        String b = normalizeName(macName);
        if (a.isEmpty() || b.isEmpty()) return true;
        if (a.equals(b)) return true;
        // Check if one contains the other (partial match)
        if (a.contains(b) || b.contains(a)) return true;
        // Check reversed name order: "First Last" vs "Last First"
        String[] aParts = a.split("\\s+");
        String[] bParts = b.split("\\s+");
        if (aParts.length >= 2 && bParts.length >= 2) {
            String aReversed = aParts[aParts.length - 1] + " " + aParts[0];
            String bReversed = bParts[bParts.length - 1] + " " + bParts[0];
            if (a.equals(bReversed) || b.equals(aReversed)) return true;
        }
        return false;
    }

    private static String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().replaceAll("\\s+", " ").toLowerCase()
            .replace(",", "").replace(".", "");
    }

    // ==================== Auto-fill ====================

    private void doAutoFill() {
        int readyCount = 0;
        int nameWarnCount = 0;
        for (ComparisonRow row : comparisonData) {
            if ("Ready to fill".equals(row.status)) readyCount++;
            if ("Name mismatch!".equals(row.status)) nameWarnCount++;
        }

        if (readyCount == 0 && nameWarnCount == 0) {
            JOptionPane.showMessageDialog(this, "ไม่มีคู่ที่พร้อม fill");
            return;
        }

        int skipMatch = 0, skipMismatch = 0;
        for (ComparisonRow row : comparisonData) {
            if (row.status.equals("Match") || row.status.contains("Match (Name!)")) skipMatch++;
            if (row.status.equals("Mismatch")) skipMismatch++;
        }

        String msg = String.format(
            "จะเติมผล %d คู่ จากทั้งหมด %d คู่\n" +
            "(ข้าม %d คู่ที่ตรงอยู่แล้ว, ข้าม %d คู่ที่ไม่ตรง)",
            readyCount, comparisonData.size(), skipMatch, skipMismatch);

        if (nameWarnCount > 0) {
            msg += String.format("\n\n*** พบ %d คู่ที่ชื่อไม่ตรง! ***\nต้องการเติมผลคู่เหล่านี้ด้วยหรือไม่?", nameWarnCount);
        }
        msg += "\n\nดำเนินการ?";

        int confirm = JOptionPane.showConfirmDialog(this, msg, "Confirm Auto-fill",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.OK_OPTION) return;

        // If name mismatches exist, ask specifically about them
        boolean fillNameMismatch = false;
        if (nameWarnCount > 0) {
            int nameConfirm = JOptionPane.showConfirmDialog(this,
                String.format("พบ %d คู่ที่ชื่อไม่ตรงกัน\nเติมผลคู่ที่ชื่อไม่ตรงด้วยไหม?", nameWarnCount),
                "Name Mismatch Warning",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            fillNameMismatch = (nameConfirm == JOptionPane.YES_OPTION);
        }

        int filled = 0;
        int failed = 0;

        for (ComparisonRow row : comparisonData) {
            boolean isReady = "Ready to fill".equals(row.status);
            boolean isNameWarn = "Name mismatch!".equals(row.status);
            if (!isReady && !(isNameWarn && fillNameMismatch)) continue;
            if (row.macPairing == null) continue;

            try {
                Object resultDesc = convertResult(row.tesujiResult);
                if (resultDesc != null) {
                    setResultMethod.invoke(row.macPairing, resultDesc);
                    filled++;
                }
            } catch (Exception e) {
                failed++;
                System.err.println("[Launcher] Failed to set result for board " + row.boardNumber + ": " + e.getMessage());
            }
        }

        // Refresh MacMahon UI
        refreshMacMahonUI();

        JOptionPane.showMessageDialog(this,
            String.format("เติมผลสำเร็จ %d คู่" + (failed > 0 ? " (ล้มเหลว %d คู่)" : ""), filled, failed),
            "Auto-fill Complete", JOptionPane.INFORMATION_MESSAGE);

        // Re-verify
        doVerify();
    }

    // ==================== Fix Names ====================

    private void doFixNames() {
        // Count what needs fixing
        int nameMismatch = 0, boardMismatch = 0;
        for (ComparisonRow row : comparisonData) {
            if (row.macPairing == null) continue;
            if (!row.nameMatch) nameMismatch++;
            if (!row.boardMatch) boardMismatch++;
        }
        if (nameMismatch == 0 && boardMismatch == 0) {
            JOptionPane.showMessageDialog(this, "ไม่มีอะไรต้องแก้ไข (ชื่อและเลขโต๊ะตรงหมดแล้ว)");
            return;
        }

        // Build detail list
        StringBuilder detail = new StringBuilder();
        if (boardMismatch > 0) {
            detail.append(String.format("เลขโต๊ะไม่ตรง %d คู่:\n", boardMismatch));
            for (ComparisonRow row : comparisonData) {
                if (row.macPairing == null || row.boardMatch) continue;
                try {
                    int macBoard = (Integer) getBoardNumberMethod.invoke(row.macPairing);
                    detail.append(String.format("  %s vs %s: Board %d -> Table %d\n",
                        row.macBlack, row.macWhite, macBoard, row.boardNumber));
                } catch (Exception e) { /* skip */ }
            }
            detail.append("\n");
        }
        if (nameMismatch > 0) {
            detail.append(String.format("ชื่อไม่ตรง %d คู่:\n", nameMismatch));
            for (ComparisonRow row : comparisonData) {
                if (row.macPairing == null || row.nameMatch) continue;
                if (!namesMatch(row.tesujiBlack, row.macBlack)) {
                    detail.append(String.format("  Table %d Black: \"%s\" -> \"%s\"\n",
                        row.boardNumber, row.macBlack, row.tesujiBlack));
                }
                if (!namesMatch(row.tesujiWhite, row.macWhite)) {
                    detail.append(String.format("  Table %d White: \"%s\" -> \"%s\"\n",
                        row.boardNumber, row.macWhite, row.tesujiWhite));
                }
            }
            detail.append("\n");
        }
        detail.append("แก้ไขใน MacMahon ให้ตรงกับ TESUJI?");

        int confirm = JOptionPane.showConfirmDialog(this, detail.toString(),
            "Force Pairing", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) return;

        int nameFixed = 0, boardFixed = 0, failed = 0;
        for (ComparisonRow row : comparisonData) {
            if (row.macPairing == null) continue;

            // 1) Fix board number for ALL matched pairings
            if (!row.boardMatch) {
                try {
                    int currentBoard = (Integer) getBoardNumberMethod.invoke(row.macPairing);
                    setBoardNumberMethod.invoke(row.macPairing, row.boardNumber, true);
                    boardFixed++;
                    System.out.println("[Sync] Board " + currentBoard + " -> Table " + row.boardNumber);
                } catch (Exception e) {
                    System.err.println("[Sync] Set board failed: " + e.getMessage());
                }
            }

            // 2) Fix names for mismatched ones
            if (!row.nameMatch) {
                if (!namesMatch(row.tesujiBlack, row.macBlack)) {
                    try {
                        renameParticipant(getBlackMethod.invoke(row.macPairing), row.tesujiBlack);
                        nameFixed++;
                    } catch (Exception e) {
                        failed++;
                        System.err.println("[Sync] Fix name failed table " + row.boardNumber + " Black: " + e.getMessage());
                    }
                }
                if (!namesMatch(row.tesujiWhite, row.macWhite)) {
                    try {
                        renameParticipant(getWhiteMethod.invoke(row.macPairing), row.tesujiWhite);
                        nameFixed++;
                    } catch (Exception e) {
                        failed++;
                        System.err.println("[Sync] Fix name failed table " + row.boardNumber + " White: " + e.getMessage());
                    }
                }
            }
        }

        refreshMacMahonUI();

        StringBuilder resultMsg = new StringBuilder("Force Pairing สำเร็จ!\n");
        if (boardFixed > 0) resultMsg.append(String.format("แก้เลขโต๊ะ %d คู่\n", boardFixed));
        if (nameFixed > 0) resultMsg.append(String.format("แก้ชื่อ %d คน\n", nameFixed));
        if (failed > 0) resultMsg.append(String.format("ล้มเหลว %d คน\n", failed));
        JOptionPane.showMessageDialog(this, resultMsg.toString(),
            "Force Pairing Complete", JOptionPane.INFORMATION_MESSAGE);

        doVerify();
    }

    /**
     * Rename a MacMahon participant to match a TESUJI name.
     * Goes through: Participant → IndividualParticipant.getGoPlayer() → Person.setFirstName/setSurname
     */
    private void renameParticipant(Object participant, String tesujiName) throws Exception {
        if (participant == null || tesujiName == null || tesujiName.trim().isEmpty()) return;

        Object goPlayer = getGoPlayerMethod.invoke(participant);
        if (goPlayer == null) throw new Exception("getGoPlayer() returned null");

        // Put entire TESUJI name into surname field only
        String name = tesujiName.trim();
        setFirstNameMethod.invoke(goPlayer, "");
        setSurnameMethod.invoke(goPlayer, name);
        System.out.println("[Sync] Renamed: surname=\"" + name + "\"");
    }

    // ==================== MacMahon Reflection ====================

    private String getMacMahonTournamentName() {
        try {
            Method getTournament = appInstance.getClass().getMethod("getTournament");
            Object tournament = getTournament.invoke(appInstance);
            if (tournament == null) return "";
            Method getName = tournament.getClass().getMethod("getName");
            Object name = getName.invoke(tournament);
            return name != null ? name.toString() : "";
        } catch (Exception e) {
            System.err.println("[Sync] Could not get tournament name: " + e.getMessage());
            return "";
        }
    }

    /**
     * Find best matching division from TESUJI for the MacMahon tournament name.
     * Tournament name format: "01 - 1-2 Dan A", "02 - 1-2 Dan B", etc.
     * The leading number is the division ID.
     */
    private TesujiClient.Division findBestDivisionMatch(String macName, List<TesujiClient.Division> divs) {
        if (macName == null || macName.trim().isEmpty() || divs.isEmpty()) return null;
        String name = macName.trim();

        // Extract leading number from tournament name (e.g. "01" from "01 - 1-2 Dan A")
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+)").matcher(name);
        String leadingNum = null;
        if (matcher.find()) {
            leadingNum = matcher.group(1);
        }
        System.out.println("[Sync] Tournament name: " + name + " -> leading number: " + leadingNum);

        if (leadingNum != null) {
            String numNorm = leadingNum.replaceFirst("^0+", "");
            for (TesujiClient.Division d : divs) {
                String divId = d.id.trim();
                // Exact match: "01" == "01"
                if (divId.equals(leadingNum)) return d;
                // Normalized match: "1" == "1" (handles "01" vs "1")
                String divNorm = divId.replaceFirst("^0+", "");
                if (!numNorm.isEmpty() && numNorm.equals(divNorm)) return d;
            }
        }

        return null;
    }

    private int getMacMahonCurrentRound() throws Exception {
        Method getTournament = appInstance.getClass().getMethod("getTournament");
        Object tournament = getTournament.invoke(appInstance);
        if (tournament == null) throw new Exception("No tournament open");
        Method getCurrentRound = tournament.getClass().getMethod("getCurrentRoundNumber");
        return (Integer) getCurrentRound.invoke(tournament);
    }

    private Map<Integer, Object> getMacMahonPairings(int roundNumber) throws Exception {
        Method getTournament = appInstance.getClass().getMethod("getTournament");
        Object tournament = getTournament.invoke(appInstance);
        if (tournament == null) throw new Exception("กรุณาเปิด Tournament ใน MacMahon ก่อน");

        Method getCurrentRoundNum = tournament.getClass().getMethod("getCurrentRoundNumber");
        int currentRound = (Integer) getCurrentRoundNum.invoke(tournament);
        System.out.println("[Sync] MacMahon currentRound=" + currentRound + ", requested=" + roundNumber);

        Object tournamentRound = null;

        // Try getCurrentRound() first (most reliable)
        if (roundNumber == currentRound) {
            Method getCurrentRound = tournament.getClass().getMethod("getCurrentRound");
            tournamentRound = getCurrentRound.invoke(tournament);
            System.out.println("[Sync] Using getCurrentRound()");
        }

        // Fallback: try getRound(index) — 0-indexed
        if (tournamentRound == null) {
            try {
                Method getRound = tournament.getClass().getMethod("getRound", int.class);
                tournamentRound = getRound.invoke(tournament, roundNumber - 1);
                System.out.println("[Sync] Using getRound(" + (roundNumber - 1) + ")");
            } catch (Exception e) {
                System.err.println("[Sync] getRound failed: " + e.getMessage());
            }
        }

        // Fallback 2: access m_rounds array directly
        if (tournamentRound == null) {
            try {
                java.lang.reflect.Field roundsField = tournament.getClass().getDeclaredField("m_rounds");
                roundsField.setAccessible(true);
                Object[] rounds = (Object[]) roundsField.get(tournament);
                if (rounds != null && roundNumber - 1 >= 0 && roundNumber - 1 < rounds.length) {
                    tournamentRound = rounds[roundNumber - 1];
                    System.out.println("[Sync] Using m_rounds[" + (roundNumber - 1) + "] (array length=" + rounds.length + ")");
                } else {
                    System.out.println("[Sync] m_rounds is " + (rounds == null ? "null" : "length=" + rounds.length));
                }
            } catch (Exception e) {
                System.err.println("[Sync] m_rounds fallback failed: " + e.getMessage());
            }
        }

        if (tournamentRound == null) {
            throw new Exception("Round " + roundNumber + " ไม่พบใน MacMahon (currentRound=" + currentRound + ")");
        }

        // getPairings()
        Method getPairings = tournamentRound.getClass().getMethod("getPairings");
        List<?> pairings = (List<?>) getPairings.invoke(tournamentRound);

        Map<Integer, Object> result = new LinkedHashMap<Integer, Object>();
        if (pairings != null) {
            for (Object pairing : pairings) {
                int boardNum = (Integer) getBoardNumberMethod.invoke(pairing);
                result.put(boardNum, pairing);
            }
        }
        System.out.println("[Sync] Found " + result.size() + " pairings in MacMahon round " + roundNumber);
        return result;
    }

    private Object convertResult(String tesujiResult) {
        if (tesujiResult == null) return null;
        String norm = normalizeResult(tesujiResult);
        switch (norm) {
            case "1-0": return BLACK_WINS;
            case "0-1": return WHITE_WINS;
            case "1/2-1/2": return JIGO;
            case "0-0": return BOTH_LOSE;
            case "1-1": return BOTH_WIN;
            default: return null;
        }
    }

    private String normalizeResult(String result) {
        if (result == null) return "";
        String r = result.trim();
        // Handle TESUJI format
        if (r.equalsIgnoreCase("B+") || r.equalsIgnoreCase("B") || r.equals("1-0")) return "1-0";
        if (r.equalsIgnoreCase("W+") || r.equalsIgnoreCase("W") || r.equals("0-1")) return "0-1";
        if (r.equalsIgnoreCase("Jigo") || r.equalsIgnoreCase("Draw")
            || r.equals("½-½") || r.equals("1/2-1/2")) return "1/2-1/2";
        if (r.equals("0-0")) return "0-0";
        if (r.equals("1-1")) return "1-1";
        return r;
    }

    private void refreshMacMahonUI() {
        try {
            // Call fireWalllistTableDataChanged + firePairingsTableDataChanged
            Method fireWalllist = appInstance.getClass().getMethod("fireWalllistTableDataChanged");
            fireWalllist.invoke(appInstance);
            Method firePairings = appInstance.getClass().getMethod("firePairingsTableDataChanged");
            firePairings.invoke(appInstance);

            // Also rebuild scores
            Method getTournament = appInstance.getClass().getMethod("getTournament");
            Object tournament = getTournament.invoke(appInstance);
            if (tournament != null) {
                Method buildScores = tournament.getClass().getMethod("buildParticipantScores");
                buildScores.invoke(tournament);
            }
        } catch (Exception e) {
            System.err.println("[Launcher] UI refresh partial: " + e.getMessage());
        }
    }

    // ==================== Data Classes ====================

    private static class ComparisonRow {
        int boardNumber;       // TESUJI table number
        String tesujiBlack;
        String tesujiWhite;
        String macBlack = "";
        String macWhite = "";
        String tesujiResult;
        String macResult;
        String status;
        boolean nameMatch = true;
        boolean boardMatch = true; // MacMahon board == TESUJI table?
        Object macPairing; // Pairing object from MacMahon
    }
}
