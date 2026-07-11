package launcher;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * MacMahon Launcher — wraps MacMahon JAR, adds "Sync Results" menu
 * to pull results from TESUJI server and auto-fill into MacMahon.
 */
public class MacMahonLauncher {

    private static final String PROPERTIES_FILE = "launcher.properties";
    private static String tesujiUrl = "";
    private static String tesujiToken = "";
    private static URLClassLoader macmahonClassLoader;
    private static Object appInstance; // MacMahonApplication instance
    private static String chosenFontName = "TH Sarabun New";

    /** The Thai-compatible font family resolved for this system (see setupThaiFont()). */
    public static String getChosenFontName() {
        return chosenFontName;
    }

    private static File tournamentFileToOpen = null;
    private static File[] allTournamentFiles = null;

    public static void main(String[] args) {
        System.out.println("[Launcher] MacMahon Launcher starting...");

        // Step 0: Ensure Java 25+ — if running on older Java, find Java 25 and relaunch
        ensureJava25();

        // Step 0b: Set UTF-8 encoding + Thai-compatible font
        System.setProperty("file.encoding", "UTF-8");
        // Put the menu bar at the top of the screen like a native Mac app,
        // instead of inside the window. Must be set before any AWT/Swing
        // class touches the Toolkit (i.e. before setupThaiFont() below).
        if (isMac()) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
        }
        setupThaiFont();

        // Step 1: Read config
        Properties props = loadOrCreateProperties();
        tesujiUrl = props.getProperty("tesuji.url", "https://tesuji-reg.vercel.app").trim();
        tesujiToken = props.getProperty("tesuji.token", "").trim();
        if (tesujiUrl.endsWith("/")) {
            tesujiUrl = tesujiUrl.substring(0, tesujiUrl.length() - 1);
        }
        System.out.println("[Launcher] TESUJI URL: " + tesujiUrl);

        // Scan for .xml tournament files in same folder
        allTournamentFiles = scanTournamentFiles();

        // Step 2: Find MacMahon JAR
        File macmahonJar = findMacMahonJar();
        if (macmahonJar == null) {
            JOptionPane.showMessageDialog(null,
                "ไม่พบไฟล์ MacMahon (macmahon-*.jar) ใน folder เดียวกับ Launcher\n" +
                "กรุณาวาง macmahon-launcher.jar ไว้ folder เดียวกับ macmahon JAR",
                "MacMahon Launcher — Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        System.out.println("[Launcher] Found MacMahon: " + macmahonJar.getName());

        // Step 3: Load MacMahon into ClassPath
        try {
            URL jarUrl = macmahonJar.toURI().toURL();
            macmahonClassLoader = new URLClassLoader(
                new URL[]{jarUrl},
                MacMahonLauncher.class.getClassLoader()
            );
        } catch (Exception e) {
            showError("โหลด MacMahon JAR ไม่สำเร็จ: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Step 4: Launch MacMahon
        try {
            Class<?> appClass = macmahonClassLoader.loadClass(
                "de.cgerlach.macmahon.gui.MacMahonApplication"
            );
            Method mainMethod = appClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) new String[]{});
            System.out.println("[Launcher] MacMahon launched");
        } catch (Exception e) {
            showError("เปิด MacMahon ไม่สำเร็จ: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }

        // Step 5: Wait for JFrame, then inject menu + tab bar
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                Timer timer = new Timer(500, null);
                timer.addActionListener(new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        Frame[] frames = Frame.getFrames();
                        for (Frame frame : frames) {
                            if (frame instanceof JFrame && frame.isVisible()) {
                                JFrame jf = (JFrame) frame;
                                if (jf.getJMenuBar() != null && jf.getJMenuBar().getMenuCount() > 0) {
                                    System.out.println("[Launcher] JFrame found, injecting menu...");
                                    findAppInstance(jf);
                                    applyThaiFontToAll(jf);
                                    injectMenu(jf);
                                    // Inject tab bar if multiple .xml files found
                                    if (allTournamentFiles != null && allTournamentFiles.length > 0) {
                                        injectTabBar(jf, allTournamentFiles);
                                    }
                                    startFontMaintenanceTimer();
                                    timer.stop();
                                }
                            }
                        }
                    }
                });
                timer.start();
            }
        });
    }

    // ==================== Tournament Tab Bar ====================

    private static JPanel tabBarPanel;
    private static JButton[] tabButtons;
    private static int activeTabIndex = -1;

    /**
     * Scan for .xml tournament files. Prefers an "xml" subfolder next to the
     * launcher (keeps the working directory tidy); falls back to the launcher
     * directory itself when no such subfolder exists (legacy layout).
     */
    private static File[] scanTournamentFiles() {
        File dir = new File(getLauncherDir(), "xml");
        if (!dir.isDirectory()) {
            dir = getLauncherDir();
        }
        System.out.println("[Launcher] Scanning for .xml in: " + dir.getAbsolutePath());
        File[] xmlFiles = dir.listFiles(new FileFilter() {
            public boolean accept(File f) {
                return f.isFile() && f.getName().toLowerCase().endsWith(".xml");
            }
        });
        if (xmlFiles != null && xmlFiles.length > 0) {
            java.util.Arrays.sort(xmlFiles, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    return compareTournamentFileNames(a.getName(), b.getName());
                }
            });
            System.out.println("[Launcher] Found " + xmlFiles.length + " .xml files");
        }
        return xmlFiles;
    }

    /**
     * Compare tournament file names by their leading division number as an
     * integer first (so "2 - ..." sorts before "10 - ..."), falling back to
     * plain case-insensitive string comparison.
     */
    private static int compareTournamentFileNames(String a, String b) {
        java.util.regex.Matcher ma = java.util.regex.Pattern.compile("^(\\d+)").matcher(a);
        java.util.regex.Matcher mb = java.util.regex.Pattern.compile("^(\\d+)").matcher(b);
        if (ma.find() && mb.find()) {
            try {
                int na = Integer.parseInt(ma.group(1));
                int nb = Integer.parseInt(mb.group(1));
                if (na != nb) return Integer.compare(na, nb);
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return a.compareToIgnoreCase(b);
    }

    /**
     * Inject a tab bar at the top of the MacMahon JFrame.
     * Each tab = one .xml file. Clicking switches tournament.
     */
    private static void injectTabBar(final JFrame frame, final File[] xmlFiles) {
        tabBarPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        tabBarPanel.setBackground(new Color(60, 63, 65));

        tabButtons = new JButton[xmlFiles.length];
        for (int i = 0; i < xmlFiles.length; i++) {
            final int idx = i;
            String label = xmlFiles[i].getName().replaceFirst("(?i)\\.xml$", "");
            JButton btn = new JButton(label);
            btn.setFocusPainted(false);
            btn.setFont(new Font(chosenFontName, Font.BOLD, 14));
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.setOpaque(true);
            btn.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    switchToTab(idx, xmlFiles, frame);
                }
            });
            tabButtons[i] = btn;
            tabBarPanel.add(btn);
        }

        // Wrap tab bar in scroll pane — no visible scrollbar, scroll via mouse wheel
        final JScrollPane tabScroll = new JScrollPane(tabBarPanel,
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        tabScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(100, 100, 100)));
        tabScroll.addMouseWheelListener(new java.awt.event.MouseWheelListener() {
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                JScrollBar hBar = tabScroll.getHorizontalScrollBar();
                hBar.setValue(hBar.getValue() + e.getWheelRotation() * 60);
            }
        });

        // Insert tab bar: wrap the original content pane (preserves its layout/constraints)
        // MacMahon uses GridBagLayout internally — we must NOT modify it
        Container originalContentPane = frame.getContentPane();
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(tabScroll, BorderLayout.NORTH);
        wrapper.add(originalContentPane, BorderLayout.CENTER);
        frame.setContentPane(wrapper);

        updateTabStyles(-1); // no tab active initially
        frame.revalidate();
        frame.repaint();
        System.out.println("[Launcher] Tab bar injected (" + xmlFiles.length + " tabs)");

        // Auto-open first file
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                switchToTab(0, xmlFiles, frame);
            }
        });
    }

    /**
     * Switch to a tournament tab.
     * Key design: open new tournament FIRST, only overwrite m_tournament on success.
     * This prevents "disappearing" tournaments when open fails.
     */
    private static void switchToTab(int index, File[] xmlFiles, JFrame frame) {
        if (index == activeTabIndex) return;
        if (appInstance == null) {
            System.err.println("[Launcher] switchToTab: appInstance is null!");
            return;
        }

        File file = xmlFiles[index];
        System.out.println("[Launcher] Switching to tab " + index + ": " + file.getName());

        try {
            // Step 1: Auto-save current tournament before switching.
            // tournamentSave() returns 0 on success; non-zero (or a thrown
            // exception) means the save did NOT happen (e.g. user cancelled
            // a Save As dialog) — ask before discarding unsaved changes.
            if (activeTabIndex >= 0) {
                boolean saveOk = false;
                try {
                    Method saveMethod = appInstance.getClass().getMethod("tournamentSave");
                    int saveResult = (Integer) saveMethod.invoke(appInstance);
                    saveOk = (saveResult == 0);
                    System.out.println("[Launcher] Auto-saved tournament (result=" + saveResult + ")");
                } catch (Exception ex) {
                    System.err.println("[Launcher] Auto-save failed: " + ex.getMessage());
                }
                if (!saveOk) {
                    int confirm = JOptionPane.showConfirmDialog(frame,
                        "บันทึกทัวร์นาเมนต์ปัจจุบันไม่สำเร็จ\nการเปลี่ยนแปลงที่ยังไม่ได้บันทึกจะหายไปถ้าสลับ tab ต่อ\n\nสลับ tab ต่อหรือไม่?",
                        "Auto-save ล้มเหลว",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (confirm != JOptionPane.YES_OPTION) return;
                }
            }

            // Step 2: Open new tournament FIRST (don't clear old one yet!)
            Method openMethod = appInstance.getClass().getDeclaredMethod(
                "tournamentOpenInternal", java.io.File.class);
            openMethod.setAccessible(true);
            Object newTournament = openMethod.invoke(appInstance, file);
            System.out.println("[Launcher] tournamentOpenInternal returned: " + (newTournament != null));

            if (newTournament != null) {
                // Step 3: Success — overwrite m_tournament directly (no null gap)
                Field tournamentField = appInstance.getClass().getDeclaredField("m_tournament");
                tournamentField.setAccessible(true);
                tournamentField.set(appInstance, newTournament);
                System.out.println("[Launcher] Set m_tournament on app instance");

                Method openedMethod = appInstance.getClass().getDeclaredMethod("tournamentOpened");
                openedMethod.setAccessible(true);
                openedMethod.invoke(appInstance);

                activeTabIndex = index;
                updateTabStyles(index);

                frame.revalidate();
                frame.repaint();

                System.out.println("[Launcher] Opened: " + file.getName());
            } else {
                // Open failed — stay on current tab, nothing lost
                System.err.println("[Launcher] Failed to open: " + file.getName()
                    + " — staying on current tab");
            }
        } catch (Exception e) {
            System.err.println("[Launcher] Switch tab failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Update tab button styles — VS Code-style with blue underline on active tab.
     */
    private static void updateTabStyles(int activeIndex) {
        Color activeBg = new Color(75, 110, 175);
        Color activeFg = Color.WHITE;
        Color inactiveBg = new Color(80, 83, 85);
        Color inactiveFg = new Color(200, 200, 200);

        for (int i = 0; i < tabButtons.length; i++) {
            if (i == activeIndex) {
                tabButtons[i].setBackground(activeBg);
                tabButtons[i].setForeground(activeFg);
                tabButtons[i].setFont(new Font(chosenFontName, Font.BOLD, 14));
            } else {
                tabButtons[i].setBackground(inactiveBg);
                tabButtons[i].setForeground(inactiveFg);
                tabButtons[i].setFont(new Font(chosenFontName, Font.PLAIN, 14));
            }
            tabButtons[i].setOpaque(true);
            tabButtons[i].setBorderPainted(false);
        }

        // Scroll active tab into view
        if (activeIndex >= 0 && activeIndex < tabButtons.length) {
            tabButtons[activeIndex].scrollRectToVisible(tabButtons[activeIndex].getBounds());
        }
    }

    /**
     * Find the MacMahonApplication instance by traversing
     * from JFrame menu action listeners -> inner class -> this$0 -> m_application
     */
    private static void findAppInstance(JFrame frame) {
        try {
            JMenuBar menuBar = frame.getJMenuBar();
            if (menuBar == null || menuBar.getMenuCount() == 0) return;

            // Get any menu item's action listener
            for (int i = 0; i < menuBar.getMenuCount(); i++) {
                JMenu menu = menuBar.getMenu(i);
                if (menu == null) continue;
                for (int j = 0; j < menu.getItemCount(); j++) {
                    JMenuItem item = menu.getItem(j);
                    if (item == null) continue;
                    java.awt.event.ActionListener[] listeners = item.getActionListeners();
                    for (java.awt.event.ActionListener al : listeners) {
                        // Inner class of MacMahonMainWindow has this$0
                        try {
                            Field outerRef = al.getClass().getDeclaredField("this$0");
                            outerRef.setAccessible(true);
                            Object mainWindow = outerRef.get(al);

                            // MacMahonMainWindow.m_application -> MacMahonApplication
                            Field appField = mainWindow.getClass().getDeclaredField("m_application");
                            appField.setAccessible(true);
                            appInstance = appField.get(mainWindow);
                            System.out.println("[Launcher] Got MacMahonApplication instance");
                            return;
                        } catch (NoSuchFieldException nsfe) {
                            // Not the right inner class, try next
                        }
                    }
                }
            }

            // Fallback: try through MacMahonApplication class directly
            if (appInstance == null) {
                System.out.println("[Launcher] Trying fallback to find app instance...");
                // The MacMahonApplication might store itself somewhere we can find
                Class<?> appClass = macmahonClassLoader.loadClass(
                    "de.cgerlach.macmahon.gui.MacMahonApplication"
                );
                // Check if there's a static field holding the instance
                for (Field f : appClass.getDeclaredFields()) {
                    if (f.getType().equals(appClass) && Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        appInstance = f.get(null);
                        if (appInstance != null) {
                            System.out.println("[Launcher] Got app instance from static field");
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Launcher] Failed to find app instance: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Recursively apply Thai-compatible font to ALL components
     * in the MacMahon JFrame (overrides MacMahon's own font choices).
     */
    private static void applyThaiFontToAll(Container container) {
        Font thaiFont = new Font(chosenFontName, Font.PLAIN, 12);
        Font thaiFontBold = new Font(chosenFontName, Font.BOLD, 12);
        tableFoundCount = 0;
        int changed = applyFontRecursive(container, thaiFont, thaiFontBold);
        // Only log when this pass actually changed something — the maintenance
        // timer calls this every 5s and most passes are no-ops once fonts settle.
        if (changed > 0) {
            System.out.println("[Launcher] Thai font applied — " + changed + " components changed, "
                + tableFoundCount + " JTables found");
        }
    }

    private static int tableFoundCount = 0;

    private static int applyFontRecursive(Container container, Font normal, Font bold) {
        int changed = 0;
        for (Component comp : container.getComponents()) {
            // Preserve original style (bold/italic) — only change font family
            if (preserveFontFamily(comp)) changed++;

            if (comp instanceof JTable) {
                JTable table = (JTable) comp;
                wrapTableRenderers(table, normal, bold);
                tableFoundCount++;
            }
            if (comp instanceof JMenuBar) {
                JMenuBar mb = (JMenuBar) comp;
                for (int i = 0; i < mb.getMenuCount(); i++) {
                    JMenu menu = mb.getMenu(i);
                    if (menu != null) {
                        if (preserveFontFamily(menu)) changed++;
                        for (int j = 0; j < menu.getItemCount(); j++) {
                            JMenuItem item = menu.getItem(j);
                            if (item != null && preserveFontFamily(item)) changed++;
                        }
                    }
                }
            }
            if (comp instanceof Container) {
                changed += applyFontRecursive((Container) comp, normal, bold);
            }
        }
        return changed;
    }

    /**
     * Change font family to Thai-compatible font while preserving style (bold/italic) and size.
     * No-op if the component's font family already matches — avoids churning
     * setFont()/repaint() on every component on every maintenance timer tick.
     * Returns true iff the font was actually changed.
     */
    private static boolean preserveFontFamily(Component comp) {
        Font current = comp.getFont();
        if (current != null && !chosenFontName.equals(current.getName())) {
            comp.setFont(new Font(chosenFontName, current.getStyle(), current.getSize()));
            return true;
        }
        return false;
    }

    /**
     * Inject "Sync Results" menu into the menu bar
     */
    private static void injectMenu(JFrame mainFrame) {
        JMenuBar menuBar = mainFrame.getJMenuBar();

        JMenu tesujiMenu = new JMenu("TESUJI");

        JMenuItem syncItem = new JMenuItem("Sync from TESUJI...");
        syncItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSyncDialog(mainFrame);
            }
        });
        tesujiMenu.add(syncItem);

        tesujiMenu.addSeparator();

        JMenuItem exportPairingsItem = new JMenuItem("Export Pairings to TESUJI...");
        exportPairingsItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                exportPairingsToTesuji(mainFrame);
            }
        });
        tesujiMenu.add(exportPairingsItem);

        JMenuItem exportWalllistItem = new JMenuItem("Export Wall List to TESUJI...");
        exportWalllistItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                exportWalllistToTesuji(mainFrame);
            }
        });
        tesujiMenu.add(exportWalllistItem);

        menuBar.add(tesujiMenu);
        menuBar.revalidate();
        menuBar.repaint();
        System.out.println("[Launcher] Menu injected successfully");
    }

    /**
     * Open the Sync Dialog
     */
    private static void openSyncDialog(JFrame parent) {
        if (appInstance == null) {
            showError("ไม่พบ MacMahon Application instance\nกรุณาเปิดทัวร์นาเมนต์ก่อน");
            return;
        }
        try {
            SyncDialog dialog = new SyncDialog(parent, appInstance, macmahonClassLoader, tesujiUrl, tesujiToken);
            dialog.setVisible(true);
        } catch (Exception e) {
            showError("เปิด Sync Dialog ไม่สำเร็จ: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== Export to TESUJI ====================

    /**
     * Show a Yes/No confirm dialog on the EDT and block the calling thread
     * until the user answers. Safe to call from a background thread so that
     * network calls (which precede these dialogs) never run on the EDT.
     */
    private static boolean confirmYesNoOnEdt(final Component parent, final String message,
            final String title, final boolean warning) {
        final boolean[] result = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    int opt = JOptionPane.showConfirmDialog(parent, message, title,
                        JOptionPane.YES_NO_OPTION,
                        warning ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);
                    result[0] = (opt == JOptionPane.YES_OPTION);
                }
            });
        } catch (Exception e) {
            return false;
        }
        return result[0];
    }

    /**
     * Walk the user through the 3-strike overwrite-warning sequence shared by
     * Export Pairings and Export Wall List. Returns true only if the user
     * confirmed all three prompts.
     */
    private static boolean confirmOverwriteOnEdt(Component parent, String[] warnings) {
        for (int w = 0; w < warnings.length; w++) {
            if (!confirmYesNoOnEdt(parent, warnings[w],
                    "คำเตือน (" + (w + 1) + "/" + warnings.length + ")", true)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Extract division ID and name from tab file name.
     * "01 - 1-2 Kyu.xml" -> id="1", name="1-2 Kyu"
     */
    private static String[] getDivisionInfo() {
        String divId = "1";
        String divName = "Division 1";
        if (allTournamentFiles != null && activeTabIndex >= 0 && activeTabIndex < allTournamentFiles.length) {
            String fileName = allTournamentFiles[activeTabIndex].getName().replaceFirst("(?i)\\.xml$", "");
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+)\\s*-\\s*(.+)$").matcher(fileName);
            if (matcher.find()) {
                divId = matcher.group(1); // keep as-is: "01", "02", etc.
                divName = matcher.group(2).trim();
            } else {
                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("^(\\d+)").matcher(fileName);
                if (m2.find()) {
                    divId = m2.group(1);
                    divName = fileName;
                }
            }
        }
        return new String[]{divId, divName};
    }

    /**
     * Export pairings for current round to TESUJI.
     */
    /**
     * The McMahon score a participant enters `currentRound` with, formatted
     * exactly as MacMahon's own pairing display does inside "(...)":
     *   participant.getScoreDisplayString(participant.getScoreAfterRound(currentRound - 1))
     * (verified against Participant.getPairingDisplayString bytecode). Returns a
     * String so half/quarter points (jigo → "1½") survive; null on any failure.
     */
    private static String mmScoreDisplay(Object participant, int currentRound) {
        if (participant == null) return null;
        try {
            int raw = (Integer) participant.getClass()
                .getMethod("getScoreAfterRound", int.class).invoke(participant, currentRound - 1);
            return (String) participant.getClass()
                .getMethod("getScoreDisplayString", int.class).invoke(participant, raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static void exportPairingsToTesuji(final JFrame parent) {
        if (appInstance == null) {
            showError("ไม่พบ MacMahon Application instance\nกรุณาเปิดทัวร์นาเมนต์ก่อน");
            return;
        }
        try {
            Method getTournament = appInstance.getClass().getMethod("getTournament");
            Object tournament = getTournament.invoke(appInstance);
            if (tournament == null) { showError("ไม่มีทัวร์นาเมนต์เปิดอยู่"); return; }

            Method getCurrentRoundNumber = tournament.getClass().getMethod("getCurrentRoundNumber");
            int roundNum = (Integer) getCurrentRoundNumber.invoke(tournament);
            if (roundNum <= 0) {
                showError("ยังไม่มี Round ใน MacMahon\nกรุณา Make Pairing ก่อน");
                return;
            }

            Method getRound = tournament.getClass().getMethod("getRound", int.class);
            Object round = getRound.invoke(tournament, roundNum);

            final java.util.List<TesujiClient.ExportMatch> matches = new java.util.ArrayList<TesujiClient.ExportMatch>();
            if (round != null) {
                Method getPairings = round.getClass().getMethod("getPairings");
                java.util.List<?> pairings = (java.util.List<?>) getPairings.invoke(round);
                for (Object p : pairings) {
                    if ((Boolean) p.getClass().getMethod("isPairingWithBye").invoke(p)) continue;
                    Object blackP = p.getClass().getMethod("getBlack").invoke(p);
                    Object whiteP = p.getClass().getMethod("getWhite").invoke(p);
                    int board = (Integer) p.getClass().getMethod("getBoardNumber").invoke(p);
                    String bName = blackP != null ? (String) blackP.getClass().getMethod("getName").invoke(blackP) : "?";
                    String wName = whiteP != null ? (String) whiteP.getClass().getMethod("getName").invoke(whiteP) : "?";
                    String bScore = mmScoreDisplay(blackP, roundNum);
                    String wScore = mmScoreDisplay(whiteP, roundNum);
                    matches.add(new TesujiClient.ExportMatch(String.valueOf(board), bName, wName, bScore, wScore));
                }
            }

            final String[] divInfo = getDivisionInfo();
            final String roundStr = String.valueOf(roundNum);

            // Everything from here on may touch the network — run it off the EDT
            // so a slow/unreachable TESUJI server never freezes the whole app.
            new Thread(new Runnable() {
                public void run() {
                    try {
                        TesujiClient client = new TesujiClient(tesujiUrl, tesujiToken);

                        // Check if data for this round already exists on TESUJI
                        boolean dataExists = false;
                        int existingCount = 0;
                        try {
                            TesujiClient.MatchData existing = client.getMatches(divInfo[0], roundStr);
                            if (existing.matches != null && !existing.matches.isEmpty()) {
                                dataExists = true;
                                existingCount = existing.matches.size();
                            }
                        } catch (Exception ex) {
                            // Division may not exist yet — no warning needed
                        }

                        boolean proceed;
                        if (dataExists) {
                            String[] warnings = {
                                "⚠ Round " + roundStr + " ของ " + divInfo[1] + " มีข้อมูลอยู่แล้ว " + existingCount + " คู่\n"
                                    + "ข้อมูลเดิมจะถูกเขียนทับทั้งหมด!\n\n"
                                    + "ต้องการดำเนินการต่อ?",
                                "⚠ ยืนยันอีกครั้ง\n\n"
                                    + "ข้อมูล Round " + roundStr + " ที่มีอยู่ใน TESUJI จะถูกลบ\n"
                                    + "และแทนที่ด้วยข้อมูลใหม่ " + matches.size() + " คู่\n\n"
                                    + "ยืนยัน?",
                                "⚠ ยืนยันครั้งสุดท้าย\n\n"
                                    + "กด Yes เพื่อเขียนทับข้อมูล Round " + roundStr + "\n"
                                    + "Division: " + divInfo[0] + " (" + divInfo[1] + ")\n\n"
                                    + "ดำเนินการ?"
                            };
                            proceed = confirmOverwriteOnEdt(parent, warnings);
                        } else {
                            String msg = "Export Pairings to TESUJI\n\n"
                                + "Division: " + divInfo[0] + " (" + divInfo[1] + ")\n"
                                + "Round: " + roundStr + "\n"
                                + "Pairings: " + matches.size() + " matches\n\n"
                                + "ดำเนินการ?";
                            proceed = confirmYesNoOnEdt(parent, msg, "Export Pairings", false);
                        }
                        if (!proceed) return;

                        client.ensureDivision(divInfo[0], divInfo[1]);
                        try { client.deleteRound(divInfo[0], roundStr); } catch (Exception ex) { /* OK */ }
                        client.exportPairings(divInfo[0], roundStr, matches);
                        System.out.println("[Export] Pairings uploaded: " + matches.size());
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                JOptionPane.showMessageDialog(parent,
                                    "Export Pairings สำเร็จ!\n" + matches.size() + " matches (Round " + roundStr + ")",
                                    "Export Pairings", JOptionPane.INFORMATION_MESSAGE);
                            }
                        });
                    } catch (final Exception ex) {
                        ex.printStackTrace();
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() { showError("Export Pairings failed: " + ex.getMessage()); }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            showError("Export error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Export wall list / standings to TESUJI.
     */
    private static void exportWalllistToTesuji(final JFrame parent) {
        if (appInstance == null) {
            showError("ไม่พบ MacMahon Application instance\nกรุณาเปิดทัวร์นาเมนต์ก่อน");
            return;
        }
        try {
            final java.util.List<String> headers = new java.util.ArrayList<String>();
            final java.util.List<java.util.List<String>> rows = new java.util.ArrayList<java.util.List<String>>();

            Field mainWindowField = appInstance.getClass().getDeclaredField("m_mainWindow");
            mainWindowField.setAccessible(true);
            Object mainWindow = mainWindowField.get(appInstance);
            if (mainWindow == null) { showError("ไม่พบ MainWindow"); return; }

            Method getWalllistTable = mainWindow.getClass().getMethod("getJTableWalllist");
            javax.swing.JTable wallTable = (javax.swing.JTable) getWalllistTable.invoke(mainWindow);
            if (wallTable == null) { showError("ไม่พบตาราง Wall List"); return; }

            javax.swing.table.TableModel model = wallTable.getModel();
            for (int c = 0; c < model.getColumnCount(); c++) {
                headers.add(model.getColumnName(c));
            }
            for (int r = 0; r < model.getRowCount(); r++) {
                java.util.List<String> row = new java.util.ArrayList<String>();
                for (int c = 0; c < model.getColumnCount(); c++) {
                    Object val = model.getValueAt(r, c);
                    row.add(val != null ? val.toString() : "");
                }
                rows.add(row);
            }

            final String[] divInfo = getDivisionInfo();

            // Everything from here on may touch the network — run it off the EDT
            // so a slow/unreachable TESUJI server never freezes the whole app.
            new Thread(new Runnable() {
                public void run() {
                    try {
                        TesujiClient client = new TesujiClient(tesujiUrl, tesujiToken);

                        // Check if division already exists on TESUJI (standings may exist)
                        boolean standingsExist = false;
                        try {
                            java.util.List<TesujiClient.Division> divisions = client.getDivisions();
                            for (TesujiClient.Division d : divisions) {
                                if (d.id.equals(divInfo[0])) {
                                    standingsExist = true;
                                    break;
                                }
                            }
                        } catch (Exception ex) {
                            // Can't check — proceed normally
                        }

                        // Wall List is re-exported every round, so a single
                        // confirmation is enough — just note when it overwrites
                        // existing standings so the user still knows.
                        String msg = "Export Wall List to TESUJI\n\n"
                            + "Division: " + divInfo[0] + " (" + divInfo[1] + ")\n"
                            + "Wall List: " + rows.size() + " rows, " + headers.size() + " columns\n\n";
                        if (standingsExist) {
                            msg += "⚠ มี Wall List เดิมอยู่แล้ว — จะถูกเขียนทับทั้งหมด\n\n";
                        }
                        msg += "ดำเนินการ?";
                        if (!confirmYesNoOnEdt(parent, msg, "Export Wall List", standingsExist)) return;

                        client.ensureDivision(divInfo[0], divInfo[1]);
                        client.exportStandings(divInfo[0], headers, rows);
                        System.out.println("[Export] Wall list uploaded: " + rows.size() + " rows");
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                JOptionPane.showMessageDialog(parent,
                                    "Export Wall List สำเร็จ!\n" + rows.size() + " rows",
                                    "Export Wall List", JOptionPane.INFORMATION_MESSAGE);
                            }
                        });
                    } catch (final Exception ex) {
                        ex.printStackTrace();
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() { showError("Export Wall List failed: " + ex.getMessage()); }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            showError("Export error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== Thai Font Setup ====================

    /**
     * Set up Thai-compatible font for all Swing components.
     * Without this, Thai characters appear as boxes/question marks.
     */
    private static void setupThaiFont() {
        try {
            // Find best Thai-compatible font available on this system.
            // "Thonburi"/"Ayuthaya"/"Krungthep"/"Silom"/"Sathu" are Apple's own
            // Thai-script fonts, bundled on macOS (verified present via
            // GraphicsEnvironment on a real Mac) — harmless on Windows since
            // they simply won't be in the available-fonts set there.
            String[] preferredFonts = {"TH Sarabun New", "TH SarabunPSK", "Sarabun",
                                        "Thonburi", "Ayuthaya", "Krungthep", "Silom", "Sathu",
                                        "Tahoma", "Leelawadee UI", "Segoe UI", "Cordia New",
                                        "Microsoft Sans Serif"};
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            java.util.Set<String> available = new java.util.HashSet<String>(
                java.util.Arrays.asList(ge.getAvailableFontFamilyNames())
            );

            String chosenFont = "TH Sarabun New"; // default fallback
            for (String pf : preferredFonts) {
                if (available.contains(pf)) {
                    chosenFont = pf;
                    break;
                }
            }
            chosenFontName = chosenFont; // store globally

            System.out.println("[Launcher] Using font: " + chosenFont);
            Font thaiFont = new Font(chosenFont, Font.PLAIN, 12);

            // Diagnostic: can this font display Thai?
            int canDisplay = thaiFont.canDisplayUpTo("กขคงจฉช");
            System.out.println("[Launcher] Font '" + chosenFont + "' canDisplayUpTo Thai: " + canDisplay
                + " (family=" + thaiFont.getFamily() + ", name=" + thaiFont.getFontName() + ")");
            if (canDisplay != -1) {
                System.err.println("[Launcher] WARNING: Font cannot display Thai characters!");
            }

            // Override ALL Swing component default fonts
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // ignore
            }

            javax.swing.plaf.FontUIResource fontResource =
                new javax.swing.plaf.FontUIResource(chosenFont, Font.PLAIN, 12);

            java.util.Enumeration<Object> keys = UIManager.getDefaults().keys();
            while (keys.hasMoreElements()) {
                Object key = keys.nextElement();
                Object value = UIManager.get(key);
                if (value instanceof javax.swing.plaf.FontUIResource) {
                    UIManager.put(key, fontResource);
                }
            }

            // Also set specific components that might be missed
            String[] fontKeys = {
                "Label.font", "Button.font", "MenuItem.font", "Menu.font",
                "MenuBar.font", "ComboBox.font", "Table.font", "TableHeader.font",
                "TextField.font", "TextArea.font", "OptionPane.messageFont",
                "OptionPane.buttonFont", "TabbedPane.font", "List.font",
                "Panel.font", "ScrollPane.font", "ToolTip.font",
                "Tree.font", "Spinner.font", "TitledBorder.font",
                "EditorPane.font", "FormattedTextField.font"
            };
            for (String fk : fontKeys) {
                UIManager.put(fk, fontResource);
            }

            // Install global AWT listener to catch ALL components as they're added
            installGlobalFontListener(chosenFont);

        } catch (Exception e) {
            System.err.println("[Launcher] Font setup warning: " + e.getMessage());
        }
    }

    /**
     * Global AWT event listener that intercepts ALL component additions.
     * When any component is added to any container, we set Thai-compatible font on it.
     * This catches MacMahon's tables/labels as they're created — no timer needed.
     */
    private static void installGlobalFontListener(final String fontName) {
        final Font normal = new Font(fontName, Font.PLAIN, 12);
        final Font bold = new Font(fontName, Font.BOLD, 12);

        Toolkit.getDefaultToolkit().addAWTEventListener(new java.awt.event.AWTEventListener() {
            public void eventDispatched(AWTEvent event) {
                if (event instanceof java.awt.event.ContainerEvent) {
                    java.awt.event.ContainerEvent ce = (java.awt.event.ContainerEvent) event;
                    if (ce.getID() == java.awt.event.ContainerEvent.COMPONENT_ADDED) {
                        final Component child = ce.getChild();
                        setThaiFontOnComponent(child, normal, bold);
                        // For JTables, also schedule renderer wrapping after setup completes
                        if (child instanceof JTable) {
                            SwingUtilities.invokeLater(new Runnable() {
                                public void run() {
                                    wrapTableRenderers((JTable) child, normal, bold);
                                }
                            });
                        }
                    }
                }
            }
        }, AWTEvent.CONTAINER_EVENT_MASK);
        System.out.println("[Launcher] Global font listener installed");
    }

    /**
     * Set Thai font on a single component.
     */
    private static void setThaiFontOnComponent(Component comp, Font normal, Font bold) {
        // Preserve original style (bold/italic) — only change font family
        Font current = comp.getFont();
        if (current == null) {
            comp.setFont(normal);
        } else if (!chosenFontName.equals(current.getName())) {
            comp.setFont(new Font(chosenFontName, current.getStyle(), current.getSize()));
        }
        if (comp instanceof JTable) {
            JTable table = (JTable) comp;
            if (table.getTableHeader() != null) {
                Font hf = table.getTableHeader().getFont();
                if (hf != null && !chosenFontName.equals(hf.getName())) {
                    table.getTableHeader().setFont(new Font(chosenFontName, hf.getStyle(), hf.getSize()));
                }
            }
        }
    }

    /**
     * Wrap ALL renderers on a JTable with ThaiCellRenderer to force Thai font.
     */
    private static void wrapTableRenderers(JTable table, Font normal, Font bold) {
        try {
            boolean headerWrapped = false;
            // Wrap header renderer
            if (table.getTableHeader() != null) {
                TableCellRenderer headerRenderer = table.getTableHeader().getDefaultRenderer();
                if (headerRenderer != null && !(headerRenderer instanceof ThaiCellRenderer)) {
                    table.getTableHeader().setDefaultRenderer(new ThaiCellRenderer(headerRenderer, bold));
                    headerWrapped = true;
                }
            }

            // Wrap default renderers for ALL column classes used in this table
            java.util.Set<Class<?>> classesWrapped = new java.util.HashSet<Class<?>>();
            classesWrapped.add(Object.class);
            classesWrapped.add(String.class);
            classesWrapped.add(Number.class);
            classesWrapped.add(Integer.class);
            classesWrapped.add(Double.class);
            classesWrapped.add(Float.class);
            classesWrapped.add(Boolean.class);
            // Also add actual column classes from the table model
            for (int col = 0; col < table.getColumnCount(); col++) {
                try {
                    classesWrapped.add(table.getColumnClass(col));
                } catch (Exception ex) { /* ignore */ }
            }
            for (Class<?> cls : classesWrapped) {
                try {
                    TableCellRenderer defRenderer = table.getDefaultRenderer(cls);
                    if (defRenderer != null && !(defRenderer instanceof ThaiCellRenderer)) {
                        table.setDefaultRenderer(cls, new ThaiCellRenderer(defRenderer, normal));
                    }
                } catch (Exception ex) { /* ignore */ }
            }

            // Wrap per-column renderers
            int tableCount = 0;
            if (table.getColumnModel() != null) {
                for (int col = 0; col < table.getColumnModel().getColumnCount(); col++) {
                    TableColumn tc = table.getColumnModel().getColumn(col);
                    TableCellRenderer colRenderer = tc.getCellRenderer();
                    if (colRenderer != null && !(colRenderer instanceof ThaiCellRenderer)) {
                        tc.setCellRenderer(new ThaiCellRenderer(colRenderer, normal));
                        tableCount++;
                    }
                    // If no per-column renderer, force one from the effective renderer
                    if (colRenderer == null) {
                        try {
                            Class<?> colClass = table.getColumnClass(col);
                            TableCellRenderer effective = table.getDefaultRenderer(colClass);
                            if (effective != null && !(effective instanceof ThaiCellRenderer)) {
                                tc.setCellRenderer(new ThaiCellRenderer(effective, normal));
                                tableCount++;
                            }
                        } catch (Exception ex) { /* ignore */ }
                    }
                }
            }
            // Only log when something was actually (re)wrapped — this runs on every
            // maintenance timer tick and is normally a no-op after the first pass.
            if (tableCount > 0 || headerWrapped) {
                System.out.println("[Launcher] Wrapped " + tableCount + " column renderers on "
                    + table.getClass().getSimpleName() + " (" + table.getColumnCount() + " cols)");
            }
        } catch (Exception e) {
            System.err.println("[Launcher] wrapTableRenderers error: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private static Properties loadOrCreateProperties() {
        Properties props = new Properties();
        File propFile = new File(getLauncherDir(), PROPERTIES_FILE);

        if (propFile.exists()) {
            try (FileInputStream fis = new FileInputStream(propFile)) {
                props.load(new InputStreamReader(fis, "UTF-8"));
                System.out.println("[Launcher] Loaded " + propFile.getAbsolutePath());
            } catch (Exception e) {
                System.err.println("[Launcher] Failed to load properties: " + e.getMessage());
            }
        } else {
            // Create default
            props.setProperty("tesuji.url", "https://tesuji-reg.vercel.app");
            props.setProperty("tesuji.token", "your_secret_token_here");
            try (FileOutputStream fos = new FileOutputStream(propFile)) {
                props.store(new OutputStreamWriter(fos, "UTF-8"),
                    "MacMahon Launcher Config\ntesuji.url = URL of TESUJI server\ntesuji.token = Admin token");
                System.out.println("[Launcher] Created default " + propFile.getAbsolutePath());
            } catch (Exception e) {
                System.err.println("[Launcher] Failed to create properties: " + e.getMessage());
            }

            JOptionPane.showMessageDialog(null,
                "สร้างไฟล์ config ใหม่: " + propFile.getAbsolutePath() + "\n" +
                "กรุณาตั้งค่า tesuji.url และ tesuji.token",
                "MacMahon Launcher", JOptionPane.INFORMATION_MESSAGE);
        }
        return props;
    }

    private static File findMacMahonJar() {
        // Option 1: External macmahon-*.jar next to launcher (legacy / override)
        File dir = getLauncherDir();
        File[] jars = dir.listFiles(new FileFilter() {
            public boolean accept(File f) {
                String name = f.getName().toLowerCase();
                return name.startsWith("macmahon-") && name.endsWith(".jar")
                    && !name.contains("launcher") && !name.contains("tesuji");
            }
        });
        if (jars != null && jars.length > 0) {
            System.out.println("[Launcher] Using external JAR: " + jars[0].getName());
            return jars[0];
        }

        // Option 2: Extract embedded JAR from inside this launcher
        try {
            InputStream embeddedStream = MacMahonLauncher.class.getResourceAsStream("/embedded/macmahon.jar");
            if (embeddedStream != null) {
                File tempDir = new File(System.getProperty("java.io.tmpdir"), "macmahon-launcher");
                tempDir.mkdirs();
                File tempJar = new File(tempDir, "macmahon.jar");
                File tmpFile = new File(tempDir, "macmahon.jar.tmp");
                try {
                    // Write to a scratch file first, then move into place — a
                    // partial/interrupted write (disk full, killed process)
                    // can never leave a truncated macmahon.jar behind.
                    try (InputStream in = embeddedStream;
                         FileOutputStream fos = new FileOutputStream(tmpFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                    Files.move(tmpFile.toPath(), tempJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[Launcher] Extracted embedded MacMahon to: " + tempJar.getAbsolutePath());
                } catch (IOException e) {
                    tmpFile.delete();
                    // Destination may be locked by another running instance — use existing if valid
                    if (tempJar.exists() && tempJar.length() > 0) {
                        System.out.println("[Launcher] Using cached embedded JAR: " + tempJar.getAbsolutePath());
                    } else {
                        throw e;
                    }
                }
                return tempJar;
            }
        } catch (Exception e) {
            System.err.println("[Launcher] Embedded JAR extraction failed: " + e.getMessage());
        }

        return null;
    }

    private static File getLauncherDir() {
        try {
            URI uri = MacMahonLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
            File jarFile = new File(uri);
            return jarFile.getParentFile();
        } catch (Exception e) {
            return new File(".");
        }
    }

    /**
     * Background timer that periodically re-applies Thai font to all visible JFrames.
     * This catches tables/components that MacMahon creates AFTER our initial font pass
     * (e.g., when a tournament is opened or rounds are created).
     */
    private static void startFontMaintenanceTimer() {
        Timer fontTimer = new Timer(5000, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Frame[] frames = Frame.getFrames();
                for (Frame frame : frames) {
                    if (frame instanceof JFrame && frame.isVisible()) {
                        applyThaiFontToAll((Container) frame);
                    }
                }
            }
        });
        fontTimer.setRepeats(true);
        fontTimer.start();
        System.out.println("[Launcher] Font maintenance timer started (every 5s)");
    }

    /**
     * Wrapper renderer that forces Thai-compatible font on any JTable cell renderer.
     * This ensures MacMahon's custom renderers (WalllistCellRenderer, PairingsCellRenderer)
     * use Thai-compatible font instead of the default Dialog font.
     */
    private static class ThaiCellRenderer implements TableCellRenderer {
        private final TableCellRenderer delegate;
        private final Font font;

        ThaiCellRenderer(TableCellRenderer delegate, Font font) {
            this.delegate = delegate;
            this.font = font;
        }

        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            // Recursively set Thai font on ALL sub-components
            // (handles compound renderers that use JPanel with JLabels inside)
            setFontDeep(c, font);
            return c;
        }

        private void setFontDeep(Component comp, Font f) {
            // Preserve bold/italic style from original font.
            // Skip setFont() when the family already matches — this runs on
            // every cell repaint, so a redundant setFont() here means every
            // visible cell repaints itself on every paint pass.
            // Compare via getName() (always the exact string passed to the Font
            // constructor), not getFamily() (which the OS may resolve to
            // "Dialog" if the family isn't installed, masking real changes).
            Font current = comp.getFont();
            if (current == null) {
                comp.setFont(f);
            } else if (!f.getName().equals(current.getName())) {
                comp.setFont(new Font(f.getName(), current.getStyle(), current.getSize()));
            }
            if (comp instanceof Container) {
                for (Component child : ((Container) comp).getComponents()) {
                    setFontDeep(child, f);
                }
            }
        }
    }

    // ==================== Java 25 Auto-Detection ====================

    /**
     * If not running on Java 25+, search for it and relaunch.
     * This allows double-clicking the JAR with any Java version.
     */
    private static void ensureJava25() {
        String version = System.getProperty("java.version");
        int major = getMajorVersion(version);
        System.out.println("[Launcher] Java version: " + version + " (major=" + major + ")");

        if (major >= 25) return; // OK

        System.out.println("[Launcher] Need Java 25+, searching...");
        String java25 = findJava25Path();
        if (java25 != null) {
            System.out.println("[Launcher] Found Java 25+: " + java25);
            if (relaunchWithJava(java25)) {
                System.exit(0);
            } else {
                showError("เปิดโปรแกรมใหม่ด้วย Java 25 ไม่สำเร็จ\n"
                    + "Java path: " + java25 + "\n\n"
                    + "ลองรัน macmahon-tesuji.jar ด้วยตนเองผ่าน command line:\n"
                    + "\"" + java25 + "\" -jar macmahon-tesuji.jar");
                System.exit(1);
            }
        } else {
            JOptionPane.showMessageDialog(null,
                "MacMahon requires Java 25+\n\n" +
                "Current: Java " + version + "\n\n" +
                javaInstallHint(),
                "MacMahon Launcher", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String javaInstallHint() {
        if (isMac()) {
            return "ติดตั้ง Java 25 (Temurin) ผ่าน Homebrew:\n"
                + "  brew install --cask temurin\n\n"
                + "หรือดาวน์โหลด .pkg จาก:\n"
                + "https://adoptium.net/temurin/releases/?version=25";
        }
        return "Download Amazon Corretto 25:\n"
            + "https://corretto.aws/downloads/latest/amazon-corretto-25-x64-windows-jdk.msi";
    }

    private static int getMajorVersion(String version) {
        if (version.startsWith("1.")) version = version.substring(2);
        int dot = version.indexOf('.');
        if (dot > 0) version = version.substring(0, dot);
        try { return Integer.parseInt(version); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String findJava25Path() {
        if (isMac()) return findJava25Mac();
        if (isWindows()) return findJava25Windows();
        return findJava25Generic();
    }

    /**
     * Locate Java 25+ on macOS. Prefers Apple's own JVM registry (java_home),
     * which correctly finds any properly-installed JDK/JRE regardless of
     * vendor (Temurin, Corretto, Zulu, ...) or install method (pkg, brew
     * cask). Falls back to scanning the standard JVM bundle directory.
     */
    private static String findJava25Mac() {
        try {
            Process p = new ProcessBuilder("/usr/libexec/java_home", "-v", "25+").start();
            String home = readProcessOutput(p).trim();
            int exit = p.waitFor();
            if (exit == 0 && !home.isEmpty()) {
                File javaBin = new File(home, "bin/java");
                if (javaBin.exists()) return javaBin.getAbsolutePath();
            }
        } catch (Exception e) {
            System.err.println("[Launcher] java_home lookup failed: " + e.getMessage());
        }

        File jvmDir = new File("/Library/Java/JavaVirtualMachines");
        File[] bundles = jvmDir.listFiles();
        if (bundles != null) {
            for (File bundle : bundles) {
                if (bundle.getName().matches("(?i).*(?:jdk-?25|corretto-?25|temurin-?25|zulu.*25).*")) {
                    File javaBin = new File(bundle, "Contents/Home/bin/java");
                    if (javaBin.exists()) return javaBin.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String findJava25Windows() {
        String[] basePaths = {
            "C:\\Program Files\\Amazon Corretto",
            "C:\\Program Files\\Java",
            "C:\\Program Files\\Eclipse Adoptium",
            "C:\\Program Files\\BellSoft",
            "C:\\Program Files\\Microsoft"
        };
        for (String base : basePaths) {
            File dir = new File(base);
            if (!dir.exists()) continue;
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File d : children) {
                if (!d.isDirectory()) continue;
                // Match jdk25, jdk-25, jdk25.0.3_9, etc.
                if (d.getName().matches("(?i).*(?:jdk-?25|corretto-?25).*")) {
                    File javaw = new File(d, "bin" + File.separator + "javaw.exe");
                    if (javaw.exists()) return javaw.getAbsolutePath();
                    File java = new File(d, "bin" + File.separator + "java.exe");
                    if (java.exists()) return java.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /** Best-effort lookup for other OSes (e.g. Linux) — JAVA_HOME, then /usr/lib/jvm. */
    private static String findJava25Generic() {
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null) {
            File javaBin = new File(javaHomeEnv, "bin/java");
            if (javaBin.exists()) return javaBin.getAbsolutePath();
        }
        File jvmDir = new File("/usr/lib/jvm");
        File[] children = jvmDir.listFiles();
        if (children != null) {
            for (File d : children) {
                if (d.getName().matches(".*25.*")) {
                    File javaBin = new File(d, "bin/java");
                    if (javaBin.exists()) return javaBin.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String readProcessOutput(Process p) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            br.close();
        }
    }

    private static boolean relaunchWithJava(String javaPath) {
        try {
            String jarPath = new File(MacMahonLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(
                javaPath, "-Dfile.encoding=UTF-8", "-jar", jarPath
            );
            pb.directory(new File(jarPath).getParentFile());
            pb.start();
            return true;
        } catch (Exception e) {
            System.err.println("[Launcher] Relaunch failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static void showError(String message) {
        JOptionPane.showMessageDialog(null, message,
            "MacMahon Launcher — Error", JOptionPane.ERROR_MESSAGE);
    }
}
