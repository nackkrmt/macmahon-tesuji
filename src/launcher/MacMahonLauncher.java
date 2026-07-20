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
    private static final String DEFAULT_TESUJI_URL = "https://tesuji-reg.vercel.app";
    private static String tesujiUrl = "";
    private static String tesujiToken = "";
    private static URLClassLoader macmahonClassLoader;
    private static Object appInstance; // MacMahonApplication instance

    private static File[] allTournamentFiles = null;
    /** False when the loaded MacMahon JAR failed the compatibility check. */
    private static boolean tesujiFeaturesEnabled = true;

    public static void main(String[] args) {
        setupFileLogging();
        System.out.println("[Launcher] MacMahon Launcher starting...");

        // Step 0: Ensure Java 25+ — if running on older Java, find Java 25 and relaunch
        JavaFinder.ensureJava25();

        // Step 0b: Set UTF-8 encoding + Thai-compatible font
        System.setProperty("file.encoding", "UTF-8");
        // Put the menu bar at the top of the screen like a native Mac app,
        // instead of inside the window. Must be set before any AWT/Swing
        // class touches the Toolkit (i.e. before setupThaiFont() below).
        if (JavaFinder.isMac()) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
        }
        ThaiFontManager.setupThaiFont();

        // Step 1: Read config
        Properties props = loadOrCreateProperties();
        tesujiUrl = props.getProperty("tesuji.url", DEFAULT_TESUJI_URL).trim();
        tesujiToken = props.getProperty("tesuji.token", "").trim();
        if (tesujiUrl.endsWith("/")) {
            tesujiUrl = tesujiUrl.substring(0, tesujiUrl.length() - 1);
        }
        System.out.println("[Launcher] TESUJI URL: " + tesujiUrl);
        if (tesujiUrl.startsWith("http://")) {
            System.err.println("[Launcher] WARNING: tesuji.url is plain http — admin token would be sent unencrypted");
            JOptionPane.showMessageDialog(null,
                "คำเตือน: tesuji.url ใน launcher.properties เป็น http (ไม่เข้ารหัส)\n" +
                "token ผู้ดูแลจะถูกส่งผ่านเครือข่ายแบบอ่านได้\n" +
                "แนะนำให้เปลี่ยนเป็น https", "MacMahon Launcher",
                JOptionPane.WARNING_MESSAGE);
        }

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

        // Step 3b: Verify the MacMahon internals we touch via reflection.
        // A different MacMahon version would otherwise fail silently,
        // feature by feature, in the middle of a live tournament.
        java.util.List<String> missingMembers = checkMacMahonCompat(macmahonClassLoader);
        if (!missingMembers.isEmpty()) {
            tesujiFeaturesEnabled = false;
            System.err.println("[Launcher] MacMahon compat check FAILED — missing: " + missingMembers);
            StringBuilder few = new StringBuilder();
            for (int i = 0; i < missingMembers.size() && i < 8; i++) {
                few.append("  - ").append(missingMembers.get(i)).append("\n");
            }
            if (missingMembers.size() > 8) {
                few.append("  ... และอีก ").append(missingMembers.size() - 8).append(" รายการ\n");
            }
            JOptionPane.showMessageDialog(null,
                "MacMahon JAR ที่พบไม่ตรงกับเวอร์ชันที่ Launcher รองรับ (3.10)\n"
                + "ฟีเจอร์ TESUJI (เมนู Sync/Export และ tab bar) จะถูกปิด\n"
                + "ตัวโปรแกรม MacMahon เองยังใช้งานได้ตามปกติ\n\n"
                + "ส่วนที่หายไป:\n" + few,
                "MacMahon Launcher — Compatibility", JOptionPane.WARNING_MESSAGE);
        } else {
            System.out.println("[Launcher] MacMahon compat check OK");
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
                                    ThaiFontManager.applyThaiFontToAll(jf);
                                    if (tesujiFeaturesEnabled) {
                                        injectMenu(jf);
                                        // Inject tab bar if multiple .xml files found
                                        if (allTournamentFiles != null && allTournamentFiles.length > 0) {
                                            injectTabBar(jf, allTournamentFiles);
                                        }
                                    }
                                    ThaiFontManager.startFontMaintenanceTimer();
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
    static int compareTournamentFileNames(String a, String b) {
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
            btn.setFont(new Font(ThaiFontManager.getChosenFontName(), Font.BOLD, 14));
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
                tabButtons[i].setFont(new Font(ThaiFontManager.getChosenFontName(), Font.BOLD, 14));
            } else {
                tabButtons[i].setBackground(inactiveBg);
                tabButtons[i].setForeground(inactiveFg);
                tabButtons[i].setFont(new Font(ThaiFontManager.getChosenFontName(), Font.PLAIN, 14));
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
     * Extract division ID and name from the active tab's file name.
     * "01 - 1-2 Kyu.xml" -> id="01", name="1-2 Kyu".
     * Returns null when there is no active tab or the name has no leading
     * division number — callers must refuse to export then: guessing a
     * default division here would delete/overwrite ANOTHER division's data
     * on the server (exports clear the target round first).
     */
    private static String[] getDivisionInfo() {
        if (allTournamentFiles == null || activeTabIndex < 0 || activeTabIndex >= allTournamentFiles.length) {
            return null;
        }
        String fileName = allTournamentFiles[activeTabIndex].getName().replaceFirst("(?i)\\.xml$", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+)\\s*-\\s*(.+)$").matcher(fileName);
        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(2).trim()}; // keep id as-is: "01", "02", ...
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("^(\\d+)").matcher(fileName);
        if (m2.find()) {
            return new String[]{m2.group(1), fileName};
        }
        return null;
    }

    /** Explain the file-naming rule exports depend on, then let the caller abort. */
    private static void showDivisionNamingError() {
        showError("ระบุ Division จากชื่อไฟล์ไม่ได้ — ยกเลิกการ Export\n\n"
            + "การ Export ต้องเปิดทัวร์นาเมนต์ผ่าน tab และชื่อไฟล์ต้องขึ้นต้นด้วยเลข Division เช่น\n"
            + "   \"01 - 1-2 Kyu.xml\"   หรือ   \"2 - Open.xml\"\n\n"
            + "กรุณาเปลี่ยนชื่อไฟล์ .xml แล้วเปิดโปรแกรมใหม่อีกครั้ง");
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
            if (divInfo == null) { showDivisionNamingError(); return; }
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
            if (divInfo == null) { showDivisionNamingError(); return; }

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
                                if (TesujiClient.sameDivisionId(d.id, divInfo[0])) {
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

    // ==================== Helpers ====================

    /**
     * Duplicate System.out/err into launcher.log next to the jar, so
     * problems can still be diagnosed when the app was started by
     * double-click (no console). Truncated on every start to stay small.
     */
    private static void setupFileLogging() {
        try {
            File logFile = new File(getLauncherDir(), "launcher.log");
            PrintStream fileStream = new PrintStream(new FileOutputStream(logFile, false), true, "UTF-8");
            System.setOut(new TeePrintStream(System.out, fileStream));
            System.setErr(new TeePrintStream(System.err, fileStream));
            System.out.println("[Launcher] Log: " + logFile.getAbsolutePath()
                + " | " + new java.util.Date()
                + " | Java " + System.getProperty("java.version")
                + " | " + System.getProperty("os.name"));
        } catch (Exception e) {
            System.err.println("[Launcher] File logging unavailable: " + e.getMessage());
        }
    }

    /** PrintStream that writes everything to two streams (console + log file). */
    private static class TeePrintStream extends PrintStream {
        private final PrintStream second;

        TeePrintStream(PrintStream first, PrintStream second) throws UnsupportedEncodingException {
            super(first, true, "UTF-8");
            this.second = second;
        }

        public void write(int b) {
            super.write(b);
            second.write(b);
        }

        public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            second.write(buf, off, len);
        }

        public void flush() {
            super.flush();
            second.flush();
        }

        public void close() {
            super.close();
            second.close();
        }
    }

    /**
     * Verify every MacMahon class/method/field the launcher and SyncDialog
     * touch via reflection ("m:" = method by name, "f:" = field). The list
     * is verified against macmahon-3.10.jar. Returns the missing members;
     * empty list = fully compatible. loadClass() does not run static
     * initializers, so this is side-effect-free before the app starts.
     */
    private static java.util.List<String> checkMacMahonCompat(ClassLoader cl) {
        String[][] targets = {
            {"de.cgerlach.macmahon.gui.MacMahonApplication",
                "m:main", "m:getTournament", "m:tournamentSave", "m:tournamentOpenInternal",
                "m:tournamentOpened", "m:fireWalllistTableDataChanged", "m:firePairingsTableDataChanged",
                "f:m_tournament", "f:m_mainWindow"},
            {"de.cgerlach.macmahon.gui.MacMahonMainWindow", "m:getJTableWalllist", "f:m_application"},
            {"de.cgerlach.macmahon.model.Tournament",
                "m:getCurrentRoundNumber", "m:getRound", "m:getCurrentRound", "m:getName",
                "m:buildParticipantScores", "f:m_rounds"},
            {"de.cgerlach.macmahon.model.TournamentRound", "m:getPairings"},
            {"de.cgerlach.macmahon.model.Pairing",
                "f:BLACK_WINS", "f:WHITE_WINS", "f:JIGO", "f:BOTH_LOSE", "f:BOTH_WIN",
                "m:getResult", "m:setResult", "m:getBoardNumber", "m:setBoardNumber",
                "m:getBlack", "m:getWhite", "m:isPairingWithBye"},
            {"de.cgerlach.macmahon.model.Pairing$ResultDescriptor", "m:getShortName"},
            {"de.cgerlach.macmahon.model.Participant",
                "m:getName", "m:getScoreAfterRound", "m:getScoreDisplayString"},
            {"de.cgerlach.macmahon.model.IndividualParticipant", "m:getGoPlayer"},
            {"de.cgerlach.macmahon.model.Person", "m:setFirstName", "m:setSurname"},
        };

        java.util.List<String> missing = new java.util.ArrayList<String>();
        for (String[] entry : targets) {
            String className = entry[0];
            Class<?> c;
            try {
                c = cl.loadClass(className);
            } catch (Throwable t) {
                missing.add(className + " (class)");
                continue;
            }
            String shortName = className.substring(className.lastIndexOf('.') + 1);
            for (int i = 1; i < entry.length; i++) {
                String member = entry[i].substring(2);
                boolean found = false;
                if (entry[i].startsWith("m:")) {
                    for (Method m : c.getMethods()) {
                        if (m.getName().equals(member)) { found = true; break; }
                    }
                    if (!found) {
                        for (Method m : c.getDeclaredMethods()) {
                            if (m.getName().equals(member)) { found = true; break; }
                        }
                    }
                } else {
                    try {
                        c.getDeclaredField(member);
                        found = true;
                    } catch (NoSuchFieldException e) {
                        try {
                            c.getField(member);
                            found = true;
                        } catch (NoSuchFieldException e2) { /* missing */ }
                    }
                }
                if (!found) missing.add(shortName + "." + member);
            }
        }
        return missing;
    }

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
            props.setProperty("tesuji.url", DEFAULT_TESUJI_URL);
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

    static void showError(String message) {
        JOptionPane.showMessageDialog(null, message,
            "MacMahon Launcher — Error", JOptionPane.ERROR_MESSAGE);
    }
}
